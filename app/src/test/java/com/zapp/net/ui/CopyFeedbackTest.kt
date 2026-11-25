package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `session-ui/src/components/copy-machine.ts` — the copy button's feedback table.
 *
 * The interesting cases are the ones a bare `var copied` gets wrong: a second tap inside the
 * window, a stale attempt's late reply, and the 2 000 ms window itself.
 */
class CopyFeedbackTest {

    private fun run(vararg events: CopyFeedback.Event): CopyFeedback.Transition {
        var state = CopyFeedback.INITIAL
        var last = CopyFeedback.Transition(state, emptyList())
        for (e in events) {
            last = CopyFeedback.transition(state, e)
            state = last.state
        }
        return last
    }

    @Test
    fun `the feedback window is two seconds`() {
        assertEquals(2_000, CopyFeedback.MS)
    }

    @Test
    fun `a first copy writes and shows nothing yet`() {
        val t = CopyFeedback.transition(CopyFeedback.INITIAL, CopyFeedback.Event.Requested("x"))
        assertEquals(CopyFeedback.State.Copying(1, copied = false), t.state)
        assertEquals(listOf<CopyFeedback.Effect>(CopyFeedback.Effect.Write(1, "x")), t.effects)
        assertFalse(CopyFeedback.copied(t.state))
    }

    @Test
    fun `an empty copy is not an attempt`() {
        val t = CopyFeedback.transition(CopyFeedback.INITIAL, CopyFeedback.Event.Requested(""))
        assertEquals(CopyFeedback.INITIAL, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `success shows the checkmark and schedules the reset`() {
        val t = run(
            CopyFeedback.Event.Requested("x"),
            CopyFeedback.Event.Succeeded(1),
        )
        assertEquals(CopyFeedback.State.Copied(1), t.state)
        assertEquals(
            listOf<CopyFeedback.Effect>(CopyFeedback.Effect.ScheduleReset(1, 2_000)),
            t.effects,
        )
        assertTrue(CopyFeedback.copied(t.state))
    }

    @Test
    fun `a second tap inside the window keeps the checkmark and cancels the timer`() {
        val t = run(
            CopyFeedback.Event.Requested("x"),
            CopyFeedback.Event.Succeeded(1),
            CopyFeedback.Event.Requested("y"),
        )
        assertEquals(CopyFeedback.State.Copying(2, copied = true), t.state)
        assertEquals(
            listOf<CopyFeedback.Effect>(CopyFeedback.Effect.ClearReset, CopyFeedback.Effect.Write(2, "y")),
            t.effects,
        )
        assertTrue(CopyFeedback.copied(t.state))
    }

    @Test
    fun `a second tap while still copying does not show the checkmark early`() {
        val t = run(
            CopyFeedback.Event.Requested("x"),
            CopyFeedback.Event.Requested("y"),
        )
        assertEquals(CopyFeedback.State.Copying(2, copied = false), t.state)
    }

    @Test
    fun `a stale success is ignored`() {
        val t = run(
            CopyFeedback.Event.Requested("x"),
            CopyFeedback.Event.Requested("y"),
            CopyFeedback.Event.Succeeded(1),
        )
        assertEquals(CopyFeedback.State.Copying(2, copied = false), t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `a failure drops the button back to idle`() {
        val t = run(
            CopyFeedback.Event.Requested("x"),
            CopyFeedback.Event.Failed(1),
        )
        assertEquals(CopyFeedback.State.Idle(1), t.state)
        assertFalse(CopyFeedback.copied(t.state))
    }

    @Test
    fun `a failure after success is ignored`() {
        val t = run(
            CopyFeedback.Event.Requested("x"),
            CopyFeedback.Event.Succeeded(1),
            CopyFeedback.Event.Failed(1),
        )
        assertEquals(CopyFeedback.State.Copied(1), t.state)
    }

    @Test
    fun `the reset timer clears the checkmark`() {
        val t = run(
            CopyFeedback.Event.Requested("x"),
            CopyFeedback.Event.Succeeded(1),
            CopyFeedback.Event.ResetDue(1),
        )
        assertEquals(CopyFeedback.State.Idle(1), t.state)
        assertFalse(CopyFeedback.copied(t.state))
    }

    @Test
    fun `a reset that fires while copying is ignored`() {
        val t = run(
            CopyFeedback.Event.Requested("x"),
            CopyFeedback.Event.Requested("y"),
            CopyFeedback.Event.ResetDue(1),
        )
        assertEquals(CopyFeedback.State.Copying(2, copied = false), t.state)
    }

    @Test
    fun `disposal carries the attempt count forward`() {
        val t = run(
            CopyFeedback.Event.Requested("x"),
            CopyFeedback.Event.Succeeded(1),
            CopyFeedback.Event.Disposed,
        )
        assertEquals(CopyFeedback.State.Idle(1), t.state)
        assertEquals(listOf<CopyFeedback.Effect>(CopyFeedback.Effect.ClearReset), t.effects)
    }

    @Test
    fun `the next copy after disposal is a new attempt`() {
        val t = run(
            CopyFeedback.Event.Requested("x"),
            CopyFeedback.Event.Succeeded(1),
            CopyFeedback.Event.Disposed,
            CopyFeedback.Event.Requested("z"),
        )
        assertEquals(CopyFeedback.State.Copying(2, copied = false), t.state)
    }

    @Test
    fun `idle ignores everything but a request`() {
        for (e in listOf(
            CopyFeedback.Event.Succeeded(1),
            CopyFeedback.Event.Failed(1),
            CopyFeedback.Event.ResetDue(1),
        )) {
            val t = CopyFeedback.transition(CopyFeedback.INITIAL, e)
            assertEquals(CopyFeedback.INITIAL, t.state)
            assertTrue(t.effects.isEmpty())
        }
    }
}
