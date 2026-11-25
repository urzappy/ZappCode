package com.zapp.net.guard

import android.content.Context
import android.content.Intent
import com.zapp.net.vpn.ZappVpnService
import com.zapp.net.termux.EgressProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The rotation brain, ported from Avatar's shape (evidence: ouc_common.hop_once):
 *
 *   wall detected -> cooldown pause -> new keypair -> register (new /64) ->
 *   swap tunnel identity -> verify egress -> old reg deleted.
 *
 * Their shape, our code, our branding, zero reuse of their binaries.
 */
class RotationEngine(private val ctx: Context) {

    private val pool = WarpPool(ctx)
    /**
     * Process-wide, not per-instance. `MainActivity`, the guard service and the panel each
     * construct their own RotationEngine; per-instance mutexes meant two of them could hop
     * at the same time (measured 20:51 and 21:08, two hops 60 s apart on one wall burst).
     */
    private val mutex = hopMutex

    // Loop-breaker state (their shape: GUARD_STATE_FILE). Persisted so restarts don't reset it.
    private val prefs = ctx.getSharedPreferences("zapp_engine", Context.MODE_PRIVATE)
    private var lastHopMs: Long = prefs.getLong("lastHopMs", 0L)
    private var freshWallHops: Int = prefs.getInt("freshWallHops", 0)
    private var pauseUntilMs: Long = prefs.getLong("pauseUntilMs", 0L)
    // Wall hops allowed inside one backoff window. Instance state would be useless here:
    // `RotationEngine(ctx)` is constructed per call, so this lives in prefs.
    private var wallBurst: Int = prefs.getInt("wallBurst", 0)
    private var wallBurstStartMs: Long = prefs.getLong("wallBurstStartMs", 0L)
    // Tor rotation floor. Persisted for the same reason as the fields above: this object is
    // built per call, so an instance-only value would read 0 on the next wall and the
    // cooldown would never apply - which is exactly the storm it exists to stop.
    private var lastTorRotateAt: Long = prefs.getLong("lastTorRotateAt", 0L)

    /**
     * Last time a refusal measured outside the tunnel was answered with a tunnel repair.
     *
     * Avatar separates a "fallback" (traffic left through the real network) from a route or
     * quota failure - `_mark_fallback` writes its own flag and event kind. We need the same
     * separation: a wall measured with `warp=off` names the CARRIER address, so rotating a
     * WARP identity cannot fix it and only burns prefixes the provider never saw.
     */
    private var lastFallbackRepairMs: Long = 0L
    /**
     * The last /64 WARP handed back while it was already walled. Cloudflare's free tier has a
     * tiny shared pool: measured 05:34:52 and 05:36, every registration resolved to
     * `2a09:bac1:5a80:40::/64` - the prefix the wall had just burned. Two identical answers in a
     * row mean the pool is not moving, so more edge changes only cost 10 s each.
     */
    private var lastHandedBack: String = ""
    /** Consecutive exits that were followed by a wall inside REWALL_MS. */
    private var torWallStreak: Int = prefs.getInt("torWallStreak", 0)

    init {
        // Migration: earlier builds wrote a pause that ran to UTC midnight. Measured
        // 2026-11-19 22:02 that value survived restarts and made the panel look dead for
        // the rest of the day, so an over-long stored pause is clamped to the real backoff.
        val cap = System.currentTimeMillis() + EXHAUSTED_BACKOFF_MS
        if (pauseUntilMs > cap) {
            pauseUntilMs = cap
            persistEngineState()
            GuardState.log("wall", "stored pause clamped to ${EXHAUSTED_BACKOFF_MS / 60_000} min")
        }
    }

    private fun persistEngineState() {
        prefs.edit()
            .putLong("lastHopMs", lastHopMs)
            .putInt("freshWallHops", freshWallHops)
            .putLong("pauseUntilMs", pauseUntilMs)
            .putInt("wallBurst", wallBurst)
            .putLong("wallBurstStartMs", wallBurstStartMs)
            .putLong("lastTorRotateAt", lastTorRotateAt)
            .putInt("torWallStreak", torWallStreak)
            .apply()
    }

    fun isPaused(): Boolean =
        System.currentTimeMillis() < pauseUntilMs ||
            System.currentTimeMillis() < WallPause.pausedUntilMs(ctx)

    fun pausedUntilMs(): Long = maxOf(pauseUntilMs, WallPause.pausedUntilMs(ctx))

    /** The panel's "Try now": drop the loop-breaker without waiting for UTC midnight. */
    fun clearPause() {
        pauseUntilMs = 0L
        freshWallHops = 0
        WallPause.clearPause(ctx)
        persistEngineState()
        GuardState.setPaused(0L)
    }

    /** Creates the very first registration so the tunnel has an identity on first run. */
    suspend fun ensureFirstRegistration(): WarpReg? = mutex.withLock {
        withContext(Dispatchers.IO) {
            pool.usable()?.let { return@withContext it }
            registerNew("first-reg")
        }
    }

    private suspend fun registerNew(reason: String): WarpReg? = withContext(Dispatchers.IO) {
        for (attempt in 1..3) {
            try {
                val (priv, pub) = KeyGen.newKeypair()
                val reg = CloudflareRegister.register(pub.joinToString("") { "%02x".format(it) })
                val newReg = WarpReg(
                    id = reg.regId,
                    privHex = priv.joinToString("") { "%02x".format(it) },
                    peerPubHex = reg.peerPubHex,
                    endpoint = reg.endpoint,
                    v6 = reg.ourV6,
                    v4 = reg.ourV4,
                    createdAtMs = System.currentTimeMillis(),
                    note = reason,
                )
                pool.add(newReg)
                pool.persist()
                GuardState.refresh(pool)
                GuardState.log("register", "new /64 pool entry ${WarpPool.prefix64Of(newReg.v6) ?: newReg.v6}")
                return@withContext newReg
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A cancelled job must stop here. Swallowing this retried a dead registration
                // three times with 6 s sleeps and logged "register attempt N failed" as if
                // Cloudflare were at fault (measured 09:20:06-09:20:18).
                throw e
            } catch (e: Exception) {
                GuardState.setRegisterError("register attempt $attempt failed: ${e.message}")
                if (attempt == 3) return@withContext null
                Thread.sleep(6000) // Cloudflare register retry backoff, same as their shape
            }
        }
        null
    }

    /**
     * Keep the reserve stocked - the owner, 22 Nov: "MAY RESERBA AGAD ... PARA TULOY TULOY NA".
     *
     * One registration per call, and the keeper calls this once per 45 s tick, so ten spares
     * cost ten ticks instead of a burst. Registering is an HTTP round-trip to Cloudflare: it
     * does not touch the TUN, does not roll the live address, and cannot cut a stream. That is
     * why it is safe while he is idle - and why it is skipped while a turn is streaming.
     */
    suspend fun warmFill(): WarpReg? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (GuardState.status.value.streaming) return@withContext null
            // Self-heal before counting: a /64 is one token, so a second registration for the
            // same prefix would inflate the reserve and never be spendable.
            runCatching { pool.pruneDuplicates() }
            val live = pool.ready().size
            if (live >= WarpPool.POOL_TARGET) return@withContext null
            // Trim: an unproven spare has no value once the file is twice the target, and the
            // pool file is read on every panel refresh.
            val junk = pool.all().filter { it.verifiedAtMs == 0L }.sortedBy { it.createdAtMs }
            if (pool.all().size > WarpPool.POOL_TARGET * 2 && junk.isNotEmpty()) {
                runCatching { pool.retire(junk.first()) }
            }
            val reg = registerNew("warm:${live + 1}") ?: return@withContext null
            GuardState.log(
                "pool",
                "reserve ${live + 1}/${WarpPool.POOL_TARGET}: registered " +
                    "${WarpPool.prefix64Of(reg.v6) ?: reg.v6} - no rebuild",
            )
            reg
        }
    }

    /**
     * Move to a proven spare **while he is idle**, before the current address runs out.
     *
     * the owner, 22 Nov: "MAKE SURE THAT ITS OPTIMIZED AND NO LAG AND NO DELAY SO THAT THE AI WILL
     * CONTINUE HER/HIS DOIN AFTER DISCONNECT OR OUT OF TOKEN". Until now the only trigger was the
     * provider's refusal - he had to sit in a wall, read the error and press send again. A wall
     * is avoidable: the ledger knows how much context each turn costs and how much budget the
     * address has left, so the swap can happen *between* turns where nothing is streaming.
     *
     * Differs from [proveSpare] in one way: it installs a **proven** address, not an unproven
     * spare, because the whole point is that the next prompt must not need a measurement first.
     *
     * Returns null when the swap is unsafe or unnecessary; every refusal is logged with the
     * reason so a silent null cannot hide a broken pool.
     */
    suspend fun swapToFreshSpare(reason: String): WarpReg? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (GuardState.turnIsLive() || GuardState.status.value.wallPending) {
                GuardState.log("pool", "swap($reason) skipped: a turn is live")
                return@withContext null
            }
            if (ZappVpnService.verifyingNow()) {
                GuardState.log("pool", "swap($reason) skipped: a verify is in flight")
                return@withContext null
            }
            val active = ZappVpnService.activeIdentityId
            val now = System.currentTimeMillis()
            val spare = pool.ready()
                .filter { it.id != active }
                .filter { it.verifiedAtMs > 0L && (now - it.verifiedAtMs) < WarpPool.STALE_MS }
                .filter { it.spentTokens < WarpPool.BUDGET }
                .maxByOrNull { it.verifiedAtMs }
            if (spare == null) {
                GuardState.log("pool", "swap($reason): no proven spare with budget left")
                return@withContext null
            }
            val genBefore = ZappVpnService.generation
            pool.promote(spare)
            val t0 = System.nanoTime()
            ctx.startService(
                Intent(ctx, ZappVpnService::class.java).setAction(ZappVpnService.ACTION_START),
            )
            var waited = 0
            while (ZappVpnService.generation == genBefore && waited < 2000) {
                Thread.sleep(5)
                waited++
            }
            if (ZappVpnService.generation == genBefore) {
                GuardState.log("pool", "swap($reason): tunnel did not take ${spare.id.take(8)}")
                return@withContext null
            }
            GuardState.log(
                "pool",
                "swap($reason): wearing proven ${spare.id.take(8)} " +
                    "(${(System.nanoTime() - t0) / 1_000_000.0} ms) - no wall, no resend",
            )
            spare
        }
    }


    /**
     * Prove one unverified spare by wearing it.
     *
     * Measured 22 Nov 10:15, the pool held **10 registrations and only 3 solid**:
     *
     *     POOL: 10 registration(s), 3 solid, 10 ready, runway 600k
     *     070700db  egress=2a09:bac5:4ff5:2e1e::/64  proof=7m    solid=true
     *     e753c6bf  egress=2a09:bac5:4ff7:263c::/64  proof=38m   solid=true
     *     a6cfa0d0  egress=2a09:bac5:4ff6:2e1e::/64  proof=42m   solid=true
     *     992a00e4  egress=-                         proof=never  solid=false
     *     ... six more, all proof=never
     *
     * `warmFill` registers a spare but nothing ever proves it, so 70% of the reserve was an
     * untested guess and the runway read 600k instead of 2.0M. A registration only becomes a
     * token once `cdn-cgi/trace` has answered through it, and that requires wearing it.
     *
     * So while he is idle, one spare per keeper tick is installed and left to the normal verify
     * loop. The address is not wasted: it becomes the live identity, is proven by the same probe
     * every other address goes through, and lands in `solid()` when the reading arrives. If it
     * carries nothing the verify loop discards it - which is the answer we wanted anyway.
     */
    suspend fun proveSpare(): WarpReg? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val st = GuardState.status.value
            if (GuardState.turnIsLive() || st.wallPending) {
                GuardState.log(
                    "pool",
                    "prove skipped: turnLive=${GuardState.turnIsLive()} wallPending=${st.wallPending}",
                )
                return@withContext null
            }
            // Never swap under a probe that is still deciding the current identity's fate.
            if (ZappVpnService.verifyingNow()) {
                GuardState.log("pool", "prove skipped: a verify is in flight")
                return@withContext null
            }
            val candidates = pool.all().filter { it.verifiedAtMs == 0L }
            if (candidates.isEmpty()) return@withContext null
            val spare = candidates
                .filter { it.peerPubHex.length == 64 }
                .filter { it.id != ZappVpnService.activeIdentityId }
                .minByOrNull { it.createdAtMs }
            if (spare == null) {
                // Say exactly which precondition failed instead of returning a silent null.
                GuardState.log(
                    "pool",
                    "prove skipped: ${candidates.size} unproven but none usable " +
                        "(peerLen=${candidates.joinToString(",") { it.peerPubHex.length.toString() }} " +
                        "active=${ZappVpnService.activeIdentityId?.take(8)})",
                )
                return@withContext null
            }
            val genBefore = ZappVpnService.generation
            pool.promote(spare)
            val t0 = System.nanoTime()
            ctx.startService(
                Intent(ctx, ZappVpnService::class.java).setAction(ZappVpnService.ACTION_START),
            )
            var waited = 0
            while (ZappVpnService.generation == genBefore && waited < 2000) {
                Thread.sleep(5)
                waited++
            }
            if (ZappVpnService.generation == genBefore) {
                GuardState.log("pool", "prove: tunnel did not take ${spare.id.take(8)}")
                return@withContext null
            }
            GuardState.log(
                "pool",
                "prove: wearing ${spare.id.take(8)} " +
                    "(${(System.nanoTime() - t0) / 1_000_000.0} ms) - the verify loop will measure it",
            )
            spare
        }
    }

    /**
     * Wall detection.
     *
     * The live path is typed: the server sends `session.step.failed` with
     * `data.error.type` already classified (`provider.free-limit`, `provider.quota`, ...).
     * [QuotaWall.parse] reads that. The text form is only the fallback for a raw HTTP
     * failure of the prompt call, and its strings are the product's own copy.
     */
    fun isQuotaWall(apiText: String): Boolean {
        val typed = runCatching { QuotaWall.parse(apiText) }.getOrNull()
        if (typed != null) return typed.isWall
        return QuotaWall.looksLikeWallText(apiText)
    }

    /** full classification, for callers that need to decide whether hopping can help */
    fun wallOf(apiText: String): QuotaWall? {
        val typed = runCatching { QuotaWall.parse(apiText) }.getOrNull()
        if (typed != null) return typed.takeIf { it.isWall }
        return if (QuotaWall.looksLikeWallText(apiText)) {
            QuotaWall.Quota(apiText.trim().take(300), null)
        } else null
    }

    /**
     * Hop: blacklist current /64 (wall hit), build a fresh registration, hand it back
     * so the VPN service can swap identities.
     */
    suspend fun hop(reason: String, banForMs: Long? = null): WarpReg? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            // A rebuild tears down every socket Termux holds, so a hop made while a turn is
            // streaming does not rescue that turn - it kills it. Measured 2026-11-20
            // 01:34:34-01:34:42: the provider had accepted the prompt and was emitting
            // reasoning tokens when a late wall frame from the *previous* attempt started a
            // hop; the rebuild surfaced as `provider.transport ECONNRESET` mid-answer and the
            // turn was lost even though the new address was good.
            //
            // But a *walled* turn is already dead - the refusal IS its end - and the wall is
            // normally parsed while the send that caused it is still marked in flight, so the
            // old ordering deferred almost every wall-driven hop. Measured 04:51:18:
            //   [wall] address limit (provider.free-limit) -> rotating WARP /64
            //   [hop] not rotating during a live turn (wall:provider.free-limit)
            // three seconds before the Failed card appeared on screen. `wallPending` exists
            // for exactly this bypass (see GuardState.Status) and it was being set *after*
            // the check, so it could never take effect.
            GuardState.setWallPending(true)
            // `GuardState.turnIsLive()` is the same predicate the VPN service uses, so a stuck
            // `streaming` flag can no longer block a hop while the tunnel is dead (measured
            // 22 Nov 10:04:52 and 10:05:03: two manual hops refused with
            // `not rotating during a live turn` on a tunnel whose lane answered nothing).
            if (GuardState.turnIsLive() && !reason.startsWith("wall")) {
                GuardState.log(
                    "hop",
                    "not rotating during a live turn ($reason) - a rebuild would cut the stream",
                )
                GuardState.setWallPending(false)
                return@withContext null
            }

            // `zen_guard.main` order, verbatim: `_note_wall` runs BEFORE the cooldown and
            // before `_rotate`. A wall that returns right after a confirmed new address is
            // counted; two of those and rotation stops until the allowance's boundary.
            // `hop_now` (our "manual") is the documented way through, so it clears the pause
            // exactly as `avatar_ui.py:1535-1538` does.
            val manual = reason.startsWith("manual")
            if (manual) WallPause.clearPause(ctx)
            // Their `_note_wall` lives inside `main()`'s try: a fault in the wall bookkeeping
            // never takes the process down. Ours did exactly that on 2026-11-20 10:14 (an
            // int/long type clash in the prefs). Fail closed - treat a broken ledger as a
            // pause - rather than crash or rotate blind.
            val wallPaused = if (manual) false else runCatching {
                WallPause.noteWall(ctx, now, isFreeLimit = reason.contains("free-limit"))
            }.getOrElse { e ->
                GuardState.log("wall", "wall ledger failed (${e.javaClass.simpleName}: ${e.message}) - not rotating")
                true
            }
            if (wallPaused) {
                GuardState.setWallPending(false)
                return@withContext null
            }

            // Tor lane first. When the proxy is carrying the server's traffic the provider
            // never sees a WARP /64, so rebuilding the tunnel changes nothing it can observe.
            // What changes is the *exit*, which is why their `zen_guard.py` rotates the circuit
            // in this exact position. Measured 2026-11-20: 00:50 exit 185.220.101.106 ->
            // `provider.free-limit`; 00:52 exit 185.220.101.22 -> the same prompt answered.
            if (com.zapp.net.settings.GuardPrefs.torOn(ctx) &&
                com.zapp.net.net.TorLane.state.value.ready
            ) {
                // Their `zen_guard.main` gates a rotation on one number and nothing else:
                //     if age >= COOLDOWN_SECONDS: _rotate(...)
                //     else: "wall seen but in cooldown (Xs < 75s) - waiting"
                // `COOLDOWN_SECONDS = 75`. The `mode == "tor"` branch inside `_rotate` then
                // does one `rotate_tor_circuit()`, increments `hops`, and returns - it does
                // not count streaks, does not classify scope, and cannot pause anything.
                //
                // Ours had a `FREE_WALL_HOPS_MAX` streak that set `GuardState.setPaused` for
                // 10 min once three exits in a row were refused. That is a client-side
                // refusal the reference does not have, and with the auto-send loop gone there
                // is nothing left for it to protect: a wall arriving inside the window is
                // simply another wall, and the next tap must still be allowed to try.
                val since = now - lastTorRotateAt
                torWallStreak = if (lastTorRotateAt > 0L && since < REWALL_MS) {
                    torWallStreak + 1
                } else {
                    0
                }
                persistEngineState()
                if (since < TOR_HOP_COOLDOWN_MS) {
                    GuardState.log(
                        "wall",
                        "tor exit cooling down ${(TOR_HOP_COOLDOWN_MS - since) / 1000}s ($reason)",
                    )
                    GuardState.addWall(reason)
                    GuardState.setWallPending(false)
                    return@withContext null
                }
                val (before, after) = com.zapp.net.net.TorLane.rotate()
                val moved = after.isNotBlank() && after != before
                lastTorRotateAt = System.currentTimeMillis()
                persistEngineState()
                GuardState.log(
                    "wall",
                    "tor exit $before -> $after streak=$torWallStreak ($reason)" +
                        if (moved) "" else " [NEWNYM did not change the exit]",
                )
                GuardState.addWall(reason)
                GuardState.setWallPending(false)
                // Hand back the same registration: the WARP identity did not change, and null
                // here would read to WallGate as "no rotation happened at all".
                return@withContext pool.usable()
            }

            // 0. loop-breaker: if fresh /64s keep walling it is not an address problem.
            //    Bounded pause, never "until UTC midnight": measured 2026-11-19 22:02, two
            //    address walls in a row set the pause to midnight and the panel claimed
            //    "account cap" - a claim we have no evidence for, since an address wall is
            //    exactly what a stale prefix produces.
            if (now < pauseUntilMs) {
                // A wall is fresh evidence that we must move, so it may use a bounded
                // number of attempts even inside a backoff. Measured 22:29:44, a real
                // `provider.free-limit` arrived while a backoff from an earlier exhaustion
                // was still running and the guard answered "rotation paused 353s" - i.e.
                // did nothing at all, which is the exact "not working" that was reported.
                val wallDriven = reason.startsWith("wall")
                // A human tapping hop outranks a backoff a machine verdict earned. Their
                // keeper pauses its own loop (`keeper_pause_ctx("zen-guard", seconds=600)`);
                // a manual command still rotates. Measured 03:31:41: our backoff refused a
                // manual hop 4 min after a *false* no-IPv6 verdict - the probe had been
                // queued behind another probe for 45 s, so it never saw the tunnel.
                if (manual) {
                    GuardState.log(
                        "hop",
                        "manual hop - backoff bypassed (${(pauseUntilMs - now) / 1000}s left)",
                    )
                    pauseUntilMs = 0L
                    GuardState.setPaused(0L)
                    persistEngineState()
                }
                if (now - wallBurstStartMs > WALL_BURST_WINDOW_MS) {
                    wallBurstStartMs = now
                    wallBurst = 0
                }
                if (manual) {
                    // nothing to do: the bypass above already cleared the pause
                } else if (wallDriven && wallBurst < WALL_BURST_MAX) {
                    wallBurst += 1
                    GuardState.log(
                        "wall",
                        "backoff bypassed for a wall ($wallBurst/$WALL_BURST_MAX) - ${(pauseUntilMs - now) / 1000}s left on it",
                    )
                } else {
                    GuardState.log("wall", "rotation paused ${(pauseUntilMs - now) / 1000}s (backoff)")
                    GuardState.setWallPending(false)
                    return@withContext null
                }
            }
            // Their REWALL_SECONDS / FREE_WALL_HOPS_MAX shape: a wall arriving inside
            // REWALL_MS of the last hop means the prefix we just paid for did not buy
            // anything, so the next one probably will not either.
            if (lastHopMs != 0L && now - lastHopMs < REWALL_MS) {
                freshWallHops += 1
                GuardState.log("wall", "wall ${(now - lastHopMs) / 1000}s after a hop ($freshWallHops/$FREE_WALL_HOPS_MAX)")
                if (freshWallHops >= FREE_WALL_HOPS_MAX) {
                    // Re-present the prefix withheld longest instead of going silent. A
                    // permanent ban over a handful of prefixes is a dead end: the measured
                    // pool here is only about six distinct /64s.
                    //
                    // But a lifted prefix must not be one we already burned. Measured 02:18:42
                    // and 02:18:48: `liftOldestBan` handed back `4ff2:25d7` and `4ff2:25c3`
                    // eleven seconds after `4ff2` was burned, so the guard re-presented a
                    // prefix the provider had just refused and counted the resulting wall as
                    // "fresh /64s keep walling".
                    var lifted: String? = null
                    while (true) {
                        val cand = pool().liftOldestBan() ?: break
                        if (isBurned(cand)) {
                            GuardState.log(
                                "wall",
                                "withheld $cand is a burned /64 - dropping it instead of re-presenting",
                            )
                            continue
                        }
                        lifted = cand
                        break
                    }
                    if (lifted == null) {
                        pauseUntilMs = now + EXHAUSTED_BACKOFF_MS
                        persistEngineState()
                        GuardState.setPaused(pauseUntilMs)
                        GuardState.log("wall", "rotation paused ${EXHAUSTED_BACKOFF_MS / 60_000} min - nothing withheld left to re-present")
                        return@withContext null
                    }
                    GuardState.log("wall", "fresh /64s keep walling - re-presenting the oldest withheld prefix $lifted")
                    freshWallHops = 0
                }
            }

            // 1. Ban what we MEASURED, not what we assumed. The registration's own
            //    address lives in 2606:4700:110::/48 and never appears on the wire, so
            //    banning it would burn a prefix no request ever used.
            //
            //    Only the tunnel's own egress may be attached to a registration. If the probe
            //    came back `warp=off` the reading is the owner's home /64, and writing that onto a
            //    WARP identity poisons the pool - measured 16:27: `2001:4455:59c:4700::/64`
            //    (his ISP prefix) landed on a registration that had never carried a packet.
            val before = runCatching { EgressProbe.measure(ctx) }.getOrNull()
            // Three states, and collapsing any two of them breaks a different case:
            //
            //   warp=off            -> traffic really did leave outside WARP (a leak: repair)
            //   warp=on, v4 address -> inside WARP, but no rotatable /64 (A171: pause)
            //   warp=on, v6 address -> inside WARP and rotatable (ban + hop)
            //
            // `through` is therefore about the wire, not the family. Measured 05:18:03, a
            // `warp=on` reading on `104.28.252.31` was treated as "outside WARP" and the hop
            // was refused with `fallback ... repairing the tunnel instead of rotating`.
            val insideWarp = before != null && before.warp.equals("on", ignoreCase = true)
            val through = insideWarp && before!!.isV6
            // A wall that lands during a rebuild measures nothing (04:35:53: the probe answered
            // `unmeasured` two seconds into a bringUp), and a blank prefix here used to skip the
            // ban entirely and hop blind. Fall back to the last address the provider was proven
            // to have seen, which is still the identity the wall belongs to. Two sources, both
            // v6-only: the verify's record, then the probe's own newest v6 reading.
            // A wall that arrives while the probe could not answer names nothing.
            //
            // The app is deliberately excluded from the VpnService tun - the tunnel captures
            // `com.termux`, and Android never routes a VpnService's own sockets into the
            // tunnel it built - so a probe from this process can only see WARP by going out
            // through the lane on 41988. When the lane answers 502 during a rebuild,
            // `EgressProbe.measure` returns null and there is no measurement at all.
            //
            // Measured 23 Nov 16:13 on this phone, straight from the log:
            //
            //   [wall] egress check: ip=unmeasured warp=? via-tunnel=false (wall:provider.quota)
            //   [wall] burned /64 2a09:bac5:4ff3:137d - will not present it again this session
            //   [wall] limit follows the account: 3 distinct /64s refused inside 20 min
            //          - pausing rotation for 10 min instead of burning more
            //
            // Not one of those three prefixes had been measured for the wall it was burned
            // for: each one came from `lastEgress64`, i.e. from the *last* address that was
            // proven, not from the address the provider refused. The pool drained itself and
            // rotation paused for ten minutes, which is precisely the "all the unlimited
            // system is broken" the owner reported. A blind reading is not a refusal.
            val remembered = GuardState.status.value.lastEgress64
                .ifBlank { EgressProbe.lastV6?.prefix64.orEmpty() }
            val burnt = if (through) before?.v6Prefix.orEmpty() else ""
            if (!through && remembered.isNotBlank()) {
                GuardState.log(
                    "wall",
                    "unmeasured wall - not charging $remembered to the provider " +
                        "(it is the last proven /64, not this wall's address)",
                )
            }
            // THE ADDRESS WE MUST NOT ACCEPT BACK.
            //
            // When the wall arrives while the probe cannot answer through the tunnel (`through`
            // false), `burnt` is empty by design - a blind reading is not a refusal, and
            // charging the last proven /64 for it drained the pool on 23 Nov 16:13. But an
            // empty `burnt` also disabled the only check that rejects a hop landing on the SAME
            // address we were already using. Measured tonight, 23:23 and 23:46, two rotations
            // 23 minutes apart both answered `2a09:bac1:5a80:40::/64` and both were called a
            // rotation while the provider saw one address the whole time - so the wall came
            // back on every send and "unlimited" looked broken.
            //
            // `remembered` is the last /64 this phone was PROVEN on. Being handed it again is
            // not a rotation, measured or not, so it is the floor for the check below.
            val avoid = burnt.ifBlank { remembered }
            val current = pool.usable()
            GuardState.log(
                "wall",
                "egress check: ip=${before?.ip ?: "unmeasured"} warp=${before?.warp ?: "?"} " +
                    "via-tunnel=$through ($reason)",
            )
            // Only a `warp=off` reading is a leak. A `warp=on` v4 answer is inside WARP and is
            // not a reason to rebuild - it only means there is no rotatable /64 to retire.
            if (before != null && !insideWarp) {
                // Their `_mark_fallback` distinction, applied to the phone: a refusal that
                // arrived while traffic was leaving outside WARP names the real network, not a
                // WARP identity. Rotating here burns a /48 the provider never saw and hides the
                // actual fault - a dead tunnel.
                //
                // Measured 2026-11-20 02:26-02:30: with the tunnel down the egress read
                // `ip=2001:4455:59c:4700:... warp=off` and each prompt came back
                // `provider.free-limit`; after a repair the same account served 46 consecutive
                // turns on `2a09:bac5:4ff7:2e1e::/64` with no wall. The 429 was the carrier
                // address speaking, and every hop in that window was wasted.
                GuardState.log(
                    "wall",
                    "fallback: traffic left outside WARP (ip=${before.ip}) - repairing the " +
                        "tunnel instead of rotating ($reason)",
                )
                GuardState.addWall(reason)
                GuardState.setWallPending(false)
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastFallbackRepairMs >= FALLBACK_REPAIR_COOLDOWN_MS) {
                    lastFallbackRepairMs = nowMs
                    ctx.startService(
                        Intent(ctx, ZappVpnService::class.java)
                            .setAction(ZappVpnService.ACTION_START),
                    )
                } else {
                    GuardState.log(
                        "wall",
                        "fallback repair already requested " +
                            "${(nowMs - lastFallbackRepairMs) / 1000}s ago - not rebuilding again",
                    )
                }
                return@withContext null
            }
            if (burnt.isNotBlank()) {
                // Attach the measured prefix to the registration BEFORE banning it. Without
                // this, `usable()` sees egress64 == null for the current reg, cannot tie the
                // banned /64 to it, and hands the same identity straight back to the rebuild.
                current?.let { pool.setEgress(it, burnt) }
                // The server states its own retry window: measured 22:13,
                // `retryAfterMs = 38 801 000` (~10h 46m). Withholding that prefix for the
                // window it named is the only honest TTL; the clamped default is for walls
                // that carry no number.
                pool.ban(burnt, reason, banForMs)
                // The pool ban above is keyed on the exact /64, and the meter is coarser than
                // that: `2a09:bac5:4ff3:2e1e::/64` and `2a09:bac5:4ff3:30c8::/64` were both
                // refused (01:31), and a hop landed on `4ff3:2e1e` again at 01:47:19 and was
                // refused 0 s after the hop. Remember the /48 too, or the next registration
                // walks straight back into the same bucket.
                noteBurned(burnt)
                // Evidence gate before spending more registrations: a refusal that follows the
                // account cannot be hopped away, and pretending otherwise is what makes the
                // app sit on "rotating" for minutes at a time while it hunts an address that
                // does not exist. Measured 05:01-05:06, five distinct /64s refused the same
                // 71-token prompt back to back.
                if (noteWalledPrefix(burnt)) {
                    pauseUntilMs = System.currentTimeMillis() + ACCOUNT_WALL_PAUSE_MS
                    persistEngineState()
                    GuardState.setPaused(pauseUntilMs)
                    GuardState.log(
                        "wall",
                        "limit follows the account: $ACCOUNT_WALL_DISTINCT distinct /64s refused " +
                            "inside ${ACCOUNT_WALL_WINDOW_MS / 60_000} min - pausing rotation for " +
                            "${ACCOUNT_WALL_PAUSE_MS / 60_000} min instead of burning more",
                    )
                    GuardState.setWallPending(false)
                    return@withContext null
                }
            } else {
                // Nothing was measured, so nothing may be burned and nothing may count
                // toward the account-wide pause.
                //
                // The honest move is the same one the `warp=off` branch makes: this is more
                // likely a dead tunnel talking than a refused identity, so repair the tunnel
                // and let the next wall be measured. Rotating here is what used to spend
                // registrations hunting an address that was never the problem.
                GuardState.log(
                    "wall",
                    "no egress measured for this wall - repairing the tunnel instead of " +
                        "burning an address ($reason)",
                )
                GuardState.addWall(reason)
                GuardState.setWallPending(false)
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastFallbackRepairMs >= FALLBACK_REPAIR_COOLDOWN_MS) {
                    lastFallbackRepairMs = nowMs
                    ctx.startService(
                        Intent(ctx, ZappVpnService::class.java)
                            .setAction(ZappVpnService.ACTION_START),
                    )
                } else {
                    GuardState.log(
                        "wall",
                        "repair already requested " +
                            "${(nowMs - lastFallbackRepairMs) / 1000}s ago - not rebuilding again",
                    )
                }
                return@withContext null
            }
            GuardState.addWall(reason)
            GuardState.refresh(pool)

            // 2. their `_rotate` shape: register, swap, measure, and if Cloudflare
            //    handed back a prefix we already burned -> try again instead of
            //    serving the wall from a different address
            var noV6 = false
            // THE RESET, copied from `ouc_common.hop_once` (read 06:02):
            //
            //   run_warp(["disconnect"])
            //   run_warp(["registration", "delete"])   <-- the identity is destroyed
            //   run_warp(["registration", "new"])      <-- and a new one created
            //
            // warp-cli only ever holds ONE registration, so "delete then new" is their whole
            // state machine. Ours kept a pool and `usable()` returned `regs.lastOrNull`,
            // which means a rebuild could be handed an identity Cloudflare had already
            // assigned a /64 to - the same address, walled again, "verified" with no token
            // reset. That is the defect the owner named: a new keypair is not the same as
            // discarding the old device.
            //
            // Local pool cleared on every hop so exactly one identity is ever live, like
            // theirs. The Server-side DELETE is best-effort and capped, because a large
            // legacy pool would otherwise spend minutes in HTTP before the tunnel even moves.
            // VERIFY BEFORE DESTROYING AN IDENTITY.
            //
            // Their `_rotate` never checks anything first: `warp-cli registration delete`
            // is unconditional. On Windows the server is a daemon they can see, so a dead
            // daemon is obvious. Here the server is Termux, and the two failures look
            // identical from the guard's side: "the provider refused my turn" and "Termux
            // is gone". Destroying the registration on the second one buys nothing and
            // costs the identity that was working.
            //
            // The check is desktop's own (`runtime/server/health.ts`, `GET /api/info`,
            // 30 s deadline / 2 retries / 100 ms / 750 ms cache). `Unauthorized` is just as
            // decisive as `Unreachable`: a password the server rejects cannot be fixed by
            // asking Cloudflare for a different address.
            run {
                val base = com.zapp.net.settings.observe(ctx).first().serverBase
                    .ifBlank { com.zapp.net.settings.PHONE_SERVER }
                val tok = com.zapp.net.api.loadToken(ctx)
                when (val health = com.zapp.net.api.ServerHealth.verify(base, tok)) {
                    is com.zapp.net.api.ServerHealth.Result.Healthy -> GuardState.log(
                        "hop",
                        "verify: server ${health.version} healthy at $base - resetting the identity",
                    )
                    com.zapp.net.api.ServerHealth.Result.Unauthorized -> {
                        GuardState.log(
                            "hop",
                            "verify: server rejected the credential - NOT discarding the identity " +
                                "(an address cannot fix a password)",
                        )
                        return@withContext null
                    }
                    com.zapp.net.api.ServerHealth.Result.Unreachable -> {
                        GuardState.log(
                            "hop",
                            "verify: server unreachable at $base - NOT discarding the identity " +
                                "(a hop cannot fix a dead server)",
                        )
                        return@withContext null
                    }
                }
            }

            // WARM POOL. the owner, 22 Nov: "ITS 200K TOKEN EACH ... PAG NAUBOS NA YUNG TOKEN
            // NONG ISA MAY RESERBA AGAD".
            //
            // This block used to retire EVERY registration and delete the three newest
            // server-side, copying `warp-cli registration delete` + `new`. That is right for a
            // client that holds exactly one registration and fatal for a pool: the first wall
            // destroyed all ten proven addresses, which is why nothing could ever be "reserved".
            //
            // Now only the identity that answered this wall is retired. Every other proven
            // address stays parked, and the swap below installs one of them with no register
            // round-trip and no verify wait - the proof is already in the file.
            val walled = current
            val parked = pool.ready().filter { it.id != walled?.id }
            if (walled != null) {
                runCatching { pool.retire(walled) }
                runCatching { CloudflareRegister.delete(walled.id) }
                GuardState.log(
                    "hop",
                    "retired the walled identity only - ${parked.size} proven address(es) kept",
                )
            } else if (parked.isNotEmpty()) {
                GuardState.log("hop", "no walled identity to retire - ${parked.size} proven kept")
            }

            // 1b. Install a proven address if one is parked: one rebuild, no HTTP, no probe.
            parked.firstOrNull()?.let { warm ->
                awaitIdle()
                val genBefore = ZappVpnService.generation
                pool.promote(warm)
                val t0 = System.nanoTime()
                // A UAPI rekey cannot move the /64 (measured 09:47: cross-identity rekey is
                // instant and kills the tunnel, because the egress is bound to the interface
                // address). The warm path is therefore a real rebuild - still 6-20 ms measured,
                // against the 20-40 s register+verify it replaces.
                ctx.startService(
                    Intent(ctx, ZappVpnService::class.java).setAction(ZappVpnService.ACTION_START),
                )
                var waited = 0
                // 5 ms: a UAPI rekey is a socket write, so polling at 50 ms would hide the
                // very number we are trying to measure.
                while (ZappVpnService.generation == genBefore && waited < 2000) {
                    Thread.sleep(5)
                    waited++
                }
                if (ZappVpnService.generation != genBefore) {
                    // Report the address the SERVICE installed, not the registration's cached
                    // /64. Measured 09:49: the log claimed `2a09:bac1:5ae0:40` while the lane
                    // was answering `2a09:bac5:4ff6:2e1e` - a stale label makes the pool's
                    // accounting look right when it is not.
                    val installed = com.zapp.net.guard.GuardState.status.value.currentV6
                    GuardState.log(
                        "hop",
                        "warm swap: installed $installed in " +
                            "${(System.nanoTime() - t0) / 1_000_000.0} ms " +
                            "(no register, no verify wait)",
                    )
                    GuardState.setWallPending(false)
                    GuardState.refresh(pool)
                    return@withContext warm
                }
                // The rebuild did not take: put the ordering back and fall through to a fresh
                // registration rather than reporting a swap that never happened.
                GuardState.log("hop", "warm swap: tunnel did not take the parked identity - registering")
            }

            // their `_rotate` records `last_hop` for the attempt, not only for success:
            // the 75 s cooldown in `zen_guard.main` is measured from it.
            WallPause.noteHopAttempt(ctx, System.currentTimeMillis())
            for (attempt in 1..HOP_TRIES) {
                // every rebuild below kills sockets riding the tun; never do it mid-turn
                awaitIdle()
                val reg = registerNew("hop$attempt:$reason") ?: return@withContext null
                val genBefore = ZappVpnService.generation
                ctx.startService(
                    Intent(ctx, ZappVpnService::class.java).setAction(ZappVpnService.ACTION_START),
                )

                // wait for the tunnel to actually rebuild on the new identity
                var waited = 0
                while (ZappVpnService.generation == genBefore && waited < 60) {
                    Thread.sleep(500)
                    waited++
                }
                if (ZappVpnService.generation == genBefore) {
                    GuardState.log("hop", "try $attempt: tunnel did not swap - discarding registration")
                    runCatching { CloudflareRegister.delete(reg.id) }
                    pool.retire(reg)
                    continue
                }
                Thread.sleep(1500) // WireGuard handshake settle

                // A burned /64 on a new identity is usually the EDGE's prefix, not the
                // registration's: measured 21:12, one registration answered 2a09:bac5:...
                // on .192.10 and 2a09:bac1:5ae0:40::/64 (never burned) on .192.9. So
                // re-roll the edge on the same keypair before paying for a new register.
                var p64 = ""
                var lastIp = ""
                var burnThisIdentity = false
                var burnedHere = false
                var measured = false
                var carriedV4Only = false
                for (edgeTry in 1..EDGE_CHANGES) {
                    // One read is not enough: the rebuild and the probe race. Measured
                    // 2026-11-19 22:13:42, the hop fired while the old device was being
                    // torn down, the probe answered null, and the guard declared
                    // "no IPv6 egress - retries paused" one second before the new device
                    // came up at 22:13:57. Retry before concluding anything.
                    var after: com.zapp.net.termux.EgressProbe.Reading? = null
                    for (readTry in 1..4) {
                        after = runCatching {
                            com.zapp.net.termux.EgressProbe.measure(ctx, 13_000)
                        }.getOrNull()
                        if (after != null && after.warp.equals("on", ignoreCase = true) && after.isV6) break
                        if (readTry < 4) kotlinx.coroutines.delay(1_500)
                    }
                    // A `warp=off` answer is HIS CARRIER, not an address WARP handed out. Measured
                    // 05:39:42: right after a rebuild the in-process read came back
                    // `2001:4455:59c:4700::...` `warp=off` - the home WiFi prefix, which is already
                    // in the burn list - and the hop read that as "the /64 I was given is walled",
                    // burned an edge change and rebuilt again for nothing. A leak is a statement
                    // about the tunnel, never about the provider's bucket.
                    val through = after != null && after.warp.equals("on", ignoreCase = true)
                    if (after != null && after.known && !through) {
                        GuardState.log(
                            "hop",
                            "try $attempt.$edgeTry: read left OUTSIDE WARP (${after.ip}) - leak, not an address verdict",
                        )
                    }
                    var got = if (through) after!!.v6Prefix else ""
                    lastIp = after?.ip.orEmpty()
                    GuardState.log(
                        "hop",
                        "try $attempt.$edgeTry: egress=${after?.ip ?: "unmeasured"} warp=${after?.warp ?: "?"}",
                    )
                    // Their v3.0 "honest IP rotation": a hop only counts when the PUBLIC
                    // address changes. Measured 2026-11-20 01:04 and 01:06, two separate
                    // registrations both answered the same `2a09:bac1:5a80:40::/64` while the
                    // log called each one a rotation - because the wall handler above had no
                    // trustworthy "before" prefix to ban (the proxy was carrying the traffic,
                    // so the probe read `warp=off`). The provider saw one address the whole
                    // time. Comparing against the pre-hop prefix closes that hole regardless
                    // of why the ban list is empty.
                    //
                    // And the granularity is a /64, exactly as their guard assumes. The
                    // earlier /48 reading came from a window where every "walled" prompt had
                    // left through the carrier (`warp=off`), so the refusal named an address
                    // WARP never handed out. Directly against it: `4ff2:25c3::/64` walled at
                    // 02:16:37 while `4ff2:2646::/64` verified `warp=on` at 02:20:46 - same
                    // /48, different outcome - and `4ff7:2e1e::/64` served 46 consecutive
                    // turns. Banning /48s threw away addresses the provider would have served.
                    if (got.isNotBlank() && (sameBucket(got, avoid) || isBurned(got))) {
                        GuardState.log(
                            "hop",
                            "try $attempt.$edgeTry: $got is a walled /64 - re-rolling edge",
                        )
                        // The /64 often follows the COLO, not the keypair: measured 21:12, one
                        // registration answered `2a09:bac1:5ae0` on .192.9 and `2a09:bac5:...` on
                        // .192.10. Breaking out here instead of re-rolling is what produced the
                        // 02:20:44-02:20:50 loop: five rebuilds in six seconds, every one of them
                        // landing back on `2a09:bac5:4ff2`, seconds after `tunnel VERIFY ok` had
                        // just proven that exact address carries traffic. Mark it and let the
                        // same edge re-roll path below run, which is what the banned-/64 case
                        // already does.
                        burnedHere = true
                    }
                    if (after != null && after.known && !after.isV6) {
                        // Their Â§171 case, stated instead of faked: WARP is carrying IPv4, the
                        // server meters a v6 /64 we never presented, and a "hop" here would
                        // only burn a registration.
                        GuardState.log(
                            "hop",
                            "try $attempt.$edgeTry: WARP carried v4 only (${after.ip}) - no /64 to rotate",
                        )
                        carriedV4Only = true
                        break
                    }
                    if (got.isBlank()) {
                        // unmeasured is not success: keep the registration but say so
                        GuardState.log("hop", "try $attempt.$edgeTry: egress unmeasured - keeping ${reg.v6.take(14)}...")
                        break
                    }
                    measured = true
                    if (!burnedHere && !pool.isBanned(got)) {
                        p64 = got
                        pool.setEgress(reg, got)
                        break
                    }
                    // A reading that names the prefix we just moved AWAY from is usually the
                    // old tunnel, not the new one: measured 05:25:37.478, a fresh registration
                    // reported `4ff7:263c` - the exact /64 burned 3 s earlier - because the
                    // route still went through the previous TUN. Condemning the edge for that
                    // is what made a hop take 40 s (four re-rolls) instead of one rebuild.
                    var staleTries = 0
                    while (got == avoid && staleTries < 3) {
                        staleTries++
                        GuardState.log(
                            "hop",
                            "try $attempt.$edgeTry: $got is the address we just left - stale route, re-reading ($staleTries/3)",
                        )
                        Thread.sleep(1_200)
                        val after2 = runCatching { EgressProbe.measure(ctx, 13_000) }.getOrNull()
                        val got2 = after2?.v6Prefix.orEmpty()
                        if (got2.isNotBlank() && got2 != got && !pool.isBanned(got2)) {
                            got = got2
                            break
                        }
                    }
                    if (!burnedHere && !pool.isBanned(got)) {
                        p64 = got
                        pool.setEgress(reg, got)
                        break
                    }
                    // Cloudflare's free WARP hands out a small shared pool: measured 05:34:52 and
                    // again 05:36, every new registration resolved to `2a09:bac1:5a80:40::/64`,
                    // the same prefix the wall had just burned. Re-rolling the edge cannot change
                    // a /64 that comes from the pool, it only costs another 10 s per attempt -
                    // that is the "ang tagal maka-hanap ng address" he reports. One edge change
                    // is enough to rule that out; after that, stop and say it.
                    if (pool.isBanned(got) && got == lastHandedBack) {
                        GuardState.log(
                            "hop",
                            "WARP handed back the same walled /64 twice ($got) - rotation cannot help here",
                        )
                        // Do not hand the caller a failure it will immediately retry: the pool is
                        // not moving, so the honest state is "wait", the same wait the engine uses
                        // when it has nothing left to present. Persisted, so a restart does not
                        // resume hunting a prefix that will not change.
                        val until = System.currentTimeMillis() + EXHAUSTED_BACKOFF_MS
                        pauseUntilMs = until
                        persistEngineState()
                        GuardState.setPaused(until)
                        GuardState.log(
                            "wall",
                            "rotation paused ${EXHAUSTED_BACKOFF_MS / 60_000} min - the free pool keeps returning the same walled /64",
                        )
                        return@withContext null
                    }
                    lastHandedBack = got
                    GuardState.log("hop", "try $attempt.$edgeTry: $got already walled - re-rolling edge")
                    if (edgeTry == EDGE_CHANGES) { burnThisIdentity = true; break }
                    val next = EdgePins.pick()
                    GuardState.log("hop", "try $attempt.$edgeTry: forced edge $next on the same identity")
                    awaitIdle()
                    ZappVpnService.requestEdge(next)
                    val gen2 = ZappVpnService.generation
                    ctx.startService(
                        Intent(ctx, ZappVpnService::class.java).setAction(ZappVpnService.ACTION_START),
                    )
                    var w2 = 0
                    while (ZappVpnService.generation == gen2 && w2 < 60) {
                        Thread.sleep(500)
                        w2++
                    }
                    if (ZappVpnService.generation == gen2) {
                        GuardState.log("hop", "try $attempt.$edgeTry: forced edge did not swap")
                        break
                    }
                    Thread.sleep(1200)
                }
                if (!measured) {
                    GuardState.log(
                        "hop",
                        if (carriedV4Only)
                            "no IPv6 egress through WARP - rotation cannot change the metered address"
                        else "no egress measured - not declaring a hop",
                    )
                    if (carriedV4Only) {
                        // A real v4 answer means WARP is carrying v4 only, and their README
                        // (A171) is explicit that no amount of /64 rotation changes that
                        // bucket. Their `_rotate` returns false and pauses retries for ten
                        // minutes here, and that verdict is justified by evidence.
                        noV6 = true
                        break
                    }
                    // A probe that answers *nothing* is not evidence that WARP is v4-only -
                    // it is evidence that the probe failed, and it fails right after a
                    // rebuild. Measured 2026-11-20 04:43:50: `egress=unmeasured` twice on a
                    // healthy tunnel, and the guard answered `no IPv6 egress - nothing to
                    // rotate, retries paused`, i.e. a transient probe miss locked rotation
                    // out for ten minutes. Hand back null instead of a verdict: the resume
                    // loop's widening schedule (0,20,40,60,90,120,150,180...) is the throttle,
                    // so nothing hammers and nothing is claimed.
                    GuardState.log(
                        "hop",
                        "probe missed - unknown, not a v4-only verdict; leaving the retry to the resume loop",
                    )
                    GuardState.setWallPending(false)
                    return@withContext null
                }
                if (burnThisIdentity) {
                    GuardState.log("hop", "try $attempt: every edge gave a walled /64 - discarding ${reg.v6.take(14)}...")
                    runCatching { CloudflareRegister.delete(reg.id) }
                    pool.retire(reg)
                    continue
                }
                if (measured && p64.isBlank()) {
                    // measured through every attempted edge and all were banned
                    runCatching { CloudflareRegister.delete(reg.id) }
                    pool.retire(reg)
                    continue
                }

                // A verified tunnel is not a working address. Only a real turn proves the
                // server will answer from this /64 - see AddressCheck for the two measurements
                // that separate those states. Do it before committing the swap, so a spent
                // prefix is retired instead of being handed to the retry loop as "rotated".
                //
                // Only a limit retires the prefix. A transport failure (`error:`) is not
                // evidence about the bucket and must not ban an address that may be fine.
                if (measured && p64.isNotBlank() && !AddressCheck.qualifies(ctx, p64)) {
                    if (AddressCheck.lastVerdict.startsWith("wall:")) {
                        pool.ban(p64, "address did not answer (${AddressCheck.lastVerdict})", banForMs)
                        noteBurned(p64)
                        GuardState.log(
                            "hop",
                            "try $attempt: ${AddressCheck.lastVerdict} from $p64 - not a working address, retiring it",
                        )
                        if (noteWalledPrefix(p64)) {
                            pauseUntilMs = System.currentTimeMillis() + ACCOUNT_WALL_PAUSE_MS
                            persistEngineState()
                            GuardState.setPaused(pauseUntilMs)
                            GuardState.log(
                                "wall",
                                "limit follows the account: $ACCOUNT_WALL_DISTINCT distinct /64s refused " +
                                    "inside ${ACCOUNT_WALL_WINDOW_MS / 60_000} min - pausing rotation",
                            )
                            GuardState.setWallPending(false)
                            return@withContext null
                        }
                    } else {
                        GuardState.log(
                            "hop",
                            "try $attempt: could not qualify ($p64): ${AddressCheck.lastVerdict} - not banning an address over that",
                        )
                    }
                    runCatching { CloudflareRegister.delete(reg.id) }
                    pool.retire(reg)
                    continue
                }

                // 3. the swap held and the prefix is clean: drop the old identity
                current?.let {
                    if (it.id != reg.id) {
                        runCatching { CloudflareRegister.delete(it.id) }
                        pool.retire(it)
                    }
                }
                lastHopMs = System.currentTimeMillis()
                freshWallHops = 0
                wallBurst = 0
                wallBurstStartMs = 0L
                // their success block: failed_hops/backoff/hop_counted are all cleared and
                // `last_ok_hop` becomes the anchor the next wall is judged against.
                WallPause.noteOkHop(ctx, lastHopMs)
                // `zen_guard.py:436-440`: a confirmed hop re-rolls the proxy's pinned edge.
                requestEdgeReroll(ctx)
                // A proven hop is evidence that moving works, so a backoff earned by earlier
                // exhaustion has done its job. Leaving it set made the retry loop refuse the
                // very prompt the hop had just unblocked ("retry loop stopped: rotation
                // paused", measured 01:47 and 01:51).
                pauseUntilMs = 0L
                // A successful hop means the pool moved, so the "same /64 twice" verdict from an
                // earlier wall no longer describes reality.
                lastHandedBack = ""
                persistEngineState()
                GuardState.addHop(lastIp.ifBlank { reg.v6 })
                GuardState.log("hop", "rotated #$attempt -> ${p64.ifBlank { reg.v6 }} âœ“")
                GuardState.setWallPending(false)
                return@withContext reg
            }

            GuardState.log(
                "hop",
                if (noV6) "no IPv6 egress - nothing to rotate, retries paused"
                else "every registration came back walled - nothing left to rotate to",
            )
            // Do not hammer: each attempt costs a registration and a TUN. Ten minutes is
            // short enough that a different colo can appear, long enough to stop the storm.
            pauseUntilMs = System.currentTimeMillis() + EXHAUSTED_BACKOFF_MS
            persistEngineState()
            GuardState.setPaused(pauseUntilMs)
            GuardState.setWallPending(false)
            null
        }
    }

    /**
     * `zen_guard.py:436-440` / `avatar_ui.py:1518-1523`: tell the persistent proxy to pick a
     * new pinned edge after a confirmed hop.
     *
     * The proxy runs inside Termux, so the control file can only be written from Termux's
     * own uid - `RunCommandService` is the documented way in and is already used for the
     * server lifecycle. When the request cannot be delivered nothing breaks: the proxy also
     * re-rolls on an observed /64 change (`zen_proxy._WarpState.get:80-92`), which is the
     * path that actually fires on this device.
     */
    private fun requestEdgeReroll(ctx: Context) {
        val cmd = """
            L="${'$'}HOME/.zapp/lane"
            mkdir -p "${'$'}L"
            if [ -f "${'$'}L/edge_override.txt" ]; then
              ov=$(tr -d '[:space:]' < "${'$'}L/edge_override.txt" | tr 'A-Z' 'a-z')
            else
              ov=auto
            fi
            if [ "${'$'}ov" = auto ]; then date +%s > "${'$'}L/edge_reroll"; fi
        """.trimIndent()
        val ok = runCatching {
            com.zapp.net.termux.RunCommand.run(ctx, cmd, "/sdcard/zapp_edge_reroll.log")
        }.getOrDefault(false)
        GuardState.log(
            "hop",
            if (ok) "edge re-roll requested from the proxy (edge_reroll)"
            else "edge re-roll not delivered - proxy will re-roll on the /64 change",
        )
    }

    /** Wait (bounded) for the chat to leave its live turn before touching the tunnel. */
    private suspend fun awaitIdle(maxMs: Long = 60_000) {
        // A walled session stays `busy` while the server retries a dead address, so waiting
        // for it is waiting forever. The rebuild is what frees it.
        if (GuardState.status.value.wallPending) return
        val end = System.currentTimeMillis() + maxMs
        while (GuardState.status.value.streaming && System.currentTimeMillis() < end) {
            kotlinx.coroutines.delay(1_000)
        }
    }

    /**
     * Does the provider meter these two as one address?
     *
     * Avatar's guard answers this for us: `normalize_v6_64` is the unit in `_blacklist_add`,
     * `_is_burnt`, `hop_once(avoid_prefixes=...)` and `same_address` - every ban, every
     * freshness check, every "same address" decision is a /64. Nothing in their code
     * compares /48s.
     *
     * Our own measurements now agree. The /48 theory came from two refusals inside
     * `4ff3`, but every refusal in that window was a leak: 2026-11-20 02:26-02:30 showed
     * `warp=off` on the carrier address at the same moments. Directly against the /48 rule:
     *
     *   `2a09:bac5:4ff2:25c3::/64` -> 429 at 02:16:37
     *   `2a09:bac5:4ff2:2646::/64` -> verified `warp=on` at 02:20:46, same /48
     *   `2a09:bac5:4ff7:2e1e::/64` -> 46 consecutive turns, zero walls
     *
     * So a fresh /64 inside a previously walled /48 is a fresh address, exactly as their
     * guard assumes, and banning /48s only threw away usable addresses.
     */
    private fun sameBucket(a: String, b: String): Boolean {
        val ka = bucketKey(a)
        val kb = bucketKey(b)
        return ka.isNotBlank() && ka == kb
    }

    /**
     * /64s this install has already been refused from.
     *
     * The unit is theirs: `_blacklist_add` stores `normalize_v6_64(addr)`, `_is_burnt`
     * compares the same. Ours used to store the /48, which is coarser than anything their
     * guard ever bans and threw away fresh addresses: measured 02:20:46, `4ff2:2646`
     * verified `warp=on` while `4ff2:25c3` had walled 4 minutes earlier - same /48.
     */
    private fun noteBurned(prefix: String) {
        val k = bucketKey(prefix)
        if (k.isBlank()) return
        val now = System.currentTimeMillis()
        val rows = prefs.getStringSet(KEY_BURNED64, emptySet()).orEmpty()
            .mapNotNull { row ->
                val at = row.indexOf('#')
                if (at <= 0) null
                else row.substring(0, at).toLongOrNull()?.let { it to row.substring(at + 1) }
            }
            .filter { now - it.first < BURNED64_TTL_MS }
            .toMutableList()
        if (rows.any { it.second == k }) return
        rows.add(now to k)
        val trimmed = rows.sortedByDescending { it.first }.take(BURNED64_MAX)
        prefs.edit().putStringSet(
            KEY_BURNED64,
            trimmed.map { "${it.first}#${it.second}" }.toSet(),
        ).apply()
        GuardState.log("wall", "burned /64 $k - will not present it again this session")
    }

    private fun isBurned(prefix: String): Boolean {
        val k = bucketKey(prefix)
        if (k.isBlank()) return false
        val now = System.currentTimeMillis()
        return prefs.getStringSet(KEY_BURNED64, emptySet()).orEmpty().any { row ->
            val at = row.indexOf('#')
            // older builds stored the bare key with no timestamp; treat it as current
            if (at <= 0) return@any row == k
            val ts = row.substring(0, at).toLongOrNull() ?: return@any false
            now - ts < BURNED64_TTL_MS && row.substring(at + 1) == k
        }
    }

    private fun bucketKey(prefix: String): String =
        prefix.substringBefore('/').split(':').filter { it.isNotBlank() }.take(4).joinToString(":")

    fun pool() = pool

    /**
     * The panel's "Fix WARP (Reset Keys)". Their wording: renews the session keys,
     * reconnects, and recreates the registration only if it is missing. Ours: a fresh
     * keypair + register call, swapped onto the tunnel, old registration deleted. No
     * prefix is blacklisted - this is a repair, not a wall reaction.
     */
    suspend fun resetKeys(measureEgress: Boolean = true): WarpReg? = actionLock.withLock {
        withContext(Dispatchers.IO) {
            val current = pool.usable()
            val reg = registerNew("reset-keys") ?: run {
                GuardState.log("error", "reset keys: registration failed")
                return@withContext null
            }
            val genBefore = ZappVpnService.generation
            ctx.startService(
                Intent(ctx, ZappVpnService::class.java)
                    .setAction(ZappVpnService.ACTION_START)
                    // This caller earned a forced rebuild: proof stale, a 20 s egress probe
                    // timed out, an underlying network exists, and no handshake in 180 s.
                    // Without the flag the bring-up defers itself against a stuck turn flag
                    // and this whole block below fails at "tunnel did not swap".
                    .putExtra(ZappVpnService.EXTRA_FORCE, true),
            )
            var waited = 0
            while (ZappVpnService.generation == genBefore && waited < 60) {
                Thread.sleep(500)
                waited++
            }
            if (ZappVpnService.generation == genBefore) {
                GuardState.log("error", "reset keys: tunnel did not swap")
                runCatching { CloudflareRegister.delete(reg.id) }
                pool.retire(reg)
                return@withContext null
            }
            // The egress reading here is only useful when the tunnel is already carrying.
            //
            // Measured 2026-11-24 02:48:55, the one-tap repair's own stage log:
            //
            //   02:48:34.452  repair: one-tap started
            //   02:48:55.398  repair: Fix WARP keys ok in 20s     <- this function
            //   02:48:55.402  repair: Reconnect ok in 0s
            //
            // 20 s for a stage whose only real work is one registration POST. The repair
            // pipeline DISCONNECTS the tunnel as stage 1 and only reconnects at stage 3, so
            // `EgressProbe.measure` here can never succeed - it burns its whole timeout plus
            // the 1.2 s settle, and stage 5 then waits for the service's verify loop to take
            // the same measurement properly. `Repair` passes `false`; every other caller keeps
            // the reading.
            if (measureEgress) {
                Thread.sleep(1200)
                val after = runCatching { EgressProbe.measure(ctx) }.getOrNull()
                if (after != null && after.prefix64.isNotBlank()) pool.setEgress(reg, after.prefix64)
                current?.let {
                    if (it.id != reg.id) {
                        runCatching { CloudflareRegister.delete(it.id) }
                        pool.retire(it)
                    }
                }
                pool.persist()
                GuardState.refresh(pool)
                GuardState.log("vpn", "keys renewed -> ${reg.v6.take(14)}... (${after?.warp ?: "unverified"})")
            } else {
                current?.let {
                    if (it.id != reg.id) {
                        runCatching { CloudflareRegister.delete(it.id) }
                        pool.retire(it)
                    }
                }
                pool.persist()
                GuardState.refresh(pool)
                GuardState.log("vpn", "keys renewed -> ${reg.v6.take(14)}... (repair: verify stage owns the reading)")
            }
            reg
        }
    }

    /**
     * The panel's "Deep Reset": clear the burned list and the recorded egress, then rotate
     * once. Their note applies here too - sessions and project data are untouched.
     */
    suspend fun deepReset(): WarpReg? = actionLock.withLock {
        withContext(Dispatchers.IO) {
            pool.clearBlacklist()
            pool.clearEgress()
            GuardState.log("wall", "deep reset: blacklist and recorded egress cleared")
        }
        pauseUntilMs = 0L
        freshWallHops = 0
        WallPause.clearPause(ctx)
        persistEngineState()
        GuardState.setPaused(0L)
        GuardState.refresh(pool)
        hop("deep-reset")
    }

    companion object {
        /** Serialises hops for the whole process (see the field comment above). */
        private val hopMutex = Mutex()

        /**
         * Is a hop or a panel action in flight right now?
         *
         * `avatar_ui._auto_repair_tick:533-534` refuses to touch connectivity while
         * `self._action_lock` is held, so a repair never races the user's own rotation.
         * Theirs is one lock; ours is two, so both are checked.
         */
        fun busy(): Boolean = hopMutex.isLocked || actionLock.isLocked

        /**
         * Distinct /64s that returned `provider.free-limit` inside this window, with the time
         * each was refused.
         *
         * The premise of rotation is that the limit follows the address. That premise is only
         * sometimes true. Measured 2026-11-20 04:26 and 04:37, two fresh prefixes answered a
         * 71-token prompt; measured 05:01-05:06, five more prefixes refused the same 71-token
         * prompt in a row. A refusal that follows the ACCOUNT cannot be hopped away, and the
         * only thing rotation achieves then is spending registrations and making him wait
         * through 27-second verifies that cannot succeed.
         *
         * Three distinct prefixes inside twenty minutes, with no success between them, is
         * enough evidence to say so instead of hunting.
         */
        private val walled64s = java.util.Collections.synchronizedList(ArrayList<Pair<String, Long>>())
        private const val ACCOUNT_WALL_WINDOW_MS = 20 * 60_000L
        private const val ACCOUNT_WALL_DISTINCT = 3
        private const val ACCOUNT_WALL_PAUSE_MS = 10 * 60_000L

        /** Records a refused prefix; true when the evidence now says the limit is the account. */
        @Synchronized
        fun noteWalledPrefix(prefix64: String, nowMs: Long = System.currentTimeMillis()): Boolean {
            if (prefix64.isNotBlank()) {
                walled64s.add(prefix64 to nowMs)
            }
            walled64s.removeAll { nowMs - it.second > ACCOUNT_WALL_WINDOW_MS }
            val distinct = walled64s.map { it.first }.distinct()
            return distinct.size >= ACCOUNT_WALL_DISTINCT
        }

        /** A turn that actually answered clears the case: the account is not capped. */
        @Synchronized
        fun noteAnswered() {
            if (walled64s.isNotEmpty()) walled64s.clear()
        }

        /**
         * Is this /64 already burned?
         *
         * Same rule as the member function below, exposed for the verify path: measured
         * 2026-11-20 03:27:58, a tunnel verified `ok` on `2a09:bac1:5aa0:40::/64` - the exact
         * prefix the wall handler had burned 51 s earlier - and the app then tore the tunnel
         * down and rebuilt it twice. A verified proof on a walled prefix is not a success; it
         * is the same address being handed back.
         */
        fun isBurnedPrefix(context: Context, prefix: String): Boolean {
            val k = prefix.substringBefore('/').split(':').filter { it.isNotBlank() }
                .take(4).joinToString(":")
            if (k.isBlank()) return false
            val prefs = context.getSharedPreferences("zapp_engine", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            return prefs.getStringSet(KEY_BURNED64, emptySet()).orEmpty().any { row ->
                val at = row.indexOf('#')
                if (at <= 0) return@any row == k
                val ts = row.substring(0, at).toLongOrNull() ?: return@any false
                now - ts < BURNED64_TTL_MS && row.substring(at + 1) == k
            }
        }
        /**
         * Drop the whole burn list.
         *
         * The burn list records what THIS app withheld because a refusal arrived on that
         * address. Once the refusal itself expires there is no evidence left against any
         * entry, and keeping them only shrinks a pool WARP already hands out thinly
         * (measured 05:42:56: `tunnel VERIFY ok` on `4ff6:2e1e::/64` immediately followed by
         * `is BURNED - not marking the guard verified` on a prefix burned the same hour).
         *
         * Called at the free tier's own reset instant - 00:00 UTC, measured 2026-11-20 -
         * which is the one moment every recorded burn provably has no cause behind it.
         */
        fun clearBurnHistory(context: Context) {
            val prefs = context.getSharedPreferences("zapp_engine", Context.MODE_PRIVATE)
            val had = prefs.getStringSet(KEY_BURNED64, emptySet()).orEmpty().size
            prefs.edit().remove(KEY_BURNED64).apply()
            GuardState.log("wall", "burn history cleared ($had /64) after the budget reset")
        }

        /** Serialises the panel's repair actions against each other. */
        private val actionLock = Mutex()

        /** One-liner hop from anywhere (app UI, guard service). */
        suspend fun hopNow(context: Context, reason: String, banForMs: Long? = null): WarpReg? =
            RotationEngine(context).hop(reason, banForMs)

        // their REWALL_SECONDS / FREE_WALL_HOPS_MAX shape
        const val REWALL_MS = 180_000L
        const val FREE_WALL_HOPS_MAX = 2

        /** Wall hops permitted inside one backoff window before the pause is honoured. */
        const val WALL_BURST_MAX = 4
        const val WALL_BURST_WINDOW_MS = 10 * 60_000L

        /**
         * How often a refusal measured outside the tunnel may trigger a rebuild.
         *
         * Avatar's `_REPAIR_INTERVAL` is 20 s because its repair is a `warp connect` on a
         * host. Ours replaces the whole WireGuard device, which cuts every live stream, so
         * one request per two minutes is the honest ceiling.
         */
        const val FALLBACK_REPAIR_COOLDOWN_MS = 120_000L

        /**
         * Their `zen_guard.MAX_HOP_TRIES` - 8, read from `zen_guard.py:25` on 2026-11-20.
         *
         * This said 4 and cited theirs as 4. It is not: their loop is
         * `for attempt in range(1, MAX_HOP_TRIES + 1)`, eight full `hop_once` cycles before
         * they give up and recycle. On this phone a single attempt costs ~2 s now (verify
         * 63 s -> 1.9 s, build B7B7F03F), so matching their depth is cheaper than stopping
         * early and leaving him walled.
         */
        const val HOP_TRIES = 8

        /**
         * Minimum spacing between Tor circuit rotations.
         *
         * A NEWNYM is free (no finite /64 pool), so without this every wall became an
         * instant "hop succeeded" and the auto-resume re-sent 200 ms later. Measured
         * 2026-11-20 00:55-00:56: dozens of 'continue' sends inside one minute.
         */
        const val TOR_HOP_COOLDOWN_MS = 75_000L

        /** Edge re-rolls on one identity before paying for a new registration. */
        const val EDGE_CHANGES = 3

        /** After every reachable prefix came back walled. */
        const val EXHAUSTED_BACKOFF_MS = 10 * 60_000L

        /** Persisted set of walled /64s; see [noteBurned]. New key: the old /48 rows stay ignored. */
        private const val KEY_BURNED64 = "burned64"

        /** Their `zen_guard.BLACKLIST_MAX = 200`, applied to /64 rows. */
        private const val BURNED64_MAX = 200
        /**
         * How long a refused /64 is withheld.
         *
         * Was 12 hours, copied from the server's own `retryAfterMs` (~10h 46m). That is the
         * ACCOUNT's deadline, not the address's, and on this location it deadlocked rotation
         * completely: WARP hands out only a handful of /64s, every one of them was burned
         * within minutes, and with a 12-hour TTL the app then refused every prefix WARP could
         * ever give it. Measured 05:42:56 - `tunnel VERIFY ok` on `4ff6:2e1e::/64`, followed by
         * `is BURNED - not marking the guard verified`, on a prefix burned earlier the same
         * hour.
         *
         * The measured recovery: 2026-11-19 23:36 refusal -> 23:40 the SAME prompt answered on
         * the SAME prefix, four minutes later. `WarpPool.BAN_TTL_MS` already uses five minutes
         * for exactly that reason; this now matches it so the two lists cannot disagree.
         */
        private const val BURNED64_TTL_MS = 5 * 60_000L

        /**
         * Kept for reference only - the detector is now [QuotaWall]. These are the
         * product's own quota strings, so a caller that still greps text is not guessing.
         */
        @Deprecated("use QuotaWall.looksLikeWallText")
        val WALL_SIGNATURES = listOf(
            "free usage limit reached",
            "workspace usage limit reached",
            "you've reached the spend limit for this model",
            "usage limit reached",
            "freeusagelimiterror",
            "budgetlimiterror",
            "gousagelimiterror",
            "quota exceeded",
            "insufficient_quota",
        )

        private fun nextUtcMidnight(now: Long): Long {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.timeInMillis = now
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}
