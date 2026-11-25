package com.zapp.net.ui

import com.zapp.net.ui.ReviewScroll.Context
import com.zapp.net.ui.ReviewScroll.Effect
import com.zapp.net.ui.ReviewScroll.Event
import com.zapp.net.ui.ReviewScroll.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `review/scroll-machine.ts` - one test per branch of their transition function.
 *
 * The three rules worth protecting:
 * - only `settled` may save a scroll position, so a restore never overwrites itself;
 * - a restore and a focused file belong to their visit;
 * - a focused file scrolls a frame at a time, capped at `MAX_FRAMES` retries.
 */
class ReviewScrollTest {

    private fun ctx(
        visit: Int = 1,
        narrow: Boolean = true,
        scroller: Boolean = true,
        ready: Boolean = true,
        layoutReady: Boolean = true,
        commentFocused: Boolean = false,
    ) = Context(visit, narrow, scroller, ready, layoutReady, commentFocused)

    /** A list that is mounted, loaded and ready to restore. */
    private fun restorableState(attempt: Int = 1) =
        State.Restoring(ctx(), restoreQueued = false, attempt = attempt)

    private fun t(state: State, event: Event) = ReviewScroll.transition(state, event)

    @Test
    fun initialIsRestoringWithNothingQueued() {
        val s = ReviewScroll.initial as State.Restoring
        assertFalse(s.restoreQueued)
        assertEquals(0, s.attempt)
        assertFalse(s.context.narrow)
        // the wide panel's context never restores
        assertFalse(ReviewScroll.canSave(ReviewScroll.initial))
    }

    @Test
    fun restoreRequestedQueuesOneFrame() {
        val r = t(restorableState(), Event.RestoreRequested)
        val s = r.state as State.Restoring
        assertTrue(s.restoreQueued)
        assertEquals(2, s.attempt)
        assertEquals(listOf<Effect>(Effect.FrameRestore(2)), r.effects)

        // a second request while one is queued does nothing
        val again = t(s, Event.RestoreRequested)
        assertEquals(s, again.state)
        assertTrue(again.effects.isEmpty())
    }

    @Test
    fun restoreIsRefusedWhenTheListCannotRestoreYet() {
        // not narrow: the wide panel previews one file and scrolls no list
        for (c in listOf(
            ctx(narrow = false),
            ctx(scroller = false),
            ctx(layoutReady = false),
        )) {
            val state = State.Restoring(c, restoreQueued = false, attempt = 1)
            val r = t(state, Event.RestoreRequested)
            assertEquals(state, r.state)
            assertTrue(r.effects.isEmpty())
        }
    }

    @Test
    fun restoreSteppedClearsTheQueueOnlyForItsOwnAttempt() {
        val queued = t(restorableState(), Event.RestoreRequested).state as State.Restoring
        // stale attempt
        assertEquals(queued, t(queued, Event.RestoreStepped(1)).state)
        // nothing queued
        val cleared = t(queued, Event.RestoreStepped(2)).state as State.Restoring
        assertFalse(cleared.restoreQueued)
        assertTrue(t(cleared, Event.RestoreStepped(2)).effects.isEmpty())
    }

    @Test
    fun aFocusedFileWaitsUntilTheListCanScroll() {
        val notReady = State.Restoring(ctx(ready = false), restoreQueued = false, attempt = 1)
        val r = t(notReady, Event.FileFocused("a.kt"))
        val w = r.state as State.Waiting
        assertEquals("a.kt", w.path)
        assertEquals(2, w.attempt)
        assertTrue(r.effects.isEmpty())

        // once the diffs load, the sync starts the scroll
        val synced = t(w, Event.ContextSynced(ctx(ready = true)))
        val s = synced.state as State.Scrolling
        assertEquals("a.kt", s.path)
        assertEquals(0, s.retries)
        assertEquals(listOf<Effect>(Effect.FrameSchedule(3, "a.kt")), synced.effects)
    }

    @Test
    fun aFocusedFileOnTheWidePanelDoesNothing() {
        val state = State.Restoring(ctx(narrow = false), restoreQueued = false, attempt = 1)
        val r = t(state, Event.FileFocused("a.kt"))
        assertEquals(state, r.state)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun focusingAFileCancelsAScheduledFrame() {
        val queued = t(restorableState(), Event.RestoreRequested).state
        val r = t(queued, Event.FileFocused("b.kt"))
        // the queued restore is attempt 2, so the scroll frame is attempt 3
        assertEquals(listOf<Effect>(Effect.FrameCancel, Effect.FrameSchedule(3, "b.kt")), r.effects)
    }

    @Test
    fun aFrameStepsUntilSettledOrTheCap() {
        val scrolling = t(
            restorableState(),
            Event.FileFocused("c.kt"),
        ).state as State.Scrolling

        // a stale frame is ignored
        assertEquals(scrolling, t(scrolling, Event.FrameStepped(1, settled = true)).state)

        // retry increments and re-schedules
        val retry = t(scrolling, Event.FrameStepped(scrolling.attempt, settled = false))
        val next = retry.state as State.Scrolling
        assertEquals(1, next.retries)
        assertEquals(listOf<Effect>(Effect.FrameSchedule(next.attempt, "c.kt")), retry.effects)

        // settled stops
        val done = t(next, Event.FrameStepped(next.attempt, settled = true))
        assertTrue(done.state is State.Settled)
        assertTrue(done.effects.isEmpty())

        // the cap stops even without a settled frame
        val atCap = next.copy(retries = ReviewScroll.MAX_FRAMES)
        assertTrue(t(atCap, Event.FrameStepped(atCap.attempt, settled = false)).state is State.Settled)
    }

    @Test
    fun onlySettledMaySaveAndOnlyWhenRestorable() {
        assertTrue(ReviewScroll.canSave(State.Settled(ctx(), 1)))
        assertFalse(ReviewScroll.canSave(State.Settled(ctx(layoutReady = false), 1)))
        assertFalse(ReviewScroll.canSave(State.Restoring(ctx(), false, 1)))
        assertFalse(ReviewScroll.canSave(State.Scrolling(ctx(), "a", 0, 1)))
    }

    @Test
    fun canRestoreNeedsItsOwnAttemptAndAQueuedFrame() {
        val queued = t(restorableState(), Event.RestoreRequested).state
        assertTrue(ReviewScroll.canRestore(queued, 2))
        assertFalse(ReviewScroll.canRestore(queued, 1))
        assertFalse(ReviewScroll.canRestore(restorableState(), 1))
    }

    @Test
    fun interactingSettlesTheListSoItsPositionIsStored() {
        val r = t(restorableState(), Event.Interacted)
        val s = r.state as State.Settled
        assertEquals(2, s.attempt)
        // already settled: nothing to do
        assertEquals(s, t(s, Event.Interacted).state)
        // the wide panel never settles from an interaction
        val wide = State.Restoring(ctx(narrow = false), false, 1)
        assertEquals(wide, t(wide, Event.Interacted).state)
    }

    @Test
    fun aNewVisitRestoresItsOwnScrollAndNeverInheritsAFrame() {
        // a scroll frame from visit 1 is still running
        val scrolling = t(restorableState(), Event.FileFocused("x.kt")).state
        val r = t(scrolling, Event.ContextSynced(ctx(visit = 2)))
        val s = r.state as State.Restoring
        assertEquals(2, s.context.visitID)
        assertTrue(s.restoreQueued)
        // the scroll was attempt 2, so the new visit's restore is attempt 3
        assertEquals(listOf<Effect>(Effect.FrameCancel, Effect.FrameRestore(3)), r.effects)
    }

    @Test
    fun aNewVisitWithAFocusedCommentSettlesInstead() {
        val r = t(restorableState(), Event.ContextSynced(ctx(visit = 9, commentFocused = true)))
        assertTrue(r.state is State.Settled)
        assertEquals(9, (r.state as State.Settled).context.visitID)
    }

    @Test
    fun theListUnmountingSettlesOrRestoresDependingOnWhetherItIsStillThere() {
        val scrolling = t(restorableState(), Event.FileFocused("y.kt")).state
        // scroller still mounted but the diffs reloaded -> settle
        val settled = t(scrolling, Event.ContextSynced(ctx(ready = false))).state
        assertTrue(settled is State.Settled)
        // the list itself unmounted -> restore when it comes back
        val restoring = t(scrolling, Event.ContextSynced(ctx(scroller = false))).state
        assertTrue(restoring is State.Restoring)
        assertFalse((restoring as State.Restoring).restoreQueued)
    }

    @Test
    fun theListRemountingOnTheSameVisitKeepsItsAttempt() {
        val settled = State.Settled(ctx(scroller = false), attempt = 7)
        val r = t(settled, Event.ContextSynced(ctx(scroller = true)))
        val s = r.state as State.Settled
        assertEquals(7, s.attempt)
        assertTrue(s.context.scroller)
    }

    @Test
    fun aFocusedCommentBeatsTheStoredScroll() {
        val r = t(restorableState(), Event.ContextSynced(ctx(commentFocused = true)))
        assertTrue(r.state is State.Settled)
    }

    @Test
    fun disposalIsTerminalAndCancelsAFrame() {
        val queued = t(restorableState(), Event.RestoreRequested).state
        val d = t(queued, Event.Disposed)
        assertEquals(State.Disposed(3), d.state)
        assertEquals(listOf<Effect>(Effect.FrameCancel), d.effects)

        for (e in listOf(
            Event.RestoreRequested,
            Event.RestoreStepped(3),
            Event.FileFocused("z"),
            Event.FrameStepped(3, settled = true),
            Event.Interacted,
            Event.ContextSynced(ctx(visit = 5)),
            Event.Disposed,
        )) {
            val r = t(d.state, e)
            assertEquals(State.Disposed(3), r.state)
            assertTrue(r.effects.isEmpty())
        }
    }

    @Test
    fun sameContextComparesEveryField() {
        val a = ctx()
        assertTrue(ReviewScroll.sameContext(a, ctx()))
        assertFalse(ReviewScroll.sameContext(a, ctx(visit = 2)))
        assertFalse(ReviewScroll.sameContext(a, ctx(narrow = false)))
        assertFalse(ReviewScroll.sameContext(a, ctx(scroller = false)))
        assertFalse(ReviewScroll.sameContext(a, ctx(ready = false)))
        assertFalse(ReviewScroll.sameContext(a, ctx(layoutReady = false)))
        assertFalse(ReviewScroll.sameContext(a, ctx(commentFocused = true)))
    }
}
