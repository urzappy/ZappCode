package com.zapp.net.net

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The lane: a local HTTP CONNECT proxy that picks the egress per connection.
 *
 * This is the Android port of the reference `src/zen_proxy.py` lane. Their order is
 * WARP -> Tor -> direct; on this phone WARP cannot be reached from this process at all
 * (the VpnService only captures `com.termux`, and an app cannot route through its own
 * tun), so the honest order is:
 *
 *   1. Tor     - SOCKS5 to the Termux daemon on 127.0.0.1:9050. A different operator
 *                entirely, which is the whole point: every WARP /64 is Cloudflare AS13335.
 *   2. Direct  - the phone's own network. Keeps the server working when Tor is down.
 *
 * Measured before writing this (2026-11-20 00:34, phone behind no VPN): Tor exit
 * `185.220.101.106` -> `https://opencode.ai/` = 200 in 2.6 s, so a Tor exit is served,
 * not challenged. That is what makes the lane worth having.
 *
 * Fail-open by design: their `_mark_fallback` records the switch, and so does [Stats].
 * A proxy that fails closed would take his chat down with it.
 *
 * ---------------------------------------------------------------------------------
 * the owner, 23 Nov: "all bout the guard system is broken not the same as before".
 *
 * It was. The lane used to be `zapp_proxy.py` under Termux, and that made the guard's
 * whole existence depend on `apt-get install python`, which measured on this phone costs
 * **96.9 MB to download and 588 MB installed** (Termux's `python` hard-depends on clang,
 * llvm, make, ndk-sysroot, pkg-config) and needs a reachable mirror. On 23 Nov 14:43 the
 * install answered `E: Unable to locate package python` in the same second, `python3`
 * never appeared, `zapp_proxy.py` never started, 41988 stayed closed - and the engine on
 * 4096 kept working, which hid it. The panel then had nothing to read, so Guard looked
 * broken while the app insisted everything was fine.
 *
 * A guard must not be hostage to a package manager. This object now serves the lane from
 * inside the app: no python, no apt, no mirror, no 96.9 MB, and it cannot be broken by a
 * Termux state we do not control. `serve.sh` still starts the python copy when one exists,
 * but only if this port is not already answering, so the two never fight.
 */
object EgressProxy {

    /**
     * The port the guard reads and `serve.sh` hands to the engine as `HTTPS_PROXY`.
     *
     * This is the lane that matters, and it must live **inside Termux**.
     *
     * Measured 23 Nov 15:15 after the lane was moved into this app: `EgressProbe.pull()`
     * asks the lane for its exit address, the lane's own connect() left from this app's uid,
     * and Android never routes a VpnService's own sockets into the tunnel it built. So every
     * reading came back as the carrier -
     *
     *     egress local: ip=2001:4455:59c:4700:3854:9d68:56ce:15a warp=off
     *
     * - and `warp=off` means "not verified", which empties `WarpPool.solid()`, which is what
     * makes the card read `Solid addresses 0 of 10` and `Token runway 0 of 2.0M`. One break,
     * every number zero.
     *
     * `zapp_lane` (static arm64, shipped in the tarball) serves this port from inside Termux,
     * where `ZappVpnService`'s tun captures it. This object therefore binds [ALT_PORT] only:
     * it is the fallback lane, and it is honest about being outside the tunnel.
     */
    const val PORT = 41988

    /** Where this app's own lane listens. Their `41987` fallback port. */
    const val ALT_PORT = 41987

    /** What `RouteStatus` asks for, and what `zen_proxy.proxy_status()` publishes. */
    private const val STATUS_ROUTE = "/__zapp/status"

    /** Their `proxy_status()` keeps a 300 s trail; so does [events]. */
    private const val EVENT_WINDOW_MS = 300_000L
    private const val EVENT_LIMIT = 80

    data class Stats(
        val running: Boolean = false,
        val port: Int = PORT,
        val lastRoute: String = "-",
        val tor: Int = 0,
        val direct: Int = 0,
        val failed: Int = 0,
        val statusReads: Int = 0,
        val lastError: String = "",
    )

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    @Volatile private var servers: List<ServerSocket> = emptyList()

    /**
     * Mirrors `GuardPrefs.tor`, so the panel switch is the truth. Set by the keeper each
     * tick; defaults on because that is where a fresh exit is worth having.
     */
    @Volatile var torEnabled: Boolean = true

    /**
     * The tabs' choice, pushed in by `LaneConfig.write` the moment one is tapped.
     *
     * Their lane reads `egress_mode.json` per connection (`load_cfg()`), which is why a tab
     * takes effect on the next request with no restart. A file in Termux's private home is
     * unreadable from here, so the same four keys are kept in memory and the Termux copy is
     * still written for the standalone python lane.
     */
    @Volatile private var mode: String = "warp"
    @Volatile private var scope: String = "isolated"
    @Volatile private var customProxy: String = ""
    @Volatile private var customType: String = "socks5"

    fun applyConfig(m: String, s: String, p: String, t: String) {
        mode = m.ifBlank { "warp" }
        scope = s.ifBlank { "isolated" }
        customProxy = p
        customType = t.ifBlank { "socks5" }
    }

    // --- the trail the panel reads ---------------------------------------------------
    private data class Event(val ts: Long, val kind: String, val detail: String, val host: String, val edge: String)

    private val events = ArrayDeque<Event>()
    private val eventLock = Any()
    @Volatile private var lastError = ""
    @Volatile private var lastKind = ""
    @Volatile private var lastDetail = ""
    @Volatile private var lastHost = ""
    @Volatile private var lastEdgeAt = 0L
    @Volatile private var activeTunnels = 0

    private fun record(kind: String, detail: String, host: String = "", edge: String = "") {
        val now = System.currentTimeMillis()
        synchronized(eventLock) {
            events.addLast(Event(now, kind, detail, host, edge))
            while (events.size > EVENT_LIMIT) events.removeFirst()
            while (events.isNotEmpty() && now - events.first().ts > EVENT_WINDOW_MS) events.removeFirst()
        }
        if (kind in ERROR_KINDS) {
            lastError = detail
            lastKind = kind
            lastDetail = detail
            lastHost = host
            lastEdgeAt = now
        }
    }

    /** Their `ERROR_KINDS`, verbatim - `proxy_status()` counts only these. */
    private val ERROR_KINDS = setOf("error", "reset", "stream_reset", "connect_fail", "edge_fail")

    private fun count(kind: String): Int = synchronized(eventLock) {
        val now = System.currentTimeMillis()
        events.count { it.kind == kind && now - it.ts <= EVENT_WINDOW_MS }
    }

    /**
     * Bind the fallback lane only, on [ALT_PORT].
     *
     * It deliberately does **not** take [PORT]. `41988` belongs to `zapp_lane` inside Termux,
     * because that is the only process whose connect() enters the VPN tun. If this object
     * grabbed 41988 first, `zapp_lane` would find the port busy and every guard reading would
     * come back `warp=off` again - the exact failure this split exists to prevent.
     */
    fun start(): Boolean {
        if (servers.isNotEmpty()) return true
        val bound = ArrayList<ServerSocket>(1)
        try {
            bound += ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", ALT_PORT), 128)
            }
        } catch (e: Exception) {
            // A stale copy of something else holding 41987 must not stop the app from
            // starting; 41987 is only the fallback.
            Log.e("ZAPP", "proxy: cannot bind 127.0.0.1:$ALT_PORT: ${e.message}")
            _stats.value = _stats.value.copy(lastError = e.message.orEmpty())
        }
        if (bound.isEmpty()) return false
        servers = bound
        _stats.value = _stats.value.copy(running = true, port = bound.first().localPort)
        record("start", "fallback lane listening on 127.0.0.1:${bound.first().localPort}")
        for (s in bound) {
            Thread({ acceptLoop(s) }, "zapp-proxy-${s.localPort}").apply { isDaemon = true }.start()
        }
        Log.e("ZAPP", "proxy: fallback lane listening on ${bound.first().localPort}")
        return true
    }

    fun stop() {
        for (s in servers) runCatching { s.close() }
        servers = emptyList()
        _stats.value = _stats.value.copy(running = false)
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try {
                s.accept()
            } catch (e: Exception) {
                break
            }
            Thread({ handle(c) }, "zapp-proxy-c").apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val head = readHead(input) ?: return
            val line = head.lineSequence().firstOrNull().orEmpty()
            val parts = line.split(' ')

            // The status route comes first. It is a plain GET on the same socket, and it is
            // the only thing the panel and `serve.sh` use to decide whether the lane is
            // alive, so it must answer even when every egress is down.
            val path = parts.getOrNull(1).orEmpty()
            if (!parts[0].equals("CONNECT", ignoreCase = true) && path.startsWith(STATUS_ROUTE)) {
                serveStatus(client)
                return
            }

            if (parts.size < 3 || !parts[0].equals("CONNECT", ignoreCase = true)) {
                client.getOutputStream().also {
                    it.write("HTTP/1.1 501 Not Implemented\r\n\r\n".toByteArray())
                    it.flush()
                }
                return
            }
            val target = parts[1]
            val host = target.substringBeforeLast(':').trim('[', ']')
            val port = target.substringAfterLast(':').toIntOrNull() ?: 443

            val picked = dial(host, port)
            if (picked == null) {
                // Logged unconditionally: a silent 502 is indistinguishable from "the server
                // never used this proxy at all", which is exactly the ambiguity that made the
                // first HTTPS_PROXY test inconclusive (20 Nov 00:41).
                _stats.value = _stats.value.copy(failed = _stats.value.failed + 1, lastError = "no route to $host:$port")
                record("connect_fail", "no route to $host:$port", host)
                Log.e("ZAPP", "proxy: no route to $host:$port")
                client.getOutputStream().also {
                    it.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                    it.flush()
                }
                return
            }
            val (sock, route) = picked
            upstream = sock
            _stats.value = _stats.value.let {
                when (route) {
                    "tor" -> it.copy(tor = it.tor + 1, lastRoute = "Tor")
                    else -> it.copy(direct = it.direct + 1, lastRoute = "direct")
                }
            }
            record("repair", "$host:$port via $route", host, route)
            Log.i("ZAPP", "proxy: $host:$port via $route")
            client.getOutputStream().also {
                it.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                it.flush()
            }
            val up = sock
            activeTunnels++
            try {
                val t = Thread({ pump(up, client) }, "zapp-proxy-up").apply { isDaemon = true }
                t.start()
                pump(client, up)
                t.join(1_000)
            } finally {
                activeTunnels--
            }
        } catch (e: Exception) {
            record("error", "${e.javaClass.simpleName}: ${e.message}")
            Log.e("ZAPP", "proxy: ${e.message}")
        } finally {
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    /**
     * The document `RouteStatus.parse` reads, key for key.
     *
     * Same keys, same shapes, same numbers as `zen_proxy.proxy_status()`: the app is not
     * inventing a parallel status, it is answering the route the reference tool answers.
     * Written by hand rather than with `JSONObject` so a hostile host string in the trail
     * cannot break the document.
     */
    private fun serveStatus(client: Socket) {
        val s = _stats.value
        val now = System.currentTimeMillis()
        val recent = synchronized(eventLock) { events.toList() }
        val sb = StringBuilder(1024)
        sb.append("{\"ok\":true")
        sb.append(",\"edge\":").append(q(if (s.lastRoute == "-") "app" else s.lastRoute.lowercase()))
        // The real tab, not a description of it: `GuardScreen` lights a tab from this value.
        sb.append(",\"mode\":").append(q(mode))
        sb.append(",\"warp_scope\":").append(q(scope))
        sb.append(",\"custom_proxy\":").append(q(customProxy))
        sb.append(",\"custom_type\":").append(q(customType))
        sb.append(",\"custom_pool_count\":").append(if (customProxy.isBlank()) 0 else 1)
        sb.append(",\"custom_current_node\":").append(q(customProxy))
        sb.append(",\"strict\":false")
        sb.append(",\"active_tunnels\":").append(activeTunnels)
        sb.append(",\"stream_resets_5m\":").append(count("stream_reset"))
        sb.append(",\"errors_5m\":").append(count("error") + count("connect_fail") + count("edge_fail"))
        sb.append(",\"fallbacks_5m\":").append(count("fallback"))
        sb.append(",\"fallback_age\":").append(
            if (lastEdgeAt <= 0L) "null" else ((now - lastEdgeAt) / 1000L).toString()
        )
        sb.append(",\"last_error\":").append(q(lastError))
        sb.append(",\"last_event\":{")
        sb.append("\"ts\":").append(lastEdgeAt / 1000L)
        sb.append(",\"kind\":").append(q(lastKind))
        sb.append(",\"detail\":").append(q(lastDetail))
        sb.append(",\"host\":").append(q(lastHost))
        sb.append(",\"edge\":\"\"}")
        sb.append(",\"recent\":[")
        recent.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            sb.append("{\"ts\":").append(e.ts / 1000L)
            sb.append(",\"kind\":").append(q(e.kind))
            sb.append(",\"detail\":").append(q(e.detail))
            sb.append(",\"host\":").append(q(e.host))
            sb.append(",\"edge\":").append(q(e.edge))
            sb.append('}')
        }
        sb.append("]}")
        val body = sb.toString().toByteArray(Charsets.UTF_8)
        _stats.value = s.copy(statusReads = s.statusReads + 1)
        client.getOutputStream().also {
            it.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
            )
            it.write(body)
            it.flush()
        }
    }

    private fun q(v: String): String {
        val sb = StringBuilder(v.length + 2)
        sb.append('"')
        for (ch in v) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u").append(String.format("%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * Route table. Each attempt is bounded, like their `EDGE_CONNECT_TIMEOUT = 3.0`:
     * a dead Tor must not eat the whole request before the direct fallback runs.
     *
     * Order is the tab's, not a guess: `custom` tries the typed endpoint, `tor` tries the
     * SOCKS5 daemon, `warp`/`clean` go straight out because this process cannot enter the
     * VpnService tun it created. Every branch falls back to direct, because a lane that
     * fails closed takes the chat down with it.
     */
    private fun dial(host: String, port: Int): Pair<Socket, String>? {
        if (mode == LaneConfig.MODE_CUSTOM && customProxy.isNotBlank()) {
            openCustom(host, port)?.let { return it to "custom" }
            record("fallback", "custom $customProxy did not answer, using direct for $host:$port", host)
        }
        if (mode != LaneConfig.MODE_CUSTOM && torEnabled && TorLane.state.value.ready) {
            runCatching {
                Socks5.connect("127.0.0.1", TorLane.SOCKS_PORT, host, port, 12_000)
            }.getOrNull()?.let { return it to "tor" }
            record("fallback", "tor did not answer, using direct for $host:$port", host)
        }
        plain(host, port, 8_000)?.let { return it to "direct" }
        return null
    }

    /** Their `open_http_tunnel` / `open_socks5_tunnel`, for the `custom` tab. */
    private fun openCustom(host: String, port: Int): Socket? {
        val hp = customProxy.substringAfter("://", customProxy)
        val ph = hp.substringBeforeLast(':').trim('[', ']')
        val pp = hp.substringAfterLast(':').toIntOrNull() ?: return null
        return runCatching {
            if (customType.equals("http", ignoreCase = true) ||
                customType.equals("https", ignoreCase = true)
            ) {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(ph, pp), 10_000)
                val out = s.getOutputStream()
                out.write(
                    ("CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n\r\n").toByteArray()
                )
                out.flush()
                val head = readHead(s.getInputStream()) ?: run { s.close(); return null }
                if (!head.lineSequence().firstOrNull().orEmpty().contains(" 200")) {
                    s.close()
                    return null
                }
                s
            } else {
                Socks5.connect(ph, pp, host, port, 12_000)
            }
        }.getOrNull()
    }

    private fun plain(host: String, port: Int, timeoutMs: Int): Socket? {
        val s = Socket()
        return try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s
        } catch (e: Exception) {
            runCatching { s.close() }
            null
        }
    }

    private fun pump(from: Socket, to: Socket) {
        val buf = ByteArray(16 * 1024)
        try {
            val i: InputStream = from.getInputStream()
            val o: OutputStream = to.getOutputStream()
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
                o.flush()
            }
        } catch (_: IOException) {
            // a closed peer is the normal way out of a proxy pipe
        } finally {
            runCatching { to.shutdownOutput() }
        }
    }

    /** Read until the blank line; returns the whole header block as text. */
    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder(256)
        var last4 = 0
        while (sb.length < 16_384) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A || last4 == 0x0A0A) break
        }
        return sb.toString()
    }
}
