package com.zapp.net.guard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One shared state object: the UI (GuardScreen) and the engines (VPN, rotation)
 * meet here. No singleton framework — plain object with StateFlows.
 */
object GuardState {

    /**
     * A scope that belongs to the process, not to a screen.
     *
     * Measured 22 Nov 09:20, four times in twelve seconds:
     *
     * ```
     * [error] register attempt 1 failed: The coroutine scope left the composition
     * [error] Reset keys: The coroutine scope left the composition
     * ```
     *
     * Every Guard button launched its work on `rememberCoroutineScope()`, so leaving the panel
     * - or any recomposition that disposed it - **cancelled the rotation mid-flight**. That is
     * the real reason the panel felt slow: the work was not slow, it was being killed.
     * Guard actions are engine work; they must outlive the screen that started them.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class GuardEvent(
        val tsMs: Long,
        val kind: String,   // "hop" | "wall" | "vpn" | "register" | "error"
        val text: String,
    )

    data class Status(
        val vpnRunning: Boolean = false,
        val currentV6: String = "—",
        val hops: Long = 0,
        val walls: Long = 0,
        /** Epoch ms of the last proven hop; the panel's "Last hop". */
        val lastHopMs: Long = 0L,
        /** Rotation is paused until this instant (free-lane loop-breaker). */
        val pausedUntilMs: Long = 0L,
        /** Set once a tunnel verify has ever come back warp=on. */
        val verified: Boolean = false,
        /**
         * The server is inside a turn right now (`session.time.busy`). A rebuild while this
         * is true tears the wireguard device out from under an open socket, which is exactly
         * the `ECONNRESET` / `EAI_AGAIN` pair the chat showed at 21:42–21:43.
         */
        val streaming: Boolean = false,
        /**
         * A typed address-scoped wall the guard has not yet answered with a proven rotation.
         * While this is true the live-turn deferral is bypassed: a walled turn is already
         * dead and rebuilding the tunnel is the only thing that can free it.
         */
        val wallPending: Boolean = false,
        /**
         * The last `/64` that was actually seen on the wire with `warp=on`.
         *
         * A wall almost always arrives while the tunnel is being rebuilt, so the probe that
         * the wall handler runs can answer `unmeasured` - measured 04:35:53, the wall landed
         * 2 s into a `bringUp` and `EgressProbe.measure` returned nothing. The handler then
         * skipped the ban entirely ("no tunnel egress to retire") and hopped blind, which is
         * how the guard can hand back a prefix it already burned seconds earlier.
         *
         * This is the fallback for exactly that case: the address the provider most recently
         * observed. It is only ever written from a `warp=on` reading, so it can never be the
         * carrier prefix.
         */
        val lastEgress64: String = "",
        /**
         * Proven addresses parked right now (see `WarpPool.solid()`), and the tokens they still
         * hold. the owner's shape: ten addresses x 200k = ~2M of runway, worn one at a time.
         */
        val solid: Int = 0,
        val runwayTokens: Long = 0L,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val _events = MutableStateFlow<List<GuardEvent>>(emptyList())
    val events: StateFlow<List<GuardEvent>> = _events

    private val _pool = MutableStateFlow<List<WarpReg>>(emptyList())
    val pool: StateFlow<List<WarpReg>> = _pool

    private val _blacklist = MutableStateFlow<List<BlacklistEntry>>(emptyList())
    val blacklist: StateFlow<List<BlacklistEntry>> = _blacklist

    /**
     * The counters are evidence, so they are written at event time, not by a UI collector.
     *
     * Measured 2026-11-20: a hop completed at 10:06:25 (`zapp_engine.xml lastHopMs`,
     * `warppool.json note="hop1:wall:provider.free-limit"`) yet the panel still said
     * `Hops 0 · Walls 0 · Last hop never` at 10:09, because
     *   * `hops`/`walls` were persisted only by the Activity's status collector, and a later
     *     settings emission restored the stale stored value over the live one, and
     *   * `lastHopMs` was never persisted at all, so it read 0 after every process start.
     */
    @Volatile private var appCtx: android.content.Context? = null

    private fun counterPrefs() = appCtx?.getSharedPreferences("zapp_counters", android.content.Context.MODE_PRIVATE)

    /** Called from the Activity and the guard service; idempotent and cheap. */
    fun attach(ctx: android.content.Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
        val p = counterPrefs() ?: return
        val s = _status.value
        val lastOk = runCatching { WallPause.lastOkHopMs(ctx) }.getOrDefault(0L)
        val lastFree = runCatching { WallPause.lastFreeWallMs(ctx) }.getOrDefault(0L)
        _status.value = s.copy(
            hops = maxOf(s.hops, p.getLong("hops", 0L), if (lastOk > 0L) 1L else 0L),
            walls = maxOf(s.walls, p.getLong("walls", 0L), if (lastFree > 0L) 1L else 0L),
            // the engine's own `last_ok_hop` is the oldest surviving truth for this value
            lastHopMs = maxOf(
                s.lastHopMs,
                p.getLong("lastHopMs", 0L),
                lastOk,
            ),
        )
        // write the seeded values back so the file and the card cannot disagree
        persistCounters()
    }

    private fun persistCounters() {
        val p = counterPrefs() ?: return
        val s = _status.value
        p.edit()
            .putLong("hops", s.hops)
            .putLong("walls", s.walls)
            .putLong("lastHopMs", s.lastHopMs)
            .apply()
    }

    fun refresh(pool: WarpPool) {
        _pool.value = pool.all()
        _blacklist.value = pool.blacklistView()
        // The two numbers the panel and the in-app banner show. Computed here because `refresh`
        // is already the one place that reads the pool after every registration, wall and hop -
        // a separate updater would drift from the card.
        val s = _status.value
        val solid = runCatching { pool.solid() }.getOrDefault(emptyList())
        val runway = solid.sumOf { (WarpPool.BUDGET - it.spentTokens).coerceAtLeast(0L) }
        _status.value = s.copy(solid = solid.size, runwayTokens = runway)
    }

    fun log(kind: String, text: String) {
        val list = _events.value.toMutableList()
        list.add(0, GuardEvent(System.currentTimeMillis(), kind, text))
        _events.value = list.take(200)
        // The guard's own decisions were invisible outside the UI (this list is in-memory
        // only), which made every diagnosis a guess. Mirror to logcat so the device carries
        // its own evidence: `adb logcat -s ZAPP:* | grep '\[hop\]'`.
        android.util.Log.i("ZAPP", "[$kind] $text")
    }

    /**
     * Never let a stored value overwrite live evidence. The stored counter can only be
     * behind (it is written from the same events), so the larger number is the honest one.
     */
    fun restoreCounters(hops: Long, walls: Long) {
        val s = _status.value
        _status.value = s.copy(
            hops = maxOf(s.hops, hops),
            walls = maxOf(s.walls, walls),
            lastHopMs = maxOf(s.lastHopMs, counterPrefs()?.getLong("lastHopMs", 0L) ?: 0L),
        )
    }

    fun setVpnRunning(run: Boolean, currentV6: String? = null) {
        _status.value = _status.value.copy(
            vpnRunning = run,
            currentV6 = currentV6 ?: _status.value.currentV6,
        )
        log("vpn", if (run) "tunnel UP" else "tunnel DOWN")
    }

    fun addHop(newV6: String) {
        val s = _status.value
        _status.value = s.copy(hops = s.hops + 1, currentV6 = newV6, lastHopMs = System.currentTimeMillis())
        persistCounters()
        log("hop", "rotated -> $newV6")
    }

    fun setVerified(ok: Boolean) {
        _status.value = _status.value.copy(verified = ok)
    }

    /** Record the `/64` the provider last saw with `warp=on`; see [Status.lastEgress64]. */
    fun setLastEgress64(prefix: String) {
        val p = prefix.trim()
        if (p.isBlank() || p == _status.value.lastEgress64) return
        _status.value = _status.value.copy(lastEgress64 = p)
    }

    fun setPaused(untilMs: Long) {
        _status.value = _status.value.copy(pausedUntilMs = untilMs)
    }

    /** Mirror of the chat's live-turn flag; read by the VPN service before any rebuild. */
    fun setStreaming(on: Boolean) {
        if (_status.value.streaming == on) return
        _status.value = _status.value.copy(streaming = on)
        streamingSinceMs = if (on) System.currentTimeMillis() else 0L
    }

    /**
     * When the current turn became "live", or 0.
     *
     * Measured 22 Nov 10:01: `sending` is refreshed from the server (`time.completed == null`),
     * so a dead socket never clears it. The guard then read "a turn is live" forever, held a
     * dead tunnel, and refused every hop - `no reading on 162.159.192.5:2408, but a turn is
     * live - holding` - which is the owner's "I keep disconnecting and I can't connect". A turn
     * flag is a claim; the handshake is the evidence.
     */
    @Volatile var streamingSinceMs: Long = 0L
        private set

    /**
     * When the last *session* event arrived from the server (a text delta, a reasoning delta,
     * a step update, a completion). Heartbeats do not count - they prove the socket is alive,
     * not that a turn is.
     *
     * This is the evidence behind a `streaming` claim. Measured 22 Nov 10:21: a freshly started
     * app reported `streaming=true` with no chat open and no turn running, because
     * `sending` mirrors the server's `detail.time.isBusy` and an **orphaned turn** (the provider
     * socket died mid-answer) leaves `isBusy` true forever. That single stuck flag made the
     * guard hold a dead tunnel and refuse every hop: the owner's "I keep disconnecting and I can't
     * connect".
     */
    @Volatile var lastTurnEventMs: Long = 0L
        private set

    /** Note that the turn produced something. Called from the stream handler. */
    fun noteTurnEvent() {
        lastTurnEventMs = System.currentTimeMillis()
    }

    /**
     * Age of the live WireGuard handshake, in seconds, as last measured by `ZappVpnService`.
     * `null` means "no reading yet". Published by the service so the rotation engine - which
     * runs outside it - judges on the same number.
     */
    @Volatile var lastHandshakeAgeSec: Long? = null

    /**
     * Last moment this tunnel proved it carried anything at all.
     *
     * The handshake is one proof; a completed egress verify is the other, and it exists for the
     * case the handshake cannot answer: `Uapi.handshakeAgeSec` returns `null` when there is no
     * active tunnel yet, or when the UAPI socket is unreadable - and a `null` reading used to
     * mean "no evidence", which the guard then treated as "no reason to act" and held a dead
     * tunnel while the reply on screen never moved.
     *
     * `0` means nothing has been proven since the last teardown, which is a fresh tunnel and
     * gets the grace period instead of a verdict.
     */
    @Volatile var lastCarryProofMs: Long = 0L
        private set

    /** Note that bytes were measured leaving through the tunnel. Called on verify ok. */
    fun noteCarry() {
        lastCarryProofMs = System.currentTimeMillis()
    }

    /**
     * Publish a handshake reading. **It is not a carry proof.**
     *
     * These two used to share one clock, and that made [turnIsLive] self-feeding:
     * `ZappVpnService.turnIsLive()` publishes the handshake as a side effect of being asked,
     * so every evaluation refreshed `lastCarryProofMs` and the carry-stale breaker at the end
     * of [turnIsLive] could never fire. A predicate that writes its own evidence never
     * reports a dead turn.
     *
     * Measured 2026-11-24, exactly that:
     *
     *   02:38:55  reveal: seg=0 msg=msg_209b38c1 start=35 len=35 ... sending=true
     *   02:39:06  tunnel VERIFY: no reading on 162.159.193.5:2408, but a turn is live - holding
     *   02:41:12  stream cadence: 1 deltas -> 2 flushes in 154703ms (0/s in, 0/s drawn)
     *
     * One delta in 154 s, three consecutive probe timeouts, and every recovery path held the
     * tunnel because the turn "was live". Nothing healed until he refreshed and reconnected.
     *
     * A WireGuard handshake proves the peer answered a handshake. It does not prove the peer
     * forwarded a packet to the provider - and Cloudflare answering handshakes while carrying
     * nothing is the exact failure this file exists for. Carriage is proven by a completed
     * egress verify ([noteCarry], called from the verify path and the keeper's own probe), or
     * by nothing at all.
     */
    fun noteHandshake(ageSec: Long?) {
        lastHandshakeAgeSec = ageSec
    }

    /**
     * A proof exists and has aged out. `0` is not staleness: it is a fresh tunnel, which gets
     * the grace period instead of a verdict.
     */
    fun carryStale(): Boolean {
        val c = lastCarryProofMs
        return c > 0L && System.currentTimeMillis() - c > CARRY_STALE_MS
    }

    /** Forget the proof: a torn-down tunnel has carried nothing. */
    fun clearCarry() {
        lastCarryProofMs = 0L
    }

    /**
     * Is a turn really producing tokens right now?
     *
     * Three independent claims must agree, because each one alone has lied to us:
     *
     * 1. `streaming` - the server's `isBusy`. An orphaned turn leaves it true forever
     *    (measured 22 Nov 10:21 on a freshly started app with no chat open).
     * 2. A session event within [TURN_SILENCE_MS] - a live turn emits deltas. Necessary but
     *    not sufficient: measured 22 Nov 10:36, `session.*` frames kept arriving while three
     *    probe reads timed out AND both probe URLs threw `SocketTimeoutException`.
     * 3. A WireGuard handshake within [HANDSHAKE_FRESH_S] - the physical proof that bytes are
     *    moving. A turn cannot produce tokens through a tunnel that carries nothing, so a
     *    stale handshake outranks every claim above it. Keepalive is 25 s and WireGuard
     *    rekeys at 120 s, so a healthy tunnel never goes quiet for 90 s.
     *
     * The single answer used by BOTH the rotation engine and the VPN service. They used to
     * disagree: `hop()` only looked at `streaming`, so a stuck flag blocked every hop with
     * `not rotating during a live turn` while the tunnel was demonstrably dead - measured
     * 22 Nov 10:04:52 and 10:05:03, two manual hops refused in a row on a tunnel whose lane
     * answered nothing.
     */
    fun turnIsLive(): Boolean {
        val st = _status.value
        if (!st.streaming || st.wallPending) return false
        val last = lastTurnEventMs
        if (last == 0L) return false
        val silentFor = System.currentTimeMillis() - last
        val hs = lastHandshakeAgeSec

        // A measured stale handshake outranks every claim below it, including a recent event.
        // See [HANDSHAKE_STALE_S]: this is the deadlock breaker ported from avatar's
        // `_active_tunnels() == 0`, and it only fires on a reading that exists.
        if (hs != null && hs > HANDSHAKE_STALE_S) return false

        // Nothing has left this device through the tunnel for far too long. This outranks the
        // "recent event" rule below, and that ordering is the whole point.
        //
        // Measured 23 Nov 18:28:36 on this phone, with the tunnel demonstrably dead:
        //
        //   tunnel VERIFY read 3/3 on 162.159.192.9:2408: no reply (probe timed out)
        //   tunnel VERIFY: no reading on 188.114.96.1:500, but a turn is live - holding
        //   egress local failed on https://api64.ipify.org: SocketTimeoutException
        //   stream silent for 55506ms (gen 1) -> resubscribing
        //   [pool] prove skipped: turnLive=true wallPending=false
        //
        // Three probes across two edges, both egress URLs, all timed out; the handshake had
        // no reading at all (`hs == null`, so the check above could not fire); and the turn
        // was inside [TURN_SILENCE_MS] of its last event, so the line below answered `true`
        // and the guard held a tunnel it had just failed to measure three times.
        //
        // A turn cannot be producing tokens through a tunnel that has proven nothing for two
        // and a half minutes. That is a measurement, not a guess - and it is the phone-side
        // equivalent of avatar's `_active_tunnels() == 0` gate, which never trusted a turn flag
        // at all (zen_proxy.py:919-935).
        if (carryStale()) return false

        // A turn that is still emitting events is live, whatever the handshake says.
        //
        // The handshake used to outrank this, which is backwards. Measured 22 Nov 11:56 on
        // this phone: a long `python3 -m unittest` tool call emits no session event for
        // minutes, and a just-rebuilt tunnel reports `handshake=absent` for its first seconds.
        // Either one made `turnIsLive()` false, the keeper rebuilt the tunnel, and the
        // provider's socket died mid-answer:
        //
        //   Connection lost while reading the response: ECONNRESET:
        //   The socket connection was closed unexpectedly.
        //
        // That is the "connection lost after a long run" he kept hitting, and it was caused by
        // this app, not by the network.
        if (silentFor < TURN_SILENCE_MS) return true

        // Silent for a long time: either the turn is inside one long tool call, or it is a
        // zombie left behind by a tunnel that carries nothing. Only the handshake can tell
        // those apart, so it decides here and nowhere else.
        if (hs != null) {
            // A fresh handshake means the tunnel is carrying. The turn is therefore in a long
            // tool call - cutting it would destroy real work, so it is never cut.
            return hs <= HANDSHAKE_FRESH_S
        }
        // No reading yet (a rebuild just happened): give a just-started turn a minute so a
        // fresh tunnel is not cut before its first packet leaves.
        val since = streamingSinceMs
        return since != 0L && System.currentTimeMillis() - since < 60_000
    }

    /** How long a `streaming` flag may stand without a single session event behind it. */
    private const val TURN_SILENCE_MS = 180_000L

    /**
     * How old a WireGuard handshake may be before the tunnel is judged not to be carrying.
     * Used for the "is the turn still alive" question: a fresh handshake means the peer is
     * answering, so a silent turn is inside a long tool call and must not be cut.
     */
    private const val HANDSHAKE_FRESH_S = 90L

    /**
     * How old a handshake must be before it overrules a live turn outright.
     *
     * Avatar's `Opencode Unlimited v4.0` gates repair on `_active_tunnels() == 0` - a count of
     * real open proxy sockets - and never on a "turn is live" flag, so a dead tunnel can always
     * be repaired (zen_proxy.py:919-935). Ours gated on the flag, and that deadlocks:
     *
     *   measured 23 Nov 18:18-18:19 on this phone
     *   stream silent for 55506ms (gen 1) -> resubscribing
     *   [pool] prove skipped: turnLive=true wallPending=false
     *   tunnel VERIFY: no reading on <edge>, but a turn is live - holding
     *
     * The turn could not finish because the tunnel was dead, and the guard would not repair the
     * tunnel because the turn was "live". A handshake this stale is a measured physical fact:
     * WireGuard keepalives every 25 s and rekeys at 120 s, so a carrying tunnel never reaches
     * 180 s. When the reading exists and is this old, the turn is a zombie and repair wins.
     *
     * `hs == null` is different - a tunnel rebuilt seconds ago has no reading yet - and keeps
     * the 60 s grace below, which is why the 22 Nov ECONNRESET regression cannot come back.
     */
    private const val HANDSHAKE_STALE_S = 180L

    /**
     * How long a tunnel may prove nothing before a "live" turn stops being believable.
     *
     * Shorter than [HANDSHAKE_STALE_S] on purpose: that constant only fires when a reading
     * *exists*, and the case that actually froze this phone had no reading at all. 150 s is
     * six keepalive intervals and more than one rekey, so a carrying tunnel cannot reach it.
     */
    private const val CARRY_STALE_MS = 150_000L

    /** Set while a wall is unanswered, cleared once a rotation has been proven. */
    fun setWallPending(on: Boolean) {
        if (_status.value.wallPending == on) return
        _status.value = _status.value.copy(wallPending = on)
    }

    fun addWall(reason: String) {
        val s = _status.value
        _status.value = s.copy(walls = s.walls + 1)
        persistCounters()
        log("wall", reason)
    }

    fun setRegisterError(text: String) = log("error", text)

    // ---- one-tap repair: a staged pipeline with its own visible loader ----
    //
    // the owner, 22 Nov: "MAKE THE DISCONNECT WAIT UNTIL DISCONNECT THEN FIX WARP WAIT UNTIL
    // SUCCESS THEN REFRESH UNTIL REFRESHED AND THEN ROTATE UNTIL SUCCESSFUL ROTATE AND THEN
    // VERIFIED TO CONNECT... 1 BUTTON LANG YAN PERO MAY PARANG LOADER NA INDICATOR NOT
    // BORING NA INDICATOR".
    //
    // Every step already existed as its own button, and each one was fire-and-forget: the
    // panel showed "Rotating..." while the engine was still tearing down the old device, so
    // the next tap landed on a half-built tunnel. This pipeline makes each stage *wait for
    // its own proof* before the next one starts, and publishes which stage is running so
    // the panel can draw it.

    data class RepairStep(
        val name: String,
        /** 0 pending, 1 running, 2 done, 3 failed, 4 skipped */
        val state: Int = 0,
        val detail: String = "",
    ) {
        companion object {
            const val PENDING = 0
            const val RUNNING = 1
            const val DONE = 2
            const val FAILED = 3
            const val SKIPPED = 4
        }
    }

    private val _repair = MutableStateFlow<List<RepairStep>>(emptyList())

    /** The stages of the running repair, or empty when nothing is running. */
    val repair: StateFlow<List<RepairStep>> = _repair

    private val _repairRunning = MutableStateFlow(false)
    val repairRunning: StateFlow<Boolean> = _repairRunning

    /** One line under the loader: what the current stage is waiting for right now. */
    private val _repairNote = MutableStateFlow("")
    val repairNote: StateFlow<String> = _repairNote

    /** Epoch ms the run started, so the loader can count seconds instead of spinning. */
    private val _repairStartedMs = MutableStateFlow(0L)
    val repairStartedMs: StateFlow<Long> = _repairStartedMs

    fun beginRepair(names: List<String>) {
        _repair.value = names.map { RepairStep(it) }
        _repairStartedMs.value = System.currentTimeMillis()
        _repairNote.value = ""
        _repairRunning.value = true
    }

    fun repairStep(index: Int, state: Int, detail: String = "") {
        val cur = _repair.value.toMutableList()
        if (index !in cur.indices) return
        cur[index] = cur[index].copy(state = state, detail = detail)
        _repair.value = cur
    }

    fun repairNote(text: String) {
        _repairNote.value = text
    }

    fun endRepair() {
        _repairRunning.value = false
        _repairNote.value = ""
    }
}
