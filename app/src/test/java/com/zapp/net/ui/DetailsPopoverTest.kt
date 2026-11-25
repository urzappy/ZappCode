package com.zapp.net.ui

import com.zapp.net.ui.DetailsPopover.Effect
import com.zapp.net.ui.DetailsPopover.Event
import com.zapp.net.ui.DetailsPopover.State
import com.zapp.net.ui.DetailsPopover.Via
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `details/popover-machine.ts` - one test per branch of their transition function.
 *
 * The rule worth protecting is the focus one: a pointer open must focus the popover itself, never
 * its first row, and a deferred focus must be ignored once its attempt number is stale. On the
 * phone every open is a pointer open, so this is the branch that actually runs.
 */
class DetailsPopoverTest {

    private fun t(state: State, event: Event) = DetailsPopover.transition(state, event)

    @Test
    fun initialIsClosedAtZero() {
        assertEquals(State.Closed(0), DetailsPopover.initial)
        assertFalse(DetailsPopover.isOpen(DetailsPopover.initial))
        assertFalse(DetailsPopover.focusesContent(DetailsPopover.initial))
    }

    @Test
    fun openingFromClosedTakesTheNextAttempt() {
        val first = t(State.Closed(0), Event.Opened(Via.Pointer))
        assertEquals(State.Open(issued = 1, attempt = 1, via = Via.Pointer, focusPending = false), first.state)
        assertTrue(first.effects.isEmpty())
        assertTrue(DetailsPopover.isOpen(first.state))
        // a pointer open focuses the content
        assertTrue(DetailsPopover.focusesContent(first.state))

        // closed again, then reopened: the attempt advances, it never reuses 1
        val closed = t(first.state, Event.Closed)
        val second = t(closed.state, Event.Toggled(Via.Keyboard))
        assertEquals(2, (second.state as State.Open).attempt)
        // a keyboard open does not
        assertFalse(DetailsPopover.focusesContent(second.state))
    }

    @Test
    fun eventsOtherThanOpenAndToggleAreInertWhileClosed() {
        for (e in listOf(
            Event.Closed,
            Event.AutofocusIntercepted,
            Event.FocusDue(1),
        )) {
            val r = t(State.Closed(3), e)
            assertEquals(State.Closed(3), r.state)
            assertTrue(r.effects.isEmpty())
        }
    }

    @Test
    fun closingWhileFocusIsPendingCancelsIt() {
        val open = t(State.Closed(0), Event.Opened(Via.Pointer)).state
        val pending = t(open, Event.AutofocusIntercepted)
        assertTrue((pending.state as State.Open).focusPending)
        assertEquals(listOf(Effect.ScheduleFocus(1)), pending.effects)

        val closed = t(pending.state, Event.Closed)
        assertEquals(State.Closed(1), closed.state)
        assertEquals(listOf<Effect>(Effect.CancelFocus), closed.effects)

        // toggled closes too, and keeps `issued`
        val toggled = t(pending.state, Event.Toggled(Via.Pointer))
        assertEquals(State.Closed(1), toggled.state)
        assertEquals(listOf<Effect>(Effect.CancelFocus), toggled.effects)
    }

    @Test
    fun closingWithNothingPendingHasNoEffects() {
        val open = t(State.Closed(0), Event.Opened(Via.Pointer)).state
        val closed = t(open, Event.Closed)
        assertEquals(State.Closed(1), closed.state)
        assertTrue(closed.effects.isEmpty())
    }

    @Test
    fun onlyAPointerOpenDefersFocus() {
        val keyboard = t(State.Closed(0), Event.Opened(Via.Keyboard)).state
        val r = t(keyboard, Event.AutofocusIntercepted)
        assertEquals(keyboard, r.state)
        assertTrue(r.effects.isEmpty())

        val pointer = t(State.Closed(0), Event.Opened(Via.Pointer)).state
        val once = t(pointer, Event.AutofocusIntercepted)
        // a second interception does not schedule a second timer
        val twice = t(once.state, Event.AutofocusIntercepted)
        assertEquals(once.state, twice.state)
        assertTrue(twice.effects.isEmpty())
    }

    @Test
    fun aStaleFocusAttemptIsIgnored() {
        val open = t(State.Closed(0), Event.Opened(Via.Pointer)).state
        val pending = t(open, Event.AutofocusIntercepted).state as State.Open
        // attempt 1 is the live one; 0 and 2 are stale
        for (wrong in listOf(0, 2)) {
            val r = t(pending, Event.FocusDue(wrong))
            assertEquals(pending, r.state)
            assertTrue(r.effects.isEmpty())
        }
        val live = t(pending, Event.FocusDue(1))
        assertEquals(pending.copy(focusPending = false), live.state)
        assertEquals(listOf<Effect>(Effect.FocusContent), live.effects)
    }

    @Test
    fun aFocusDueWithNothingPendingDoesNothing() {
        val open = t(State.Closed(0), Event.Opened(Via.Pointer)).state
        val r = t(open, Event.FocusDue(1))
        assertEquals(open, r.state)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun disposalIsTerminal() {
        val disposed = t(State.Closed(0), Event.Disposed)
        assertEquals(State.Disposed, disposed.state)
        assertTrue(disposed.effects.isEmpty())

        // an open popover with a pending focus cancels it on disposal
        val pending = t(t(State.Closed(0), Event.Opened(Via.Pointer)).state, Event.AutofocusIntercepted).state
        val pendingDisposed = t(pending, Event.Disposed)
        assertEquals(State.Disposed, pendingDisposed.state)
        assertEquals(listOf<Effect>(Effect.CancelFocus), pendingDisposed.effects)

        // and nothing reaches a disposed popover
        for (e in listOf(
            Event.Opened(Via.Pointer),
            Event.Toggled(Via.Pointer),
            Event.Closed,
            Event.AutofocusIntercepted,
            Event.FocusDue(1),
            Event.Disposed,
        )) {
            val r = t(State.Disposed, e)
            assertEquals(State.Disposed, r.state)
            assertTrue(r.effects.isEmpty())
        }
    }
}
