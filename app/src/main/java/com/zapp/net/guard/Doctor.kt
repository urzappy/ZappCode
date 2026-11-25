package com.zapp.net.guard

import android.content.Context
import kotlinx.coroutines.flow.first

/**
 * Their `doctor.py`, on the phone.
 *
 * Theirs is eight checks written for a Windows install (scheduled tasks, mutexes,
 * `warp-cli`, a PATH shim). The questions are the same; the evidence has to come from
 * this device, so every check below reads a real source - never a guess, never a
 * config value asserted as fact:
 *
 *   1. the lane process    -> `/__zapp/status` answering (RouteStatus)
 *   2. the active mode     -> the mode `proxy_status` publishes, then a probe *for that mode*
 *   3. external egress     -> `GuardState.Status.lastEgress64`, classified as tunnel or carrier
 *   4. the guard           -> prefs + hop/wall counters + the pause deadline
 *   5. the kill-switch     -> their `is_killswitch_installed()` has no rootless equivalent,
 *                             so this reports the substitute that is actually in force:
 *                             the proxy's strict fail-closed switch
 *   6. the CLI             -> the `opencode serve` port this app talks to
 *   7. lane traffic proof  -> whether any provider event has ever arrived through the lane
 *   8. rotation health     -> the wall backoff and the hop budget
 *
 * Dropped on purpose: the PATH shim (no PATH here) and the scheduled-task/mutex checks
 * (an Android service has neither). Reporting them as "missing" would be a lie about a
 * platform that does not have them.
 */
object Doctor {

    enum class Level { OK, WARN, BAD, INFO }

    data class Check(val name: String, val level: Level, val detail: String)

    /** Same rule `AutoRepair` uses to call an address a WARP egress. */
    private fun String.isTunnel(): Boolean = WarpEgress.isTunnel(this)

    suspend fun run(ctx: Context): List<Check> = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO,
    ) {
        val out = ArrayList<Check>(9)
        val route = RouteStatus.read()
        val st = GuardState.status.value
        val prefs = com.zapp.net.settings.GuardPrefs

        // 1. the lane process
        if (route.ok) {
            out += Check("lane process", Level.OK, "127.0.0.1:41988 answering · edge ${route.edge.ifBlank { "auto-random" }}")
        } else {
            out += Check(
                "lane process",
                Level.BAD,
                "127.0.0.1:41988 not answering (${route.error.ifBlank { "no response" }}) - " +
                    "restart the keeper",
            )
        }

        // 2. the active mode, then one probe that only makes sense for that mode
        val mode = route.mode.ifBlank { "warp" }
        val scope = route.scope.ifBlank { "isolated" }
        out += Check("egress mode", Level.INFO, mode.uppercase() + " (scope: $scope)")
        when (mode) {
            "tor" -> {
                val alive = com.zapp.net.net.TorLane.probe()
                out += if (alive) {
                    Check("tor lane", Level.OK, "SOCKS5 127.0.0.1:9050 answers")
                } else {
                    Check("tor lane", Level.BAD, "SOCKS5 9050 does not answer - Tor tab, Start Tor Daemon")
                }
            }
            "custom" -> {
                out += if (route.customPoolCount > 0) {
                    Check(
                        "custom pool",
                        Level.OK,
                        "${route.customPoolCount} node(s) · active ${route.customNode.ifBlank { route.customProxy }} " +
                            "(${route.customType.ifBlank { "socks5" }})",
                    )
                } else {
                    Check("custom pool", Level.BAD, "no custom proxy configured - Custom tab, Apply & Route")
                }
            }
            else -> {
                out += if (st.verified) {
                    Check("warp tunnel", Level.OK, "verified through the tunnel (${st.currentV6})")
                } else {
                    Check(
                        "warp tunnel",
                        Level.WARN,
                        "not verified yet - current v6 ${st.currentV6}, last egress " +
                            st.lastEgress64.ifBlank { "unknown" },
                    )
                }
                if (scope == "isolated") {
                    out += Check(
                        "isolated proxy",
                        Level.WARN,
                        "127.0.0.1:40000 is warp-cli's port and does not exist here; the lane " +
                            "tries it and falls through - the same path theirs takes when WARP is absent",
                    )
                }
            }
        }

        // 3. external egress
        val eg = st.lastEgress64.ifBlank { st.currentV6 }
        out += when {
            eg.isBlank() || eg == "-" ->
                Check("external egress", Level.WARN, "no egress reading yet - tap Refresh on the panel")
            eg.isTunnel() -> Check("external egress", Level.OK, "$eg (cloudflare / WARP)")
            else -> Check("external egress", Level.WARN, "$eg (NOT the tunnel - carrier path)")
        }

        // 4. the guard
        val hops = st.hops
        val walls = st.walls
        out += if (prefs.guardOn(ctx)) {
            Check("zen guard", Level.OK, "on · $hops rotations · $walls walls seen")
        } else {
            Check("zen guard", Level.BAD, "off - a wall will not be rotated")
        }
        if (!prefs.keeperOn(ctx)) {
            out += Check("warp keeper", Level.WARN, "off - nothing restarts a dead tunnel")
        }

        // 5. the kill-switch substitute
        out += if (route.strict) {
            Check(
                "fail-closed",
                Level.OK,
                "strict is ON: a provider call with no tunnel is refused, never sent over the carrier",
            )
        } else {
            Check(
                "fail-closed",
                Level.WARN,
                "strict is OFF: a dead tunnel falls back to WARP-direct, and a failure there has " +
                    "no route. Their firewall rule needs admin; this switch is the rootless one",
            )
        }

        // 6. the CLI this app actually talks to - the port the app is *configured* with,
        //    not a literal. Theirs calls `find_opencode_cli()`; the phone equivalent is the
        //    server base in SettingsStore, which can point at the PC relay as easily as
        //    at Termux, so a hardcoded 4096 would report a failure for a working setup.
        val base = runCatching {
            com.zapp.net.settings.observe(ctx).first().serverBase
        }.getOrNull().orEmpty().ifBlank { com.zapp.net.settings.PHONE_SERVER }
        val uri = runCatching { java.net.URI(base) }.getOrNull()
        val host = uri?.host ?: "127.0.0.1"
        val port = if (uri == null || uri.port <= 0) 4096 else uri.port
        val serverUp = runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 1200) }
        }.isSuccess
        out += if (serverUp) {
            Check("opencode server", Level.OK, "$host:$port accepts a connection")
        } else {
            Check("opencode server", Level.BAD, "$host:$port refused - serve.sh is not running")
        }

        // 7. proof the lane has carried real provider traffic
        val provider = route.recent.lastOrNull { it.host.contains("opencode") }
        out += if (provider != null) {
            val when_ = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(provider.ts * 1000))
            Check("lane traffic", Level.OK, "$when_ · ${provider.kind} · ${provider.host}")
        } else {
            Check(
                "lane traffic",
                Level.WARN,
                "no provider event in the 5 min trail - either the server is idle or HTTPS_PROXY " +
                    "does not point at the lane",
            )
        }

        // 8. rotation health
        val pausedUntil = WallPause.pausedUntilMs(ctx)
        val now = System.currentTimeMillis()
        out += if (pausedUntil > now) {
            Check(
                "rotation",
                Level.WARN,
                "paused for ${(pausedUntil - now) / 1000}s after ${WallPause.failedHops(ctx)} failed " +
                    "hop(s), ${WallPause.freeWallHops(ctx)} free-wall hop(s) of 2",
            )
        } else {
            Check(
                "rotation",
                Level.OK,
                "ready · failed hops ${WallPause.failedHops(ctx)}/2 · free-wall hops " +
                    "${WallPause.freeWallHops(ctx)}/2",
            )
        }

        out
    }

    /** One line summary: the worst level, for a badge. */
    fun worst(checks: List<Check>): Level = when {
        checks.any { it.level == Level.BAD } -> Level.BAD
        checks.any { it.level == Level.WARN } -> Level.WARN
        else -> Level.OK
    }
}
