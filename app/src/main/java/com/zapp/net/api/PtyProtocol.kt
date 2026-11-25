package com.zapp.net.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The wire format of their persistent PTY.
 *
 * Everything here was lifted from `solid-B85sHMHe.js` in the desktop build and then checked
 * against their own minified functions running in Node (`theirs-pty.mjs`), so the byte output and
 * the URL string are not my guess.
 *
 * Two functions carry the whole protocol:
 *
 * ```
 * function F(e,t){                                  // client -> server, one binary frame
 *   let n=new Uint8Array(5+(t?.byteLength??0)), r=new DataView(n.buffer);
 *   n[0]=+!!t, r.setUint16(1,e.cols), r.setUint16(3,e.rows), t&&n.set(t,5), n }
 *
 * function I(e,t){ return { async connect(n){
 *   let r = await e.experimental.persistentPty.connectToken({ptyID:n.ptyID,"x-opencode-ticket":`1`});
 *   let i = new URL(t.url); i.pathname.endsWith(`/`) || (i.pathname += `/`);
 *   let a = new URL(`api/experimental/persistent-pty/${encodeURIComponent(n.ptyID)}/connect`, i);
 *   a.searchParams.set(`ticket`, r.ticket);
 *   a.searchParams.set(`cursor`, String(n.cursor));
 *   a.searchParams.set(`attachment_id`, n.attachmentID);
 *   a.searchParams.set(`takeover`, String(n.takeover ?? false));
 *   a.searchParams.set(`input_protocol`, `1`);
 *   a.protocol = a.protocol === `https:` ? `wss:` : `ws:`;
 *   let o = t.openSocket?.(a) ?? new WebSocket(a);
 *   return o.binaryType = `arraybuffer`, o } } }
 * ```
 *
 * Note the asymmetry, because it matters: a **client** frame carries the 5-byte header, a
 * **server** frame is raw output bytes (`M.output(new Uint8Array(e.data))` - no header at all).
 */
object PtyProtocol {

    /** `ae.fields.size` - the size object the server and the client both send. */
    data class Size(val cols: Int, val rows: Int)

    /**
     * `F(e, t)` - the only binary frame a client sends.
     *
     * `[0]` is `+!!payload` (1 when a payload follows), then two **big-endian** `uint16`s
     * (`DataView.setUint16` defaults to big-endian), then the payload verbatim. A frame with no
     * payload is exactly 5 bytes and still carries the current size - that is how a resize is
     * sent.
     */
    fun inputFrame(cols: Int, rows: Int, payload: ByteArray? = null): ByteArray {
        val out = ByteArray(5 + (payload?.size ?: 0))
        out[0] = if (payload != null) 1 else 0
        val c = cols.coerceIn(0, 0xFFFF)
        val r = rows.coerceIn(0, 0xFFFF)
        out[1] = ((c ushr 8) and 0xFF).toByte()
        out[2] = (c and 0xFF).toByte()
        out[3] = ((r ushr 8) and 0xFF).toByte()
        out[4] = (r and 0xFF).toByte()
        if (payload != null) payload.copyInto(out, 5)
        return out
    }

    /**
     * `encodeURIComponent`. Escapes everything except `A-Z a-z 0-9 - _ . ! ~ * ' ( )`, as UTF-8
     * with **uppercase** hex. `URLEncoder` is not this: it turns a space into `+`.
     */
    fun encodeComponent(s: String): String {
        val keep = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
        val sb = StringBuilder(s.length)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val ch = (b.toInt() and 0xFF).toChar()
            if (ch in keep) sb.append(ch) else sb.append('%').append(HEX[(b.toInt() and 0xFF) ushr 4])
                .append(HEX[b.toInt() and 0x0F])
        }
        return sb.toString()
    }

    /**
     * `URLSearchParams` serialisation - the `application/x-www-form-urlencoded` set, which is
     * **not** `encodeURIComponent`: a space becomes `+`, and `~ ! ' ( )` are escaped. Their URL
     * shows it: ticket `a b&c=d` -> `a+b%26c%3Dd`.
     */
    fun formEncode(s: String): String {
        val keep = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789*-._"
        val sb = StringBuilder(s.length)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            val ch = v.toChar()
            when {
                ch == ' ' -> sb.append('+')
                ch in keep -> sb.append(ch)
                else -> sb.append('%').append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
        }
        return sb.toString()
    }

    private const val HEX = "0123456789ABCDEF"

    /**
     * The `WebSocket` URL their `I(...).connect` builds.
     *
     * The base is the server origin the app already talks to (`http://127.0.0.1:4096`). Their
     * code appends a `/` to the pathname before resolving, so a base with a path
     * (`ws://h:1/base`) becomes `ws://h:1/base/api/...`, and a bare origin becomes
     * `ws://host:port/api/...`. An empty [ptyID] therefore yields `//connect`, exactly as theirs
     * does - the caller is expected to have a real id.
     */
    fun connectUrl(
        base: String,
        ptyID: String,
        ticket: String,
        cursor: Long,
        attachmentID: String,
        takeover: Boolean,
    ): String {
        val b = base.trim()
        val schemeEnd = b.indexOf("://")
        val scheme = if (schemeEnd > 0) b.substring(0, schemeEnd).lowercase() else "http"
        val afterScheme = if (schemeEnd > 0) b.substring(schemeEnd + 3) else b
        val authority = afterScheme.substringBefore('/')
        var path = afterScheme.substring(authority.length)
        path = path.substringBefore('?').substringBefore('#')
        if (!path.endsWith("/")) path += "/"
        val wsScheme = if (scheme == "https" || scheme == "wss") "wss" else "ws"
        val q = buildString {
            append("ticket=").append(formEncode(ticket))
            append("&cursor=").append(cursor)
            append("&attachment_id=").append(formEncode(attachmentID))
            append("&takeover=").append(if (takeover) "true" else "false")
            append("&input_protocol=1")
        }
        return "$wsScheme://$authority$path" +
            "api/experimental/persistent-pty/${encodeComponent(ptyID)}/connect?$q"
    }

    /**
     * One text frame from the server. Their schema (`var N=y([...])` in `solid-B85sHMHe.js`) has
     * seven object variants and no union of anything else, but a newer server may add one, so an
     * unrecognised `type` is kept rather than dropped.
     */
    sealed interface Event {
        data class Attached(val inputProtocol: Int?, val size: Size?, val role: String?) : Event
        data object ReplayComplete : Event
        data class Resized(val cols: Int, val rows: Int, val checkpoint: String?) : Event
        data class ControllerChanged(val attachmentID: String?) : Event
        data class Exited(val exitCode: Int?) : Event
        data class TitleChanged(val title: String) : Event
        data class ForegroundProcessChanged(val process: String?) : Event
        data class Unknown(val type: String) : Event
    }

    /** `P(e)` - parse one text frame. Never throws; malformed input becomes [Event.Unknown]. */
    fun decode(text: String): Event {
        val o = runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return Event.Unknown("")
        return when (val t = o.str("type").orEmpty()) {
            "attached" -> {
                val size = o["info"]?.let { runCatching { it.jsonObject }.getOrNull() }
                    ?.get("size")?.let { runCatching { it.jsonObject }.getOrNull() }
                    ?.let { s ->
                        val c = s.int("cols")
                        val r = s.int("rows")
                        if (c != null && r != null) Size(c, r) else null
                    }
                Event.Attached(
                    inputProtocol = o.int("inputProtocol"),
                    size = size,
                    role = o.str("role"),
                )
            }
            "replay_complete" -> Event.ReplayComplete
            "resized" -> Event.Resized(
                cols = o.int("cols") ?: 0,
                rows = o.int("rows") ?: 0,
                checkpoint = o.str("checkpoint"),
            )
            "controller_changed" -> Event.ControllerChanged(o.str("attachmentID"))
            "exited" -> Event.Exited(o.int("exitCode"))
            "title_changed" -> Event.TitleChanged(o.str("title").orEmpty())
            "foreground_process_changed" -> Event.ForegroundProcessChanged(o.str("process"))
            else -> Event.Unknown(t)
        }
    }

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = false }

    private fun JsonObject.str(key: String): String? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p.content == "null") return null
        return p.content
    }

    private fun JsonObject.int(key: String): Int? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p.content == "null") return null
        return p.content.toIntOrNull()
    }
}
