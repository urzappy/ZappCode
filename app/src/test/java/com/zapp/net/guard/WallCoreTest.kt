package com.zapp.net.guard

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `tests/test_reliability.py::FreeWallTests`, ported assertion for assertion.
 *
 * Their tests call the pure function with a state dict; ours call [WallCore] with the
 * same fields. Nothing here touches Android, so these run on the JVM and cannot pass by
 * accident of a device being in a particular state.
 */
class WallCoreTest {

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(y, mo - 1, d, h, mi, s)
        return c.timeInMillis
    }

    /** `test_reset_is_the_next_utc_midnight` */
    @Test
    fun resetIsTheNextUtcMidnight() {
        val now = utc(2026, 11, 17, 12, 29)
        assertEquals(utc(2026, 11, 18, 0, 0), WallCore.nextUtcMidnight(now))
    }

    /** `test_two_fresh_addresses_that_hit_the_free_wall_pause_until_reset` */
    @Test
    fun twoFreshAddressesThatHitTheFreeWallPauseUntilReset() {
        val now = utc(2026, 11, 17, 12, 0)
        val state = WallCore.State()
        state.lastOkHop = now - 30_000L
        state.lastFreeWall = now - 5_000L

        assertFalse(WallCore.noteWall(state, now).paused)
        assertEquals(1, state.freeWallHops)

        state.lastOkHop = now + 100_000L
        state.lastFreeWall = now + 105_000L
        assertTrue(WallCore.noteWall(state, now + 110_000L).paused)

        assertEquals(WallCore.nextUtcMidnight(now + 110_000L), state.freeWallUntil)
        assertEquals(state.freeWallUntil, state.backoffUntil)
    }

    /** Their `FREE_WALL_HOPS_MAX` is 2: one fresh wall is not enough evidence. */
    @Test
    fun aSingleFreshFreeWallDoesNotPause() {
        val now = utc(2026, 11, 17, 9, 0)
        val state = WallCore.State()
        state.lastOkHop = now - 10_000L
        state.lastFreeWall = now - 1_000L
        assertFalse(WallCore.noteWall(state, now).paused)
        assertEquals(1, state.freeWallHops)
        assertEquals(0L, state.backoffUntil)
    }

    /** Their step 3/6: two failed hops without a free-tier wall become a 2 min backoff. */
    @Test
    fun twoFailedHopsBecomeATwoMinuteBackoff() {
        val now = utc(2026, 11, 17, 9, 0)
        val state = WallCore.State()
        state.lastOkHop = now - 5_000L
        WallCore.noteWall(state, now)
        assertEquals(1, state.failedHops)

        state.lastOkHop = now + 10_000L
        val d = WallCore.noteWall(state, now + 20_000L)
        assertEquals(2, state.failedHops)
        assertEquals(now + 20_000L + WallCore.BACKOFF_SECONDS * 1000L, state.backoffUntil)
        assertTrue(d.paused)
    }

    /** Their step 1: once the pause expires, everything it counted is spent evidence. */
    @Test
    fun anExpiredPauseClearsItsCounters() {
        val now = utc(2026, 11, 17, 9, 0)
        val state = WallCore.State()
        state.backoffUntil = now - 1_000L
        state.failedHops = 2
        state.freeWallHops = 2
        state.freeWallUntil = now - 1_000L
        val d = WallCore.noteWall(state, now)
        assertEquals(0, state.failedHops)
        assertEquals(0, state.freeWallHops)
        assertEquals(0L, state.freeWallUntil)
        assertFalse(d.paused)
    }

    /** Their step 2: while paused the answer is always "do not rotate". */
    @Test
    fun aLivePauseAlwaysAnswersDoNotRotate() {
        val now = utc(2026, 11, 17, 9, 0)
        val state = WallCore.State()
        state.backoffUntil = now + 60 * 60_000L
        // a real pause always records when it started; a hand-built one with 0 would log
        // immediately, which is their behaviour too
        state.backoffLogged = now
        assertTrue(WallCore.noteWall(state, now).paused)
        val again = WallCore.noteWall(state, now + 5_000L)
        assertTrue(again.paused)
        assertEquals(emptyList<String>(), again.logs)
        // one minute later it is allowed to say so once
        assertEquals(1, WallCore.noteWall(state, now + 65_000L).logs.size)
    }
}
