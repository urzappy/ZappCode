package com.zapp.net.guard

import android.content.Context
import android.content.SharedPreferences

/**
 * The persisted side of `zen_guard._note_wall` - the decision itself is [WallCore].
 *
 * Their guard keeps one `state` dict on disk and their tests call `_note_wall(state, now)`
 * directly. Keeping those two roles apart here is what makes the port checkable rather
 * than merely plausible: `WallCoreTest` runs their own two assertions against the same
 * code the app uses.
 *
 * Why the pause matters on this device: every rotation rebuilds the WireGuard device,
 * which cuts every live stream Termux holds. Rotating in a loop against a limit that is
 * not following the address is what made the app feel random - it was tearing its own
 * sockets down every 75 s and never recovering.
 *
 * This is a ROTATION pause, never a send gate: `hop_now` (= our "manual" reason) clears
 * it on the spot (`avatar_ui.py:1535-1538`), and no code path here refuses a prompt.
 */
object WallPause {

    /** their `REWALL_SECONDS`, in ms */
    const val REWALL_MS = WallCore.REWALL_SECONDS * 1000L

    /** their `FAILED_HOPS_MAX` */
    const val FAILED_HOPS_MAX = WallCore.FAILED_HOPS_MAX

    /** their `BACKOFF_SECONDS` */
    const val BACKOFF_MS = WallCore.BACKOFF_SECONDS * 1000L

    /** their `FREE_WALL_HOPS_MAX` */
    const val FREE_WALL_HOPS_MAX = WallCore.FREE_WALL_HOPS_MAX

    /** their `COOLDOWN_SECONDS` */
    const val COOLDOWN_MS = 75_000L

    // Same store the engine already uses, so no new file format.
    private const val PREFS = "zapp_engine"
    private const val K_LAST_HOP = "wp_lastHop"
    private const val K_LAST_OK = "wp_lastOkHop"
    private const val K_LAST_FREE = "wp_lastFreeWall"
    private const val K_FREE_HOPS = "wp_freeWallHops"
    private const val K_FREE_COUNTED = "wp_freeWallCounted"
    private const val K_FREE_UNTIL = "wp_freeWallUntil"
    private const val K_HOP_COUNTED = "wp_hopCounted"
    private const val K_FAILED = "wp_failedHops"
    private const val K_BACKOFF = "wp_backoffUntil"
    private const val K_BACKOFF_LOGGED = "wp_backoffLogged"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Read a long that an earlier build may have stored as an int.
     *
     * `SharedPreferencesImpl.getLong` throws `ClassCastException` on an int value, and the
     * throw happens on whatever coroutine touched the wall path - on 2026-11-20 at 10:14
     * that killed the app three times in a row (`WallPause.kt:127`, `wp_hopCounted` was
     * written with `putInt` by the two reset paths above). The stored value is adopted and
     * rewritten as a long, so the bad type is gone after one read.
     */
    private fun longOf(sp: SharedPreferences, key: String): Long = try {
        sp.getLong(key, 0L)
    } catch (_: ClassCastException) {
        val v = runCatching { sp.getInt(key, 0).toLong() }.getOrDefault(0L)
        sp.edit().putLong(key, v).apply()
        v
    }

    // ------------------------------------------------------------------ writers

    /** A `FreeUsageLimitError` was seen. Their `state["last_free_wall"] = wall_seen`. */
    fun noteFreeWall(ctx: Context, atMs: Long = System.currentTimeMillis()) {
        prefs(ctx).edit().putLong(K_LAST_FREE, atMs).apply()
    }

    /** A rotation attempt started. Their `last_hop` (set even when the attempt fails). */
    fun noteHopAttempt(ctx: Context, atMs: Long = System.currentTimeMillis()) {
        prefs(ctx).edit().putLong(K_LAST_HOP, atMs).apply()
    }

    /** A rotation produced a *confirmed* new address. Their `last_ok_hop` success block. */
    fun noteOkHop(ctx: Context, atMs: Long = System.currentTimeMillis()) {
        prefs(ctx).edit()
            .putLong(K_LAST_HOP, atMs)
            .putLong(K_LAST_OK, atMs)
            .putInt(K_FAILED, 0)
            .putLong(K_BACKOFF, 0L)
            .putLong(K_BACKOFF_LOGGED, 0L)
            .putLong(K_HOP_COUNTED, 0L)
            .apply()
        GuardState.setPaused(0L)
    }

    /** Their `hop_now`: "A manual rotation is an explicit request to try again now." */
    fun clearPause(ctx: Context) {
        prefs(ctx).edit()
            .putLong(K_BACKOFF, 0L)
            .putInt(K_FAILED, 0)
            .putLong(K_HOP_COUNTED, 0L)
            .apply()
        GuardState.setPaused(0L)
    }

    // ------------------------------------------------------------------ readers

    fun lastHopAttemptMs(ctx: Context): Long = longOf(prefs(ctx), K_LAST_HOP)

    /**
     * The last *proven* hop. This is the only hop timestamp that survives a restart, so it
     * is what the panel's "Last hop" must show - `GuardState.lastHopMs` alone reads 0 after
     * every process start and printed `never` despite a confirmed hop 35 s earlier.
     */
    fun lastOkHopMs(ctx: Context): Long = longOf(prefs(ctx), K_LAST_OK)

    fun pausedUntilMs(ctx: Context): Long = longOf(prefs(ctx), K_BACKOFF)

    fun freeWallUntilMs(ctx: Context): Long = longOf(prefs(ctx), K_FREE_UNTIL)

    /** `state["last_free_wall"]` — proof that a free-tier wall has been seen at least once. */
    fun lastFreeWallMs(ctx: Context): Long = longOf(prefs(ctx), K_LAST_FREE)

    /** `state["failed_hops"]`, for the panel. */
    fun failedHops(ctx: Context): Int = prefs(ctx).getInt(K_FAILED, 0)

    /** `state["free_wall_hops"]`, for the panel. */
    fun freeWallHops(ctx: Context): Int = prefs(ctx).getInt(K_FREE_HOPS, 0)

    // ------------------------------------------------------------------ the port

    /**
     * `zen_guard._note_wall`, through the persisted state.
     *
     * @param isFreeLimit the refusal came from the free tier (`FreeUsageLimitError`).
     * @return true while auto-rotation is paused - the caller must NOT rotate.
     */
    fun noteWall(
        ctx: Context,
        now: Long = System.currentTimeMillis(),
        isFreeLimit: Boolean,
    ): Boolean {
        val sp = prefs(ctx)
        // theirs records the wall at detection time, then `_note_wall` reads it
        if (isFreeLimit) sp.edit().putLong(K_LAST_FREE, now).apply()

        val state = WallCore.State(
            backoffUntil = longOf(sp, K_BACKOFF),
            failedHops = sp.getInt(K_FAILED, 0),
            freeWallHops = sp.getInt(K_FREE_HOPS, 0),
            freeWallCounted = longOf(sp, K_FREE_COUNTED),
            freeWallUntil = longOf(sp, K_FREE_UNTIL),
            hopCounted = longOf(sp, K_HOP_COUNTED),
            backoffLogged = longOf(sp, K_BACKOFF_LOGGED),
            lastOkHop = longOf(sp, K_LAST_OK),
            lastFreeWall = longOf(sp, K_LAST_FREE),
        )
        val decision = WallCore.noteWall(state, now)
        sp.edit()
            .putLong(K_BACKOFF, state.backoffUntil)
            .putInt(K_FAILED, state.failedHops)
            .putInt(K_FREE_HOPS, state.freeWallHops)
            .putLong(K_FREE_COUNTED, state.freeWallCounted)
            .putLong(K_FREE_UNTIL, state.freeWallUntil)
            .putLong(K_HOP_COUNTED, state.hopCounted)
            .putLong(K_BACKOFF_LOGGED, state.backoffLogged)
            .apply()
        for (line in decision.logs) GuardState.log("wall", line)
        GuardState.setPaused(if (state.backoffUntil > now) state.backoffUntil else 0L)
        return decision.paused
    }

    /** `zen_guard._next_utc_midnight`. */
    fun nextUtcMidnight(now: Long): Long = WallCore.nextUtcMidnight(now)

    /** their `strftime("%H:%M UTC")` */
    fun utcHhmm(ms: Long): String = WallCore.utcHhmm(ms)
}
