package com.zapp.net.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import com.zapp.net.guard.GuardState
import com.zapp.net.guard.RotationEngine
import com.zapp.net.guard.WarpPool
import com.zapp.net.guard.WarpReg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/**
 * App-only WARP tunnel.
 *
 * ONLY ONE VpnService MAY EXIST PER APP. `com.wireguard.android:tunnel` ships
 * GoBackend, whose own VpnService does the establishing; when this class also called
 * `builder.establish()` the two revoked each other and the tunnel flapped every
 * second (guard log: "tunnel UP / tunnel DOWN / tunnel DOWN" on a 1 s loop). So the
 * TUN is owned by GoBackend alone, and the "Termux only" rule is expressed the way
 * the library expects it: Interface.includeApplication("com.termux"), which GoBackend
 * turns into addAllowedApplication on its builder.
 *
 * Nothing else on the phone enters the tunnel, so real fingers keep their own route.
 */
class ZappVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTunnel = AtomicReference<WireTunnel?>(null)
    /**
     * Next bring-up's endpoint, set when a verify proves the previous edge dead or when the
     * rotation engine wants a specific colo. Measured 21:12 on one registration: edge
     * 162.159.192.9 answered with a fresh /64 (2a09:bac1:5ae0:40::/64) while 162.159.192.10
     * answered with one already burned � the edge decides which Cloudflare prefix we land on.
     */
    private val forcedEdge = AtomicReference<String?>(null)
    private val edgeTries = java.util.concurrent.atomic.AtomicInteger(0)
    /**
     * Consecutive edges whose verify produced no reply at all. Two in a row mean WARP is not
     * carrying traffic on this phone at this moment, which is not something an edge re-roll can
     * fix; see the verify path.
     */
    private val noReplyStreak = java.util.concurrent.atomic.AtomicInteger(0)
    /** Re-rolls spent on "verified, but on an already-burned /64"; see the verify path. */
    private val burnedVerifyTries = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * A turn is producing tokens right now. That is direct proof this tunnel carries, so no
     * probe timeout may be used to justify replacing the device underneath it.
     *
     * A walled turn is excluded: it is already dead, and the rebuild is what frees it.
     *
     * The flag alone is not proof. Measured 22 Nov 10:01: `sending` mirrors server state
     * (`time.completed == null`), so a socket that dies never clears it - the guard then held
     * a dead tunnel indefinitely and refused every hop. A live stream sends data, and data
     * keeps the WireGuard handshake fresh (keepalive is 25 s), so the handshake is the evidence
     * that the turn is really still running.
     */
    private fun turnIsLive(): Boolean {
        // Publish the handshake first: `GuardState.turnIsLive()` requires it as the physical
        // evidence that bytes are moving, and `hop()` reads it from the rotation engine where
        // no UAPI socket is reachable.
        GuardState.noteHandshake(Uapi.handshakeAgeSec(filesDir, activeTunnel.get()?.name ?: ""))
        val live = GuardState.turnIsLive()
        if (!live && GuardState.status.value.streaming && !GuardState.status.value.wallPending) {
            val last = GuardState.lastTurnEventMs
            android.util.Log.e(
                "ZAPP",
                "turn flag says live but " +
                    "lastEvent=" + (if (last == 0L) "never" else "${(System.currentTimeMillis() - last) / 1000}s") +
                    " handshake=" + (GuardState.lastHandshakeAgeSec?.let { "${it}s" } ?: "absent") +
                    " - not holding",
            )
        }
        return live
    }
    private val tunnelSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private val registrationTries = java.util.concurrent.atomic.AtomicInteger(0)

    /** Last moment the tunnel proved a real WARP egress; the keeper's staleness clock. */
    @Volatile private var lastVerifyOkMs = 0L

    /** Minute bucket of the last "held" line, so the release is reported once per minute. */
    @Volatile private var heldLoggedForBucket = -1L

    override fun onCreate() {
        super.onCreate()
        com.zapp.net.settings.GuardPrefs.load(this)
        // A release proven before the process died must still hold after it comes back.
        carryBlockedUntilMs = com.zapp.net.settings.GuardPrefs.carryBlockedUntil(this)
        // The MCP listener is anchored here, not only in MainActivity: this service is a
        // foreground service that outlives the UI, so the agent keeps its hands even after
        // the activity is swiped away. Idempotent - start() is a no-op when already bound.
        runCatching { com.zapp.net.mcp.McpServer.start(this) }
            .onFailure { android.util.Log.e("ZAPP", "mcp start (service) failed: $it") }
        // The reference `warp_keeper.py` runs its `ensure_connected` pass on a 45 s tick and
        // never resets a healthy session. Same tick, same rule, ours.
        scope.launch { keeperLoop() }
        holdAwake()
    }

    /**
     * Keep the CPU running while the tunnel is up.
     *
     * the owner, 22 Nov: "RUN A TEXT MULTIPLE TIMES MOVE THE SCREEN OF MY EVERY 10MINS PARA HINIDI
     * MAMATAY ANG CP". Measured before this existed: `am_proc_died [0,14873,com.zapp.net,50,4]`
     * at 08:35:27 with no `am_kill` and no FATAL, `com.termux` dead one second later - the ROM
     * reaper, taking both because nothing held the process awake.
     *
     * A partial wake lock on a foreground service is the supported way to survive doze: it
     * keeps the CPU and the radio, not the screen, and Android does not count it against the
     * foreground service it already permits. Held for the life of the tunnel, released in
     * [onDestroy] - a wake lock leaked past the service is a battery bug, and this phone is
     * the only one he has.
     */
    private fun holdAwake() {
        runCatching {
            val pm = getSystemService(PowerManager::class.java) ?: return@runCatching
            if (wakeLock?.isHeld == true) return@runCatching
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zapp:tunnel").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
            android.util.Log.e("ZAPP", "wake lock held: the tunnel outlives the screen")
        }.onFailure { android.util.Log.e("ZAPP", "wake lock failed: $it") }
    }

    /**
     * Ported keeper (`warp_keeper.py`: `TICK_SECONDS = 45`, `REPAIR_COOLDOWN_SECONDS = 120`).
     *
     * Order is theirs: connected and fresh -> nothing; down -> connect; still unproven after
     * the cooldown -> renew the keys. A healthy tunnel is never rebuilt just because the tick
     * came around, because every rebuild costs a TUN and can land on a different prefix.
     */
    private suspend fun keeperLoop() {
        var lastRepairMs = 0L
        while (true) {
            kotlinx.coroutines.delay(45_000)
            // Keep the handshake age fresh for `GuardState.turnIsLive()`: the rotation engine
            // runs outside this service and has no UAPI socket of its own.
            runCatching {
                GuardState.noteHandshake(
                    Uapi.handshakeAgeSec(filesDir, activeTunnel.get()?.name ?: ""),
                )
            }
            if (!com.zapp.net.settings.GuardPrefs.keeperOn(this)) continue
            // WARP was proven to carry nothing a few minutes ago. Rebuilding now would cut the
            // server's DNS again for another cycle of nothing, so leave it down until the block
            // expires. See `carryBlockedUntilMs`.
            val blockedUntil = carryBlockedUntilMs
            if (System.currentTimeMillis() < blockedUntil) {
                val left = (blockedUntil - System.currentTimeMillis()) / 1000
                android.util.Log.e("ZAPP", "keeper: WARP carries nothing - leaving the direct path for ${left}s")
                continue
            }
            if (!running || !tunnelUp) {
                // A verify that outlived its own probe budget is a leaked flag, not work in
                // progress: honouring it here made the keeper skip every 45 s tick forever.
                if (verifying && System.currentTimeMillis() - verifyingSinceMs < VERIFY_FLAG_TTL_MS) continue
                android.util.Log.e("ZAPP", "keeper: tunnel is down -> starting it")
                GuardState.log("vpn", "keeper: tunnel down, starting")
                // Call the rebuild directly, forced. Going through ACTION_START let the
                // bring-up's own "up and verifying" guard refuse it in the same tick
                // (`bringUp skipped: 67676ef2�`, measured 02:23:42) while `ifconfig tun0`
                // was empty.
                bringUp(force = true, reason = "keeper")
                continue
            }
            // The keeper's job is "nothing is carrying packets -> bring it up". It is NOT
            // "the proof is old -> rotate". Measured 2026-11-20 01:49-01:50, the stale-clock
            // rule fired with `lastVerifyOkMs = 0` after every teardown, so it renewed keys,
            // tore the fresh tunnel down, saw 0 again, and repeated every ~40 s:
            //   01:49:48 keeper: unproven for never -> renewing keys
            //   01:49:50 tunnel DOWN
            //   01:50:32 keeper: unproven for never -> renewing keys
            // Each rebuild rolled a new public /48 and walked into walled prefixes. A live
            // tunnel is left strictly alone; a truly dead one is caught by the verify path
            // (auto-repair) and by the wall-driven hop, both of which have evidence.
            if (verifying) continue
            if (GuardState.status.value.wallPending) continue
            // Stock the reserve. One registration per tick, never while a turn streams, and it
            // does not touch the TUN - so a healthy tunnel stays exactly where it is while the
            // spare addresses accumulate behind it.
            val engine = RotationEngine(this@ZappVpnService)
            runCatching { engine.warmFill() }
            // A registration is not a token until `cdn-cgi/trace` has answered through it.
            // Measured 22 Nov 10:15: 10 registrations, 3 solid, 7 `proof=never`, runway 600k
            // instead of 2.0M. When there is nothing left to register, wear one unproven spare
            // so the verify loop measures it and it becomes a real token.
            runCatching { engine.proveSpare() }
            val stale = lastVerifyOkMs != 0L &&
                System.currentTimeMillis() - lastVerifyOkMs >= KEEPER_STALE_MS
            if (!stale) continue
            val now = System.currentTimeMillis()
            if (now - lastRepairMs < 120_000L) continue
            lastRepairMs = now
            android.util.Log.e("ZAPP", "keeper: no proof for ${(now - lastVerifyOkMs) / 1000}s -> probing")
            val proof = runCatching {
                // 8 s. Twenty was a desktop budget: the keeper runs every 25 s and this probe
                // sits in the same service that has to answer the chat. Measured 02:21-02:24
                // it is also the only path that ever rebuilt a *proven* tunnel, so a shorter
                // budget makes the failure case cheaper without changing the verdict.
                com.zapp.net.termux.EgressProbe.measure(this@ZappVpnService, 8_000)
            }.getOrNull()
            if (proof != null && proof.ip.isNotBlank() && proof.warp == "on") {
                lastVerifyOkMs = System.currentTimeMillis()
                GuardState.noteCarry()
                // THE SAME VERDICT THE VERIFY PATH PUBLISHES.
                //
                // `stopTunnel` sets `verified = false` on every teardown, and only the verify
                // path ever set it back to true. The keeper's own successful probe did not, so
                // a tunnel that had just proved egress still read as "running but has not
                // carried traffic" to AutoRepair - which then ran "Fix WARP keys" and rebuilt
                // it. Measured 2026-11-24:
                //
                //   02:21:00.082  egress local: ip=2a09:bac5:4ffa:263c::3cf:4c warp=on
                //   02:21:04      auto-repair start_keeper ("running but has not carried")
                //   02:21:19.566  [hop] repair: Fix WARP keys ok in 15s
                //   02:21:19.577  bringUp(user-start) force=true gen=5
                //   02:21:24.919  egress local failed ... SocketTimeoutException: timeout
                //
                // A working tunnel was destroyed by our own repair loop, and the one that
                // replaced it carried nothing - which is the reply that stops mid-thought.
                //
                // A burned prefix stays burned, exactly as the verify path treats it.
                val burnedHere = com.zapp.net.guard.RotationEngine
                    .isBurnedPrefix(this@ZappVpnService, proof.prefix64)
                if (!burnedHere) GuardState.setVerified(true)
                GuardState.log(
                    "vpn",
                    "keeper: still proven (${proof.ip} warp=on) - no rebuild, same /48 kept",
                )
                continue
            }
            // Same rule as the verify path: a probe with no underlying network underneath proves
            // nothing about the tunnel, so it must not cost a registration and a new /48.
            if (!com.zapp.net.termux.EgressProbe.hasUnderlyingNetwork(this@ZappVpnService)) {
                android.util.Log.e(
                    "ZAPP",
                    "keeper: no proof, but the phone has no underlying network - not renewing keys",
                )
                continue
            }
            // And a fresh WireGuard handshake outranks a silent probe: the peer's own answer
            // says the tunnel is moving bytes, so the probe is the thing that failed. Without
            // this the keeper renewed keys on a healthy idle tunnel every time a probe timed
            // out, which is the same churn the verify path had (pass 49).
            val hsAge = Uapi.handshakeAgeSec(filesDir, activeTunnel.get()?.name ?: "")
            if (hsAge != null && hsAge in 0..180) {
                lastVerifyOkMs = System.currentTimeMillis()
                // A HANDSHAKE IS NOT CARRIAGE.
                //
                // This branch used to call `GuardState.noteCarry()`, which made the carry clock
                // immune: a WireGuard handshake is refreshed every 25 s by keepalive, so
                // `lastCarryProofMs` was always fresh and `turnIsLive()`'s carry-stale breaker
                // could never fire. Cloudflare answering handshakes while forwarding nothing is
                // exactly the failure path, and it is what held this tunnel on 2026-11-24:
                //
                //   02:39:06  tunnel VERIFY: no reading on 162.159.193.5:2408, but a turn is live - holding
                //   02:41:12  stream cadence: 1 deltas -> 2 flushes in 154703ms (0/s in, 0/s drawn)
                //
                // Keys are still held on a cold probe - renewing on one failed measurement is the
                // churn this guard exists for. But when nothing has actually carried for the full
                // stale window, the handshake no longer buys a hold.
                val carryStale = GuardState.carryStale()
                if (!carryStale) {
                    android.util.Log.e(
                        "ZAPP",
                        "keeper: no proof, but handshake was ${hsAge}s ago - holding",
                    )
                    GuardState.log("vpn", "keeper: handshake ${hsAge}s ago - probe is cold, holding")
                    continue
                }
                android.util.Log.e(
                    "ZAPP",
                    "keeper: handshake ${hsAge}s fresh but nothing carried for " +
                        "${(System.currentTimeMillis() - GuardState.lastCarryProofMs) / 1000}s - renewing keys",
                )
                GuardState.log(
                    "vpn",
                    "keeper: the peer answers handshakes and forwards nothing " +
                        "(${(System.currentTimeMillis() - GuardState.lastCarryProofMs) / 1000}s without carriage)" +
                        " -> renewing keys",
                )
            } else {
                GuardState.log("vpn", "keeper: no egress through the tun -> renewing keys")
            }
            runCatching { RotationEngine(this@ZappVpnService).resetKeys() }
        }
    }

    /**
     * One bring-up at a time.
     *
     * Measured 21:11:50�21:12:42: two bring-ups interleaved, each `establish()`ed its own
     * TUN, the newer generation replaced the older one mid-verify, and the older one threw
     * away the only successful egress reading it ever got ("VERIFY superseded gen 53 -> 54").
     * A hop then waits on `awaitEgress`, never sees a value, and reports the rotation as
     * failed while the tunnel was in fact working. Serialising fixes the cause, not the log.
     */
    private val upLock = Mutex()

    /**
     * When [verifying] was set. The flag alone deadlocked the service: a verify that was
     * superseded never cleared it, so every later bring-up returned "up and verifying" while
     * `ifconfig tun0` was empty, DNS died and the server answered `EAI_AGAIN opencode.ai`
     * (measured 2026-11-20 01:52:37 and 01:53:22). A flag with no age cannot be trusted.
     */
    @Volatile private var verifyingSinceMs = 0L

    @Volatile private var tunFd: ParcelFileDescriptor? = null

    /** True only after the backend accepted the tunnel. Never inferred. */
    @Volatile private var tunnelUp = false

    companion object {
        const val ACTION_START = "com.zapp.net.vpn.START"
        const val ACTION_STOP = "com.zapp.net.vpn.STOP"

        /**
         * A start the owner asked for with his own thumb.
         *
         * `ACTION_START` is also fired by four automatic paths (cold start, keeper, wall
         * rotation, budget reset) and all of them must respect the carry release - a tunnel
         * that carries nothing takes Termux's DNS down with it. A tap is different: it is a
         * decision, and honouring it is the only way the Connect button can ever mean
         * anything. Measured 22 Nov 08:0x: `bringUp(action-start) held: WARP carries nothing,
         * 186s left on the release` after a tap, with `CARRY_BLOCK_MS = 10 min` behind it -
         * the owner: "AYAW MAG CONNECT ... SUBRANG BAGAL SA LAHAT NA PIPINDUTIN MO DON SA GUARD".
         */
        const val ACTION_START_USER = "com.zapp.net.vpn.START_USER"

        /**
         * Swap the identity **without** rebuilding the tunnel.
         *
         * `GoBackend.setState(UP)` always tears the current device down first (decompiled
         * `GoBackend.setState`, bytecode 57-99), which costs 200-700 ms and kills every socket.
         * The native device accepts a `set=1` rekey over its own UAPI socket instead - see
         * [Uapi]. This action carries a registration id in [EXTRA_REG_ID].
         */
        const val ACTION_HOT_SWAP = "com.zapp.net.vpn.HOT_SWAP"
        const val EXTRA_REG_ID = "reg_id"

        /**
         * `ACTION_START` carrying a caller that has already measured this tunnel as dead.
         *
         * The live-turn deferral exists to protect a turn that is producing tokens. It must
         * never outrank a verdict, and the keeper's `resetKeys` produces one: its proof went
         * stale, a 20 s egress probe timed out, the phone had an underlying network, and no
         * WireGuard handshake happened in the last 180 s. Three independent negative readings.
         *
         * Measured 2026-11-24 01:55:44 anyway:
         *
         *   keeper: no proof for 137s -> probing
         *   egress local failed on https://api64.ipify.org: SocketTimeoutException: timeout
         *   keeper: no egress through the tun -> renewing keys
         *   bringUp(action-start) force=false gen=7
         *   bringUp deferred: a turn is live
         *   rebuild deferred - a turn is live
         *
         * `resetKeys` then waited 30 s for a generation that never changed, deleted and retired
         * the registration it had just made, and the dead tunnel stayed up. That is the same
         * "hop never ran" deadlock as 21:49 - the repair's own evidence was ignored by its own
         * gate. Only the keeper sets this, and only from that path.
         */
        const val EXTRA_FORCE = "force"

        /**
         * The endpoint the live tunnel was built on (`ip:port`).
         *
         * Their proxy owns its upstream socket, so it always knows which edge a reset came
         * from. We do not: opencode holds that socket. Publishing the edge the TUN was built
         * on is the closest honest answer, and it is exactly what
         * `EdgePins.noteStreamReset` needs to apply their two-strikes rule.
         */
        @Volatile
        var activeEdgeName: String? = null
            internal set

        internal fun clearActiveEdge() {
            activeEdgeName = null
        }

        /**
         * Edge for the next bring-up, set from outside the service. The rotation engine
         * uses it to re-roll the edge on an identity it keeps, instead of paying for a
         * brand-new registration: the edge, not the keypair, decides the Cloudflare /64.
         */
        @Volatile private var requestedEdge: String? = null
        fun requestEdge(edge: String) { requestedEdge = edge }
        /**
         * Until this moment WARP has been measured to carry nothing on this phone.
         *
         * Measured 05:51-05:54: after a wall-driven rebuild every edge answered NOTHING, and
         * Termux - which is inside the tunnel - then lost DNS too (`EAI_AGAIN opencode.ai` on
         * screen). A tunnel that carries nothing is strictly worse than no tunnel: the server
         * still has the home network, which answered IPv4 in 54 ms and IPv6 to Cloudflare.
         *
         * While this is in the future the keeper leaves the tunnel down instead of rebuilding it
         * every 45 s, so the server keeps a working path while WARP is retried later.
         */
        @Volatile var carryBlockedUntilMs: Long = 0L
            internal set

        /**
         * How long a proven non-carrying WARP is left alone before it is tried again.
         *
         * 90 s, not ten minutes.
         *
         * The release exists to stop a rebuild loop against a path Cloudflare is not
         * forwarding - and that is still what it does: the keeper ticks every 45 s, so a
         * release of 90 s permits at most two rebuilds before the verdict is re-measured.
         *
         * Ten minutes was ours, not a port, and it was the longest wait in the whole app.
         * Measured 2026-11-24 02:23:59:
         *
         *     tunnel DOWN: backend state now DOWN
         *     stopTunnel(destroy) hard=true
         *     lane: Cloudflare WARP tunnel unavailable (fail-closed strict mode)
         *
         * `stopTunnel` calls `stopSelf`, so after the verdict the service that would have
         * re-probed is gone. The only things that could break the release were his thumb
         * (`ACTION_START_USER` clears it) or AutoRepair's 180 s `connect_warp`, which the
         * release then refused. Strict fail-closed refuses a pinned host with no tunnel
         * edge, so ten minutes of release was ten minutes of nothing working - which is
         * exactly what he called "unlimited pero super tagal".
         *
         * The verdict it protects is cheap to repeat. A warm swap installs a proven `/64`
         * in 146 ms and carried in 1.4 s (measured 02:27:59 -> 02:28:00), so re-measuring
         * costs one probe, while a ten-minute lockout costs the whole session.
         */
        private const val CARRY_BLOCK_MS = 90_000L
        /** Their config re-rolls edges on failure; three keeps a bad register call bounded. */
        private const val EDGE_TRIES = 3
        /** Then the registration itself is replaced, twice, before giving up. */
        private const val REGISTRATION_TRIES = 2
        var running = false
            private set

        /** Bumped on every successful bring-up so callers can wait for a swap instead
            of guessing with a sleep. Reading it is how a hop knows the new identity
            is actually carrying traffic. */
        var generation = 0
            private set

        /**
         * The identity the live TUN was built from, and whether its verify is still running.
         *
         * In the companion, not on the instance, because `RotationEngine` must read both before
         * it wears a spare: swapping while a probe is still deciding the current identity's fate
         * makes that probe answer for a tunnel that no longer exists - the same cross-generation
         * mis-attribution that corrupted the pool labels on 22 Nov 09:57.
         */
        @Volatile
        var activeIdentityId: String? = null
            internal set

        @Volatile
        private var verifying = false

        /** Is a verify in flight? The rotation engine must not swap underneath one. */
        fun verifyingNow(): Boolean = verifying

        /** how stale a proof may be before the keeper touches the tunnel (3 ticks) */
        private const val KEEPER_STALE_MS = 3 * 45_000L

        /**
         * Longest a verify may hold the bring-up gate.
         *
         * The gate exists to stop two rebuilds racing inside one verify (4 s apart, measured
         * 21:09:57). A verify cannot legitimately outlive its own probe budget � three reads
         * at 7 s plus two 2 s gaps is under 30 s � so anything past this is a leaked flag,
         * not work in progress.
         */
        private const val VERIFY_FLAG_TTL_MS = 90_000L

        /**
         * How recently a tunnel must have proved itself before a silent probe stops being a
         * verdict about its edge.
         *
         * Measured 2026-11-23 22:52:33 -> 22:53:53 on this phone: six `verify-reroll` rebuilds,
         * sixteen seconds apart, while WARP was demonstrably carrying - the tunnel thrown away
         * at 22:53:53 answered `tunnel VERIFY ok: ip=2a09:bac5:4ff6:25c3::3c3:e warp=on` 1.9 s
         * after its replacement came up, and the one two rebuilds later read
         * `2a09:bac1:5a80:40::/64`. Three 5 s reads that start 5.6 s after `establish()` can all
         * land before the route table and Termux's DNS are warm, which is a cold probe, not a
         * dead edge.
         *
         * Every one of those rebuilds destroyed `tun0`, and with it every socket `com.termux`
         * held: the lane on 41988, the engine's SSE, whatever turn was open. A 47 s stream
         * silence and a reply arriving 64 s late are what that churn looks like from his side.
         */
        private const val RECENT_PROOF_MS = 150_000L
    }

    /**
     * Move the live device onto another WARP edge **without** rebuilding the TUN.
     *
     * This is the real use of [Uapi.rekey], as `hotSwap` already documents: a `verify-reroll`
     * changes only the edge, never the identity, and the interface address that WARP binds the
     * egress /64 to stays valid. So the swap costs one Unix-socket write instead of an
     * `establish()`, and - the part that matters - `com.termux` never loses the interface
     * underneath its open sockets.
     *
     * Returns true only when the device accepted the new endpoint (`errno=0`). A cross-identity
     * request is refused here on purpose: that needs `establish()` to move the interface
     * address, exactly as `hotSwap` refuses it.
     */
    private fun rekeyEdge(reg: WarpReg, newEdge: String): Boolean {
        val t = activeTunnel.get() ?: return false
        if (!tunnelUp) return false
        if (reg.id != activeIdentityId) return false
        val started = System.nanoTime()
        val us = Uapi.rekey(filesDir, t.name, reg, newEdge) ?: run {
            android.util.Log.e("ZAPP", "edge re-roll: UAPI refused (${Uapi.lastError}) - falling back to a rebuild")
            return false
        }
        activeEdgeName = newEdge
        generation += 1
        val wall = (System.nanoTime() - started) / 1_000
        val ms = String.format(java.util.Locale.US, "%.2f", wall / 1000.0)
        android.util.Log.e(
            "ZAPP",
            "edge re-roll in place: $newEdge in ${ms}ms (UAPI rekey, TUN untouched, " +
                "com.termux keeps its sockets)",
        )
        GuardState.log("vpn", "edge re-roll in place -> $newEdge (${ms}ms, no rebuild)")
        return true
    }

    private inner class WireTunnel(val tunnelName: String) : Tunnel {
        override fun getName() = tunnelName
        override fun onStateChange(newState: Tunnel.State) {
            // tunnel state transitions land here; guard service watches service state
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // A soft stop closes our fd but leaves GoBackend's own VpnService owning the tun,
            // so Android keeps steering Termux into a dead tunnel and the egress still reads
            // `warp=on`. Measured 02:36:50: `vpn relay: stop` + `tunnel DOWN`, and 4 s later
            // `ip=2a09:bac5:4ff1:2e1e::498:5b warp=on` with the same address. That is exactly
            // the state that produced every phantom "wall" tonight: the tunnel is dead, the
            // traffic leaves through the carrier, and the provider answers with a free-limit.
            ACTION_STOP -> { stopTunnel(hard = true, reason = "action-stop"); return START_NOT_STICKY }
            ACTION_START_USER -> scope.launch {
                // The release is a cooldown for automatic rebuilds, not a veto over him. Clear
                // it and force this one through; the verify still decides whether it carries,
                // so a tap cannot leave a dead tunnel presented as connected.
                if (carryBlockedUntilMs != 0L) {
                    carryBlockedUntilMs = 0L
                    com.zapp.net.settings.GuardPrefs.setCarryBlockedUntil(this@ZappVpnService, 0L)
                    GuardState.log("vpn", "panel: connect tap cleared the carry release")
                }
                bringUp(force = true, reason = "user-start")
            }
            ACTION_HOT_SWAP -> {
                // `reg_id = self` rekeys the live identity onto itself. That is the control
                // experiment: if the tunnel survives a self-rekey, the message format is
                // correct and a dead tunnel means Cloudflare rejected the new source address.
                val id = intent.getStringExtra(EXTRA_REG_ID)?.let {
                    if (it == "self") activeIdentityId else it
                }
                scope.launch {
                    val pool = com.zapp.net.guard.WarpPool(this@ZappVpnService)
                    val reg = pool.all().firstOrNull { it.id == id }
                    if (reg == null) {
                        GuardState.log("hop", "hot swap: registration $id not in the pool")
                        return@launch
                    }
                    hotSwap(reg)
                }
            }
            else -> {
                // `EXTRA_FORCE` is the keeper's verdict, not a bypass: it only arrives from
                // `RotationEngine.resetKeys`, which reaches here after three failed readings
                // of the same tunnel (see the constant's note).
                val forced = intent?.getBooleanExtra(EXTRA_FORCE, false) == true
                scope.launch {
                    bringUp(
                        force = forced,
                        reason = if (forced) "keeper-rekeys" else "action-start",
                    )
                }
            }
        }
        return START_STICKY
    }

    /**
     * @param force skip the live-turn deferral. Two callers must be able to force:
     *   the wall rotation (a walled turn is already dead - rebuilding is the cure, not the
     *   harm) and the resume path (it already waited once; waiting again is the deadlock
     *   measured at 21:49:26 -> 21:51:04, where the server kept the session busy retrying
     *   against a walled address and every rebuild deferred forever).
     */
    private suspend fun bringUp(force: Boolean = false, reason: String = "start") =
        upLock.withLock { bringUpLocked(force, reason) }

    private suspend fun bringUpLocked(force: Boolean = false, reason: String = "start") {
        // Who rebuilt, and why. Without this line every rebuild in logcat looked identical,
        // so the 03:30:59 teardown could not be attributed to any caller.
        android.util.Log.e("ZAPP", "bringUp($reason) force=$force gen=$generation")
        // WARP was measured to carry nothing for every registration on this phone, and a tunnel
        // that carries nothing is worse than no tunnel: Termux sits inside it, so the server
        // loses its own DNS (`getaddrinfo EAI_AGAIN opencode.ai`, on screen 05:53) and no
        // address can be reached, walled or not. Three consecutive rebuild-after-wall samples
        // (05:51, 05:53, 05:55) all landed a registration whose packets entered tun0 and never
        // left (`from /172.16.0.2 ... after 4000ms`).
        //
        // So while the release holds, nothing rebuilds automatically - not the cold start, not
        // the keeper, not a wall - and the reason still occupies the log so the cycle is
        // visible instead of silent.
        val blockedUntil = carryBlockedUntilMs
        if (System.currentTimeMillis() < blockedUntil) {
            val left = (blockedUntil - System.currentTimeMillis()) / 1000
            // Four ACTION_START senders can fire within three seconds (measured 06:05:25-26),
            // and each one logged the same line. Once per whole minute is enough to show the
            // release is holding without turning the log into noise.
            val bucket = blockedUntil / 60_000L
            if (bucket != heldLoggedForBucket) {
                heldLoggedForBucket = bucket
                GuardState.log(
                    "vpn",
                    "bringUp($reason) held: WARP carries nothing, ${left}s left on the release",
                )
            }
            return
        }
        // The wireguard-go blob carries an absolute UAPI socket dir as a Go string constant.
        // The AAR ships `/data/data/com.wireguard.android/cache/wireguard`, which no other app
        // may create: UAPIOpen fails, the device never starts, and the tunnel still looks UP
        // while every packet times out. tools/patch-wg-go.ps1 rewrites that constant to the
        // path below (same byte length, so the Go string header stays valid); we own it.
        runCatching { java.io.File(filesDir, "wg-uapi-sockets.00").mkdirs() }

        // A rebuild replaces the wireguard device (see the "new Tunnel instance" note below),
        // which kills every socket riding it. While the server is inside a *healthy* turn that
        // is the ECONNRESET / EAI_AGAIN pair the chat showed at 21:42-21:43. So defer once,
        // bounded, and come back when the turn ends.
        //
        // Not deferred when:
        //  - a wall is pending: that turn is already dead, and the rebuild is what frees it;
        //  - the caller forces it: this may be the single deferred resume, and deferring a
        //    second time is how the hop never ran (measured 21:49:26 wall -> 21:51:04 still
        //    on the old prefix, `bringUp deferred` five times).
        // A verify re-roll means the engine already decided this tunnel is not carrying; a
        // "live" turn on a dead tunnel is not a reason to protect it. Measured 02:34:02:
        // `VERIFY: no reading � -> re-roll`, immediately followed by `bringUp deferred: a
        // turn is live`, and the dead tunnel was kept because its own stuck turn outranked
        // the repair. Avatar defers repair per connection (`_active_tunnels() == 0`), never
        // against a verify verdict.
        // A live turn outranks every rebuild, including one an edge request asked for.
        //
        // `edgeWanted` used to defeat this deferral, and that is what he feels as "connection
        // lost while reading": measured 2026-11-21 11:46:08 and 11:47:52, two
        // `bringUp(verify-reroll)` / `bringUp(verify-burned64)` rebuilds inside a single reply.
        //
        // A rebuild replaces the wireguard device, which kills every socket riding it - and
        // Termux is the ONLY app in this tunnel (`includeApplication("com.termux")`), so the
        // socket that dies is the server's own connection to the model provider. The reply
        // stops mid-sentence and the chat reports a lost connection.
        //
        // A turn that is producing tokens is direct proof this tunnel carries right now, which
        // is stronger evidence than a probe that timed out. Two escapes remain: `force` (a
        // caller with independent evidence) and `wallPending` (the turn is already dead;
        // RotationEngine sets it before a wall hop - see RotationEngine.kt:185).
        // A down tunnel is never deferred: there is nothing to protect. Measured 22 Nov
        // 10:25:44 - `stopTunnel(action-stop) hard=true` followed by four
        // `bringUp deferred: a turn is live` in a row, i.e. the app refused to reconnect
        // because a zombie turn flag said a turn was running with no tunnel underneath it.
        // That is literally "I can't connect". `GuardState.turnIsLive()` is the evidence-based
        // rule (a live turn emits session events; a zombie emits none), and it is the same
        // predicate `hop()` uses so the two can no longer disagree.
        if (!force && tunnelUp && GuardState.turnIsLive()) {
            android.util.Log.e("ZAPP", "bringUp deferred: a turn is live")
            GuardState.log("vpn", "rebuild deferred - a turn is live")
            scope.launch {
                var waited = 0
                // Ten minutes, not ninety seconds. Measured 22 Nov 11:56: one `python3 -m
                // unittest` step ran 1m31s without emitting a single session event. The old
                // 18x5 s cap expired mid-step and then called `bringUp(force = true)`, which
                // cut the provider socket anyway - the ECONNRESET on screen. A turn that is
                // still live when the cap expires is now simply left alone; the keeper's next
                // tick picks the rebuild up once the turn really ends.
                while (GuardState.turnIsLive() && waited < 120) {
                    kotlinx.coroutines.delay(5_000)
                    waited++
                }
                if (GuardState.turnIsLive()) {
                    android.util.Log.e("ZAPP", "bringUp still deferred after 10 min - turn outranks it")
                    GuardState.log("vpn", "rebuild still deferred after 10 min - the turn outranks it")
                    return@launch
                }
                android.util.Log.e(
                    "ZAPP",
                    "bringUp resumed after the turn (stillLive=${GuardState.turnIsLive()})",
                )
                bringUp(force = true, reason = "turn-finished")
            }
            return
        }

        val pool = WarpPool(this)
        var reg: WarpReg? = pool.usable()
        if (reg == null) {
            // first run: create the first registration before the tunnel can exist
            reg = RotationEngine(this).ensureFirstRegistration()
        }
        val usable = reg ?: run {
            running = false
            GuardState.setVpnRunning(false)
            return
        }

        // An explicitly requested edge means someone (a verify re-roll, or the rotation
        // engine pre-screening prefixes) wants this identity rebuilt on a different colo,
        // so it must not be skipped even while its previous verify is still in flight.
        val externalEdge = requestedEdge.also { requestedEdge = null }

        // The verify re-roll writes `forcedEdge`, not `requestedEdge`. Both are "someone
        // decided this tunnel must be rebuilt", so both have to defeat the skips below.
        // Measured 2026-11-20 03:27:54: the re-roll pinned 188.114.99.1, logged nothing
        // else, and whether a rebuild happened depended on `alive` happening to be false -
        // a coin flip that leaves a dead edge pinned and the chat on a dead tunnel.
        val internalEdge = forcedEdge.get()

        // Same identity already up and still being proven: rebuilding it only burns a TUN
        // and invalidates the verify that is about to answer. Measured 21:09:57 and 21:10:01,
        // two bring-ups 4 s apart on the same registration, neither verdict surviving.
        //
        // Two escapes, both measured 2026-11-20 01:52-01:53:
        //  - `tunFd == null` means there is no TUN at all, so "up and verifying" is false by
        //    definition. The keeper asked to rebuild twice and was refused twice
        //    (`bringUp skipped: ... is up and verifying`), `ifconfig tun0` was empty, DNS
        //    died and the server answered `EAI_AGAIN opencode.ai`. A dead tunnel must never
        //    be protected by a verify flag.
        //  - a `verifying` flag left true by a superseded verify would otherwise be a
        //    permanent deadlock, so it also expires.
        val verifyingFresh = verifying &&
            System.currentTimeMillis() - verifyingSinceMs < VERIFY_FLAG_TTL_MS
        // A UI rebroadcast is not evidence about the tunnel, so it must not replace a live one
        // while that tunnel is being tested - whatever identity it happens to name.
        //
        // Measured 06:06:29.757: the tunnel came up on `162.159.192.10:2408` and its verify
        // started; at 06:06:39.968 a second `bringUp(action-start)` rebuilt the SAME identity
        // on the SAME edge mid-read and the whole verdict was discarded. Four screens send
        // ACTION_START on cold start (MainActivity x2, GuardScreen x4), so a cold start alone
        // could replace a healthy tunnel several times.
        if (!force && externalEdge == null && internalEdge == null &&
            verifyingFresh && tunnelUp && tunFd != null
        ) {
            android.util.Log.e("ZAPP", "bringUp skipped: a verify is in flight on the live tunnel")
            return
        }
        // `force` means the caller has independent evidence the tunnel is not serving: the
        // keeper logged "tunnel is down" and then had this guard answer "is up and verifying",
        // measured 02:23:42. A verify flag is a guess; a keeper verdict is a measurement.
        if (!force && externalEdge == null && internalEdge == null && usable.id == activeIdentityId && verifyingFresh && tunnelUp) {
            android.util.Log.e("ZAPP", "bringUp skipped: ${usable.id} is up and verifying")
            return
        }
        // A live tunnel must survive an ordinary ACTION_START. Every bring-up builds a NEW
        // wireguard-go device, which cuts every socket riding it, so "the activity was opened
        // again" is not a reason to rebuild.
        //
        // This is safe precisely because a non-forced caller cannot know the tunnel is dead:
        // the keeper measures staleness on its 45 s tick and calls bringUp(force = true), and a
        // failed verify asks for a re-roll. `tunFd == null` is the one signal a non-forced
        // caller can still see (the 01:52 case: `tunnelUp` true, no TUN at all, DNS dead).
        val alive = tunnelUp && tunFd != null
        if (!force && externalEdge == null && internalEdge == null && usable.id == activeIdentityId && alive) {
            android.util.Log.e("ZAPP", "bringUp skipped: ${usable.id} is already up and carrying")
            return
        }
        // a stale flag is not evidence: clear it before building
        if (verifying && !verifyingFresh) {
            android.util.Log.e("ZAPP", "verifying flag stale - clearing it and rebuilding")
            GuardState.log("vpn", "verify flag stale - rebuilding instead of deadlocking")
            verifying = false
        }
        val backend = backend() ?: return

        val iface = Interface.Builder()
            .addAddress(InetNetwork.parse(usable.v6))
            // Cloudflare's own config shape carries BOTH families on the interface:
            //   Address = 172.16.0.2/32
            //   Address = 2606:4700:110:.../128
            // With v6 only, every IPv4 flow into tun0 had no source address and was
            // dropped before it ever reached wireguard-go. Measured symptom: tunnel up,
            // `rc=28` (timeout) for both -4 and -6.
            .apply { usable.v4?.let { runCatching { addAddress(InetNetwork.parse("$it/32")) } } }
            .addDnsServer(java.net.InetAddress.getByName("2606:4700:4700::1111"))
            .addDnsServer(java.net.InetAddress.getByName("1.1.1.1"))
            // GoBackend owns the VpnService, so scope comes from the config:
            // only Termux (which runs the opencode server) is inside the tunnel.
            //
            // Our own package is included too, for one reason: the egress probe. Measured
            // 05:30:44, the ROM froze Termux mid-probe ("Usf_Hiber: freeze uid: 10448
            // com.termux ... last unfreeze 5s ago") and every read came back "no reply (probe
            // timed out)" - 13 s each, three edges, over two minutes of "Rotating the tunnel".
            // A probe that must ask another app to run a shell is a probe that dies when that
            // app is frozen. With this package inside the tunnel the app can read its own
            // egress in one HTTPS request and never touch Termux.
            //
            // Loopback is unaffected: the client still reaches the opencode server on
            // 127.0.0.1:4096 exactly as before.
            .includeApplication("com.termux")
            // Our own package is NOT included. An earlier version included it so the egress
            // probe could read WARP without asking Termux, and the comment claimed "loopback
            // is unaffected". Measured 2026-11-20 10:29-10:35 that claim is false on this
            // ROM: with `com.zapp.net` inside its own tunnel, an HTTP GET to
            // 127.0.0.1:41988 (`/__zapp/status`) died with SocketTimeoutException every
            // 3.5 s, while `curl` from Termux answered the same URL 200 in 7 ms and
            // `run-as com.zapp.net` reached it instantly. Removing the include made the
            // Route card answer again ("degraded" at 10:35, real numbers).
            //
            // The probe does not need the include: `EgressProbe.measureLocked` measures
            // through the Termux relay, which is inside the tunnel - the in-process path is
            // only a fallback for when the relay cannot answer.
            .setKeyPair(KeyPair(Key.fromBytes(hexToBytes(usable.privHex))))
            .setMtu(1280)
            .build()

        // edge pinning (their shape: "pinned edge / auto-random edge", re-rolled on failure).
        // The register contract's endpoint is only *an* edge, not a live one: measured
        // 2026-11-19, identity 2606:4700:110:8d7b... on 162.159.192.2:2408 carried zero
        // packets (warp=off, rc=28), and the same identity on 162.159.192.10:2408 answered
        // warp=on in 2.1 s. So a dead edge must never condemn the registration.
        val pinned = externalEdge ?: forcedEdge.getAndSet(null)
        // A registration's own endpoint may carry a port the ISP drops (measured: 880, 500 and
        // 1701 all failed verify here while 2408 answered), and every failed verify is a
        // rebuild. Only a proven port is trusted from the register answer.
        val regEdge = usable.endpoint
        val regEdgeOk = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+$").containsMatchIn(regEdge) &&
            !regEdge.endsWith(":0") &&
            regEdge.substringAfterLast(':') == "2408"
        val edge = pinned
            ?: run {
                // Clean Endpoint. Their `set_custom_endpoint` runs
                // `warp-cli tunnel endpoint set <ip:port>`, which persists in the WARP config;
                // the phone has no warp-cli, so the choice is a pref read right here.
                // A one-shot `pinned` still wins, so a dead override is repaired by the
                // verify re-roll instead of shipping a tunnel that carries nothing.
                val override = com.zapp.net.settings.GuardPrefs
                    .endpointOverride(this@ZappVpnService)
                if (override.isNotBlank()) {
                    GuardState.log("edge", "clean endpoint $override (pinned by the panel)")
                    override
                } else if (regEdgeOk) {
                    regEdge
                } else {
                    com.zapp.net.guard.EdgePins.pick()
                }
            }
        GuardState.log("edge", "pinned $edge")

        val peer = Peer.Builder()
            .addAllowedIp(InetNetwork.parse("::/0"))
            .addAllowedIp(InetNetwork.parse("0.0.0.0/0"))
            .setEndpoint(InetEndpoint.parse(edge))
            .setPublicKey(Key.fromBytes(hexToBytes(usable.peerPubHex)))
            .build()

        val cfg = Config.Builder()
            .setInterface(iface)
            .addPeer(peer)
            .build()

        // A NEW Tunnel instance (and therefore a new wireguard-go device) for every bring-up.
        // Reusing one instance and only flipping DOWN->UP kept the old device's state: measured
        // 2026-11-19, three hops in a row produced a fresh registration that carried nothing on
        // any edge, while the very same registration worked after a fresh process start. The
        // library keys devices by name, so a unique name is what forces a real rebuild.
        val t = WireTunnel("zappwarp" + (tunnelSeq.incrementAndGet()))
        val previous = activeTunnel.getAndSet(t)
        previous?.let { runCatching { backend.setState(it, Tunnel.State.DOWN, null) } }
        val up = runCatching { backend.setState(t, Tunnel.State.UP, cfg) }
        up.exceptionOrNull()?.let {
            android.util.Log.e("ZAPP", "tunnel UP threw: ${it.javaClass.simpleName}: ${it.message}")
            GuardState.log("vpn", "UP threw: ${it.message}")
        }
        android.util.Log.e(
            "ZAPP",
            "tunnel UP: id=${usable.id} v6=${usable.v6} v4=${usable.v4} edge=$edge mtu=1280 err=${up.exceptionOrNull()?.message ?: "none"}",
        )
        // The fast-swap path needs the UAPI listener to exist. One line, every bring-up, so the
        // log itself proves whether `set=1` rekeying is available on this device.
        android.util.Log.e("ZAPP", "uapi sockets: ${Uapi.listing(filesDir)}")
        activeEdgeName = edge

        running = true
        // `tunFd` was declared and never assigned, so `tunFd == null` was always true: the
        // keeper read "tunnel is down" on every 45 s tick and rebuilt continuously, which is
        // the churn that killed DNS (`EAI_AGAIN opencode.ai`, measured 01:53). The backend
        // call's own verdict is the only honest "is there a TUN" answer.
        tunnelUp = up.isSuccess
        generation += 1
        val myGeneration = generation
        activeIdentityId = usable.id
        verifying = true
        verifyingSinceMs = System.currentTimeMillis()
        GuardState.setVpnRunning(true, usable.egress64 ?: usable.v6)

        // A tunnel that reports UP but carries nothing is worse than no tunnel: every byte
        // of Termux traffic disappears into it. Measured on 2026-11-19 15:12 while the UAPI
        // path was still wrong: tun0 up, `curl -4` and `curl -6` both rc=28, zero packets.
        // So UP is not success - only a real egress reading through Termux is.
        scope.launch {
            // 30 s, not 12: the probe's own worst case is 6 + 5 s of curl plus Termux's
            // spawn and the broadcast back. At 12 s a healthy tunnel reported VERIFY failed
            // and got re-rolled onto another edge (measured 21:12:02).
            //
            // Three reads, not one. A rebuild races Termux's own socket: the first curl can
            // leave through the OLD route and answer `warp=off` with the carrier address
            // (measured 21:47:23 `49.149.65.25 warp=off` right after an UP). That is "no
            // proof yet", not "the edge is dead" � condemning it re-rolled every ~9 s and
            // each re-roll killed the live turn with ECONNRESET.
            // Let the kernel finish installing routes and the library finish its handshake
            // before asking the network anything. Measured 05:22, probes fired immediately
            // after `tunnel UP` answered blank four times in a row (`stage=done ip=- warp=-`),
            // and the verify read those blanks as "this edge is dead".
            kotlinx.coroutines.delay(900)
            var reading: com.zapp.net.termux.EgressProbe.Reading? = null
            val verifyStart = System.currentTimeMillis()
            for (tryIndex in 1..3) {
                reading = runCatching {
                    // Budget MUST exceed the script's own worst case or the probe is killed
                    // before its last curl finishes and every read comes back blank. Measured
                    // 05:27: curl ceilings 5+4+4 = 13 s inside an 8 s budget -> twelve blank
                    // reads across three edges, 108 s of "Rotating the tunnel". Script worst
                    // case is now 4+3+3 = 10 s, budget 13 s.
                    // Their `get_egress_v6` is `curl -s -m 5`: five seconds, one request, no
                    // relay. The in-process probe needs nothing more - measured 06:02:11.938,
                    // a live tunnel answered in 1.44 s. 13 s only made a DEAD read cost 13 s
                    // (measured 06:06:29.757 -> 06:06:39.694), which is the slowness he feels.
                    //
                    // The budget must still cover the script's own worst case, and after the
                    // probe was pointed through the lane that worst case is 4+3+2+2 = 11 s.
                    // At the old 6 s the waiter was killed before the proxied curl could
                    // answer, every read came back "no reply (probe timed out)", and each one
                    // re-rolled a healthy edge - measured 12:35:44 -> 12:36:07, three edges
                    // burned in 23 s on a tunnel that read `warp=on` at 12:36:08.
                    // 14 s, not 11: Termux spawn plus the broadcast back is not free.
                    com.zapp.net.termux.EgressProbe.measure(this@ZappVpnService, 14_000)
                }.getOrNull()
                val good = reading != null &&
                    reading.warp.equals("on", ignoreCase = true) &&
                    reading.isV6
                if (good) break
                // The lane proxy is Termux's, and on a fresh install it is not there yet.
                // Three reads against a closed port cost 1 ms each and prove nothing about
                // the edge, so stop reading and let the caller hold the registration.
                if (reading?.laneDown == true) {
                    android.util.Log.e(
                        "ZAPP",
                        "tunnel VERIFY read $tryIndex/3 on $edge: engine lane 41988 not up " +
                            "(no measurement, not a verdict)",
                    )
                    break
                }
                // Two different failures, and they must not read the same in the log:
                //   reading == null   -> the probe never came back (killed / no broadcast)
                //   !reading.known    -> the script ran and found no address
                android.util.Log.e(
                    "ZAPP",
                    "tunnel VERIFY read $tryIndex/3 on $edge: " +
                        if (reading == null) {
                            "no reply (probe timed out)"
                        } else if (!reading.known) {
                            "script ran, no address"
                        } else {
                            "ip=${reading.ip} warp=${reading.warp}"
                        } + " (+${(System.currentTimeMillis() - verifyStart) / 1000}s)",
                )
                if (tryIndex < 3) kotlinx.coroutines.delay(350)
            }
            // A newer bring-up (a hop) already replaced this tunnel: its verdict is the only
            // one that counts. Acting on this one tore down a healthy tunnel on 15:30:32.
            if (generation != myGeneration) {
                android.util.Log.e("ZAPP", "tunnel VERIFY superseded (gen $myGeneration -> $generation)")
                // MEASURED BUG, 22 Nov 09:57. This branch used to label `usable` with whatever
                // the probe answered. But the probe answers AFTER the newer TUN is already up,
                // so the reading describes the NEW identity, not `usable`. That is how
                // `0e0a0e91` came to claim `2a09:bac5:4ff3:263c` - the prefix the previous
                // registration had just been corrected away from. One address, two owners, and
                // a reserve count that was quietly wrong.
                //
                // A reading may only be written to the identity the tunnel is wearing right now.
                val stillWearing = usable.id == activeIdentityId
                if (reading != null && reading.warp.equals("on", ignoreCase = true) && reading.isV6) {
                    if (stillWearing) {
                        runCatching { pool.setEgress(usable, reading.v6Prefix, reading.ip) }
                        // A real `cdn-cgi/trace` came back through this identity: it is proven,
                        // not merely registered. This is what makes it a token in the warm pool.
                        runCatching { pool.markVerified(usable) }
                        runCatching { pool.persist() }
                        GuardState.refresh(pool)
                    } else {
                        android.util.Log.e(
                            "ZAPP",
                            "tunnel VERIFY (superseded): reading ${reading.v6Prefix} discarded - " +
                                "it belongs to ${activeIdentityId?.take(8)}, not ${usable.id.take(8)}",
                        )
                    }
                    // This reading is real proof that WARP carried traffic a moment ago, and
                    // it arrived while a newer TUN was already up. Leaving `lastVerifyOkMs`
                    // untouched made the keeper read the tunnel as "unproven since epoch"
                    // (measured 23:24:16: `unproven for 1795101856s`) and renew the keys on
                    // every tick - a rebuild loop every ~10 s that killed the opencode
                    // server's own DNS with `EAI_AGAIN` mid-turn.
                    lastVerifyOkMs = System.currentTimeMillis()
            GuardState.noteCarry()
                    // ...but "WARP carried traffic" is not "the server will answer". Measured
                    // 05:22:07: this exact call marked `4ff4:1014::/64` verified while that
                    // prefix was already walled, and the keeper then kept re-presenting it.
                    // A burned prefix stays burned; only a real turn clears it.
                    val burned = com.zapp.net.guard.RotationEngine
                        .isBurnedPrefix(this@ZappVpnService, reading.prefix64)
                    if (burned) {
                        android.util.Log.e(
                            "ZAPP",
                            "tunnel VERIFY (superseded) carrying a BURNED /64 ${reading.prefix64} - not blessed",
                        )
                    } else {
                        GuardState.setVerified(true)
                    }
                    android.util.Log.e(
                        "ZAPP",
                        "tunnel VERIFY recorded for superseded gen $myGeneration: /64=${reading.prefix64}",
                    )
                }
                return@launch
            }
            // A closed lane port is not a reading. Termux starts that proxy, and on a clean
            // install it does not exist until the engine does, so retiring the edge here
            // burns a registration every few seconds on a tunnel that is perfectly fine.
            // Measured 22 Nov 22:16:45: registration discarded 1.4 s after `tunnel UP`.
            if (reading?.laneDown == true) {
                android.util.Log.e(
                    "ZAPP",
                    "tunnel VERIFY: engine lane 41988 not up - holding $edge, no rebuild",
                )
                GuardState.log("vpn", "verify: engine not up yet - holding $edge")
                verifying = false
                return@launch
            }
            // A reading with a blank address is not a reading. Since the probe now answers
            // "unknown" instead of never answering (so the caller stops burning a 25 s
            // timeout), an empty result reaches here as a non-null Reading; without this gate
            // it fell into the leak branch and printed `traffic left outside WARP ()` —
            // measured 04:58:46 — i.e. the app accused the tunnel of a leak on the strength
            // of no data at all.
            val blank = reading != null && !reading.known
            if (reading == null || blank || !reading.warp.equals("on", ignoreCase = true) || !reading.isV6) {
                // Answered, but not through the tunnel (v4 carrier address, or warp=off).
                // That is "no proof yet" - rebuilding here is what killed live turns with
                // ECONNRESET. Hold the tunnel and let the keeper retry on its own clock.
                if (reading != null && !blank) {
                    android.util.Log.e(
                        "ZAPP",
                        "tunnel VERIFY answered outside WARP: ip=${reading.ip} warp=${reading.warp} - holding, no rebuild",
                    )
                    GuardState.log("vpn", "verify: traffic left outside WARP (${reading.ip}) - holding")
                    // The network answered through something, so this is not the "WARP carries
                    // nothing" case; the next verify must be allowed to judge on its own.
                    noReplyStreak.set(0)
                    verifying = false
                    return@launch
                }
                // Three answers, none of them came back at all. Measured 2026-11-19 23:26,
                // edge 162.159.192.2:2408 on identity ...:82e6 carried zero packets for a
                // full minute (`no reading`), while .9 answered in 1.9 s. A dead edge must be
                // re-rolled - that is the only way off it. What must NOT happen is the old
                // storm: it was caused by a rebuild *during* a verify, which is now recorded
                // as proof instead of discarded, so this branch runs once per 60 s per edge
                // instead of every 10 s.
                // A dead probe is only a verdict about the EDGE when the phone had somewhere to
                // send the probe. Without an underlying network the three reads say "no route",
                // not "this edge carries nothing", and acting on them retires a healthy edge,
                // burns a registration, and after six reads tears the tunnel down and blocks
                // carrying for CARRY_BLOCK_MS. Hold instead: the keeper re-probes in 45 s and
                // auto-repair keeps watching. Measured 2026-11-21 10:22:35-10:22:41, three
                // reads in 6 s on 162.159.192.3:2408.
                if (!com.zapp.net.termux.EgressProbe.hasUnderlyingNetwork(this@ZappVpnService)) {
                    android.util.Log.e(
                        "ZAPP",
                        "tunnel VERIFY: no reading on $edge, but the phone has no underlying network - holding",
                    )
                    GuardState.log("vpn", "verify: no network underneath - holding $edge")
                    verifying = false
                    return@launch
                }
                if (!com.zapp.net.settings.GuardPrefs.autoRepair(this@ZappVpnService)) {
                    android.util.Log.e("ZAPP", "tunnel VERIFY: no reading on $edge - auto-repair is off, holding")
                    GuardState.log("vpn", "verify: no reading on $edge - auto-repair off")
                    verifying = false
                    return@launch
                }
                // A live turn outranks the probe. The three reads timed out, but the reply on
                // screen is arriving, which means this tunnel is carrying right now. Hold and
                // let the keeper re-probe after the turn instead of cutting the stream.
                if (turnIsLive()) {
                    android.util.Log.e(
                        "ZAPP",
                        "tunnel VERIFY: no reading on $edge, but a turn is live - holding",
                    )
                    GuardState.log("vpn", "verify: no reading on $edge, turn is live - holding")
                    verifying = false
                    return@launch
                }
                // THE CHURN BREAKER.
                //
                // The probe needs several seconds of warm-up after every rebuild, and the
                // verifier re-rolled on three silent reads - which rebuilt the TUN and restarted
                // the warm-up. Measured 22 Nov 09:44: reads timed out at :28 and :33, the same
                // probe answered `2a09:bac1:5aa0:40::4dc:56 warp=on` at :35, and the verifier had
                // already rebuilt at :09. That loop is the "6 s churn" and most of the "bagal".
                //
                // The WireGuard handshake is the peer's own verdict and does not need a probe.
                // A handshake in the last three minutes means the device is talking to
                // Cloudflare, so a silent probe is the probe's problem, not the edge's.
                val hsAge = Uapi.handshakeAgeSec(
                    filesDir,
                    activeTunnel.get()?.name ?: "",
                )
                if (hsAge != null && hsAge in 0..180) {
                    android.util.Log.e(
                        "ZAPP",
                        "tunnel VERIFY: no reading on $edge, but handshake was ${hsAge}s ago - holding",
                    )
                    GuardState.log(
                        "vpn",
                        "verify: handshake ${hsAge}s ago - probe is cold, holding $edge",
                    )
                    verifying = false
                    return@launch
                }
                // A PROBE THAT RAN COLD IS NOT A VERDICT ABOUT THE EDGE.
                //
                // This is the churn breaker that does not depend on a reading existing. The
                // handshake check above needs `Uapi.handshakeAgeSec` to answer, and on a device
                // whose peer was just replaced it answers "never" - so it held nothing during
                // the 23 Nov 22:52-22:53 chain. `lastVerifyOkMs` is written by this very method
                // on every success, so it is always a real measurement of this tunnel carrying.
                val sinceProof = if (lastVerifyOkMs == 0L) Long.MAX_VALUE
                else System.currentTimeMillis() - lastVerifyOkMs
                if (sinceProof < RECENT_PROOF_MS) {
                    android.util.Log.e(
                        "ZAPP",
                        "tunnel VERIFY: no reading on $edge, but proved ${sinceProof / 1000}s ago " +
                            "- holding (a cold probe is not a dead edge)",
                    )
                    GuardState.log(
                        "vpn",
                        "verify: proved ${sinceProof / 1000}s ago - probe cold, holding $edge",
                    )
                    verifying = false
                    return@launch
                }
                val tries = edgeTries.incrementAndGet()
                // Measured 05:43-05:45 on this phone: every edge returned ZERO replies - not a
                // v4 leak, not a blank script, no reply at all - while the VPN network itself was
                // VALIDATED by Android, and Termux's own curl went out through the carrier with
                // `warp=off`. That is WARP not carrying traffic, not a dead edge. Re-rolling
                // three edges and then discarding the registration costs ~2 minutes per cycle
                // and fixes nothing, which is exactly the "ang tagal / mahina" he feels. After
                // two such edges, hold and say it once.
                noReplyStreak.incrementAndGet()
                // Deliberately NO early release here.
                //
                // This used to stop after two empty edges and hand Termux the direct path for
                // 10 minutes. Measured 06:02 vs 06:03, that was wrong: at 06:02 WARP carried
                // `2a09:bac1:5aa0:40::/64` and verified in 1.9 s, and at 06:03 a *freshly
                // registered* identity carried nothing on two edges in a row. WARP was fine;
                // that registration was dead - their own keeper's
                // "tunnel dead - rotating registration to repair". A blanket release would
                // have parked a working phone for ten minutes over one bad registration.
                //
                // The chain below is their `MAX_HOP_TRIES` loop: re-roll the edge, discard the
                // registration, and only when EDGE_TRIES x REGISTRATION_TRIES identities have
                // all carried nothing does the tunnel come down. See the teardown below.
                if (tries <= EDGE_TRIES) {
                    // Their `mark_edge_failed` + `_pick_edge_locked`: the edge that just
                    // carried nothing is retired for two minutes and the replacement is
                    // chosen with that exclusion, not at random over the whole table.
                    com.zapp.net.guard.EdgePins.markFailed(edge)
                    val next = com.zapp.net.guard.EdgePins.pick(exclude = edge)
                    android.util.Log.e(
                        "ZAPP",
                        "tunnel VERIFY: no reading on $edge (try $tries/$EDGE_TRIES) -> re-roll $next (${com.zapp.net.guard.EdgePins.badCount()} cooling)",
                    )
                    GuardState.log("vpn", "verify: no reading on $edge -> re-roll $next")
                    // SAME IDENTITY, DIFFERENT EDGE - so move the endpoint over UAPI instead of
                    // rebuilding. The interface address is unchanged, which is the only thing
                    // `establish()` can give us, and the TUN survives, so `com.termux` keeps
                    // every socket it holds. This is the fix for the 23 Nov 22:52-22:53 chain:
                    // six rebuilds in eighty seconds, each destroying the lane and the SSE.
                    if (rekeyEdge(usable, next)) {
                        // A handshake on the new endpoint is the peer's own verdict and costs
                        // no probe at all, so give it a second and read it.
                        kotlinx.coroutines.delay(1_500)
                        val hs = Uapi.handshakeAgeSec(filesDir, activeTunnel.get()?.name ?: "")
                        if (hs != null && hs <= 10) {
                            android.util.Log.e(
                                "ZAPP",
                                "edge re-roll: handshake ${hs}s ago on $next - carrying, TUN never dropped",
                            )
                            lastVerifyOkMs = System.currentTimeMillis()
                            GuardState.noteCarry()
                            edgeTries.set(0)
                            GuardState.setVerified(true)
                        } else {
                            android.util.Log.e(
                                "ZAPP",
                                "edge re-roll: no handshake on $next yet - leaving it to the keeper",
                            )
                        }
                        verifying = false
                        return@launch
                    }
                    forcedEdge.set(next)
                    verifying = false // the re-roll rebuilds the same identity on a new edge
                    bringUp(reason = "verify-reroll")
                    return@launch
                }
                edgeTries.set(0)
                // Every edge refused this identity. Cloudflare hands out registrations that
                // never carry a packet (measured 2026-11-19: ...:8525 failed on .3, .194.10,
                // .195.10:4500 and .193.10 while ...:8d7b worked on .10 immediately), so the
                // registration - not the edge - is what gets thrown away next.
                val regTries = registrationTries.incrementAndGet()
                if (regTries <= REGISTRATION_TRIES) {
                    android.util.Log.e(
                        "ZAPP",
                        "tunnel VERIFY: no edge answered -> discard registration ${usable.id} (try $regTries/$REGISTRATION_TRIES)",
                    )
                    GuardState.log("vpn", "identity ${usable.v6.take(20)}- carries nothing -> discarding")
                    runCatching { com.zapp.net.guard.CloudflareRegister.delete(usable.id) }
                    runCatching { pool.retire(usable) }
                    runCatching { pool.persist() }
                    GuardState.refresh(pool)
                    forcedEdge.set(null)
                    verifying = false // a different identity is next
                    bringUp(reason = "verify-discard")
                    return@launch
                }
                registrationTries.set(0)
                android.util.Log.e(
                    "ZAPP",
                    "tunnel VERIFY: no edge and no identity carried a packet -> tear down",
                )
                GuardState.log("vpn", "no working identity (no reading) -> tunnel stopped")
                // Only here, after EDGE_TRIES x REGISTRATION_TRIES fresh identities all carried
                // nothing, is "this tunnel cannot carry" a measured verdict rather than a guess.
                // Release the phone for a while: Termux inside a tunnel that carries nothing
                // loses its own DNS (`getaddrinfo EAI_AGAIN opencode.ai`, on screen 05:53), and
                // no address rotation can fix a tunnel Cloudflare is not forwarding.
                //
                // Persisted, because a swipe-up must not undo it: measured 05:56:17
                // (`reason=13 subreason=101 Swipe-up clean`), the process died mid-cycle and the
                // restart 0.6 s later rebuilt the dead tunnel.
                carryBlockedUntilMs = System.currentTimeMillis() + CARRY_BLOCK_MS
                com.zapp.net.settings.GuardPrefs.setCarryBlockedUntil(
                    this@ZappVpnService,
                    carryBlockedUntilMs,
                )
                GuardState.log(
                    "vpn",
                    "no identity carried a packet - direct path for ${CARRY_BLOCK_MS / 1000}s, then retry",
                )
                verifying = false
                stopTunnel(hard = true, reason = "no-carrying-identity")
                return@launch
            }
            // A verified proof on an already-burned /64 is the provider handing back the same
            // walled address, not a success. Measured 03:27:58: verify `ok` on
            // `2a09:bac1:5aa0:40::/64`, the prefix burned 51 s earlier, followed by two
            // teardowns. Bounded to three re-rolls so a pool of walled prefixes cannot become
            // a rebuild loop.
            if (reading.prefix64.isNotBlank() &&
                com.zapp.net.guard.RotationEngine.isBurnedPrefix(this@ZappVpnService, reading.prefix64)
            ) {
                // A live turn outranks the re-roll: the reply on screen proves this identity
                // carries, whatever /64 it landed on. Holding also stops a burned prefix from
                // being swapped out from under an in-flight stream. Same rule as
                // `bringUpLocked`; the next turn gets the wall and the guard rotates then.
                if (turnIsLive()) {
                    android.util.Log.e(
                        "ZAPP",
                        "tunnel VERIFY ok on walled ${reading.prefix64}, but a turn is live - holding",
                    )
                    GuardState.log(
                        "vpn",
                        "verify: walled ${reading.prefix64}, turn is live - holding",
                    )
                    verifying = false
                    return@launch
                }
                val tries = burnedVerifyTries.incrementAndGet()
                if (tries <= EDGE_TRIES &&
                    !com.zapp.net.guard.GuardState.status.value.wallPending
                ) {
                    com.zapp.net.guard.EdgePins.markFailed(edge)
                    val next = com.zapp.net.guard.EdgePins.pick(exclude = edge)
                    android.util.Log.e(
                        "ZAPP",
                        "tunnel VERIFY ok on walled ${reading.prefix64} (try $tries/$EDGE_TRIES) -> re-roll $next",
                    )
                    GuardState.log(
                        "vpn",
                        "verified on a walled /64 (${reading.prefix64}) - re-rolling $next",
                    )
                    verifying = false
                    forcedEdge.set(next)
                    bringUp(reason = "verify-burned64")
                    return@launch
                }
                burnedVerifyTries.set(0)
                android.util.Log.e(
                    "ZAPP",
                    "tunnel VERIFY: still on walled ${reading.prefix64} after $EDGE_TRIES edges - keeping it",
                )
                GuardState.log("vpn", "walled /64 persists across edges - keeping the tunnel")
            } else {
                burnedVerifyTries.set(0)
            }
            edgeTries.set(0)
            noReplyStreak.set(0)
            // A real answer arrived, so the release has done its job: let the next automatic
            // start build normally instead of waiting out the remaining minutes.
            if (carryBlockedUntilMs != 0L) {
                carryBlockedUntilMs = 0L
                com.zapp.net.settings.GuardPrefs.setCarryBlockedUntil(this@ZappVpnService, 0L)
            }
            registrationTries.set(0)
            verifying = false
            lastVerifyOkMs = System.currentTimeMillis()
            GuardState.noteCarry()
            // A walled /64 can still answer `warp=on` - that only proves packets leave through
            // WARP, never that the server will accept a turn (measured 05:22:07: verified
            // `4ff4:1014::/64` on a prefix burned minutes earlier). Blessing it made the keeper
            // and the retry loop happily re-present an address the provider had already refused.
            val burned = com.zapp.net.guard.RotationEngine.isBurnedPrefix(this@ZappVpnService, reading.prefix64)
            if (burned) {
                android.util.Log.e(
                    "ZAPP",
                    "tunnel VERIFY ok but /64=${reading.prefix64} is BURNED - not marking the guard verified",
                )
                GuardState.log("vpn", "carrying a burned /64 ${reading.prefix64} - not blessed")
            } else {
                GuardState.setVerified(true)
            }
            com.zapp.net.guard.EdgePins.markOk(edge)
            android.util.Log.e("ZAPP", "tunnel VERIFY ok: ip=${reading.ip} warp=${reading.warp} /64=${reading.prefix64}")
            GuardState.log("vpn", "verified warp=${reading.warp} /64=${reading.prefix64}")
            runCatching { pool.setEgress(usable, reading.v6Prefix, reading.ip) }
            // Same stamp as the superseded path above: a `warp=on` reading through this
            // identity makes it a proven token, which is what a warm swap spends.
            runCatching { pool.markVerified(usable) }
            // Remember what the provider just saw. The wall handler needs this when its own
            // probe lands mid-rebuild and returns nothing (measured 04:35:53) - otherwise it
            // cannot ban the prefix it is about to leave and can hand the same one back.
            // Only `warp=on` readings qualify, so the carrier prefix can never land here.
            if (reading.warp.equals("on", ignoreCase = true)) {
                GuardState.setLastEgress64(reading.prefix64)
            }
            runCatching { pool.persist() }
            GuardState.refresh(pool)
            // A verified tunnel means there is something worth watching: the guard listens on
            // the server's own event feed and hops the /64 the moment a quota wall appears.
            runCatching {
                startForegroundService(Intent(this@ZappVpnService, com.zapp.net.guard.GuardForegroundService::class.java))
            }.onFailure { android.util.Log.e("ZAPP", "guard start failed: ${it.message}") }
        }
    }

    /**
     * Tear the tunnel down.
     *
     * `hard` also stops GoBackend's own VpnService. Without it, a DOWN that does not fully
     * revoke the VPN network leaves Android still steering Termux into a dead tun: measured
     * 2026-11-19 15:33, after teardown only `local 172.16.0.2 dev tun0` remained yet curl
     * still had no route (rc=1) until the whole app was force-stopped.
     */
    /**
     * Swap the live tunnel's identity in place.
     *
     * The Java backend cannot do this: `setState(UP)` tears the current device down first.
     * The native device can, over its own UAPI socket ([Uapi.rekey]) - one Unix-socket
     * connect, one `set=1` block, one `errno=0` back. The TUN fd, its routes and its DNS
     * servers are untouched, so the swap costs milliseconds instead of the 200-700 ms of a
     * rebuild, and Termux never loses the interface underneath it.
     *
     * If the socket is missing or the device refuses the config, this falls back to the full
     * rebuild rather than pretending the swap happened.
     */
    private suspend fun hotSwap(reg: WarpReg) {
        val t = activeTunnel.get()
        if (t == null || !tunnelUp) {
            GuardState.log("hop", "hot swap: no live tunnel - rebuilding instead")
            bringUp(force = true, reason = "hot-cold")
            return
        }
        // MEASURED LIMIT, 22 Nov 09:47. A UAPI rekey can change keys, peer and endpoint, but it
        // cannot change the interface address - only `establish()` can. WARP binds each
        // registration's egress /64 to that address, so a rekey onto a DIFFERENT registration
        // leaves the TUN holding the old address and Cloudflare drops the traffic:
        //
        //   self-rekey   : handshake 1795312003 -> 1795312024, lane still answers  -> works
        //   cross-identity: `hot swap: 2a09:bac1:5ae0:40::/64 in 0.96 ms` then the lane
        //                   answered nothing at all -> tunnel dead
        //
        // So a cross-identity swap is never done here. It falls back to the warm swap, which
        // was measured at 6.4-17.6 ms - the rebuild is the cheap part, not the 20-40 s
        // register+verify it replaced. The rekey stays for its real use: repairing the live
        // device's endpoint without tearing the TUN down.
        if (reg.id != activeIdentityId) {
            GuardState.log(
                "hop",
                "hot swap refused: ${reg.egress64 ?: reg.v6} is a different registration " +
                    "(the /64 is bound to the interface address) - using the warm rebuild",
            )
            bringUp(force = true, reason = "hot-cross")
            return
        }
        val endpoint = activeEdgeName?.takeIf { it.isNotBlank() } ?: reg.endpoint
        val before = Uapi.status(filesDir, t.name)
        val us = Uapi.rekey(filesDir, t.name, reg, endpoint)
        android.util.Log.e("ZAPP", "uapi before: $before")
        if (us == null) {
            GuardState.log(
                "hop",
                "hot swap: UAPI refused (${Uapi.lastError}) - rebuilding instead",
            )
            bringUp(force = true, reason = "hot-fallback")
            return
        }
        activeIdentityId = reg.id
        activeEdgeName = endpoint
        generation += 1
        GuardState.setVpnRunning(true, reg.egress64 ?: reg.v6)
        GuardState.log(
            "hop",
            "hot swap: ${reg.egress64 ?: reg.v6} in " +
                "${String.format(java.util.Locale.US, "%.2f", us / 1000.0)} ms " +
                "(UAPI rekey, TUN untouched)",
        )
        // "Accepted by the socket" is not "carrying". The rekey replaces the keys, the peer
        // and the endpoint, but the interface still holds the previous registration's address,
        // so the only proof is a trace that comes back through the new identity. This runs
        // *after* the swap has already been used - it decides whether the identity is kept,
        // never whether the swap happens, so it costs the fast path nothing.
        scope.launch {
            val pool = com.zapp.net.guard.WarpPool(this@ZappVpnService)
            kotlinx.coroutines.delay(3_000)
            android.util.Log.e("ZAPP", "uapi after 3s: ${Uapi.status(filesDir, t.name)}")
            val reading = runCatching {
                com.zapp.net.termux.EgressProbe.measure(this@ZappVpnService, 20_000)
            }.getOrNull()
            val want = reg.egress64 ?: com.zapp.net.guard.WarpPool.prefix64Of(reg.v6)
            val got = reading?.takeIf { it.known && it.isV6 }?.v6Prefix
            when {
                got != null && want != null && got == want -> {
                    runCatching { pool.setEgress(reg, got, reading.ip) }
                    runCatching { pool.markVerified(reg) }
                    runCatching { pool.persist() }
                    GuardState.refresh(pool)
                    GuardState.log("hop", "hot swap verified: egress $got is the new identity")
                }
                // A probe that never answered is NOT a failed swap. Treating "no reading" as
                // "not carrying" rebuilt a healthy hot swap at 09:32:06 - the probe was
                // timing out on every edge in that window, including the fresh rebuild that
                // verified fine at 09:32:29. Silence is unknown; only a measured *different*
                // prefix is proof that the rekey did not take.
                got == null -> GuardState.log(
                    "hop",
                    "hot swap unproven (probe gave no reading) - identity kept, not marked solid",
                )
                else -> {
                    GuardState.log(
                        "hop",
                        "hot swap NOT carrying (egress=$got want=$want) - rebuilding",
                    )
                    bringUp(force = true, reason = "hot-unverified")
                }
            }
        }
    }

    private fun stopTunnel(hard: Boolean = false, reason: String = "unspecified") {
        android.util.Log.e("ZAPP", "stopTunnel($reason) hard=$hard")
        // A second stop 41 ms after the first printed a second `tunnel DOWN` (measured
        // 03:28:04.993 and 03:28:05.034) and read like two teardowns of two tunnels. Nothing
        // was active by then: say nothing rather than lie twice.
        if (activeTunnel.get() == null && tunFd == null && !running) {
            verifying = false
            return
        }
        // Kill the work that is still running, not just the device it already built.
        // Measured 02:50:38.9: the teardown did run (`tunnel DOWN: backend state now DOWN`)
        // and five seconds later the same service rebuilt the tunnel by itself � a bring-up
        // that was mid-verify when the stop arrived finished afterwards, and `stopSelf()`
        // never cancels a coroutine. A stop has to cancel the pipeline or it is a restart.
        runCatching { scope.coroutineContext.cancelChildren() }
        runCatching {
            activeTunnel.get()?.let { t ->
                val b = backend()
                b?.setState(t, Tunnel.State.DOWN, null)
                // Say what the backend answered. A DOWN that the backend ignores looks
                // identical to a real teardown in the log, and that ambiguity is what hid
                // this defect for three measurements.
                val left = runCatching { b?.getState(t) }.getOrNull()
                android.util.Log.e("ZAPP", "tunnel DOWN: backend state now $left")
                GuardState.log("vpn", "teardown state=$left")
            }
        }.onFailure { android.util.Log.e("ZAPP", "tunnel DOWN threw: ${it.message}") }
        runCatching { tunFd?.close() }
        // Nothing is connected to this device any more, and the next bring-up must not try to
        // flip a finished Tunnel back DOWN.
        activeTunnel.set(null)
        if (hard) {
            runCatching {
                val cls = Class.forName("com.wireguard.android.backend.GoBackend\$VpnService")
                stopService(Intent(this, cls))
            }
        }
        tunFd = null
        tunnelUp = false
        running = false
        activeIdentityId = null
        com.zapp.net.vpn.ZappVpnService.clearActiveEdge()
        verifying = false
        // A new tunnel gets a fresh verdict. Keeping the streak across a teardown would let two
        // dead edges from a previous minute stop the very next verify from running.
        noReplyStreak.set(0)
        edgeTries.set(0)
        lastVerifyOkMs = 0L
        // A torn-down tunnel has carried nothing. Without this the carry clock from the old
        // tunnel would still look fresh and the new one would be judged before it lived.
        GuardState.clearCarry()
        GuardState.setVerified(false)
        GuardState.setVpnRunning(false)
        stopSelf()
    }

    override fun onRevoke() {
        // system or user turned the VPN off -> do not leak: tear down cleanly
        stopTunnel(hard = true, reason = "revoke")
    }

    override fun onDestroy() {
        // also hard: an ordinary destroy must not leave GoBackend holding the tun, or the
        // device keeps routing into a tunnel nobody is maintaining (same defect as ACTION_STOP).
        stopTunnel(hard = true, reason = "destroy")
        // Release before `super.onDestroy()`: a wake lock that outlives its service keeps the
        // CPU up with nothing to do, and this phone is the only one he has.
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
            android.util.Log.e("ZAPP", "wake lock released")
        }
        wakeLock = null
        super.onDestroy()
    }

    /** Held for the life of the service; see [holdAwake]. */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * One GoBackend for the life of this service.
     *
     * `GoBackend.setState(tunnel, DOWN, null)` only tears the tunnel down when the tunnel
     * argument is that **instance's** `currentTunnel` (bytecode: `if_acmpne 153` skips the
     * teardown entirely). Constructing a fresh `GoBackend` for every call therefore made
     * every DOWN a silent no-op: measured 02:47:39, `vpn relay: stop` logged `tunnel DOWN`,
     * and 20 s later `ip -6 addr show tun0` still held `2606:4700:110:8336:�/128` and the
     * egress was still `2a09:bac5:4ff4:2e1e::498:63 warp=on`. The same no-op is why the
     * "previous tunnel" teardown inside every rebuild never ran either.
     */
    private val backendRef = AtomicReference<Backend?>(null)

    private fun backend(): Backend? =
        backendRef.get() ?: runCatching { GoBackend(this) }
            .getOrNull()
            ?.also { backendRef.compareAndSet(null, it) }

    private fun hexToBytes(h: String): ByteArray =
        h.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
