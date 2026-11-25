package com.zapp.net.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Real egress measurement.
 *
 * Only Termux rides the tunnel (`addAllowedApplication("com.termux")`), so the app
 * itself cannot see the address Cloudflare reports — asking the app would always
 * answer with the carrier /64. The probe sends a shell command into Termux via
 * RUN_COMMAND; Termux curls the trace endpoint from inside the tunnel and broadcasts
 * the answer back with the nonce it was given. A stale or spoofed reply cannot
 * satisfy a nonce it never saw.
 */
object EgressProbe {

    /**
     * The lane's CONNECT proxy. Same listener `RouteStatus` reads (`127.0.0.1:41988`), and the
     * port the OpenCode server is pointed at by `service.json`'s `HTTPS_PROXY`.
     */
    private const val LANE_PORT = 41988

    data class Reading(
        val ip: String,
        val warp: String,
        val prefix64: String,
        val atMs: Long = System.currentTimeMillis(),
        /**
         * The lane proxy was not listening at all, so nothing was measured.
         *
         * This is not "the edge is silent" and must never be read as one. The proxy on
         * [LANE_PORT] lives inside Termux and is started by `serve.sh`; on a clean install it
         * does not exist until the engine runs, and a connect to a closed local port fails in
         * about a millisecond. Measured 22 Nov 22:16:45 after a reinstall: three verify reads
         * 363 ms apart, all reported as "no reply (probe timed out)", and the registration was
         * discarded and re-rolled on a tunnel that had come up one second earlier.
         */
        val laneDown: Boolean = false,
    ) {
        /**
         * Their `get_egress_v6` rule: an answer without a colon is the IPv4 lane. WARP keeps
         * its IPv6 on the adapter even when it has stopped carrying IPv6, so a v4 answer means
         * requests left over IPv4 — and "no amount of /64 rotation changes the server-side
         * bucket" (their README, §171). Such a reading can never prove a rotation.
         */
        val isV6: Boolean get() = ip.contains(":")

        /**
         * Did the probe actually see anything. `ip=""` is "unknown", and it must never be
         * read as either a v4 leak or a measured address — measured 04:58:46, where an empty
         * answer was reported as `traffic left outside WARP ()`.
         */
        val known: Boolean get() = ip.isNotBlank()

        /** a /64 that may be recorded as this identity's measured egress ("" for v4) */
        val v6Prefix: String get() = if (isV6) prefix64 else ""
    }

    @Volatile private var pending: CompletableDeferred<Reading>? = null
    @Volatile private var pendingNonce: String = ""
    private val mutex = Mutex()

    @Volatile var last: Reading? = null
        private set

    /**
     * Newest reading that actually named an IPv6 address. [last] is written by any probe, and
     * two probes run concurrently all the time (a tunnel verify and a hop's before-reading),
     * so `last` can be the IPv4 lane even though a /64 was measured a moment earlier. The wall
     * handler must never ban from `last` for that reason: measured 05:06:20, `last` held
     * `104.28.220.28`, `through` came out true, the prefix was blank, and the engine logged
     * `no tunnel egress to retire` - so the walled /64 was never banned and the next hop could
     * walk straight back into it.
     */
    @Volatile var lastV6: Reading? = null
        private set

    /** /64 of an IPv6 egress, /24 of an IPv4 one: the unit Cloudflare meters on. */
    fun prefixOf(ip: String): String {
        val clean = ip.trim().substringBefore("%")
        if (clean.isEmpty()) return ""
        if (!clean.contains(":")) {
            val p = clean.split(".")
            return if (p.size == 4) p.take(3).joinToString(".") + ".0/24" else clean
        }
        val parts = clean.split(":")
        if (parts.size < 4) return clean
        return parts.take(4).joinToString(":") + "::/64"
    }

    fun deliver(nonce: String, ip: String, warp: String) {
        if (nonce != pendingNonce) return
        val r = Reading(ip.trim(), warp.trim(), prefixOf(ip))
        last = r
        if (r.isV6) lastV6 = r
        pending?.complete(r)
    }

    /**
     * The relay ran and produced nothing. Complete the waiter with an empty reading so the
     * caller stops waiting; [Reading.isV6] is false and every caller already requires a
     * non-blank `ip`, so this reads as "unknown", which is what it is.
     */
    fun deliverUnknown(nonce: String) {
        if (nonce != pendingNonce) return
        pending?.complete(Reading("", "", ""))
    }

    /**
     * Wait until Android has a VPN network registered, then return true.
     *
     * `backend.setState(.., UP, ..)` returning successfully is not the same as a tunnel being
     * present: measured 05:10:13-05:10:15, the backend reported UP with `err=none` while
     * `ip -br addr` showed no `tun0`, and the probe that ran in that gap answered `warp=off`
     * with the carrier address. A blank or carrier reading costs a full verify and an edge
     * re-roll - the "verification takes forever" symptom, on a tunnel that had not finished.
     *
     * The interface itself is NOT visible from here: `NetworkInterface.getByName("tun0")`
     * returned null on every probe at 05:18 while the tunnel was demonstrably carrying
     * (`egress: ip=2a09:bac5:4ff4:1d05::2e4:d1 warp=on`), because GoBackend's VpnService owns
     * it. The registered VPN network is the signal that does work.
     */
    suspend fun awaitVpn(ctx: Context, maxMs: Long = 4_000): Boolean {
        val deadline = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < deadline) {
            if (hasVpn(ctx)) return true
            kotlinx.coroutines.delay(120)
        }
        return hasVpn(ctx)
    }

    /** Is any VPN network currently registered with the system? */
    fun hasVpn(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return@runCatching false
        cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }.getOrDefault(false)

    /**
     * Is there a real network underneath us - WiFi or mobile - with internet capability?
     *
     * This exists because a failed probe was being read as "the edge is dead" when the phone
     * itself had no route. `allNetworks` is used, not `activeNetwork`: while our own tunnel is
     * up the default network IS the VPN, so the default would always answer "yes" and the check
     * would never fire. Anything carrying TRANSPORT_VPN is skipped for the same reason.
     *
     * Measured 2026-11-21 10:22:35-10:22:41: three verify reads failed back to back on
     * `162.159.192.3:2408` in 6 s and the edge was retired for two minutes. Six such reads in a
     * row tear the whole tunnel down and block carrying for `CARRY_BLOCK_MS`. A WiFi-to-LTE
     * handover, or XOS freezing the radio for a moment, produces exactly the same three reads -
     * and that is the "laging namamatay yung tunnel" he reported. With no underlying network the
     * probe says nothing about the edge, so nothing may be retired or rebuilt on its verdict.
     */
    fun hasUnderlyingNetwork(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return@runCatching false
        val nets = cm.allNetworks ?: return@runCatching false
        nets.any { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@any false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
        }
    }.getOrDefault(false)

    /**
     * Run a trace inside Termux and wait for the broadcast back.
     * Returns null on timeout - callers must treat null as "unknown", never as "fine".
     *
     * Serialised. There is ONE pending slot and ONE nonce: two callers at once (a tunnel
     * verify and a hop's before-measurement, which is exactly what happens when a hop
     * lands mid-verify) used to overwrite each other, so both timed out and the verify
     * then tore down the brand-new tunnel it had never actually tested.
     */
    suspend fun measure(ctx: Context, timeoutMs: Long = 35_000): Reading? {
        // ONE budget for the whole measurement, not one per stage.
        //
        // Measured 05:40:15 -> 05:40:52: a single hop read took 37 s. The cause was this
        // function adding budgets together - 13 s for the in-process read, then
        // `timeoutMs + 3_000` for the Termux relay - so a fresh rebuild that failed both stages
        // cost 29 s per read, three reads per edge. Callers ask for 13 s and must get 13 s.
        val deadline = System.currentTimeMillis() + timeoutMs
        // In-process only. No Termux, no broadcast, no ROM freeze.
        //
        // Measured 06:03:11 -> 06:03:24: `tunnel UP` then `read 1/3: no reply` took 13 s on a
        // registration that carried nothing. The address itself is right: `measureLocal` gave
        // up after its 4 s per-attempt budget, and then this function handed the leftover 9 s
        // to the Termux relay - which cannot answer either, because Termux is inside the very
        // tunnel that is dead. Their `get_egress_v6` has no relay for exactly this reason: one
        // request, one timeout, then the answer is "nothing".
        //
        // This package is now inside its own tunnel (see the iface builder in ZappVpnService),
        // so the relay no longer measures anything the in-process probe cannot. It stays
        // available to the diagnostic path only.
        return withTimeoutOrNull(timeoutMs) {
            // A closed local port is not a measurement. Ask first, so the answer carries the
            // reason instead of arriving as a blank that every caller reads differently.
            //
            // ONE 250 ms loopback connect is not an answer either. Measured 23 Nov 22:50:46 on
            // v57: `egress probe: lane 127.0.0.1:41988 is not listening` while the lane had
            // been up since 22:28 (`started_ago_s=1577`) and answered `/__zapp/status` seconds
            // later. The lane was fine; a single connect landed in a moment it could not accept
            // (the process was being rescheduled while the tunnel churned) and the whole
            // measurement was abandoned on that one sample. Three probes over ~300 ms cost
            // nothing when the lane really is down - a refused connect returns instantly - and
            // they stop a scheduling hiccup from being reported as a dead lane.
            var up = laneUp()
            var tries = 1
            while (!up && tries < 3) {
                kotlinx.coroutines.delay(150)
                up = laneUp()
                tries++
            }
            if (!up) {
                Log.e("ZAPP", "egress probe: lane 127.0.0.1:$LANE_PORT is not listening ($tries probes)")
                return@withTimeoutOrNull Reading("", "", "", laneDown = true)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { measureLocal(timeoutMs) }
        }
    }

    /**
     * Is the lane's CONNECT proxy accepting connections right now.
     *
     * 250 ms is generous for a loopback connect: an open listener answers in under a
     * millisecond and a closed port refuses just as fast, so this never adds noticeable time
     * to a probe that was going to fail anyway.
     */
    fun laneUp(): Boolean = runCatching {
        java.net.Socket().use {
            it.connect(java.net.InetSocketAddress("127.0.0.1", LANE_PORT), 250)
            true
        }
    }.getOrDefault(false)

    /**
     * Diagnostic-only: ask Termux to curl inside the tunnel.
     *
     * Not used by [measure] any more (see the note there). Kept because it is the only way to
     * prove a reading when the app itself is outside the tunnel, which happens whenever the
     * package is dropped from the iface builder's `includeApplication` list.
     */
    @Suppress("unused")
    suspend fun measureViaTermux(ctx: Context, timeoutMs: Long): Reading? {
        val left = timeoutMs
        if (left < 3_500) return null
        return withTimeoutOrNull(left) { measureLocked(ctx, left) }
    }

    /**
     * Read our own egress from inside the app.
     *
     * This package is included in its own tunnel, so a plain HTTPS request from here leaves
     * through the same WARP identity Termux uses. That removes the dependency that broke
     * every read at 05:30:44, when the ROM froze Termux mid-probe
     * (`Usf_Hiber: freeze uid: 10448 com.termux ... last unfreeze 5s ago`) and each read
     * waited its full 13 s to report "no reply".
     *
     * SHAPE COPIED FROM THEIRS (`ouc_common.get_egress_v6`, read 06:02):
     *
     *    curl -x socks5h://127.0.0.1:40000 -s -m 5 https://api64.ipify.org
     *    ... and `is_cloudflare_v6()` decides WARP from the address itself:
     *    `2606:` / `2a09:` -> through WARP, anything else -> outside.
     *
     * ONE request, FIVE seconds. Ours used to sweep three URLs sequentially, each with a
     * 4 s connect plus a 4 s read, so a single failed read cost 21 s and a verify cost 63 s
     * (measured 05:58:22 -> 05:59:25, three `no reply (probe timed out)`). That wall of
     * timeouts was the whole "so slow to connect" complaint.
     *
     * The two attempts now run CONCURRENTLY, so a read that fails completely costs one
     * timeout instead of two. The trace URL is still first because it carries Cloudflare's
     * own `warp=` verdict, which the leak gate trusts; api64 is the plain shape theirs uses
     * and needs no `warp=` field because the prefix is the answer.
     */
    private suspend fun measureLocal(timeoutMs: Long): Reading? = coroutineScope {
        // One attempt may not outlive the caller's budget, and 4 s is the attempt ceiling.
        // 7 s, not 4: with the new split that is CONNECT 2.3 s + READ 4.7 s, which is the
        // first shape that actually fits a CONNECT + TLS + answer through a fresh tunnel.
        val perTry = timeouts(timeoutMs, ok = 7_000, fail = 7_000)
        val urls = listOf(
            // IPv6 literal: no DNS, which is the state for the first seconds after a rebuild.
            "https://[2606:4700:4700::1111]/cdn-cgi/trace",
            // Their probe. Plain, shortest possible, and answers with the address alone.
            "https://api64.ipify.org",
        )
        // MEASURED DEFECT, fixed here. The previous shape was
        //     pending.firstNotNullOfOrNull { it.await() }
        // which awaits IN ORDER: when the literal failed, it still had to await the second
        // entry, and `api64.ipify.org` needs a DNS lookup that `connectTimeout` does not
        // bound. On a dead tunnel the resolver itself is the cost - measured 06:30:26.259 ->
        // 06:30:39.495, 13.2 s for one read while the literal had already failed in 3 s.
        //
        // Now every attempt is wrapped in `withTimeoutOrNull`, so the deadline returns even
        // though a blocking DNS thread cannot be interrupted, and the results are collected
        // together: total cost is the slower attempt (~4 s), never their sum.
        val pending = urls.map { u ->
            async(Dispatchers.IO) {
                withTimeoutOrNull(perTry.toLong()) {
                    runCatching { pull(u, perTry) }.getOrNull()
                }
            }
        }
        val results = pending.awaitAll()
        // First non-null in URL order: the literal trace carries Cloudflare's own `warp=`
        // verdict, which the leak gate trusts, so it outranks the bare address.
        results.firstNotNullOfOrNull { it }
    }

    /** One request, parsed. Both shapes are accepted: trace `ip=`/`warp=` and a bare address. */
    private fun pull(url: String, timeoutMs: Int): Reading? {
        val body = runCatching {
            // THROUGH THE LANE, ALWAYS.
            //
            // A VpnService's own sockets never enter the tunnel it built - Android exempts the
            // owner by design. So an HttpURLConnection opened from this app can only ever see
            // the carrier, no matter which URL is asked. Measured 2026-11-20 12:13-12:34:
            // every `egress local:` line read `2001:4455:59c:4700:... warp=off` - the carrier -
            // while the same lane answered `2a09:bac5:4ff4:263c::3cf:56 warp=on colo=HKG`
            // through 41988. That is why the guard card sat on "unproven" and why the tunnel
            // verify kept concluding `answered outside WARP ... holding, no rebuild` on a
            // tunnel that was demonstrably carrying traffic.
            //
            // Their probe has the same shape and does the same thing: `get_egress_ip` asks the
            // lane (`ouc_common.py:602-678`) and only curls bare as a last resort. Here the
            // lane is our own CONNECT proxy on 41988, so the request is proxied instead of
            // being sent down a road the app's traffic never takes.
            val conn = java.net.URL(url).openConnection(
                java.net.Proxy(
                    java.net.Proxy.Type.HTTP,
                    java.net.InetSocketAddress("127.0.0.1", LANE_PORT),
                )
            ) as java.net.HttpURLConnection
            // CONNECT and READ are SEQUENTIAL budgets, not one overall deadline: 4 s + 4 s
            // meant a dead read still cost 8 s (measured 06:06:29.757 -> 06:06:39.694 =
            // 9.9 s). Split, so the whole attempt really is bounded by the number below.
            //
            // MEASURED 22 Nov 09:42: the split was `connect = 3/5` and `read = the rest`, so a
            // 4 s attempt gave CONNECT 2.4 s and the READ only 1.6 s. A CONNECT through the lane
            // plus a TLS handshake plus Cloudflare's answer does not fit in 1.6 s on a freshly
            // rebuilt tunnel, so every read timed out while the lane answered a plain curl in
            // the same second (`2a09:bac1:5aa0:40::498:4e`). Three dead reads then made the
            // verifier re-roll a healthy edge, and the re-roll rebuilt the TUN - which killed
            // the next probe. That loop is the whole "6 s churn".
            val connect = (timeoutMs / 3).coerceIn(1_500, 3_500)
            val read = (timeoutMs - connect).coerceAtLeast(2_500)
            conn.connectTimeout = connect
            conn.readTimeout = read
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "curl/8.0") // their probe's shape
            try {
                if (conn.responseCode !in 200..299) "" else
                    conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                runCatching { conn.disconnect() }
            }
        }.onFailure {
            Log.e("ZAPP", "egress local failed on $url: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault("")
        if (body.isBlank()) return null

        val traceIp = Regex("(?m)^ip=(.+)$").find(body)?.groupValues?.get(1)?.trim().orEmpty()
        // api64 answers with the address and nothing else.
        val bare = body.trim().takeIf { it.length in 2..64 && !it.contains('\n') }.orEmpty()
        val ip = traceIp.ifBlank { bare }
        if (ip.isBlank() || !ip.any { it.isDigit() }) return null

        // Their `is_cloudflare_v6`: WARP owns 2606:/2a09:, everything else left outside it.
        // Cloudflare's own `warp=` still wins when the trace provided one.
        val traceWarp = Regex("(?m)^warp=(.+)$").find(body)?.groupValues?.get(1)?.trim().orEmpty()
        val warp = traceWarp.ifBlank {
            val l = ip.substringBefore("%").lowercase()
            if (l.startsWith("2606:") || l.startsWith("2a09:")) "on" else "off"
        }
        Log.e("ZAPP", "egress local: ip=$ip warp=$warp ($url)")
        return Reading(ip, warp, prefixOf(ip))
    }

    /** Budget for one attempt: whatever the caller has, between the two bounds. */
    private fun timeouts(total: Long, ok: Int, fail: Int): Int =
        if (total >= 8_000) ok else fail.coerceIn(1_000, 6_000)
    private suspend fun measureLocked(ctx: Context, timeoutMs: Long): Reading? = mutex.withLock {
        // Never curl before a VPN network is registered. Measured 05:10:14: a probe fired 1.2 s
        // after the backend reported UP, no tunnel was registered yet, and the answer was the
        // carrier address with `warp=off` - which the verify correctly refuses to accept, so the
        // same address had to be probed again seconds later.
        val vpn = awaitVpn(ctx, 3_000)
        if (!vpn) Log.e("ZAPP", "egress probe: no VPN network after 3 s - probing anyway")
        val nonce = "e" + System.currentTimeMillis().toString(36)
        val waiter = CompletableDeferred<Reading>()
        pending = waiter
        pendingNonce = nonce

        // ONE curl, not two. The first version fetched the trace twice (once for ip,
        // once for warp) at -m 12 each inside a 25 s window, so right after a tunnel
        // rebuild the first curl ate the whole budget and the hop recorded
        // egress64=null. Both fields come from a single response.
        val script = buildString {
            // IPv6 first. The /64 is the unit Cloudflare meters and the unit we rotate; the
            // v4 egress lives in shared 104.28.x /24s, so banning one of those can burn a
            // block other people are using. v4 is only the fallback.
            //
            // Worst case here is 5 + 4 + 4 s of curl plus Termux's own spawn/broadcast, so a
            // caller must allow at least ~15 s. The previous 10 + 8 s shape could outlive
            // a 12 s verify budget and made a healthy tunnel report "VERIFY failed"
            // (measured 21:12:02: re-roll issued, egress answered 0.2 s later).
            //
            // The first attempt uses Cloudflare's resolver address as a literal so it needs
            // no DNS. Every probe failure measured tonight (04:43:50 `egress=unmeasured`,
            // twice in a row) landed within seconds of a tunnel rebuild, which is exactly
            // when the resolver inside the fresh tun is not answering yet. A literal address
            // removes that failure mode instead of widening the timeout for it.
            // Worst case here is 5 + 4 + 4 s of curl plus Termux's own spawn/broadcast. A
            // healthy read through a settled tunnel was measured at 1.06 s end to end
            // (05:03:02.681 start -> 03.742 done), but a read on a freshly rebuilt tunnel is
            // slower - at 3 s ceilings every post-rebuild read came back blank (05:22), and
            // blank reads were being read as dead edges.
            // THE PROBE MUST TAKE THE LANE'S PATH, NOT A DIRECT ONE.
            //
            // Theirs does exactly this (`ouc_common.get_egress_ip:602-678`): mode warp/clean with
            // an isolated scope probes through the tunnel's SOCKS listener, mode tor through
            // `socks5h://127.0.0.1:9050`, mode custom through that proxy - and only falls back
            // to a bare `curl -6` when no proxy is available (`:659`). The `-6` is dropped when
            // a proxy is used and the IPv6 target is given as a literal instead (`:629,655`),
            // because the proxy decides the family upstream.
            //
            // Ours built its script by hand and curled straight out of Termux, which is a route
            // the app's own requests never use. Measured 2026-11-20 12:13-12:19: every reading
            // was `ip=2001:4455:59c:4700:... warp=off` - the carrier - while the same lane
            // answered `2a09:bac5:4ff4:263c::3cf:56 warp=on colo=HKG` when probed through
            // 41988 at 12:21. So the panel's "unproven", and the before/after comparison the
            // rotator trusts, were both reading an address the lane never used.
            append("am broadcast -n com.zapp.net/.termux.EgressReceiver -a com.zapp.net.EGRESS --es nonce $nonce --es stage start >/dev/null 2>&1; ")
            append("P=http://127.0.0.1:41988; ")
            // 7 s for the first lane attempt: after a rebuild the proxy's own
            // `open_local_v6` spends up to 5 s waiting for the kernel to make the tunnel
            // the route (`zapp_proxy.LOCAL_V6_WAIT_SECONDS`), exactly as theirs waits 18 s
            // in `_WarpState.get`. A 4 s curl cut that wait short and the read came back
            // empty on a tunnel that was about to carry.
            append("t=\$(curl -x \$P -s -m 7 'https://[2606:4700:4700::1111]/cdn-cgi/trace'); ")
            append("[ -z \"\$t\" ] && t=\$(curl -x \$P -s -m 2 https://api64.ipify.org); ")
            // Lane down or proxy refusing: a direct read is still worth reporting, and `warp=off`
            // is what distinguishes it from a real tunnel answer.
            append("[ -z \"\$t\" ] && t=\$(curl -6 -s -m 1 'https://[2606:4700:4700::1111]/cdn-cgi/trace'); ")
            append("[ -z \"\$t\" ] && t=\$(curl -4 -s -m 1 https://api64.ipify.org); ")
            append("ip=\$(printf '%s\\n' \"\$t\" | sed -n 's/^ip=//p' | head -n1); ")
            append("w=\$(printf '%s\\n' \"\$t\" | sed -n 's/^warp=//p' | head -n1); ")
            // `am` refuses `--es name ""` ("argument --es requires a value"), so an empty
            // answer used to make this whole broadcast fail with its stderr sent to /dev/null.
            // The app then waited out its entire timeout on a reply that could never come:
            // measured 04:57:36/38/41, three `stage=start` receipts and not one `stage=done`.
            // Send the marker on its own when there is nothing to report, so the receiver can
            // answer "unknown" immediately.
            append("if [ -n \"\$ip\" ]; then ")
            append("am broadcast -n com.zapp.net/.termux.EgressReceiver -a com.zapp.net.EGRESS ")
            append("--es nonce $nonce --es ip \"\$ip\" --es warp \"\$w\" --es stage done >/dev/null 2>&1; ")
            append("else ")
            append("am broadcast -n com.zapp.net/.termux.EgressReceiver -a com.zapp.net.EGRESS ")
            append("--es nonce $nonce --es stage done >/dev/null 2>&1; ")
            append("fi")
        }

        val i = Intent("com.termux.RUN_COMMAND").setClassName(
            "com.termux", "com.termux.app.RunCommandService",
        )
        i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh")
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        runCatching { ctx.startForegroundService(i) }
            .onFailure { Log.e("ZAPP", "egress probe relay failed: $it") }

        val got = withTimeoutOrNull(timeoutMs) { waiter.await() }
        if (pending === waiter) {
            pending = null
            pendingNonce = ""
        }
        return got
    }
}

/** Termux broadcasts the trace result here; the nonce is the only credential needed. */
class EgressReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nonce = intent.getStringExtra("nonce") ?: return
        val ip = intent.getStringExtra("ip").orEmpty()
        val warp = intent.getStringExtra("warp").orEmpty()
        val stage = intent.getStringExtra("stage").orEmpty()
        // Every receipt is logged, including the `start` marker and blank answers. Without
        // this, "the script never ran" and "the script ran and caught nothing" look identical
        // from the app side, and the caller just sits out its whole timeout.
        Log.e("ZAPP", "egress rx: stage=$stage ip=${ip.ifBlank { "-" }} warp=${warp.ifBlank { "-" }}")
        // `start` is a liveness marker, not an answer. It carries no ip by definition, and
        // treating that blank as "unknown" completed the waiter the instant the script
        // began, so every probe answered before its curl could return. Measured 05:01:12:
        //   egress rx: stage=start ip=- warp=-
        //   egress rx: stage=done  ip=2a09:bac1:5ae0:40::4dc:25 warp=on   (364 ms later)
        // and the caller of `start` had already given up, which surfaced as
        // `tunnel VERIFY: no edge answered -> discard registration` on a tunnel that was
        // demonstrably carrying traffic.
        if (stage == "start") return
        if (ip.isBlank()) {
            // Answer "unknown" now instead of never: the waiter is completed so the caller
            // can retry or move on, rather than blocking until its timeout expires.
            EgressProbe.deliverUnknown(nonce)
            return
        }
        EgressProbe.deliver(nonce, ip, warp)
        Log.e("ZAPP", "egress: ip=$ip warp=$warp")
    }
}
