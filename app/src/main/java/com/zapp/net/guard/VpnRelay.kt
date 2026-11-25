package com.zapp.net.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.zapp.net.vpn.ZappVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Outside handle on the tunnel.
 *
 * The activity route was proven unreliable: `am start --es vpn hop` reports
 * "intent delivered to currently running top-most instance" and then onNewIntent
 * never runs, so a hop silently does nothing. A receiver is a hard, testable edge.
 *
 * adb: am broadcast -a com.zapp.net.VPN --es cmd hop
 *      am broadcast -a com.zapp.net.VPN --es cmd start
 */
class VpnRelay : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd")?.trim()?.lowercase() ?: return
        val app = context.applicationContext
        Log.e("ZAPP", "vpn relay: $cmd")

        when (cmd) {
            "start" -> {
                if (VpnService.prepare(app) == null) {
                    app.startService(
                        Intent(app, ZappVpnService::class.java)
                            .setAction(ZappVpnService.ACTION_START),
                    )
                } else {
                    Log.e("ZAPP", "vpn relay: tunnel consent missing — open the app once")
                }
            }

            // No stop path used to exist. A tunnel whose handshake never completed left
            // tun0 + its default routes in place and cut Termux off from the internet, with
            // no way back but force-stopping the whole app. A hard stop is mandatory.
            "stop" -> {
                app.startService(
                    Intent(app, ZappVpnService::class.java)
                        .setAction(ZappVpnService.ACTION_STOP),
                )
            }

            "hop" -> CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                GuardState.log("hop", "manual hop requested")
                val reg = runCatching { RotationEngine.hopNow(app, "manual") }
                    .onFailure { Log.e("ZAPP", "vpn relay hop threw", it) }
                    .getOrNull()
                Log.e("ZAPP", "vpn relay hop -> ${reg?.v6 ?: "null"}")
                GuardState.log("hop", "manual hop -> ${reg?.v6 ?: "failed"}")
            }

            /**
             * Prove the verification port on the device.
             *
             * Runs the desktop algorithm twice back to back - the second call must be
             * served from the 750 ms window, not from a second request - then once with a
             * deliberately wrong password, which must answer `Unauthorized` rather than
             * fail open.
             *
             * am broadcast -a com.zapp.net.VPN --es cmd verify
             * am broadcast -a com.zapp.net.VPN --es cmd verify --es pass wrong
             */
            "verify" -> CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val base = runCatching {
                    com.zapp.net.settings.observe(app).first().serverBase
                }.getOrDefault(com.zapp.net.settings.PHONE_SERVER)
                    .ifBlank { com.zapp.net.settings.PHONE_SERVER }
                val pass = intent.getStringExtra("pass")?.takeIf { it.isNotBlank() }
                    ?: runCatching { com.zapp.net.api.loadToken(app) }.getOrDefault("")
                fun t0() = android.os.SystemClock.elapsedRealtime()
                val a = t0()
                val first = com.zapp.net.api.ServerHealth.verify(base, pass)
                val firstMs = t0() - a
                val b = t0()
                val second = com.zapp.net.api.ServerHealth.verify(base, pass)
                val secondMs = t0() - b
                Log.e(
                    "ZAPP",
                    "verify: first=${describe(first)} in ${firstMs}ms | " +
                        "second=${describe(second)} in ${secondMs}ms " +
                        "(cache hit expected: ${secondMs < firstMs && secondMs < 50})",
                )
                GuardState.log(
                    "hop",
                    "verify: ${describe(first)} in ${firstMs}ms, repeat ${secondMs}ms" +
                        if (secondMs < 50) " (cached)" else "",
                )
            }

            /**
             * Pin an edge, or auto-heal one.
             *
             * am broadcast -a com.zapp.net.VPN --es cmd edge --es endpoint 188.114.96.1:2408
             * am broadcast -a com.zapp.net.VPN --es cmd edge
             */
            "edge" -> CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val ep = intent.getStringExtra("endpoint")?.trim().orEmpty()
                val target = if (ep.isNotBlank()) ep else {
                    val best = EdgePins.fastestEdge()
                    Log.e("ZAPP", "vpn relay edge: fastest=${best?.first ?: "none"} ${best?.second ?: ""}ms")
                    best?.first.orEmpty()
                }
                if (target.isBlank()) {
                    GuardState.log("edge", "no edge answered the latency test")
                    return@launch
                }
                ZappVpnService.requestEdge(target)
                app.startService(
                    Intent(app, ZappVpnService::class.java).setAction(ZappVpnService.ACTION_START),
                )
                GuardState.log("edge", "pinned $target")
                Log.e("ZAPP", "vpn relay edge -> $target")
            }

            /**
             * Debug builds only: prove the edge-retirement rule (`_pick_edge_locked`) without
             * waiting for a real dead edge. Marks an endpoint failed and prints the pick that
             * the tunnel would make next.
             *
             * am broadcast -a com.zapp.net.VPN --es cmd edgefail --es endpoint 162.159.192.6:2408
             */
            "edgefail" -> {
                val debuggable =
                    (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                if (!debuggable) {
                    Log.e("ZAPP", "edgefail relay: refused (release build)")
                    return
                }
                val ep = intent.getStringExtra("endpoint")?.trim().orEmpty()
                if (ep.isBlank()) {
                    Log.e("ZAPP", "edgefail relay: no endpoint")
                    return
                }
                EdgePins.markFailed(ep)
                val next = EdgePins.pick(exclude = ep)
                Log.e("ZAPP", "edgefail: marked $ep, cooling=${EdgePins.badCount()}, next=$next, excluded=${next != ep}")
                GuardState.log("edge", "marked $ep bad for 120s; next pick $next")
            }

            /**
             * Debug builds only: drive the guard's real quota path with a frame copied
             * verbatim from the server's `session.step.failed` schema, so the reaction can be
             * observed end to end before the free lane is actually exhausted. Nothing about
             * production behaviour changes; the same handler runs.
             *
             * am broadcast -a com.zapp.net.VPN --es cmd wall --es frame '<json>'
             */
            "wall" -> {
                val debuggable =
                    (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                if (!debuggable) {
                    Log.e("ZAPP", "wall relay: refused (release build)")
                    return
                }
                val frame = intent.getStringExtra("frame").orEmpty()
                val f = runCatching { QuotaWall.parseFrame(frame) }.getOrNull()
                if (f == null) {
                    Log.e("ZAPP", "wall relay: frame did not classify as a wall")
                    GuardState.log("wall", "test frame rejected (not a wall)")
                    return
                }
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    GuardState.log("wall", "test frame ${f.wall.type} scoped=${f.wall.addressScoped}")
                    // hand the same frame to the live view model so the card and the
                    // composer restore are exercised too, not just the rotation gate.
                    // A fresh message id keeps the wall ledger from swallowing a manual test:
                    // production frames are deduped by the server's own message id.
                    val dbg = f.copy(
                        assistantMessageID = "dbg_" + System.currentTimeMillis(),
                    )
                    val delivered = WallDebug.emit(dbg)
                    Log.e("ZAPP", "wall relay: delivered to chat=$delivered")
                    // Same decision the guard service makes, on purpose: a test path that
                    // disagrees with the real one proves the wrong thing.
                    //
                    // The guard now follows `zen_guard.main` verbatim - every wall past the
                    // 75 s cooldown rotates, with no class exempted. So does this.
                    val reg = WallGate.rotateOnce(app, f.wall, "wall:test:${f.wall.type}")
                    Log.e("ZAPP", "wall relay -> ${reg?.v6 ?: "no hop"}")
                    GuardState.log("wall", "test hop -> ${reg?.v6 ?: "none"}")
                }
            }
            /**
             * Debug builds only: run a command through Shizuku and put the answer in logcat.
             * This is the headless proof path - it exercises the same binder the Shell tab
             * uses, so "the panel works" can be shown without touching his screen.
             *
             * am broadcast -a com.zapp.net.VPN --es cmd sh --es c 'id'
             */
            "sh" -> {
                val debuggable =
                    (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                if (!debuggable) {
                    Log.e("ZAPP", "sh relay: refused (release build)")
                    return
                }
                val line = intent.getStringExtra("c").orEmpty()
                if (line.isBlank()) {
                    Log.e("ZAPP", "sh relay: no command")
                    return
                }
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    val out = com.zapp.net.shizuku.Shell.exec(app, line)
                    when {
                        out == null -> Log.e(
                            "ZAPP",
                            "sh relay: no hands (binder=${com.zapp.net.shizuku.Shell.alive()} " +
                                "granted=${com.zapp.net.shizuku.Shell.granted()})",
                        )
                        // One log line per output line. A single concatenated line is cut by
                        // logcat's own limit, which made `dumpsys battery` look like it printed
                        // only its header (measured 03:59:38).
                        else -> {
                            Log.e("ZAPP", "sh relay rc=${out.code} ${out.text.length}b:")
                            out.text.trimEnd().lineSequence().take(60).forEach {
                                Log.e("ZAPP", "  | $it")
                            }
                        }
                    }
                }
            }
            /**
             * Debug builds only: raise the Shizuku grant dialog from the shell, so approval
             * can be given without navigating to the Shell tab first.
             *
             * am broadcast -a com.zapp.net.VPN --es cmd shgrant
             */
            "shgrant" -> {
                val debuggable =
                    (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                if (!debuggable) {
                    Log.e("ZAPP", "shgrant relay: refused (release build)")
                    return
                }
                Log.e(
                    "ZAPP",
                    "shgrant: binder=${com.zapp.net.shizuku.Shell.alive()} " +
                        "already=${com.zapp.net.shizuku.Shell.granted()}",
                )
                com.zapp.net.shizuku.Shell.request { ok ->
                    Log.e("ZAPP", "shgrant: result granted=$ok")
                    GuardState.log("shell", "shizuku permission granted=$ok")
                }
            }
            /**
             * Debug builds only: report or restart the MCP server that gives the agent hands.
             *
             * am broadcast -a com.zapp.net.VPN --es cmd mcp
             */
            "mcp" -> {
                val debuggable =
                    (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                if (!debuggable) {
                    Log.e("ZAPP", "mcp relay: refused (release build)")
                    return
                }
                val action = intent.getStringExtra("action")?.trim()?.lowercase().orEmpty()
                if (action == "restart") com.zapp.net.mcp.McpServer.stop()
                com.zapp.net.mcp.McpServer.start(app)
                Log.e(
                    "ZAPP",
                    "mcp: running=${com.zapp.net.mcp.McpServer.running()} " +
                        "port=${com.zapp.net.mcp.McpServer.PORT} " +
                        "key=${com.zapp.net.mcp.McpServer.key(app)}",
                )
            }
            /**
             * Debug builds only: run a command inside Termux's own process (uid 10448).
             * Needed because /data/data/com.termux is `Permission denied` even to uid shell on
             * this Android 16 build (measured 04:00:18), and the OpenCode config lives there.
             *
             * am broadcast -a com.zapp.net.VPN --es cmd termux --es c 'ls ~/.config/opencode'
             */
            "termux" -> {
                val debuggable =
                    (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                if (!debuggable) {
                    Log.e("ZAPP", "termux relay: refused (release build)")
                    return
                }
                // Two transports. `c` is literal and needs quoting through adb; `b64` is
                // base64 so no metacharacter ever reaches the device shell. Measured
                // 04:06: a command containing `;`, `>` and `|` passed via `c` never arrived,
                // while the plain `ls` probe did.
                val raw = intent.getStringExtra("c").orEmpty()
                val enc = intent.getStringExtra("b64").orEmpty()
                val line = if (enc.isNotBlank()) {
                    runCatching {
                        String(android.util.Base64.decode(enc, android.util.Base64.NO_WRAP))
                    }.getOrDefault("")
                } else {
                    raw
                }
                if (line.isBlank()) {
                    Log.e("ZAPP", "termux relay: no command")
                    return
                }
                val out = intent.getStringExtra("out")?.trim()?.takeIf { it.isNotBlank() }
                    ?: "/sdcard/zapp_cmd.log"
                val ok = com.zapp.net.termux.RunCommand.run(app, line, out)
                Log.e("ZAPP", "termux relay ok=$ok out=$out :: ${line.take(120)}")
            }
        }
    }

    /** One line per verification outcome, for the `verify` relay. */
    private fun describe(r: com.zapp.net.api.ServerHealth.Result): String = when (r) {
        is com.zapp.net.api.ServerHealth.Result.Healthy ->
            "healthy v${r.version} host=${r.hostname}"
        com.zapp.net.api.ServerHealth.Result.Unauthorized -> "UNAUTHORIZED"
        com.zapp.net.api.ServerHealth.Result.Unreachable -> "UNREACHABLE"
    }
}
