package com.zapp.net.guard

/**
 * `zen_guard._note_wall` as a pure function, which is how their own tests call it:
 *
 *     state = zen_guard._default_state()
 *     state["last_ok_hop"] = now - 30
 *     state["last_free_wall"] = now - 5
 *     zen_guard._note_wall(state, now)
 *
 * Their `state` is a plain dict and `_note_wall` mutates it; this is the same shape with
 * the same field names, so the port can be checked against the same assertions instead
 * of being "trusted". Android types stay out of this file on purpose - `WallPause` owns
 * the SharedPreferences mapping, this owns the decision.
 */
object WallCore {

    /** their `REWALL_SECONDS` */
    const val REWALL_SECONDS = 180L

    /** their `FAILED_HOPS_MAX` */
    const val FAILED_HOPS_MAX = 2

    /** their `BACKOFF_SECONDS` */
    const val BACKOFF_SECONDS = 2 * 60L

    /** their `FREE_WALL_HOPS_MAX` */
    const val FREE_WALL_HOPS_MAX = 2

    // Their state file stores epoch SECONDS; every clock in this app is milliseconds, so
    // the conversions live here, once, instead of being forgotten at a call site.
    private const val REWALL_MS = REWALL_SECONDS * 1000L
    private const val BACKOFF_MS = BACKOFF_SECONDS * 1000L

    /** `zen_guard._default_state:64-85`, same keys and same zero defaults. */
    data class State(
        var backoffUntil: Long = 0L,
        var failedHops: Int = 0,
        var freeWallHops: Int = 0,
        var freeWallCounted: Long = 0L,
        var freeWallUntil: Long = 0L,
        var hopCounted: Long = 0L,
        var backoffLogged: Long = 0L,
        var lastOkHop: Long = 0L,
        var lastFreeWall: Long = 0L,
    )

    /**
     * `_note_wall:153-231`.
     *
     * @return the pause answer plus the lines their `append_log` would have written, in
     *         their order. `paused == true` means the caller must not rotate or resume.
     */
    data class Decision(val paused: Boolean, val logs: List<String>)

    fun noteWall(state: State, now: Long): Decision {
        val logs = ArrayList<String>(4)

        // 1. the pause expired -> everything it counted is spent evidence
        if (state.backoffUntil != 0L && now >= state.backoffUntil) {
            state.backoffUntil = 0L
            state.failedHops = 0
            state.freeWallHops = 0
            state.freeWallUntil = 0L
            logs.add("rotation pause is over - trying again on the next limit")
        }

        // 2. still paused: say so at most once a minute, and do not rotate
        if (now < state.backoffUntil) {
            if (now - state.backoffLogged >= 60_000L) {
                state.backoffLogged = now
                val left = maxOf(0L, (state.backoffUntil - now) / 60_000L)
                logs.add(
                    "limit seen again, but auto-rotation is paused for ~$left more min " +
                        "(new IPs kept hitting the limit immediately)",
                )
            }
            return Decision(true, logs)
        }

        // 3. did the last address last long enough to be trusted?
        val lastHop = state.lastOkHop
        if (lastHop != 0L && now - lastHop <= REWALL_MS) {
            if (state.hopCounted != lastHop) {
                state.hopCounted = lastHop
                state.failedHops += 1
                logs.add(
                    "limit came back ${(now - lastHop) / 1000}s after the last IP change " +
                        "(failed attempt ${state.failedHops} of $FAILED_HOPS_MAX)",
                )
            }
        } else if (lastHop != 0L) {
            // the previous address lasted long enough: this is not a rotation loop
            state.failedHops = 0
        }

        // 4. the free tier's own bucket: a wall right after a confirmed new address
        val freeWall = state.lastFreeWall
        val freshFree = lastHop != 0L && freeWall != 0L &&
            freeWall - lastHop in 0..REWALL_MS
        if (freshFree) {
            if (state.freeWallCounted != lastHop) {
                state.freeWallCounted = lastHop
                state.freeWallHops += 1
                logs.add(
                    "free limit came back ${(freeWall - lastHop) / 1000}s after a confirmed new " +
                        "IPv6 address (${state.freeWallHops} of $FREE_WALL_HOPS_MAX)",
                )
            }
        } else {
            state.freeWallHops = 0
        }

        // 5. two confirmed fresh addresses refused: the allowance is not following the IP
        if (state.freeWallHops >= FREE_WALL_HOPS_MAX) {
            val until = nextUtcMidnight(now)
            state.freeWallUntil = until
            state.backoffUntil = until
            state.backoffLogged = now
            state.hopCounted = lastHop
            logs.add(
                "free limit is not following the IP: $FREE_WALL_HOPS_MAX confirmed new IPv6 " +
                    "addresses hit it again. Pausing rotation until ${utcHhmm(until)}. " +
                    "Another provider, OpenCode Go, or pressing Try now is the way forward.",
            )
            return Decision(true, logs)
        }

        // 6. a short backoff when fresh addresses keep failing for another reason
        if (state.failedHops >= FAILED_HOPS_MAX) {
            state.backoffUntil = now + BACKOFF_MS
            state.backoffLogged = now
            logs.add(
                "pausing auto-rotation for ${BACKOFF_SECONDS / 60} min: the last " +
                    "$FAILED_HOPS_MAX new addresses hit the limit again immediately. " +
                    "It will try one more address by itself after that, or press Rotate /64 Now.",
            )
        }
        return Decision(now < state.backoffUntil, logs)
    }

    /** `zen_guard._next_utc_midnight:146-150`, same truncation to the second. */
    fun nextUtcMidnight(nowMs: Long): Long {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = nowMs
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** their `strftime("%H:%M UTC")` */
    fun utcHhmm(ms: Long): String {
        val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(ms)) + " UTC"
    }
}
