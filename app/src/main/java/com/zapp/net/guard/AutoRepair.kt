package com.zapp.net.guard

import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * `avatar_ui._auto_repair_tick:522-587`, ported action for action.
 *
 * Their guarantee, in their own words (`avatar_ui.py:105-106`):
 *
 *     "Auto-repair only touches connectivity, never the user's selected route or
 *      OpenCode itself. Cooldowns keep a permanent outage from becoming a loop."
 *
 * That is the whole design: one action per tick, a per-action cooldown, and a hard
 * cap of [MAX_PER_HOUR] so a permanent outage cannot become a rebuild loop. Their
 * tick is 25 s and theirs starts 20 s after launch; ours is started by the guard
 * service, which is already the long-lived component.
 *
 * Two of their five actions have no honest equivalent on this device and are
 * listed rather than faked:
 *
 *   * `restart_keeper` kills a separate Windows scheduled task and starts it
 *     again. Our guard, VPN service and relay all live in one process, so a
 *     stale heartbeat here means the process is wedged - it cannot restart
 *     itself and pretending to would be a lie. The heartbeat file is still
 *     written (`Guard.startHeartbeat`) so the panel can *show* it.
 *   * `repair_warp` runs their `warp-cli` re-registration. We have no `warp-cli`;
 *     the equivalent on this phone is rebuilding the VpnService, which is what
 *     [repairWarp] does.
 */
object AutoRepair {

    /** their `AUTO_REPAIR_TICK` */
    const val TICK_MS = 25_000L

    /** their `AUTO_REPAIR_MAX_PER_HOUR` */
    const val MAX_PER_HOUR = 6

    /** their `HEARTBEAT_STALE` */
    const val HEARTBEAT_STALE_MS = 240_000L

    /**
     * How long a measured carry proof silences auto-repair.
     *
     * Shorter than [GuardState.CARRY_STALE_MS] on purpose: that constant is the point at
     * which a turn stops being trusted, this one is the point at which a *repair* stops
     * being justified. Between the two, a tunnel with fresh evidence is left alone and a
     * stale one is already failing elsewhere.
     */
    const val CARRY_GRACE_MS = 120_000L

    /** their `AUTO_REPAIR_COOLDOWNS`, keyed by action. */
    private val COOLDOWNS = mapOf(
        "start_keeper" to 120_000L,
        "restart_keeper" to 600_000L,
        "connect_warp" to 180_000L,
        "hop" to 300_000L,
        "repair_warp" to 900_000L,
    )

    /** their `_repair_last` - monotonic stamps, so a clock change cannot unthrottle it. */
    private val lastAt = HashMap<String, Long>()

    /** their `_repair_actions`, newest last, capped at 20 like theirs. */
    private val actions = ArrayList<Entry>()

    data class Entry(
        val tsMs: Long,
        val action: String,
        val reason: String,
        val ok: Boolean,
        val message: String,
    )

    @Volatile private var status = "Watching for connection problems"

    fun statusText(): String = status

    fun recent(): List<Entry> = synchronized(actions) { actions.toList() }

    /** `avatar_ui.toggle_auto_repair`: turning it off must not leave a claim behind. */
    fun off() {
        status = "Auto-repair is off"
    }

    /**
     * `_auto_repair_tick`. Returns the action taken, or "" when it is watching.
     *
     * Ordering is theirs: the hourly cap is checked before anything else, a running
     * user action aborts the tick, and the first matching condition wins.
     */
    suspend fun tick(ctx: Context): String {
        if (!com.zapp.net.settings.GuardPrefs.autoRepair(ctx)) {
            status = "Auto-repair is off"
            return ""
        }

        val now = SystemClock.elapsedRealtime()
        synchronized(actions) {
            val recent = actions.count { it.ageMs() < 3_600_000L }
            if (recent >= MAX_PER_HOUR) {
                status = "Several repairs did not restore the route. Check the Connection " +
                    "tab; auto-repair will try again later."
                return ""
            }
        }

        // their `if self._action_lock.locked(): return` - never race a user rotation
        if (RotationEngine.busy()) return ""

        // Never tear the tunnel down under a live reply.
        //
        // Their auto-repair runs on a desktop where `warp connect` leaves the interface
        // alone. Ours replaces the whole WireGuard device, which cuts every open socket - so
        // a rebuild during a reply is the "stop and then teleport": the stream dies mid
        // sentence and the rest lands in one block when the route returns. A turn that is
        // emitting tokens is also the strongest possible proof the route works, so there is
        // nothing here to repair.
        if (GuardState.turnIsLive()) return ""

        // A CARRY PROOF WE ALREADY HOLD OUTRANKS A STALE `verified` FLAG.
        //
        // `verified` is cleared on every teardown and republished by the verify path and by
        // the keeper's probe. It can still be false for a few seconds after a rebuild that
        // already carries - and this tick used to read that as "running but has not carried
        // traffic", run `start_keeper`, and tear the fresh tunnel down again. Measured
        // 2026-11-24 02:21:00-19: a proven tunnel was rebuilt, and the replacement carried
        // nothing, which is the reply that stops mid-thought.
        //
        // Bytes measured leaving through the tunnel are direct evidence. Nothing here needs
        // repairing while that evidence is fresh.
        val carry = GuardState.lastCarryProofMs
        if (carry > 0L && System.currentTimeMillis() - carry < CARRY_GRACE_MS) {
            status = "Watching for connection problems"
            return ""
        }

        val st = GuardState.status.value
        val vpn = st.vpnRunning
        val verified = st.verified

        var action = ""
        var reason = ""
        if (vpn && !verified) {
            action = "start_keeper"
            reason = "The tunnel is running but has not carried traffic"
        } else if (!vpn) {
            action = "connect_warp"
            reason = "The tunnel is disconnected"
        } else if (vpn && verified && !st.lastEgress64.isNullOrBlank() && !st.lastEgress64.isTunnel()) {
            // their fallback branch: the route works but not through WARP
            val lastHop = lastAt["hop"] ?: 0L
            action = if (now - lastHop > 600_000L) "repair_warp" else "hop"
            reason = if (action == "repair_warp")
                "The tunnel kept failing after an edge change"
            else "The local route could not reach opencode.ai"
        }
        if (action.isEmpty()) {
            status = "Watching for connection problems"
            return ""
        }

        synchronized(actions) {
            val last = lastAt[action] ?: 0L
            if (now - last < (COOLDOWNS[action] ?: 300_000L)) return ""
            lastAt[action] = now
        }

        status = "Repairing: ${reason.lowercase()}…"
        val (ok, message) = runAction(ctx, action)
        val entry = Entry(System.currentTimeMillis(), action, reason, ok, message.take(240))
        synchronized(actions) {
            actions.add(entry)
            while (actions.size > 20) actions.removeAt(0)
        }
        GuardState.log("vpn", "${if (ok) "ok" else "failed"} auto-repair $action: $message")
        status = if (ok) "Repaired: $reason." else "Could not repair automatically: $message"
        return action
    }

    /** `_run_repair_action:589-602`. */
    private suspend fun runAction(ctx: Context, action: String): Pair<Boolean, String> = try {
        when (action) {
            "start_keeper", "connect_warp", "repair_warp" -> {
                if (android.net.VpnService.prepare(ctx) != null) {
                    false to "the system has not granted VPN permission"
                } else {
                    ctx.startService(
                        Intent(ctx, com.zapp.net.vpn.ZappVpnService::class.java)
                            .setAction(
                                if (action == "repair_warp") com.zapp.net.vpn.ZappVpnService.ACTION_STOP
                                else com.zapp.net.vpn.ZappVpnService.ACTION_START,
                            ),
                    )
                    if (action == "repair_warp") {
                        kotlinx.coroutines.delay(1_000L)
                        ctx.startService(
                            Intent(ctx, com.zapp.net.vpn.ZappVpnService::class.java)
                                .setAction(com.zapp.net.vpn.ZappVpnService.ACTION_START),
                        )
                    }
                    true to "asked the VPN service to ${if (action == "repair_warp") "rebuild" else "start"}"
                }
            }
            "hop" -> {
                val reg = RotationEngine.hopNow(ctx, "auto-repair")
                if (reg != null) true to "rotated to ${reg.v6.take(20)}…"
                else false to "no fresh /64 was available"
            }
            else -> false to "unknown repair action: $action"
        }
    } catch (e: Exception) {
        false to (e.message ?: e.javaClass.simpleName)
    }

    private fun String.isTunnel(): Boolean = WarpEgress.isTunnel(this)

    /**
     * Age on the same axis the hourly cap uses.
     *
     * Theirs stores `mono` beside the display stamp; ours derives it, and a wall-clock
     * jump backwards only makes the cap stricter, never looser.
     */
    private fun Entry.ageMs(): Long =
        (System.currentTimeMillis() - tsMs).coerceAtLeast(0L)
}
