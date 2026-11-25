package com.zapp.net.termux

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hands the two lane payloads to Termux over 127.0.0.1, for the few seconds a relay needs.
 *
 * Why this exists - measured on this phone, 2026-11-24 15:03, in the exact state a brand-new
 * user starts in (both apps freshly installed, nothing granted):
 *
 *     appops get com.termux MANAGE_EXTERNAL_STORAGE
 *     MANAGE_EXTERNAL_STORAGE: default
 *
 * `BootRelay` used to hand Termux a `cp "$D/serve.sh" $HOME/serve.sh && cp "$D/zapp_proxy.py"
 * ... && setsid sh ...` line. With storage denied the FIRST `cp` fails, `&&` kills the chain,
 * nothing is written to `$HOME/zapp_boot.log` (`/sdcard/zapp_boot.log` still frozen at 13:58),
 * no engine starts, `$HOME/.zapp/status` never exists, and setup step 4 sits at
 * `121.1 MB once - nothing downloaded yet` forever. The staged files were always fine
 * (39,745 B + 52,685 B at 14:57). The permission was the whole bug.
 *
 * Both payloads are already in this process's memory (`Vault.serve` / `Vault.proxy`), and
 * loopback needs no permission on either side - Termux already reaches 127.0.0.1:4096 in the
 * other direction, so the route is proven. So the two files are served from here and fetched
 * inside Termux with `curl` (bootstrap) or `busybox wget` (bootstrap), and the `/sdcard`
 * copy stays as the fallback for a phone that does have the storage grant.
 *
 * Nothing is exposed: the port is random, the socket is bound to loopback only, it closes as
 * soon as both files have been handed over (or after [TTL_MS]), and the only paths it answers
 * are the two file names it was given.
 */
object PayloadServer {

    private const val TAG = "ZAPP"

    /** Long enough for Termux to cold-start its service, short enough to never linger. */
    private const val TTL_MS = 60_000L

    /** The relay is a one-shot; after this many connections the socket is closed regardless. */
    private const val MAX_CONNECTIONS = 8

    class Handle internal constructor(
        val port: Int,
        private val socket: ServerSocket,
        private val alive: AtomicBoolean,
    ) {
        /** True once every payload has been fetched at least once. */
        val done: Boolean get() = !alive.get()

        fun close() {
            alive.set(false)
            runCatching { socket.close() }
        }
    }

    /**
     * Bind a random loopback port and serve [payloads] by file name.
     *
     * @return null when the port could not be bound; the relay then falls back to `/sdcard`.
     */
    fun start(payloads: Map<String, ByteArray>, ttlMs: Long = TTL_MS): Handle? {
        if (payloads.isEmpty()) return null
        val sock = try {
            ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        } catch (t: Throwable) {
            Log.e(TAG, "payload server: cannot bind loopback: $t")
            return null
        }
        val alive = AtomicBoolean(true)
        val handle = Handle(sock.localPort, sock, alive)
        Thread {
            val deadline = System.currentTimeMillis() + ttlMs
            val sent = HashSet<String>()
            var connections = 0
            runCatching { sock.soTimeout = 600 }
            try {
                while (alive.get() && System.currentTimeMillis() < deadline &&
                    connections < MAX_CONNECTIONS && sent.size < payloads.size
                ) {
                    val c = try {
                        sock.accept()
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    } catch (_: Throwable) {
                        break
                    }
                    connections++
                    val name = runCatching { answer(c, payloads) }.getOrNull()
                    runCatching { c.close() }
                    if (name != null) sent.add(name)
                }
            } finally {
                alive.set(false)
                runCatching { sock.close() }
                Log.e(
                    TAG,
                    "payload server: closed after $connections connection(s), served ${sent.size}/${payloads.size}",
                )
            }
        }.apply {
            isDaemon = true
            name = "zapp-payload"
        }.start()
        Log.e(TAG, "payload server: ${payloads.keys} on 127.0.0.1:${sock.localPort}")
        return handle
    }

    /** Serves one request and returns the payload name it handed over, or null. */
    private fun answer(c: Socket, payloads: Map<String, ByteArray>): String? {
        runCatching { c.soTimeout = 4_000 }
        val inp = BufferedInputStream(c.getInputStream())
        val out = BufferedOutputStream(c.getOutputStream())
        val request = readRequestLine(inp) ?: return null
        val path = request.split(' ').getOrNull(1)
            ?.substringBefore('?')
            ?.trimStart('/')
            .orEmpty()
        val body = payloads[path]
        if (body == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            Log.e(TAG, "payload server: 404 '$path' (asked: $request)")
            return null
        }
        out.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/octet-stream\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        out.write(body)
        out.flush()
        Log.e(TAG, "payload server: served $path ${body.size} B")
        return path
    }

    /** One CRLF-terminated line, capped so a hostile client cannot grow the heap. */
    private fun readRequestLine(inp: BufferedInputStream): String? {
        val sb = StringBuilder(64)
        while (sb.length < 512) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trim()
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString().trim()
    }
}
