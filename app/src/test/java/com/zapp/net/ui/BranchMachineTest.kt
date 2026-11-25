package com.zapp.net.ui

import com.zapp.net.ui.BranchMachine.Effect
import com.zapp.net.ui.BranchMachine.Event
import com.zapp.net.ui.BranchMachine.Popover
import com.zapp.net.ui.BranchMachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectors for `cr`/`pr`/`mr`/`hr`/`gr`/`_r`/`vr`, transcribed from
 * `route-BvCVPSgV.js` (37 723-41 070). Every case below is a branch of their
 * reducer; none is invented.
 *
 * Where a case is subtle the test name says why, because the two that bite are
 * "refresh failure keeps the old list" and "close by outside does not focus".
 */
class BranchMachineTest {

    private val dir = "/work/zapp"

    private fun loaded(vararg items: String) = BranchMachine.BranchItems(items.toList(), false)

    /** A ready, closed machine with a list - the shape most events start from. */
    private fun ready(
        items: List<String> = listOf("main", "feature/a"),
        attempt: Int = 1,
        refreshing: Boolean = false,
        popover: Popover = Popover.Closed,
    ) = State.Ready(dir, attempt, loaded(*items.toTypedArray()), refreshing, popover)

    // ---- construction -----------------------------------------------------------------------

    @Test
    fun initialStateIsInactiveAtAttemptZero() {
        val s = State.Inactive()
        assertEquals(0, s.attempt)
        assertFalse(BranchMachine.isOpen(s))
        assertFalse(BranchMachine.isBusy(s))
        assertNull(BranchMachine.initialItems(s))
    }

    // ---- target.changed ---------------------------------------------------------------------

    @Test
    fun aDirectoryStartsTheFirstFetch() {
        val r = BranchMachine.reduce(State.Inactive(), Event.TargetChanged(dir))
        val s = r.state as State.Loading
        assertEquals(dir, s.directory)
        assertEquals(1, s.attempt)
        assertFalse("their pr always starts closed", s.open)
        assertEquals(listOf(Effect.BranchesFetch(1, dir)), r.effects)
    }

    @Test
    fun aNullDirectoryParksTheMachineAndAbortsTheFetch() {
        val r = BranchMachine.reduce(State.Loading(dir, 3, open = true), Event.TargetChanged(null))
        assertEquals(State.Inactive(3), r.state)
        assertEquals(listOf(Effect.BranchesAbort(3)), r.effects)
    }

    @Test
    fun aNullDirectoryOnAnInactiveMachineIsANoOp() {
        val s = State.Inactive(2)
        val r = BranchMachine.reduce(s, Event.TargetChanged(null))
        assertSame(s, r.state)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun theSameDirectoryIsANoOp() {
        val s = ready()
        val r = BranchMachine.reduce(s, Event.TargetChanged(dir))
        assertSame(s, r.state)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun aNewDirectoryBumpsTheAttemptAndAbortsTheOldFetch() {
        val s = State.Ready(dir, 4, loaded("main"), refreshing = true)
        val r = BranchMachine.reduce(s, Event.TargetChanged("/work/other"))
        val n = r.state as State.Loading
        assertEquals("/work/other", n.directory)
        assertEquals(5, n.attempt)
        assertEquals(
            listOf(Effect.BranchesAbort(4), Effect.BranchesFetch(5, "/work/other")),
            r.effects,
        )
    }

    // ---- branches.loaded / failed ------------------------------------------------------------

    @Test
    fun aLoadFromLoadingBecomesReady() {
        val r = BranchMachine.reduce(
            State.Loading(dir, 2, open = false),
            Event.BranchesLoaded(2, listOf("main", "dev")),
        )
        val s = r.state as State.Ready
        assertEquals(loaded("main", "dev"), s.initial)
        assertFalse(s.refreshing)
        assertEquals(Popover.Closed, s.popover)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun aLoadThatWasAlreadyOpenLandsOpen() {
        // `_r` on a loading state writes `popover: e.open ? 'open' : 'closed'`. The user
        // can open the popover before the first fetch answers, and that intent survives.
        val r = BranchMachine.reduce(
            State.Loading(dir, 2, open = true),
            Event.BranchesLoaded(2, listOf("main")),
        )
        assertEquals(Popover.Open, (r.state as State.Ready).popover)
    }

    @Test
    fun aFailureFromLoadingBecomesTheFailedShape() {
        val r = BranchMachine.reduce(State.Loading(dir, 2), Event.BranchesFailed(2))
        val s = r.state as State.Ready
        assertEquals(BranchMachine.BranchItems.Failed, s.initial)
        assertTrue(s.initial.failed)
        assertTrue(s.initial.items.isEmpty())
    }

    @Test
    fun aStaleLoadIsDropped() {
        val s = State.Loading(dir, 5)
        val r = BranchMachine.reduce(s, Event.BranchesLoaded(4, listOf("old")))
        assertSame(s, r.state)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun aFailedRefreshKeepsTheListAlreadyOnScreen() {
        // `_r` on `ready && refreshing` writes `initial: n ?? e.initial`. A dead refresh
        // must not blank the list the user is reading.
        val s = ready(listOf("main", "dev"), attempt = 7, refreshing = true)
        val r = BranchMachine.reduce(s, Event.BranchesFailed(7))
        val n = r.state as State.Ready
        assertEquals(loaded("main", "dev"), n.initial)
        assertFalse(n.refreshing)
    }

    @Test
    fun aGoodRefreshReplacesTheList() {
        val s = ready(listOf("main"), attempt = 7, refreshing = true)
        val r = BranchMachine.reduce(s, Event.BranchesLoaded(7, listOf("main", "new")))
        assertEquals(loaded("main", "new"), (r.state as State.Ready).initial)
    }

    @Test
    fun aLoadOnANonRefreshingReadyIsANoOp() {
        val s = ready(attempt = 7)
        val r = BranchMachine.reduce(s, Event.BranchesLoaded(7, listOf("x")))
        assertSame(s, r.state)
    }

    // ---- timeout ----------------------------------------------------------------------------

    @Test
    fun aTimeoutSettlesFailedAndAborts() {
        val r = BranchMachine.reduce(State.Loading(dir, 3, open = true), Event.BranchesTimedOut(3))
        val s = r.state as State.Ready
        assertEquals(BranchMachine.BranchItems.Failed, s.initial)
        assertEquals(Popover.Open, s.popover)
        assertEquals(listOf(Effect.BranchesAbort(3)), r.effects)
    }

    @Test
    fun aStaleTimeoutIsANoOp() {
        val s = State.Loading(dir, 3)
        val r = BranchMachine.reduce(s, Event.BranchesTimedOut(2))
        assertSame(s, r.state)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun theTimeoutIsTenSeconds() {
        assertEquals(10_000, BranchMachine.TIMEOUT_MS)
    }

    // ---- open / close -----------------------------------------------------------------------

    @Test
    fun openingAReadyMachineRefetchesAndRaisesTheAttempt() {
        val s = ready(attempt = 3)
        val r = BranchMachine.reduce(s, Event.OpenChange(true))
        val n = r.state as State.Ready
        assertEquals(4, n.attempt)
        assertTrue(n.refreshing)
        assertEquals(Popover.Open, n.popover)
        assertEquals(listOf(Effect.BranchesFetch(4, dir)), r.effects)
    }

    @Test
    fun openingAnAlreadyOpenPopoverIsANoOp() {
        val s = ready(popover = Popover.Open)
        val r = BranchMachine.reduce(s, Event.OpenChange(true))
        assertSame(s, r.state)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun openingWhileLoadingOnlyFlipsTheFlag() {
        // No second fetch: the first one is still running and will land in this state.
        val s = State.Loading(dir, 1, open = false)
        val r = BranchMachine.reduce(s, Event.OpenChange(true))
        assertEquals(State.Loading(dir, 1, open = true), r.state)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun closingAnOpenPopoverFocusesTheComposer() {
        val r = BranchMachine.reduce(ready(popover = Popover.Open), Event.OpenChange(false))
        assertEquals(Popover.Closed, (r.state as State.Ready).popover)
        assertEquals(listOf(Effect.FocusComposer), r.effects)
    }

    @Test
    fun closingAfterATapOutsideDoesNotFocusTheComposer() {
        // `hr` picks `e.popover === 'outside' ? [] : [focus.composer]`. Tapping away
        // must not yank the cursor back into the prompt.
        val r = BranchMachine.reduce(ready(popover = Popover.Outside), Event.OpenChange(false))
        assertEquals(Popover.Closed, (r.state as State.Ready).popover)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun closingAClosedPopoverIsANoOp() {
        val s = ready(popover = Popover.Closed)
        val r = BranchMachine.reduce(s, Event.OpenChange(false))
        assertSame(s, r.state)
    }

    @Test
    fun closingWhileLoadingDropsTheOpenFlag() {
        val r = BranchMachine.reduce(State.Loading(dir, 1, open = true), Event.OpenChange(false))
        assertEquals(State.Loading(dir, 1, open = false), r.state)
        assertTrue(r.effects.isEmpty())
    }

    // ---- interact.outside -------------------------------------------------------------------

    @Test
    fun aTapOutsideMarksThePopoverOutside() {
        val r = BranchMachine.reduce(ready(popover = Popover.Open), Event.InteractOutside)
        assertEquals(Popover.Outside, (r.state as State.Ready).popover)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun aTapOutsideWhileAlreadyOutsideIsANoOp() {
        val s = ready(popover = Popover.Outside)
        val r = BranchMachine.reduce(s, Event.InteractOutside)
        assertSame(s, r.state)
    }

    @Test
    fun aTapOutsideWhileClosedIsANoOp() {
        val s = ready(popover = Popover.Closed)
        val r = BranchMachine.reduce(s, Event.InteractOutside)
        assertSame(s, r.state)
    }

    // ---- selection --------------------------------------------------------------------------

    @Test
    fun pickingABranchClosesAndAsksForTheSelection() {
        val r = BranchMachine.reduce(ready(popover = Popover.Open), Event.BranchSelected("dev"))
        assertEquals(Popover.Closed, (r.state as State.Ready).popover)
        assertEquals(
            listOf(Effect.BranchChoose("dev"), Effect.FocusComposer),
            r.effects,
        )
    }

    @Test
    fun pickingMainCheckoutAsksForMain() {
        val r = BranchMachine.reduce(ready(popover = Popover.Open), Event.CheckoutSelected)
        assertEquals(listOf(Effect.CheckoutUse, Effect.FocusComposer), r.effects)
    }

    @Test
    fun pickingWhileClosedIsANoOp() {
        val s = ready(popover = Popover.Closed)
        assertSame(s, BranchMachine.reduce(s, Event.BranchSelected("dev")).state)
        assertSame(s, BranchMachine.reduce(s, Event.CheckoutSelected).state)
    }

    // ---- command.select ---------------------------------------------------------------------

    @Test
    fun theCommandOpensWhenClosed() {
        val r = BranchMachine.reduce(ready(), Event.CommandSelect("keybind"))
        assertEquals(Popover.Open, (r.state as State.Ready).popover)
        assertEquals(1, r.effects.size)
        assertTrue(r.effects[0] is Effect.BranchesFetch)
    }

    @Test
    fun theCommandFromThePaletteDoesNotStackASecondPopover() {
        // `command.select` with `source === 'palette'` while open is a no-op: the command
        // palette is already the thing on screen.
        val s = ready(popover = Popover.Open)
        val r = BranchMachine.reduce(s, Event.CommandSelect("palette"))
        assertSame(s, r.state)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun theCommandFromAKeybindClosesAnOpenPopover() {
        val r = BranchMachine.reduce(ready(popover = Popover.Open), Event.CommandSelect("keybind"))
        assertEquals(Popover.Closed, (r.state as State.Ready).popover)
        assertEquals(listOf(Effect.FocusComposer), r.effects)
    }

    // ---- pending.dismiss --------------------------------------------------------------------

    @Test
    fun dismissingWhilePendingDropsTheOpenFlag() {
        val r = BranchMachine.reduce(State.Loading(dir, 1, open = true), Event.PendingDismiss)
        assertEquals(State.Loading(dir, 1, open = false), r.state)
    }

    @Test
    fun dismissingWhenReadyIsANoOp() {
        // `pending.dismiss` is only wired while `isPending` (loading && open).
        val s = ready(popover = Popover.Open)
        val r = BranchMachine.reduce(s, Event.PendingDismiss)
        assertSame(s, r.state)
    }

    // ---- branches.synced --------------------------------------------------------------------

    @Test
    fun aSyncFromTheSameDirectoryReplacesTheInitialList() {
        val s = ready(listOf("main"), attempt = 2, refreshing = true)
        val r = BranchMachine.reduce(s, Event.BranchesSynced(dir, listOf("main", "dev")))
        val n = r.state as State.Ready
        assertEquals(loaded("main", "dev"), n.initial)
        assertFalse(n.refreshing)
    }

    @Test
    fun aSyncFromAnotherDirectoryIsDropped() {
        val s = ready(attempt = 2, refreshing = true)
        val r = BranchMachine.reduce(s, Event.BranchesSynced("/elsewhere", listOf("x")))
        assertSame(s, r.state)
    }

    @Test
    fun aSyncWhileLoadingIsDropped() {
        // Their reducer only reaches the sync branch when `status === 'ready'`.
        val s = State.Loading(dir, 1)
        val r = BranchMachine.reduce(s, Event.BranchesSynced(dir, listOf("main")))
        assertSame(s, r.state)
    }

    // ---- dispose ----------------------------------------------------------------------------

    @Test
    fun disposeIsTerminal() {
        val r = BranchMachine.reduce(State.Loading(dir, 2), Event.Disposed)
        assertEquals(State.Disposed, r.state)
        assertEquals(listOf(Effect.BranchesAbort(2)), r.effects)
    }

    @Test
    fun aDisposedMachineIgnoresEveryEvent() {
        // `cr` starts with `if (e.status === 'disposed') return G(e)` - even `mounted`.
        // Their harness rebuilds the machine instead of poking a disposed one.
        val s = State.Disposed
        val events = listOf(
            Event.TargetChanged(dir),
            Event.OpenChange(true),
            Event.CommandSelect("keybind"),
            Event.PendingDismiss,
            Event.BranchesLoaded(1, listOf("main")),
            Event.BranchesFailed(1),
            Event.BranchesTimedOut(1),
            Event.InteractOutside,
            Event.BranchSelected("dev"),
            Event.CheckoutSelected,
            Event.BranchesSynced(dir, listOf("main")),
            Event.Disposed,
        )
        events.forEach { e ->
            val r = BranchMachine.reduce(s, e)
            assertSame("event=$e", s, r.state)
            assertTrue("event=$e", r.effects.isEmpty())
        }
    }

    // ---- predicates -------------------------------------------------------------------------

    @Test
    fun predicatesMatchTheirThreeHelpers() {
        // lr
        assertTrue(BranchMachine.isOpen(State.Loading(dir, 1, open = true)))
        assertFalse(BranchMachine.isOpen(State.Loading(dir, 1, open = false)))
        assertTrue(BranchMachine.isOpen(ready(popover = Popover.Open)))
        assertTrue(BranchMachine.isOpen(ready(popover = Popover.Outside)))
        assertFalse(BranchMachine.isOpen(ready(popover = Popover.Closed)))
        // ur
        assertTrue(BranchMachine.isPending(State.Loading(dir, 1, open = true)))
        assertFalse(BranchMachine.isPending(State.Loading(dir, 1, open = false)))
        assertFalse(BranchMachine.isPending(ready(popover = Popover.Open)))
        // dr
        assertTrue(BranchMachine.isBusy(State.Loading(dir, 1)))
        assertTrue(BranchMachine.isBusy(ready(refreshing = true)))
        assertFalse(BranchMachine.isBusy(ready(refreshing = false)))
    }

    // ---- palette rules ----------------------------------------------------------------------

    @Test
    fun theCheckoutRowIsAlwaysFirstAndNeverTextMatched() {
        val items = listOf("main", "feature/login", "hotfix")
        assertEquals(
            listOf(null, "main"),
            BranchMachine.visible(items, "MAIN"),
        )
        // The sentinel itself is not a string, so `matches(null, q)` must always be false.
        assertFalse(BranchMachine.matches(null, ""))
        assertFalse(BranchMachine.matches(null, "checkout"))
        assertTrue(BranchMachine.matches("main", ""))
    }

    @Test
    fun theMatchIsCaseInsensitiveSubstring() {
        assertTrue(BranchMachine.matches("feature/Login", "login"))
        assertTrue(BranchMachine.matches("feature/Login", "FEATURE"))
        assertFalse(BranchMachine.matches("feature/login", "main"))
    }

    @Test
    fun rowKeysNeverCollide() {
        assertEquals("checkout", BranchMachine.rowKey(null))
        assertEquals("branch:main", BranchMachine.rowKey("main"))
        assertEquals("branch:", BranchMachine.selectedKey(null))
        assertEquals("branch:dev", BranchMachine.selectedKey("dev"))
    }

    @Test
    fun theLabelSwitchesOnTheSelection() {
        assertEquals("Choose base branch", BranchMachine.label(null))
        assertEquals("from dev", BranchMachine.label("dev"))
        assertEquals("from main", BranchMachine.label("main"))
    }

    @Test
    fun theCopyIsTheirBundle() {
        assertEquals("Choose base branch", BranchMachine.Copy.CHOOSE)
        assertEquals("Use main checkout", BranchMachine.Copy.USE_MAIN)
        assertEquals("Search branches", BranchMachine.Copy.SEARCH)
        assertEquals("No results found", BranchMachine.Copy.EMPTY)
        assertEquals("Try again", BranchMachine.Copy.RETRY)
    }
}
