package com.zapp.net.ui

import com.zapp.net.notify.SoundAssets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `settings/general/sound-preview-machine.ts`, one consequence at a time.
 *
 * The machine is written as a reducer, so every test here is `transition(state, event)` and then a
 * look at the effects. The two ordering rules that are easy to get wrong get their own tests: a
 * late `load.succeeded` must still stop the sound it brought, and it must do so even after
 * `disposed`.
 */
class SoundPreviewTest {

    private val m = SoundPreviewMachine

    private fun play(id: String?) = SoundPreviewMachine.Event.PlayRequested(id)

    private fun stop() = SoundPreviewMachine.Event.StopRequested

    private fun armed(id: String = "alert-01"): SoundPreviewMachine.State =
        m.transition(m.initial(), play(id)).state

    private fun loading(id: String = "alert-01"): SoundPreviewMachine.State {
        val s = armed(id)
        return m.transition(s, SoundPreviewMachine.Event.DebounceElapsed(s.attempt)).state
    }

    // ---- the debounce -------------------------------------------------------------------------

    @Test
    fun `a fresh machine is idle at attempt zero`() {
        assertEquals(SoundPreviewMachine.State.Idle(0), m.initial())
    }

    @Test
    fun `playing arms their 100 ms debounce and nothing else`() {
        val t = m.transition(m.initial(), play("alert-01"))
        assertEquals(SoundPreviewMachine.State.Debouncing(1, "alert-01"), t.state)
        assertEquals(
            listOf(SoundPreviewMachine.Effect.TimerStart(SoundPreviewMachine.TimerKind.DEBOUNCE, 1, 100L)),
            t.effects,
        )
    }

    @Test
    fun `moving to another sound bumps the attempt and re-arms the timer`() {
        val t = m.transition(armed("alert-01"), play("nope-03"))
        assertEquals(SoundPreviewMachine.State.Debouncing(2, "nope-03"), t.state)
        // Nothing was playing, so there is nothing to stop - only the new timer.
        assertEquals(listOf(SoundPreviewMachine.Effect.TimerStart(SoundPreviewMachine.TimerKind.DEBOUNCE, 2, 100L)), t.effects)
    }

    @Test
    fun `an elapsed timer from a superseded attempt is ignored`() {
        val t = m.transition(armed("alert-01"), SoundPreviewMachine.Event.DebounceElapsed(99))
        assertEquals(armed("alert-01"), t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `the debounce elapsing starts the load and the five second leash`() {
        val s = armed("alert-01")
        val t = m.transition(s, SoundPreviewMachine.Event.DebounceElapsed(1))
        assertEquals(SoundPreviewMachine.State.Loading(1, "alert-01"), t.state)
        assertEquals(
            listOf(
                SoundPreviewMachine.Effect.AudioLoad(1, "alert-01"),
                SoundPreviewMachine.Effect.TimerStart(SoundPreviewMachine.TimerKind.LOAD, 1, 5_000L),
            ),
            t.effects,
        )
    }

    // ---- the load -----------------------------------------------------------------------------

    @Test
    fun `a load that succeeds plays and drops the leash`() {
        val s = loading()
        val active = SoundPreviewMachine.Stop {}
        val t = m.transition(s, SoundPreviewMachine.Event.LoadSucceeded(1, active))
        assertEquals(SoundPreviewMachine.State.Playing(1, "alert-01", active), t.state)
        assertEquals(listOf(SoundPreviewMachine.Effect.TimerStop), t.effects)
    }

    @Test
    fun `a load that succeeds with nothing playable returns to idle on the same attempt`() {
        val t = m.transition(loading(), SoundPreviewMachine.Event.LoadSucceeded(1, null))
        assertEquals(SoundPreviewMachine.State.Idle(1), t.state)
        assertEquals(listOf(SoundPreviewMachine.Effect.TimerStop), t.effects)
    }

    @Test
    fun `a failed load returns to idle on the same attempt`() {
        val t = m.transition(loading(), SoundPreviewMachine.Event.LoadFailed(1))
        assertEquals(SoundPreviewMachine.State.Idle(1), t.state)
        assertEquals(listOf(SoundPreviewMachine.Effect.TimerStop), t.effects)
    }

    @Test
    fun `the leash firing returns to idle without cancelling itself`() {
        val t = m.transition(loading(), SoundPreviewMachine.Event.LoadTimedOut(1))
        assertEquals(SoundPreviewMachine.State.Idle(1), t.state)
        assertEquals(listOf(SoundPreviewMachine.Effect.TimerStop), t.effects)
    }

    @Test
    fun `a load that arrives after it was superseded is stopped as it arrives`() {
        // Their line 50: the sound is silenced even though the state does not move. Without this
        // a slow file plays over the one the user actually chose.
        val active = SoundPreviewMachine.Stop {}
        val s = armed("nope-03")            // attempt 2, debouncing
        val t = m.transition(s, SoundPreviewMachine.Event.LoadSucceeded(1, active))
        assertEquals(s, t.state)
        assertEquals(listOf(SoundPreviewMachine.Effect.AudioStop(active)), t.effects)
    }

    @Test
    fun `a late load with nothing to stop is a no-op`() {
        val t = m.transition(armed(), SoundPreviewMachine.Event.LoadSucceeded(1, null))
        assertEquals(armed(), t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `a load that arrives after dispose is still stopped`() {
        // The ordering quirk: their `load.succeeded` guard runs *before* the `disposed` guard, so
        // an unmounted page cannot leak a sound that was already in flight.
        val active = SoundPreviewMachine.Stop {}
        val disposed = m.transition(loading(), SoundPreviewMachine.Event.Disposed).state
        assertEquals(SoundPreviewMachine.State.Disposed, disposed)
        val t = m.transition(disposed, SoundPreviewMachine.Event.LoadSucceeded(1, active))
        assertEquals(SoundPreviewMachine.State.Disposed, t.state)
        assertEquals(listOf(SoundPreviewMachine.Effect.AudioStop(active)), t.effects)
    }

    @Test
    fun `a load outcome from an older attempt is ignored`() {
        val s = loading()
        val t = m.transition(s, SoundPreviewMachine.Event.LoadFailed(7))
        assertEquals(s, t.state)
        assertTrue(t.effects.isEmpty())
    }

    // ---- stopping -----------------------------------------------------------------------------

    @Test
    fun `stopping a playing sound bumps the attempt and stops it`() {
        val active = SoundPreviewMachine.Stop {}
        val playing = m.transition(loading(), SoundPreviewMachine.Event.LoadSucceeded(1, active)).state
        val t = m.transition(playing, stop())
        assertEquals(SoundPreviewMachine.State.Idle(2), t.state)
        assertEquals(listOf(SoundPreviewMachine.Effect.AudioStop(active)), t.effects)
    }

    @Test
    fun `stopping an idle machine does nothing at all`() {
        val t = m.transition(SoundPreviewMachine.State.Idle(3), stop())
        assertEquals(SoundPreviewMachine.State.Idle(3), t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `stopping mid-debounce drops the timer`() {
        val t = m.transition(armed(), stop())
        assertEquals(SoundPreviewMachine.State.Idle(2), t.state)
        assertEquals(listOf(SoundPreviewMachine.Effect.TimerStop), t.effects)
    }

    @Test
    fun `playing nothing is the same as stopping`() {
        val active = SoundPreviewMachine.Stop {}
        val playing = m.transition(loading(), SoundPreviewMachine.Event.LoadSucceeded(1, active)).state
        val t = m.transition(playing, play(null))
        assertEquals(SoundPreviewMachine.State.Idle(2), t.state)
        assertEquals(listOf(SoundPreviewMachine.Effect.AudioStop(active)), t.effects)
    }

    @Test
    fun `choosing a new sound while one plays stops the old one first`() {
        val active = SoundPreviewMachine.Stop {}
        val playing = m.transition(loading(), SoundPreviewMachine.Event.LoadSucceeded(1, active)).state
        val t = m.transition(playing, play("yup-02"))
        assertEquals(SoundPreviewMachine.State.Debouncing(2, "yup-02"), t.state)
        assertEquals(
            listOf(
                SoundPreviewMachine.Effect.AudioStop(active),
                SoundPreviewMachine.Effect.TimerStart(SoundPreviewMachine.TimerKind.DEBOUNCE, 2, 100L),
            ),
            t.effects,
        )
    }

    // ---- dispose ------------------------------------------------------------------------------

    @Test
    fun `disposing mid-debounce drops the timer`() {
        val t = m.transition(armed(), SoundPreviewMachine.Event.Disposed)
        assertEquals(SoundPreviewMachine.State.Disposed, t.state)
        assertEquals(listOf(SoundPreviewMachine.Effect.TimerStop), t.effects)
    }

    @Test
    fun `disposing while playing stops the sound`() {
        val active = SoundPreviewMachine.Stop {}
        val playing = m.transition(loading(), SoundPreviewMachine.Event.LoadSucceeded(1, active)).state
        val t = m.transition(playing, SoundPreviewMachine.Event.Disposed)
        assertEquals(SoundPreviewMachine.State.Disposed, t.state)
        assertEquals(listOf(SoundPreviewMachine.Effect.AudioStop(active)), t.effects)
    }

    @Test
    fun `a disposed machine ignores every later event`() {
        val disposed = m.transition(armed(), SoundPreviewMachine.Event.Disposed).state
        for (e in listOf(play("alert-02"), stop(), SoundPreviewMachine.Event.DebounceElapsed(1), SoundPreviewMachine.Event.LoadFailed(1))) {
            val t = m.transition(disposed, e)
            assertEquals("event $e moved a disposed machine", SoundPreviewMachine.State.Disposed, t.state)
            assertTrue("event $e had effects", t.effects.isEmpty())
        }
    }

    // ---- the catalogue ------------------------------------------------------------------------

    @Test
    fun `every offered sound has a file, and every file is offered`() {
        // The id is theirs: `bip-bop-01`, not `bipbop-01`. Getting this wrong stored a value no
        // table could resolve, which is how the hyphen bug survived.
        val offered = SettingsCopy.soundOptions.map { it.first }.filter { it != "none" }
        assertEquals(45, offered.size)
        assertEquals(offered.sorted(), SoundAssets.ids.sorted())
        for (id in offered) {
            assertTrue("no asset for $id", SoundAssets.res(id) != null)
        }
        assertNull(SoundAssets.res("none"))
        assertNull(SoundAssets.res(null))
        assertTrue(offered.none { it.startsWith("bipbop-") })
    }

    @Test
    fun `their label order is kept`() {
        // alert, bip-bop, staplebops, nope, yup - `nQt` order, not alphabetical.
        val offered = SettingsCopy.soundOptions.map { it.first }
        assertEquals("none", offered.first())
        assertEquals("alert-01", offered[1])
        val firstStaple = offered.indexOfFirst { it.startsWith("staplebops-") }
        val firstNope = offered.indexOfFirst { it.startsWith("nope-") }
        assertTrue(firstStaple < firstNope)
    }
}
