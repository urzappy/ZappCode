package com.zapp.net.guard

import kotlin.random.Random

/**
 * Edge pinning — the reference tool's "Pinned Edge / Auto-Random Edge" lane, and its
 * endpoint table verbatim (`clean_endpoint.py`, `KNOWN_CLEAN_ENDPOINTS`).
 *
 * WHAT THIS TABLE DOES NOT DO. An earlier version of this comment claimed "the edge, not the
 * keypair, decides which Cloudflare prefix we land on". Measured 06:17-06:19 with all 27
 * edges driven one at a time, that is false here:
 *
 *     162.159.195.1  -> 2a09:bac1:5ac0:40::/64     (already burned)
 *     162.159.195.5  -> 2a09:bac5:4ff2:2e1e::/64   (already burned)
 *     162.159.195.3  -> 2001:4455:59c:4700:...      warp=off  (tunnel carried nothing)
 *     162.159.193.9  -> no reply on all three reads
 *     162.159.193.10 -> no reply on all three reads
 *
 * Different edges, same /48s. Cloudflare is mapping this location onto a fixed set of egress
 * /48s regardless of which anycast endpoint the handshake goes to, so the table width buys
 * latency and liveness, not address diversity. The burned set observed tonight is exactly
 * seven /48s (`2a09:bac1:5a80/5aa0/5ac0` + `2a09:bac5:4ff2/4ff3/4ff4/4ff5`).
 *
 * The table is still worth having - a dead edge costs a full rebuild, and the reference tool
 * ships these 23 entries for exactly that reason - but it is not the lever that resets a
 * quota. Their ports are not decoration either: :500/:4500/:1701/:854/:880 are the
 * alternatives when a carrier filters UDP 2408.
 *
 * Latency is measured exactly as theirs falls back to when ICMP is filtered: a TCP connect
 * to port 80 (`test_endpoint_latency`), not a WireGuard handshake.
 */
object EdgePins {

    /** `KNOWN_CLEAN_ENDPOINTS` verbatim - their 23 entries over six ports, `clean_endpoint.py:12-41`. */
    private val ENDPOINTS = listOf(
        // `KNOWN_CLEAN_ENDPOINTS` verbatim, `clean_endpoint.py:12-41`: 23 entries, six ports.
        // Two earlier edits here were wrong in opposite directions:
        //   - at 06:16 this held 9 of their entries;
        //   - then it was "corrected" to 27 entries, all on :2408, with a comment claiming the
        //     500 / 4500 / 1701 / 854 / 880 entries "do NOT exist in their source". They do.
        //     Their file is explicit: :500 "universally unblocked by ISPs", :4500 "bypasses
        //     UDP 2408 filtering", :1701 L2TP/anycast bypass, :854/:880 high ports.
        // The added IPs (.192.3-.5, .193.2, .193.9/.10, .195.2-.5, .96.2/.3, .97.2/.3,
        // .98.2/.3, .99.2/.3) are not theirs either, and a picker that offers an address the
        // reference tool does not ship is inventing endpoints - the thing this file exists to
        // avoid. Ports matter on a filtered carrier: if UDP 2408 is blocked, :500 is the
        // alternative, and that is the whole point of the owner's "use alternative" order.
        "162.159.192.1:2408", // Default Cloudflare Anycast (Primary)
        "162.159.192.2:2408", // Anycast pool node 2
        "162.159.193.1:2408", // Clean pool 1 (common RU/IR/CN bypass)
        "162.159.193.5:2408", // Clean pool 2
        "162.159.195.1:2408", // Clean pool 3
        "188.114.96.1:2408", // Cloudflare EU alternate 1
        "188.114.97.1:2408", // Cloudflare EU alternate 2
        "188.114.98.1:2408", // Cloudflare EU alternate 3
        "188.114.99.1:2408", // Cloudflare EU alternate 4
        // Alternative port 500 - IPsec IKE, universally unblocked by ISPs
        "162.159.192.1:500",
        "162.159.193.1:500",
        "188.114.96.1:500",
        "188.114.97.1:500",
        // Alternative port 4500 - IPsec NAT-T, bypasses UDP 2408 filtering
        "162.159.192.1:4500",
        "162.159.193.1:4500",
        "188.114.96.1:4500",
        "188.114.97.1:4500",
        // Alternative port 1701 - L2TP / anycast bypass
        "162.159.192.1:1701",
        "162.159.193.1:1701",
        // High-port Cloudflare WireGuard endpoints
        "162.159.192.1:854",
        "162.159.193.1:854",
        "162.159.192.1:880",
        "162.159.193.1:880",
    )

    /**
     * Auto-random picks from the whole table, exactly as theirs does (`random.choice`).
     *
     * No port filtering any more. The note this replaced said "500, 880 and 1701 all failed
     * verify" — but those ports were never in their table to begin with, so that measurement
     * was of addresses this app invented. With the table now verbatim there is nothing to
     * filter: every entry came from a table their tool ships and uses.
     */
    private val PICKABLE = ENDPOINTS

    /**
     * Their `zen_proxy.EDGE_FAILURE_COOLDOWN = 120.0`.
     *
     * `_WarpState._pick_edge_locked` excludes every edge whose last failure is younger than
     * this, then falls back to "anything but the one you just named", then to the whole pool.
     * Measured here 02:56:49-02:58:34: `162.159.192.3` and `162.159.192.6` each carried zero
     * packets, and because nothing remembered them the re-roll could hand the same dead edge
     * straight back — 105 s of no service on a registration that was fine.
     */
    private const val EDGE_FAILURE_COOLDOWN_MS = 120_000L

    /** edge -> monotonic ms of its last failed verify (their `_bad_edges`). */
    private val badEdges = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The edge handed out last, so a re-roll never re-presents the same address. */
    @Volatile private var lastPicked: String? = null

    /**
     * The port this phone has actually carried traffic on, learned from [markOk].
     *
     * Their table ships six ports on purpose (`:500` "universally unblocked by ISPs",
     * `:4500` "bypasses UDP 2408 filtering"), and the Clean picker still offers all 23
     * entries. But an *automatic* re-roll must not walk onto a port this carrier has never
     * answered: measured 12:35:44 -> 12:36:07, the verify burned `162.159.192.5:2408`,
     * `162.159.192.1:2408`, `188.114.97.1:4500` and settled on `188.114.96.1:2408`, three
     * reads each at up to 11 s, on a tunnel that was fine. Only `:2408` has ever passed the
     * tunnel verify on this carrier (2026-11-19: `:880`, `:500`, `:1701` all failed while
     * `:2408` answered in 2.1 s).
     *
     * Empty until something verifies, so a fresh install behaves exactly as before.
     */
    @Volatile private var lastOkPort: String = ""

    /**
     * Their `_pick_edge_locked`, in their order: skip cooling edges and the one that just
     * failed; if that leaves nothing, drop the cooling filter; if that also leaves nothing,
     * take the whole pool. Never returns an empty string.
     *
     * `exclude` defaults to the edge handed out last, which is the case the phone actually
     * hits: the caller rarely knows which edge the live tunnel was built on, but it always
     * wants a different one.
     */
    fun pick(exclude: String? = null): String {
        val skip = exclude ?: lastPicked
        val now = System.nanoTime() / 1_000_000
        val cooling = badEdges.entries
            .filter { now - it.value < EDGE_FAILURE_COOLDOWN_MS }
            .map { it.key }
            .toSet()
        var choices = PICKABLE.filter { it != skip && it !in cooling }
        if (choices.isEmpty()) choices = PICKABLE.filter { it != skip }
        if (choices.isEmpty()) choices = PICKABLE
        // Prefer the port that last carried traffic, but only as a filter over the pool the
        // rules above already produced - never as a replacement for them.
        if (lastOkPort.isNotEmpty()) {
            val samePort = choices.filter { it.substringAfter(':', "") == lastOkPort }
            if (samePort.isNotEmpty()) choices = samePort
        }
        // Note: the `if (choices.isEmpty()) choices = PICKABLE` above already guarantees a
        // non-empty list; filtering by port can only ever narrow it to something non-empty.
        val chosen = if (choices.isEmpty()) PICKABLE.first() else choices[Random.nextInt(choices.size)]
        lastPicked = chosen
        return chosen
    }

    /** Their `mark_edge_ok`: a working edge leaves the bad list immediately. */
    fun markOk(edge: String) {
        runCatching { badEdges.remove(edge) }
        // Remember the port that actually carried traffic; `pick()` prefers it for auto
        // re-rolls. See `lastOkPort`.
        val port = edge.substringAfter(':', "")
        if (port.isNotEmpty()) lastOkPort = port
    }

    /** Their `mark_edge_failed`: stamp it, so the next pick avoids it for two minutes. */
    fun markFailed(edge: String) {
        if (edge.isBlank()) return
        badEdges[edge] = System.nanoTime() / 1_000_000
    }

    /** Read-only view for the panel/logs; edge to ms-since-failure is not exposed there yet. */
    fun badCount(): Int {
        val now = System.nanoTime() / 1_000_000
        return badEdges.entries.count { now - it.value < EDGE_FAILURE_COOLDOWN_MS }
    }

    /**
     * Their `_WarpState.note_stream_reset`.
     *
     * "A single reset can be the provider closing a finished stream, so the edge is only
     * retired after a second reset inside five minutes." Theirs lives in the proxy that owns
     * the upstream socket; ours is driven by the `provider.transport` verdict the server puts
     * on the failed step, which is the same event one layer up.
     *
     * @return true when this reset retired the edge (two strikes inside the window).
     */
    fun noteStreamReset(edge: String): Boolean {
        if (edge.isBlank()) return false
        val now = System.nanoTime() / 1_000_000
        val stamps = streamResets.getOrDefault(edge, emptyList())
            .filter { now - it < STREAM_RESET_WINDOW_MS }
            .toMutableList()
        stamps.add(now)
        streamResets[edge] = stamps
        if (stamps.size < 2) return false
        markFailed(edge)
        streamResets.remove(edge)
        return true
    }

    /** Their `_stream_fails` window: 300 s. */
    private const val STREAM_RESET_WINDOW_MS = 300_000L

    /** edge -> monotonic ms of each recent reset. */
    private val streamResets =
        java.util.concurrent.ConcurrentHashMap<String, List<Long>>()

    /** Every listed endpoint, for the panel's picker. */
    fun endpoints(): List<String> = ENDPOINTS

    /** Distinct edge IPs, in table order (`#selCleanEndpoint`). */
    fun edges(): List<String> = ENDPOINTS.map { it.substringBefore(':') }.distinct()

    /** Distinct ports, in table order. */
    fun ports(): List<Int> = ENDPOINTS.mapNotNull { it.substringAfter(':', "").toIntOrNull() }.distinct()

    /**
     * Their `test_endpoint_latency` fallback: a TCP connect to port 80. ICMP needs root on
     * Android, so the fallback is the only half that exists here — and it is the half that
     * still answers when a network drops echo.
     */
    fun latencyMs(ip: String, timeoutMs: Int = 1500): Long? {
        val started = System.nanoTime()
        return runCatching {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(ip, 80), timeoutMs)
            }
            (System.nanoTime() - started) / 1_000_000
        }.getOrNull()
    }

    /**
     * Their `auto_heal_warp_endpoint`: benchmark the table concurrently, keep the fastest
     * that answered, and hand it to the tunnel. Returns null when nothing answered.
     */
    fun fastestEdge(): Pair<String, Long>? {
        val results = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Long>>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            ENDPOINTS.map { ep -> ep to ep.substringBefore(':') }
                .distinctBy { it.first }
                .forEach { (ep, ip) ->
                    pool.execute {
                        latencyMs(ip)?.let { results.add(ep to it) }
                    }
                }
            pool.shutdown()
            pool.awaitTermination(6, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
            pool.shutdownNow()
        }
        return results.minByOrNull { it.second }
    }
}
