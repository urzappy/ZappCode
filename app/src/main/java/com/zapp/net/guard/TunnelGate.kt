package com.zapp.net.guard

import android.content.Context
import android.content.Intent
import android.util.Log
import com.zapp.net.settings.GuardPrefs
import com.zapp.net.termux.EgressProbe
import com.zapp.net.vpn.ZappVpnService

/**
 * Send-time tunnel check — the phone's version of their firewall kill-switch.
 *
 * `ouc_firewall.install_killswitch()` adds an outbound BLOCK rule for `opencode-cli.exe`
 * against every public range, so on desktop the CLI physically cannot reach OpenCode
 * outside the proxy: either the WARP tunnel carries the request or nothing is sent.
 *
 * We have no firewall to install, and only Termux is inside the tunnel, so the equivalent
 * guarantee has to be enforced before the prompt leaves: if the guard is on and the last
 * measurement says traffic is leaving outside WARP, repair the tunnel first.
 *
 * The reason is measured, not theoretical. 2026-11-20 02:26-02:30: with the tunnel down the
 * egress read `ip=2001:4455:59c:4700:… warp=off` (the carrier, permanently walled), every
 * prompt came back `provider.free-limit`, and the guard answered by burning WARP /64s the
 * provider had never seen. After the tunnel was repaired the same account served 46
 * consecutive turns on one /64. The 429 belonged to the carrier, and only the address the
 * packets actually left through can ever answer it.
 *
 * WHAT CHANGED (the owner, 2026-11-20): this used to sit for 25 s and then REFUSE the send when
 * the tunnel would not carry. Measured 05:51:37-05:52:15: after a wall-driven rebuild the
 * fresh registration answered nothing at all, so that wait was pure delay in front of every
 * message he typed, and the refusal made his text look lost. It never blocks now — it makes
 * one repair attempt (only when the service is not even running) and then says the truth in
 * the log and lets the send through. A refusal is a wait, and a wait is his to choose.
 */
object TunnelGate {

    /** How old a reading may be and still describe the current route. */
    private const val FRESH_MS = 60_000L

    /** One repair read is allowed this long to produce a carrying tunnel. */
    private const val REPAIR_WAIT_MS = 14_000L

    /**
     * @return true unless the tunnel is provably unusable AND the guard is off. In practice
     *   always true: see the class comment for why a send is never blocked any more.
     */
    suspend fun ensureCarrying(ctx: Context): Boolean {
        // their default: the guard is on. Off means he asked for a direct connection.
        if (!GuardPrefs.guardOn(ctx)) return true

        val last = EgressProbe.last
        if (last != null && last.warp.equals("on", ignoreCase = true) &&
            System.currentTimeMillis() - last.atMs < FRESH_MS
        ) {
            return true
        }

        GuardState.log("vpn", "send gate: no fresh carrying reading - checking once before sending")
        // A RUNNING tunnel is the keeper's job. Waiting in front of his message for a reading
        // that the keeper is already chasing cost 14 s on every send during the 11:15 re-roll
        // storm, and bought nothing: the service was up the whole time. Only a service that is
        // not running gives this gate something to do.
        if (GuardState.status.value.vpnRunning) {
            GuardState.log("vpn", "send gate: tunnel is running - sending, keeper owns the repair")
            return true
        }
        runCatching {
            ctx.startService(
                Intent(ctx, ZappVpnService::class.java)
                    .setAction(ZappVpnService.ACTION_START),
            )
        }.onFailure { Log.e("ZAPP", "send gate: vpn start failed: $it") }

        val deadline = System.currentTimeMillis() + REPAIR_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val r = runCatching { EgressProbe.measure(ctx, timeoutMs = 14_000) }.getOrNull()
            if (r != null) {
                if (r.warp.equals("on", ignoreCase = true)) {
                    GuardState.log("vpn", "send gate: carrying on ${r.prefix64}")
                    return true
                }
                GuardState.log("vpn", "send gate: still outside WARP (${r.ip}) - sending anyway")
                return true
            }
            kotlinx.coroutines.delay(2_000)
        }
        // Nothing came back at all. Earlier builds refused here and the message never left;
        // measured 05:52, that is exactly the "my text goes nowhere" feeling. Send it.
        GuardState.log("vpn", "send gate: tunnel answered nothing - sending anyway, never block his message")
        return true
    }
}
