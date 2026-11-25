package com.zapp.net.guard

/**
 * The proxy's own view of the route, read from loopback.
 *
 * `zen_proxy` publishes `proxy_status.json` (`_record_event` / `proxy_status:86-200`) and
 * their desktop dashboard reads that file. An Android app cannot open Termux's private
 * data directory, so the proxy serves the identical document on
 * `http://127.0.0.1:41988/__zapp/status` and this reads it. Same keys, same numbers —
 * the app is not inventing a parallel status.
 *
 * Timeout is 3.5 s, not 1.2 s: a slow edge re-pick once held the state lock long enough
 * to expire the 1.2 s budget every 3 s (measured 2026-11-20 10:29).
 */
object RouteStatus {

    const val URL = "http://127.0.0.1:41988/__zapp/status"
    private const val HOST = "127.0.0.1"
    private const val PORT = 41988
    private const val PATH = "/__zapp/status"

    data class Snapshot(
        val ok: Boolean,
        val error: String = "",
        val edge: String = "",
        val mode: String = "",
        val scope: String = "",
        /** The lane config, published by the proxy that obeys it (`proxy_status`). */
        val customProxy: String = "",
        val customType: String = "",
        val customPoolCount: Int = 0,
        val customNode: String = "",
        val strict: Boolean = false,
        val activeTunnels: Int = 0,
        val streamResets5m: Int = 0,
        val errors5m: Int = 0,
        val fallbacks5m: Int = 0,
        val fallbackAge: Int? = null,
        val lastError: String = "",
        val lastKind: String = "",
        val lastDetail: String = "",
        /** The proxy's own 300 s event trail, exactly what `proxy_status()` returns. */
        val recent: List<Event> = emptyList(),
    ) {
        /** True when the route is doing what it was built to do. */
        val healthy: Boolean get() = ok && streamResets5m == 0 && fallbacks5m == 0
    }

    /** One entry of `recent[]`: the shape `zen_proxy._record_event:86-121` writes. */
    data class Event(
        val ts: Long,
        val kind: String,
        val detail: String,
        val host: String = "",
        val edge: String = "",
    )

    fun read(timeoutMs: Int = 3500): Snapshot {
        // A raw socket, not OkHttp and not HttpURLConnection.
        //
        // Measured 2026-11-20 11:23-11:25, same device, same second, same uid:
        //   `run-as com.zapp.net` + `toybox nc 127.0.0.1 41988` -> HTTP/1.1 200, full body
        //   this OkHttp call, three times, threads 13005/11182 -> SocketTimeoutException: timeout
        //   the proxy itself, queried from Termux            -> same 200 in 0.00 s
        // An earlier version used HttpURLConnection and timed out the same way, so this is
        // not an OkHttp defect - it is something about the HTTP stack in this process on
        // loopback. `/proc/net/tcp` showed 15 connections queued on the listen socket at
        // the same moment, i.e. the client was not getting answers it could parse.
        // A plain socket is the only primitive proven to work here, so use it.
        return try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", PORT), timeoutMs)
                s.soTimeout = timeoutMs
                val out = s.getOutputStream()
                out.write(
                    ("GET $PATH HTTP/1.1\r\nHost: 127.0.0.1:$PORT\r\n" +
                        "Connection: close\r\nAccept: */*\r\n\r\n").toByteArray()
                )
                out.flush()
                val text = s.getInputStream().readBytes().toString(Charsets.UTF_8)
                val head = text.substringBefore("\r\n\r\n")
                val code = head.lineSequence().firstOrNull()
                    ?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 0
                if (code != 200) return Snapshot(false, "proxy answered $code")
                val body = text.substringAfter("\r\n\r\n", "")
                if (body.isBlank()) return Snapshot(false, "empty response")
                parse(body)
            }
        } catch (e: Exception) {
            android.util.Log.w("ZAPP", "route status: ${e.javaClass.name}: ${e.message}")
            Snapshot(false, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun parse(text: String): Snapshot {
        val o = org.json.JSONObject(text)
        val last = o.optJSONObject("last_event")
        return Snapshot(
            ok = true,
            edge = o.optString("edge", ""),
            mode = o.optString("mode", ""),
            scope = o.optString("warp_scope", o.optString("scope", "")),
            customProxy = o.optString("custom_proxy", ""),
            customType = o.optString("custom_type", ""),
            customPoolCount = o.optInt("custom_pool_count", 0),
            customNode = o.optString("custom_current_node", ""),
            strict = o.optBoolean("strict", false),
            activeTunnels = o.optInt("active_tunnels", 0),
            streamResets5m = o.optInt("stream_resets_5m", 0),
            errors5m = o.optInt("errors_5m", 0),
            fallbacks5m = o.optInt("fallbacks_5m", 0),
            fallbackAge = if (o.isNull("fallback_age")) null else o.optInt("fallback_age", 0),
            lastError = o.optString("last_error", ""),
            lastKind = last?.optString("kind", "") ?: "",
            lastDetail = last?.optString("detail", "") ?: "",
            recent = parseRecent(o),
        )
    }

    private fun parseRecent(o: org.json.JSONObject): List<Event> {
        val arr = o.optJSONArray("recent") ?: return emptyList()
        val out = ArrayList<Event>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            out += Event(
                ts = e.optLong("ts", 0L),
                kind = e.optString("kind", ""),
                detail = e.optString("detail", ""),
                host = e.optString("host", ""),
                edge = e.optString("edge", ""),
            )
        }
        return out
    }
}
