package com.zapp.net.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.zapp.net.api.OpenCodeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource
import java.util.concurrent.atomic.AtomicLong

/**
 * The watchdog: subscribes to the OpenCode server's own /event SSE stream
 * (the same feed the web UI renders) and watches for quota-wall errors.
 *
 * The verdict is typed, exactly as the desktop reads it: `session.step.failed` carries
 * `data.error.type` (`provider.free-limit`, `provider.quota`, `provider.go-limit`, …).
 * An address-scoped limit rotates the /64; an account-scoped one does not, because a new
 * address cannot clear a plan.
 *
 * Why SSE and not packet sniffing: Termux's traffic to the model provider is TLS.
 * The server itself classifies the refusal and publishes it here.
 */
class GuardForegroundService : Service() {

    companion object {
        const val CHANNEL = "zapp-guard"
        const val SERVER = com.zapp.net.settings.PHONE_SERVER // Termux on this same phone

        /** Minimum spacing between auto-resume batches (see `resumePending`). */
        private const val RESUME_FLOOR_MS = 30_000L

        /**
         * Context above which a fresh address cannot answer this chat at all.
         * Measured 2026-11-19 23:16: 35 prompts of ~6k passed on one address while a
         * 108 416-token chat was refused on its first.
         */
        private const val CONTEXT_GATE_TOKENS = 48_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sse: EventSource? = null
    private val hops = AtomicLong(0)

    /** `zen_guard._heartbeat`: a running-but-hung guard stops writing this. */
    private var heartbeatJob: Job? = null

    /** `avatar_ui._auto_repair_loop`: the connectivity watchdog. */
    private var autoRepairJob: Job? = null

    /** Last resume batch time, used by the UI; rotation accounting lives in [WallPause]. */
    @Volatile private var lastResumeAtMs = 0L

    /** The armed budget timer; re-reads the persisted deadline every slice. */
    private var freeBudgetJob: Job? = null

    /** The deadline we already announced, so a burst of frames does not spam the log. */
    @Volatile private var freeBudgetLoggedUntil = 0L

    /** their `last_wait_log`: the "wall queued" line is written at most once a minute. */
    @Volatile private var lastWaitLogMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        com.zapp.net.settings.GuardPrefs.load(this)
        if (!com.zapp.net.settings.GuardPrefs.guardOn(this)) {
            // the panel's `chkGuard` is off: do not subscribe, do not wake on a wall
            stopSelf()
            return
        }
        startForeground(1, notif("ZAPP guard alive"))
        startStatusNotification()
        startHeartbeat()
        startAutoRepair()
        // the panel's Hops/Walls/Last-hop must survive a process start even when no Activity
        // is alive to persist them
        com.zapp.net.guard.GuardState.attach(this)
        // their `warp_keeper._proxy_watchdog`: 3 s port check, restart on silence
        ProxyKeeper.start(this)
        // the switch is the truth; make the file the proxy reads match it on every start
        runCatching {
            com.zapp.net.settings.GuardPrefs.applyStrict(
                this, com.zapp.net.settings.GuardPrefs.state.value.strict,
            )
        }

        // The Tor lane's proxy must outlive the activity: `served` reads HTTPS_PROXY once at
        // startup, so a proxy that dies with the UI would leave the server dialling a closed
        // port for the rest of its life. Same fixed port, so an app restart heals it.
        runCatching { com.zapp.net.net.EgressProxy.start() }
            .onFailure { android.util.Log.e("ZAPP", "guard: proxy start: $it") }

        // The guard is the only component that outlives the UI, so it is the only place that
        // can guarantee a tunnel exists after the process is replaced. Without this, an
        // install/force-stop/relaunch left `warp=off` indefinitely: measured 02:39:54, the
        // app came back, the guard came back, and the egress was still the carrier
        // `2001:4455:59c:4700:…` because nothing ever asked the VpnService to run. A tunnel
        // that is not there is not "down", nobody repairs it, and every prompt then leaves
        // through the carrier and answers with a free-limit that looks exactly like quota.
        runCatching {
            // ... but only for someone who actually asked for the tunnel. This used to start
            // on consent alone, so an install that had tapped "Continue without the tunnel"
            // still came back with a live VPN on the next app open - the tunnel he explicitly
            // declined. `vpnWanted` is that decision, written by the identity step and the
            // Guard switch, and it is the only thing allowed to bring the tunnel back.
            if (com.zapp.net.settings.GuardPrefs.vpnWanted(this) &&
                android.net.VpnService.prepare(this) == null
            ) {
                startService(
                    Intent(this, com.zapp.net.vpn.ZappVpnService::class.java)
                        .setAction(com.zapp.net.vpn.ZappVpnService.ACTION_START),
                )
            }
        }.onFailure { android.util.Log.e("ZAPP", "guard: vpn start: $it") }

        watch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /**
     * `zen_guard.py:56-61`: write a liveness stamp every poll.
     *
     * Their dashboard treats a stale `heartbeat.txt` as "guard process alive but hung" -
     * the one failure a notification cannot show. Ours is read by `GuardScreen`/diagnosis
     * the same way: `filesDir/heartbeat.txt`, epoch seconds.
     */
    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (true) {
                runCatching {
                    java.io.File(filesDir, "heartbeat.txt")
                        .writeText((System.currentTimeMillis() / 1000L).toString())
                }
                delay(10_000L)
            }
        }
    }

    /**
     * `avatar_ui._auto_repair_loop:511-520`: settle for 20 s, then tick every 25 s.
     *
     * Their loop is the desktop's only self-healing path. Ours has narrower repair
     * paths inside the VPN service and the rotation engine; this is the table that
     * decides *which* one to use, and it is the one that refuses to loop.
     */
    private fun startAutoRepair() {
        if (autoRepairJob?.isActive == true) return
        autoRepairJob = scope.launch {
            delay(20_000L)
            while (true) {
                runCatching { AutoRepair.tick(this@GuardForegroundService) }
                    .onFailure {
                        GuardState.log("vpn", "auto-repair internal error: ${it.message}")
                    }
                delay(AutoRepair.TICK_MS)
            }
        }
    }

    private fun watch() {
        val base = kotlinx.coroutines.runBlocking {
            com.zapp.net.settings.observe(this@GuardForegroundService).first().serverBase
        }
        val tok = kotlinx.coroutines.runBlocking {
            com.zapp.net.api.loadToken(this@GuardForegroundService)
        }
        val client = OpenCodeClient(base.ifBlank { SERVER }, initialToken = tok.ifBlank { null })
        sse = client.subscribe(onEvent = { raw ->
            // Typed, not textual: the server classifies its own provider refusal and ships
            // `session.step.failed { data.error.type }`. Hopping is only sane when the limit
            // is attached to our egress address - a Go plan or workspace budget does not
            // move with the tunnel.
            val frame = runCatching { com.zapp.net.guard.QuotaWall.parseFrame(raw) }.getOrNull()
                ?: return@subscribe
            val wall = frame.wall

            // THE FREE TIER'S BUDGET: record the window, but never stop him.
            //
            // History, both directions, so this is not re-litigated a third time:
            //   - 2026-11-19 22:56  a 4k chat walled twice, the guard rotated, and the
            //     SAME prompt then answered `finish=stop`. A fresh /64 cleared it.
            //   - 2026-11-19 23:16  on one address, 35 small prompts, 35 answers, 0 walls.
            //   - 2026-11-20 06:11  a never-used /64 was refused 1s after the change, but
            //     the chat on screen carried 108k of context, which is refused on EVERY
            //     address (23:30: "refused on every address, forever").
            //
            // So the refusal is not one thing: a small chat recovers on a fresh identity,
            // a huge one never does. Making the whole app refuse on his behalf - the
            // global `setPaused` this used to do - blocked every window he tapped.
            // the owner's order 2026-11-20: "LAHAT NG TAP WINDOW UNLIMITED".
            //
            // The window is therefore recorded for display only, and the wall falls
            // through to the rotation path under their own 75 s cooldown.
            if (wall is QuotaWall.FreeLimit) {
                // still remember the chat, so the UI can offer it after the reset
                frame.sessionID?.let { ResumeQueue.mark(this@GuardForegroundService, it) }
                noteFreeBudget(wall)
            }

            if (!wall.addressScoped && wall !is QuotaWall.FreeLimit) {
                // Go plan / workspace budget / per-model spend: no address clears it, and
                // the model is DeepSeek by the owner's order (2026-11-20: "GUSTO KO LANG NAMAN
                // IS DEEPSEEK YAN LANG MALAKAS JAN"). Say it once, churn nothing.
                GuardState.log("wall", "account limit (${wall.type}) - no address clears this")
                return@subscribe
            }
            // their `pending.setdefault(sid, now)`: remember the chat that hit the wall so it
            // can be continued once an address is proven, even if it is not the chat on screen
            frame.sessionID?.let { ResumeQueue.mark(this@GuardForegroundService, it) }
            scope.launch {
                // ONE WALL, ONE ROTATION.
                //
                // This service and MainActivity both react to the same refusal: this one from
                // the live `session.step.failed` frame, MainActivity from the stored error
                // part it reloads with the transcript. Neither consulted the other, so one
                // wall rotated twice within eight seconds on 2026-11-24:
                //
                //   02:20:52.801  [wall] address limit (provider.free-limit) -> rotating WARP /64
                //   02:20:52.855  [wall] already answered: ses_df65...|msg_209a3b046... (UI)
                //   02:21:00.359  [wall] address limit (provider.free-limit) -> rotating WARP /64
                //
                // Each rotation is a fresh registration and a full TUN rebuild, and the
                // second one landed on a tunnel that carried nothing: from 02:21:24 every
                // egress probe timed out and the reply on screen stopped mid-thought.
                //
                // The ledger is the shared record, and the message id is the identity. The
                // first component to see the event claims it; the other one skips. A wall is
                // an event, not a state.
                if (!com.zapp.net.guard.WallLedger.claim(
                        this@GuardForegroundService,
                        frame.sessionID,
                        frame.assistantMessageID,
                        wall.type,
                    )
                ) return@launch
                val now = System.currentTimeMillis()
                // `zen_guard.main:508-517` order, verbatim: `_note_wall` runs first, and a
                // true answer means the wall is queued, not rotated. `_note_wall` owns both
                // counters (fresh-address walls, failed hops) and the pause itself.
                if (WallPause.noteWall(
                        this@GuardForegroundService,
                        now,
                        isFreeLimit = wall is QuotaWall.FreeLimit,
                    )
                ) {
                    GuardState.setWallPending(false)
                    return@launch
                }
                val lastAttempt = WallPause.lastHopAttemptMs(this@GuardForegroundService)
                val sinceRotate =
                    if (lastAttempt == 0L) Long.MAX_VALUE else now - lastAttempt
                // their `age >= COOLDOWN_SECONDS` gate, with their own wording
                if (sinceRotate < WallPause.COOLDOWN_MS) {
                    // their `last_wait_log`: the queued line is written at most once a minute
                    val nowWait = System.currentTimeMillis()
                    if (nowWait - lastWaitLogMs >= 60_000L) {
                        lastWaitLogMs = nowWait
                        GuardState.log(
                            "wall",
                            "wall queued — waiting out cooldown " +
                                "(${sinceRotate / 1000}s < ${WallPause.COOLDOWN_MS / 1000}s)",
                        )
                    }
                    GuardState.setWallPending(false)
                    return@launch
                }
                // DO NOT TOUCH THE ROTATION. (the owner, 2026-11-20)
                //
                // "wag muna pakialaman yung unlimited natin paano yon dapat ganon na
                // yon baka mag bug pa". This hop is the only thing between us and the
                // free wall, and it is fragile in three ways that are easy to forget:
                //
                //   * the free tier decides by ADDRESS, not by account - a fresh /64
                //     is a fresh allowance. That is the provider's own behaviour.
                //   * every hop is a fresh Cloudflare WARP registration, and those are
                //     rate-limited per source. Hopping in a loop burns the ability to
                //     register at all, and then nobody can rotate.
                //   * the /64s come from a shared WARP pool. Every ZAPP install spends
                //     from the same pool, so more users = faster burn = a higher
                //     chance opencode.ai flags Cloudflare ranges outright.
                //
                // No extra hops, no background hopping, no "make it faster" edits.
                // Two free-wall hops then a pause (`WallPause`), and that is final.
                GuardState.log("wall", "address limit (${wall.type}) -> rotating WARP /64")
                val reg = WallGate.rotateOnce(this@GuardForegroundService, wall, "wall:${wall.type}")
                if (reg != null) {
                    hops.incrementAndGet()
                    notif("hopped -> ${reg.v6.take(14)}… (${hops.get()} total)")
                    // their `_dispatch_auto_resume` runs right after a proven address
                    ResumeQueue.markProven(this@GuardForegroundService)
                    // AUTO-SEND REMOVED (the owner, 2026-11-20): "AND ALSO REMOVE AUTO SEND".
                    // No `continue` is sent from here. A fresh address is prepared, and the
                    // composer decides. See `MainActivity.handleWall`.
                }
            }
        })

        // The window outlives the process by design (~1-2 h measured). It is reported,
        // never enforced: a persisted deadline must not make a cold start refuse him.
        //
        // The stored value can be stale by an hour (measured 07:09: boot said 08:43:01
        // while four probes at 06:33 / 06:56 / 06:58 / 07:08 all resolved to exactly
        // 08:00:00 local = 00:00:00 UTC). Clamp to the next UTC midnight when it is
        // beyond it, because showing him a later time than the server's own boundary is
        // the one thing the card must never do.
        val storedUntil = com.zapp.net.settings.GuardPrefs.freeLimitUntil(this)
        val utcMidnight = System.currentTimeMillis() + QuotaWall.msUntilUtcMidnight(System.currentTimeMillis())
        val budgetUntil = if (storedUntil > utcMidnight + 5 * 60_000L) utcMidnight else storedUntil
        if (budgetUntil != storedUntil) {
            com.zapp.net.settings.GuardPrefs.setFreeLimitUntil(this, budgetUntil)
        }
        if (budgetUntil > System.currentTimeMillis()) {
            GuardState.log(
                "wall",
                "free budget window said ${QuotaWall.localTime(budgetUntil)} local " +
                    "(${QuotaWall.utcTime(budgetUntil)} UTC) - not enforced, sends allowed",
            )
        }
        armFreeBudgetTimer()
    }

    /**
     * Record the free budget's window for the UI. Nothing else.
     *
     * This used to call `GuardState.setPaused(until)`, which is a client-side gate over
     * EVERY session: one refusal in one chat made the whole app refuse, so every window
     * he tapped read "Free usage limit reached" without a request leaving the phone.
     * the owner's order 2026-11-20: "LAHAT NG TAP WINDOW UNLIMITED".
     *
     * The deadline is still persisted, because it is genuine information - the server's
     * own `retryAfterMs` - and the card shows it. It just no longer decides whether a
     * send is allowed.
     */
    private fun noteFreeBudget(wall: QuotaWall.FreeLimit) {
        val now = System.currentTimeMillis()
        // The refusal's own window is the authority; when it states none, fall back to the
        // server's published boundary (00:00 UTC). Never let a persisted value win: the
        // stored 08:59:56 read 114 min at 07:05 while the wire said 63 min, because an
        // earlier reading was kept by `maxOf`. It is display-only now, so a wrong number
        // on the card is the whole cost - but it is still a wrong number.
        val until = QuotaWall.freeLimitDeadline(wall, now)
        if (until <= com.zapp.net.settings.GuardPrefs.freeLimitUntil(this) &&
            wall.retryAfterMs == null
        ) {
            // A frame that states nothing must not shorten a window we already hold.
            return
        }
        com.zapp.net.settings.GuardPrefs.setFreeLimitUntil(this, until)
        // NOT `setWallPending(true)`. That flag also stops `ZappVpnService` rebuilding or
        // verifying the tunnel (`ZappVpnService.kt:131/337/752`), so a free-budget window
        // would quietly freeze the very rotation this branch is now asking for. The
        // rotation path owns the flag.
        armFreeBudgetTimer()

        if (freeBudgetLoggedUntil == until) return
        freeBudgetLoggedUntil = until
        val left = (until - now) / 60_000L
        GuardState.addWall(
            "free budget window (${wall.type}) says ${QuotaWall.localTime(until)} local / " +
                "${QuotaWall.utcTime(until)} UTC (${left} min) - one shared bucket, " +
                "waiting for the reset",
        )
    }

    /**
     * Arm the reset exactly once.
     *
     * The loop re-reads the persisted deadline every slice instead of trusting one long
     * `delay`: a later refusal can move the deadline forward, and a multi-hour delay is
     * not guaranteed to survive Doze untouched. Slices are 60 s, which costs nothing.
     */
    private fun armFreeBudgetTimer() {
        if (freeBudgetJob?.isActive == true) return
        freeBudgetJob = scope.launch {
            while (true) {
                val until = com.zapp.net.settings.GuardPrefs.freeLimitUntil(this@GuardForegroundService)
                if (until <= 0L) return@launch
                val left = until - System.currentTimeMillis()
                if (left <= 0L) {
                    onFreeBudgetReset(until)
                    return@launch
                }
                delay(left.coerceAtMost(60_000L))
            }
        }
    }

    /**
     * The budget reopened. Clear what the refusal left behind and get the path ready
     * before he types - but never send anything (auto-send is removed by his order).
     */
    private suspend fun onFreeBudgetReset(until: Long) {
        com.zapp.net.settings.GuardPrefs.setFreeLimitUntil(this, 0L)
        com.zapp.net.settings.GuardPrefs.setCarryBlockedUntil(this, 0L)
        GuardState.setPaused(0L)
        GuardState.setWallPending(false)
        freeBudgetLoggedUntil = 0L
        GuardState.log(
            "wall",
            "free budget window passed (${QuotaWall.utcTime(until)} UTC) - carry block released, " +
                "tunnel asked to come up; burn list kept",
        )
        // `RotationEngine.clearBurnHistory` used to run here. It does not in their guard:
        // `zen_guard._blacklist_add` appends and never clears, capped at BLACKLIST_MAX = 200,
        // and `_rotate` only "recycles oldest" when the whole list is burnt. Clearing ours
        // on a clock would re-present addresses they deliberately remember.
        // Prove the path now, so the first prompt after the reset is not the thing that
        // discovers a dead tunnel.
        runCatching {
            if (android.net.VpnService.prepare(this) == null) {
                startService(
                    Intent(this, com.zapp.net.vpn.ZappVpnService::class.java)
                        .setAction(com.zapp.net.vpn.ZappVpnService.ACTION_START),
                )
            }
        }.onFailure { GuardState.log("error", "budget reset: vpn start: ${it.message}") }
        notif("free budget reopened - ready")
    }

    // AUTO-SEND REMOVED (the owner, 2026-11-20): "AND ALSO REMOVE AUTO SEND".
    //
    // `resumePending` used to send the literal string "continue" into every chat the wall had
    // touched (`[auto-resume] Limit bypassed -> sent 'continue' to N of M chat(s)`). That is a
    // prompt the owner never typed, and it spent a fresh /64 per chat. The function is gone, not
    // disabled: nothing in this app sends a message he did not write. `ResumeQueue` still tracks
    // which sessions were refused, so the UI can offer them - it just never sends by itself.

    /**
     * Context the next turn of this chat would carry.
     *
     * `input` alone understates it - almost everything is a cache read (measured `in=334`,
     * `cacheRead=105 984`) - and the whole request is what the free tier judges.
     */
    private suspend fun contextOf(client: OpenCodeClient, sid: String): Long {
        val list = client.messages(sid, 6)
        val last = list.lastOrNull { it.info.role == "assistant" && it.info.tokens != null } ?: return 0L
        val t = last.info.tokens ?: return 0L
        return t.total + (t.cache?.read ?: 0L) + (t.cache?.write ?: 0L)
    }

    private fun notif(text: String): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "ZAPP-Net guard", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("ZAPP-Net")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /**
     * The status bar is the only surface that can tell him the truth while the app is closed,
     * and it said the same thing forever: "ZAPP guard alive". He asked for the tunnel's real
     * state there, including the WARP IPv6 proof.
     *
     * It is driven by [GuardState], the same object the Guard panel reads, so the shade can
     * never disagree with the screen. `setOnlyAlertOnce` keeps it silent: this is a status
     * line, not an alert.
     */
    private fun startStatusNotification() {
        scope.launch {
            GuardState.status.collect { s ->
                val text = when {
                    s.verified && s.lastEgress64.isNotBlank() ->
                        "WARP IPv6 verified \u00b7 ${s.lastEgress64}"
                    s.verified && s.currentV6.isNotBlank() && s.currentV6 != "-" ->
                        "WARP IPv6 verified \u00b7 ${s.currentV6}"
                    s.vpnRunning -> "Tunnel up \u00b7 verifying WARP"
                    else -> "Tunnel off"
                }
                runCatching {
                    getSystemService(NotificationManager::class.java).notify(1, notif(text))
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { sse?.cancel() }
        super.onDestroy()
    }
}

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // the panel's `chkAutostart`; their default is opt-in, ours matches
            if (!com.zapp.net.settings.GuardPrefs.autostartOn(context)) return
            // bring the phone's own opencode server up before the guard tries to subscribe
            runCatching { com.zapp.net.termux.BootRelay.runPending(context) }
            context.startForegroundService(Intent(context, GuardForegroundService::class.java))
        }
    }
}
