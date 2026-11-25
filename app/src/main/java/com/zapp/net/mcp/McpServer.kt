package com.zapp.net.mcp

import android.content.Context
import android.util.Log
import com.zapp.net.shizuku.Shell
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An MCP server, on the phone, that hands the agent this device.
 *
 * OpenCode's own shape (read from the live `/openapi.json` on this phone):
 *
 *   mcp: { "<name>": { type: "remote", url: "<http>", headers: {...}, protocol: "legacy" } }
 *
 * `legacy` means the classic initialize handshake over streamable HTTP. So this speaks
 * JSON-RPC 2.0 on POST /mcp and answers with plain `application/json` - no SSE stream needed
 * for a tool-only server.
 *
 * Every tool ends in [Shell], which runs as uid 2000 (shell). That is the whole mechanism:
 * the model asks, the app executes, the phone obeys.
 *
 * Bound to 127.0.0.1 only, and every request must carry the shared key from [key].
 */
object McpServer {

    /** Fixed so the OpenCode config stays valid across restarts. */
    const val PORT = 45537

    private const val TAG = "ZAPP"
    private const val PATH = "/mcp"
    private const val HEADER_KEY = "x-zapp-key"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var socket: ServerSocket? = null
    private val up = AtomicBoolean(false)
    @Volatile private var appCtx: Context? = null

    /** Shared secret; generated once and kept, so a stale config still matches. */
    fun key(ctx: Context): String {
        val p = ctx.getSharedPreferences("zapp_mcp", Context.MODE_PRIVATE)
        val k = p.getString("key", null)
        if (!k.isNullOrBlank()) return k
        // This key is the only thing between a loopback peer and a shell-uid command runner,
        // so it comes from SecureRandom, not Math.random.
        val bytes = ByteArray(24)
        java.security.SecureRandom().nextBytes(bytes)
        val fresh = bytes.joinToString("") { "%02x".format(it) }
        p.edit().putString("key", fresh).apply()
        return fresh
    }

    fun running(): Boolean = up.get()

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (up.getAndSet(true)) return
        Thread({
            // Rebind forever. The listener can die because the process was trimmed, because
            // the port was taken for a moment, or because accept() threw. Without this the
            // agent silently loses its hands and the MCP connection in OpenCode goes stale -
            // a failure with no symptom until a tool call fails.
            var backoffMs = 500L
            while (up.get()) {
                try {
                    val s = ServerSocket()
                    s.reuseAddress = true
                    s.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 16)
                    socket = s
                    backoffMs = 500L
                    Log.e(TAG, "mcp: listening on 127.0.0.1:$PORT$PATH")
                    while (up.get()) {
                        val c = try {
                            s.accept()
                        } catch (t: Throwable) {
                            if (up.get()) Log.e(TAG, "mcp: accept failed, rebinding: $t")
                            break
                        }
                        Thread({ serve(c) }, "mcp-conn").start()
                    }
                    runCatching { s.close() }
                } catch (t: Throwable) {
                    Log.e(TAG, "mcp: cannot listen on $PORT: $t")
                }
                socket = null
                if (!up.get()) break
                // A short, growing pause keeps a taken port from spinning the CPU.
                runCatching { Thread.sleep(backoffMs) }
                backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
            }
            Log.e(TAG, "mcp: listener stopped")
        }, "mcp-listen").start()
    }

    fun stop() {
        up.set(false)
        runCatching { socket?.close() }
        socket = null
    }

    // ---------------------------------------------------------------- http

    private fun serve(c: Socket) {
        c.use { sock ->
            sock.soTimeout = 120_000
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            try {
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                val target = parts.getOrNull(1).orEmpty()

                var length = 0
                var gotKey = ""
                while (true) {
                    val h = readLine(input) ?: break
                    if (h.isEmpty()) break
                    val i = h.indexOf(':')
                    if (i <= 0) continue
                    val name = h.substring(0, i).trim().lowercase()
                    val value = h.substring(i + 1).trim()
                    when (name) {
                        "content-length" -> length = value.toIntOrNull() ?: 0
                        HEADER_KEY -> gotKey = value
                    }
                }

                if (!target.startsWith(PATH)) {
                    reply(out, 404, "application/json", """{"error":"not found"}""")
                    return
                }
                val ctx = appCtx
                if (ctx == null) {
                    reply(out, 503, "application/json", """{"error":"no context"}""")
                    return
                }
                if (gotKey != key(ctx)) {
                    Log.e(TAG, "mcp: rejected a request with the wrong key")
                    reply(out, 403, "application/json", """{"error":"forbidden"}""")
                    return
                }
                if (method.equals("GET", true)) {
                    // No server-initiated stream: a tool-only server never needs one.
                    reply(out, 405, "application/json", """{"error":"use POST"}""")
                    return
                }
                if (!method.equals("POST", true)) {
                    reply(out, 405, "application/json", """{"error":"use POST"}""")
                    return
                }

                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n <= 0) break
                    read += n
                }
                val text = String(body, 0, read, Charsets.UTF_8)
                val answer = handle(text)
                if (answer == null) {
                    // A notification: MCP expects 202 and no body.
                    reply(out, 202, "application/json", "")
                } else {
                    reply(out, 200, "application/json", answer)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "mcp: connection failed: $t")
                runCatching { reply(out, 500, "application/json", """{"error":"internal"}""") }
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun reply(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code ").append(
                when (code) {
                    200 -> "OK"; 202 -> "Accepted"; 403 -> "Forbidden"
                    404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error"
                },
            ).append("\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Mcp-Session-Id: zapp\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    // ---------------------------------------------------------------- rpc

    /** Returns the JSON response, or null when the message was a notification. */
    private fun handle(raw: String): String? {
        val msg = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return rpcError(null, -32700, "parse error")
        val id = msg["id"]
        val method = msg["method"]?.jsonPrimitive?.contentOrNullSafe()

        if (method == null) return null
        // Notifications carry no id and must not be answered.
        if (id == null || id is kotlinx.serialization.json.JsonNull) {
            if (method.startsWith("notifications/")) {
                Log.e(TAG, "mcp: $method")
                return null
            }
        }

        return when (method) {
            "initialize" -> {
                val params = msg["params"]?.jsonObject
                val asked = params?.get("protocolVersion")?.jsonPrimitive?.contentOrNullSafe()
                val version = if (asked.isNullOrBlank()) "2024-11-05" else asked
                val result = buildJsonObject {
                    put("protocolVersion", version)
                    put("capabilities", buildJsonObject {
                        put("tools", buildJsonObject { put("listChanged", false) })
                    })
                    put("serverInfo", buildJsonObject {
                        put("name", "zapp")
                        put("version", "1.0.0")
                    })
                }
                Log.e(TAG, "mcp: initialize (client asked $version)")
                rpcResult(id, result)
            }

            "ping" -> rpcResult(id, buildJsonObject { })

            "tools/list" -> {
                Log.e(TAG, "mcp: tools/list")
                rpcResult(id, buildJsonObject { put("tools", TOOLS) })
            }

            "tools/call" -> toolCall(id, msg["params"]?.jsonObject)

            "resources/list" -> rpcResult(id, buildJsonObject { put("resources", buildJsonArray { }) })
            "prompts/list" -> rpcResult(id, buildJsonObject { put("prompts", buildJsonArray { }) })

            else -> rpcError(id, -32601, "method not found: $method")
        }
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()

    private fun rpcResult(id: JsonElement?, result: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: kotlinx.serialization.json.JsonNull)
        put("result", result)
    }.toString()

    private fun rpcError(id: JsonElement?, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: kotlinx.serialization.json.JsonNull)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }.toString()

    private fun toolCall(id: JsonElement?, params: JsonObject?): String {
        val name = params?.get("name")?.jsonPrimitive?.contentOrNullSafe().orEmpty()
        val args = params?.get("arguments")?.jsonObject ?: JsonObject(emptyMap())
        fun str(k: String): String = args[k]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
        fun int(k: String, d: Int): Int = args[k]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull() ?: d

        val ctx = appCtx
        if (ctx == null) return toolText(id, "mcp server has no context", true)
        // Guard control does not need Shizuku - it is our own engine. Checked first so a phone
        // with Shizuku dead can still be told to rotate.
        if (name == "guard") return guardTool(ctx, id, str("action"))
        if (!Shell.alive() || !Shell.granted()) {
            return toolText(
                id,
                "No shell rights. Shizuku binder=${Shell.alive()} granted=${Shell.granted()}. " +
                    "The user must start Shizuku and grant this app permission.",
                true,
            )
        }

        val cmd = when (name) {
            "shell" -> str("command")
            "screen" -> "screencap -p /sdcard/zapp_screen.png && ls -l /sdcard/zapp_screen.png"
            "tap" -> "input tap ${int("x", 0)} ${int("y", 0)}"
            "swipe" -> "input swipe ${int("x1", 0)} ${int("y1", 0)} ${int("x2", 0)} ${int("y2", 0)} ${int("ms", 300)}"
            "key" -> "input keyevent ${str("keycode").ifBlank { "KEYCODE_WAKEUP" }}"
            "type" -> "input text '${str("text").replace("'", "'\\''").replace(" ", "%s")}'"
            "install" -> "pm install -r '${str("path")}'"
            "device" -> "echo \"model=$(getprop ro.product.model)\"; echo \"sdk=$(getprop ro.build.version.sdk)\"; " +
                "dumpsys battery | grep -E 'level|temperature'; " +
                "dumpsys activity activities | grep -m1 topResumedActivity"
            else -> return toolText(id, "unknown tool: $name", true)
        }
        if (cmd.isBlank()) return toolText(id, "empty command", true)

        val out = runBlocking { Shell.exec(ctx, cmd, 120_000) }
            ?: return toolText(id, "no hands: shell did not answer", true)

        val body = if (out.text.isBlank()) "(no output, exit ${out.code})" else out.text.trimEnd()
        Log.e(TAG, "mcp: $name rc=${out.code} ${body.length}b")
        return toolText(id, if (out.code == 0) body else "$body\n(exit ${out.code})", out.code != 0)
    }

    /**
     * Guard control from chat: `status`, `rotate`, `wall`.
     *
     * This is the tool that was missing at 04:15, when the owner typed "rotate the tunnel" four
     * times and got four quota walls because the model had nothing to call.
     */
    private fun guardTool(ctx: android.content.Context, id: JsonElement?, action: String): String {
        val s = com.zapp.net.guard.GuardState.status.value
        return when (action.trim().lowercase()) {
            "rotate", "hop", "new", "newip" -> {
                val before = s.currentV6
                val reg = runBlocking {
                    runCatching { com.zapp.net.guard.RotationEngine.hopNow(ctx, "manual") }.getOrNull()
                }
                val after = com.zapp.net.guard.GuardState.status.value.currentV6
                val text = if (reg == null) {
                    "rotation returned nothing; before=$before after=$after"
                } else {
                    "before=$before after=$after moved=${before != after}"
                }
                Log.e(TAG, "mcp: guard rotate -> $text")
                toolText(id, text, reg == null)
            }
            "wall", "walls", "last" -> {
                val last = com.zapp.net.guard.GuardState.events.value.lastOrNull { it.kind == "wall" }
                toolText(
                    id,
                    "walls=${s.walls} last=${last?.text ?: "none since start"} v6=${s.currentV6}",
                    false,
                )
            }
            else -> toolText(
                id,
                buildString {
                    append("vpn=").append(if (s.vpnRunning) "up" else "DOWN")
                    append(" verified=").append(s.verified)
                    append(" v6=").append(s.currentV6)
                    append(" hops=").append(s.hops)
                    append(" walls=").append(s.walls)
                    append(" streaming=").append(s.streaming)
                    if (s.pausedUntilMs > System.currentTimeMillis()) {
                        append(" paused=").append((s.pausedUntilMs - System.currentTimeMillis()) / 1000)
                            .append("s")
                    }
                },
                false,
            )
        }
    }

    private fun toolText(id: JsonElement?, text: String, error: Boolean): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: kotlinx.serialization.json.JsonNull)
        put("result", buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            })
            if (error) put("isError", true)
        })
    }.toString()

    // ---------------------------------------------------------------- tools

    private fun tool(
        name: String,
        desc: String,
        vararg props: Pair<String, Pair<String, Boolean>>,
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("description", desc)
        put("inputSchema", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                props.forEach { (key, spec) ->
                    put(key, buildJsonObject {
                        put("type", spec.first)
                        if (spec.second) put("description", "")
                    })
                }
            })
            put("required", buildJsonArray {
                props.filter { it.second.second }.forEach { add(JsonPrimitive(it.first)) }
            })
        })
    }

    private val TOOLS: JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("name", "guard")
                put("description",
                    "Control ZAPP's Cloudflare WARP guard on this phone. action=status reads the " +
                        "tunnel, the current /64, hops, walls and any pause. action=rotate asks " +
                        "for a new /64 now - it is a manual hop and outranks a machine-earned " +
                        "backoff. action=wall reports what the last quota wall said. Use rotate " +
                        "when a prompt comes back 'free usage limit reached'.")
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add(JsonPrimitive("status"))
                                add(JsonPrimitive("rotate"))
                                add(JsonPrimitive("wall"))
                            })
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("action")) })
                })
            },
        )
        add(
            buildJsonObject {
                put("name", "shell")
                put("description",
                    "Run a shell command on this Android phone as uid 2000 (shell), the same " +
                        "rights adb has. Use it for pm, am, input, dumpsys, settings, logcat, " +
                        "file operations under /sdcard, and anything else the device answers." +
                        "Output is stdout followed by stderr; a non-zero exit is reported.")
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "Shell command, run through sh -c.")
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("command")) })
                })
            },
        )
        add(
            buildJsonObject {
                put("name", "screen")
                put("description",
                    "Capture the phone's screen to /sdcard/zapp_screen.png and return the path " +
                        "and size. Follow with a shell call such as `base64` only if the image " +
                        "content itself is required.")
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject { })
                })
            },
        )
        add(
            buildJsonObject {
                put("name", "tap")
                put("description", "Tap the screen at pixel coordinates. The display is 1224x2720.")
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("x", buildJsonObject { put("type", "integer") })
                        put("y", buildJsonObject { put("type", "integer") })
                    })
                    put("required", buildJsonArray {
                        add(JsonPrimitive("x")); add(JsonPrimitive("y"))
                    })
                })
            },
        )
        add(
            buildJsonObject {
                put("name", "swipe")
                put("description", "Swipe from one point to another over a number of milliseconds.")
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("x1", buildJsonObject { put("type", "integer") })
                        put("y1", buildJsonObject { put("type", "integer") })
                        put("x2", buildJsonObject { put("type", "integer") })
                        put("y2", buildJsonObject { put("type", "integer") })
                        put("ms", buildJsonObject { put("type", "integer") })
                    })
                    put("required", buildJsonArray {
                        add(JsonPrimitive("x1")); add(JsonPrimitive("y1"))
                        add(JsonPrimitive("x2")); add(JsonPrimitive("y2"))
                    })
                })
            },
        )
        add(
            buildJsonObject {
                put("name", "key")
                put("description",
                    "Send a key event by name, for example KEYCODE_HOME, KEYCODE_BACK, " +
                        "KEYCODE_WAKEUP, KEYCODE_VOLUME_DOWN.")
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("keycode", buildJsonObject { put("type", "string") })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("keycode")) })
                })
            },
        )
        add(
            buildJsonObject {
                put("name", "type")
                put("description",
                    "Type text into whatever field currently has focus. Spaces become %s, " +
                        "which is what `input text` expects.")
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("text", buildJsonObject { put("type", "string") })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("text")) })
                })
            },
        )
        add(
            buildJsonObject {
                put("name", "install")
                put("description",
                    "Install an APK from a path on the phone, replacing an existing copy. " +
                        "Returns the package manager's own output.")
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject { put("type", "string") })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("path")) })
                })
            },
        )
        add(
            buildJsonObject {
                put("name", "device")
                put("description",
                    "One call that answers model, Android version, battery level, battery " +
                        "temperature and the app in the foreground.")
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject { })
                })
            },
        )
    }
}
