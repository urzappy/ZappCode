package com.zapp.net.guard

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.zapp.net.settings.GuardPrefs
import com.zapp.net.termux.EgressProbe
import com.zapp.net.vpn.ZappVpnService
import kotlinx.coroutines.launch

/**
 * The one-tap repair.
 *
 * the owner, 22 Nov: "MAKE THE DISCONNECT WAIT UNTIL DISCONNECT THEN FIX WARP WAIT UNTIL SUCCESS
 * THEN REFRESH UNTIL REFRESHED AND THEN ROTATE UNTIL SUCCESSFUL ROTATE AND THEN VERIFIED TO
 * CONNECT ... 1 BUTTON LANG YAN PERO MAY PARANG LOADER NA INDICATOR".
 *
 * Every one of those steps already existed as a button, and every one of them was
 * fire-and-forget: `run("Rotating") { RotationEngine.hopNow(...) }` showed a label and then
 * returned, so the panel never knew whether the hop actually landed. Tapping two of them in a
 * row meant the second landed on a half-built tunnel, which is how a repair could leave the
 * device worse than it started.
 *
 * This pipeline is the same actions, but each stage **waits for its own proof** before the
 * next one is allowed to run, and publishes which stage it is on so the panel can draw a real
 * loader instead of a spinner that means nothing.
 *
 * **Rotation is not in here.** the owner, 22 Nov: "REMOVE THE ROTATE ANG TAGAL NYAN E". Measured
 * on this phone, a rotate can take 0.96-3.43 ms of UAPI rekey but 142-190 ms of rebuild, and
 * the stage also refused to start while a reply was streaming, so a repair could sit on it for
 * three minutes. Repair is for a *broken tunnel*; rotating identity is a separate decision he
 * makes with the "Rotate /64 Now" button. Removing it takes the worst case from ~4 minutes to
 * under two.
 */
object Repair {

    /** Stage names, in the order they run. Indexes are the loader's rows. */
    private val STEPS = listOf(
        "Disconnect",
        "Fix WARP keys",
        "Reconnect",
        "Refresh route",
        "Verify WARP",
    )

    private const val POLL_MS = 500L

    /** Per-stage ceilings. A stage that cannot prove itself in this long is a failure. */
    private const val DISCONNECT_MS = 25_000L
    private const val KEYS_MS = 60_000L
    private const val RECONNECT_MS = 30_000L
    private const val REFRESH_MS = 60_000L

    /**
     * The verify stage waits for the tunnel's **own** verdict, so its ceiling must cover that
     * loop's whole budget: three reads per edge, up to three edges, plus the re-roll dwell.
     *
     * Measured 23 Nov 16:02: this stage used to run a second `EgressProbe.measure` beside the
     * service's verify loop and give up at 60 s. The screen showed `5. Verify WARP - timed out
     * after 60s` directly above a card reading `WARP IPv6 - verified`, with solid addresses
     * climbing 5 -> 6 in the same minute. Two probes were competing for the same lane, and the
     * repair lost the race against the thing it was waiting for.
     */
    private const val VERIFY_MS = 150_000L

    fun running(): Boolean = GuardState.repairRunning.value

    /** Start the pipeline. No-op while one is already running. */
    fun startRepair(ctx: Context) {
        if (running()) return
        GuardState.beginRepair(STEPS)
        GuardState.log("hop", "repair: one-tap started")
        GuardState.appScope.launch {
            var failed = false
            try {
                failed = !runPipeline(ctx)
            } catch (e: Throwable) {
                GuardState.log("error", "repair: ${e.message}")
                failed = true
            } finally {
                GuardState.endRepair()
                GuardState.log(
                    "hop",
                    if (failed) "repair: stopped at a stage that could not prove itself"
                    else "repair: all ${STEPS.size} stages proven",
                )
            }
        }
    }

    private suspend fun runPipeline(ctx: Context): Boolean {
        // ---- 1. disconnect, and wait until it is really down ----
        if (!stage(ctx, 0, DISCONNECT_MS) {
                stopTunnel(ctx)
                GuardState.repairNote("waiting for the tunnel to go down")
                await("tunnel down", DISCONNECT_MS) {
                    !GuardState.status.value.vpnRunning && !isVpnUp(ctx)
                }
            }
        ) return false

        // ---- 2. new WARP keys ----
        if (!stage(ctx, 1, KEYS_MS) {
                GuardState.repairNote("registering a fresh WARP key pair")
                // `measureEgress = false`: stage 1 has just taken the tunnel DOWN and stage 3
                // is what brings it back, so a probe here can never succeed - it costs its
                // whole timeout (measured 20 s for this stage on 2026-11-24 02:48:55) and
                // stage 5 then takes the same reading properly through the service's verify.
                RotationEngine(ctx).resetKeys(measureEgress = false) != null
            }
        ) return false

        // ---- 3. reconnect, because nothing below can be measured through a dead tunnel ----
        if (!stage(ctx, 2, RECONNECT_MS) {
                GuardState.repairNote("bringing the tunnel back up")
                startTunnel(ctx)
                await("tunnel up", RECONNECT_MS) { GuardState.status.value.vpnRunning }
            }
        ) return false

        // ---- 4. refresh: the pool, then one real egress reading ----
        //
        // Measured 22 Nov 15:35: this stage failed with "gave up waiting for egress reading"
        // 26 s after a reconnect that had happened 0 s earlier. The tunnel had not completed its
        // first WireGuard handshake yet, so `EgressProbe` had nothing to read through and every
        // attempt burned its whole budget. The window is 60 s now (five attempts at 10 s each)
        // and the note counts attempts, so a slow handshake is visible instead of looking like a
        // dead stage.
        if (!stage(ctx, 3, REFRESH_MS) {
                GuardState.repairNote("measuring the egress address")
                runCatching { GuardState.refresh(WarpPool(ctx)) }
                var attempt = 0
                var got: EgressProbe.Reading? = null
                await("egress reading", REFRESH_MS) {
                    attempt++
                    GuardState.repairNote("measuring the egress address (attempt $attempt)")
                    val r = EgressProbe.measure(ctx, 10_000)
                    if (r != null) {
                        got = r
                        GuardState.repairNote("egress ${r.ip}")
                        true
                    } else false
                } && got != null
            }
        ) return false

        // ---- 5. verify: the only proof that counts ----
        //
        // This waits on `GuardState.status.value.verified`, which only the service's verify
        // loop can set (`ZappVpnService:873/1148`) and only after a `warp=on` reading from
        // `cdn-cgi/trace`. It does NOT run its own probe any more.
        //
        // Two reasons, both measured:
        //   * two probes at once fight over the same lane and both get slower, so the repair
        //     could time out at exactly the moment the real verify succeeded (23 Nov 16:02,
        //     "timed out after 60s" printed above a card that said "verified");
        //   * one verdict is honest - the panel and the repair would otherwise be able to
        //     disagree about the same tunnel in the same second.
        if (!stage(ctx, 4, VERIFY_MS) {
                GuardState.repairNote("waiting for the tunnel's own proof")
                var waited = 0
                await("warp proof", VERIFY_MS) {
                    waited++
                    if (waited % 10 == 0) {
                        GuardState.repairNote(
                            "waiting for the tunnel's own proof (${waited / 2}s)",
                        )
                    }
                    GuardState.status.value.verified
                }
            }
        ) return false

        return true
    }

    /**
     * Run one stage, publish its state, and let a thrown error or a false result mark it
     * failed. The loader reads exactly these three values, so the panel and the log can
     * never disagree about where a repair stopped.
     */
    private suspend fun stage(
        ctx: Context,
        index: Int,
        ceilingMs: Long,
        body: suspend () -> Boolean,
    ): Boolean {
        GuardState.repairStep(index, GuardState.RepairStep.RUNNING)
        val started = System.currentTimeMillis()
        val ok = runCatching { body() }.getOrElse {
            GuardState.log("error", "repair/${STEPS[index]}: ${it.message}")
            false
        }
        val took = System.currentTimeMillis() - started
        if (ok) {
            GuardState.repairStep(index, GuardState.RepairStep.DONE, "${took / 1000}s")
            GuardState.log("hop", "repair: ${STEPS[index]} ok in ${took / 1000}s")
        } else {
            GuardState.repairStep(
                index,
                GuardState.RepairStep.FAILED,
                if (took >= ceilingMs) "timed out after ${ceilingMs / 1000}s" else "no proof",
            )
            GuardState.log("error", "repair: ${STEPS[index]} failed after ${took / 1000}s")
        }
        return ok
    }

    /** Poll a condition until it is true or the ceiling passes. */
    private suspend fun await(what: String, ceilingMs: Long, test: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + ceilingMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { test() }.getOrDefault(false)) return true
            kotlinx.coroutines.delay(POLL_MS)
        }
        GuardState.log("error", "repair: gave up waiting for $what")
        return false
    }

    // ---- the three tunnel verbs, without a Composable in the way ----
    //
    // `GuardScreen` owns its own copies for the buttons. These are the same two Intents and
    // the same persisted flag, kept here so the pipeline can run with the panel closed.

    private fun stopTunnel(ctx: Context) {
        GuardPrefs.setVpnWanted(ctx, false)
        GuardState.setVpnRunning(false)
        ctx.startService(Intent(ctx, ZappVpnService::class.java).setAction(ZappVpnService.ACTION_STOP))
    }

    private fun startTunnel(ctx: Context) {
        GuardPrefs.setVpnWanted(ctx, true)
        // A background scope cannot show the consent dialog. The tunnel was already running
        // before stage 1, so the app is authorised and `prepare` answers null; if it does not,
        // the reconnect stage fails honestly instead of pretending to connect.
        val ask = runCatching { VpnService.prepare(ctx) }.getOrNull()
        if (ask != null) {
            GuardState.log("error", "repair: VPN consent is missing - open Guard and tap Connect once")
            return
        }
        ctx.startService(Intent(ctx, ZappVpnService::class.java).setAction(ZappVpnService.ACTION_START_USER))
    }

    /** The OS's own answer, not our flag: is a tun device carrying our packets? */
    private fun isVpnUp(ctx: Context): Boolean = runCatching {
        val pm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        pm.allNetworks.any { n ->
            pm.getNetworkCapabilities(n)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }.getOrDefault(false)
}
