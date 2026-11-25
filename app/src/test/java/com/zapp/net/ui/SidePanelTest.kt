package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `route-CMUQLRzL.js` @ 363 900 - 366 300: `ZS` (defaults), `QS` (reducer), `nC` (sync),
 * `rC` (vanished), `iC` (open), `aC` (chosenOf), `oC` (put), `sC` (empty), `cC` (same
 * snapshot), `tC` (any tabs), `eC` (chosen of pane).
 *
 * Every assertion below is a line of theirs, quoted in the comment above it.
 */
class SidePanelTest {

    private fun state() = SidePanel.State()

    private fun snap(
        session: String = "s1",
        empty: Boolean = true,
        sideOpen: Boolean = false,
        side: List<String> = emptyList(),
        sideActive: String? = null,
        split: List<String> = emptyList(),
        bottom: List<String> = emptyList(),
    ) = SidePanel.Snapshot(
        session = session,
        empty = empty,
        regionActive = null,
        sideOpen = sideOpen,
        panes = mapOf(
            SidePanel.Pane.SIDE to SidePanel.PaneSnapshot(side, sideActive),
            SidePanel.Pane.SPLIT to SidePanel.PaneSnapshot(split),
            SidePanel.Pane.BOTTOM to SidePanel.PaneSnapshot(bottom),
        ),
    )

    // ---- ZS ------------------------------------------------------------------------------

    @Test
    fun theInitialStateIsTheirLiteral() {
        // `return{serial:0,session:``,openedEmpty:!1,panes:sC(),snapshot:void 0}`
        val s = state()
        assertEquals(0, s.serial)
        assertEquals("", s.session)
        assertFalse(s.openedEmpty)
        assertNull(s.snapshot)
        // `sC()`: three panes, all empty, all unchosen.
        SidePanel.PANES.forEach {
            assertEquals(SidePanel.PaneState(), s.panes.getValue(it))
        }
    }

    // ---- open ----------------------------------------------------------------------------

    @Test
    fun openMakesANewSerialTabAndChoosesIt() {
        // `let n=e.serial+1,r=`new:${n}`; ... {ids:[...ids,r],chosen:r}, serial:n`
        val a = SidePanel.reduce(state(), SidePanel.Event.Open(SidePanel.Pane.SIDE)).state
        assertEquals(1, a.serial)
        assertEquals(listOf("new:1"), a.panes.getValue(SidePanel.Pane.SIDE).ids)
        assertEquals("new:1", a.panes.getValue(SidePanel.Pane.SIDE).chosen)

        val b = SidePanel.reduce(a, SidePanel.Event.Open(SidePanel.Pane.SIDE)).state
        assertEquals(2, b.serial)
        assertEquals(listOf("new:1", "new:2"), b.panes.getValue(SidePanel.Pane.SIDE).ids)
        assertEquals("new:2", b.panes.getValue(SidePanel.Pane.SIDE).chosen)
    }

    @Test
    fun openOnlyTouchesItsOwnPane() {
        val a = SidePanel.reduce(state(), SidePanel.Event.Open(SidePanel.Pane.SPLIT)).state
        assertEquals(emptyList<String>(), a.panes.getValue(SidePanel.Pane.SIDE).ids)
        assertEquals(listOf("new:1"), a.panes.getValue(SidePanel.Pane.SPLIT).ids)
        // The serial is region-wide, not per pane.
        assertEquals(1, a.serial)
    }

    // ---- select.new ----------------------------------------------------------------------

    @Test
    fun selectNewOnlyMovesTheChoice() {
        val opened = SidePanel.reduce(state(), SidePanel.Event.Open(SidePanel.Pane.SIDE)).state
        val two = SidePanel.reduce(opened, SidePanel.Event.Open(SidePanel.Pane.SIDE)).state
        val back = SidePanel.reduce(
            two,
            SidePanel.Event.SelectNew(SidePanel.Pane.SIDE, "new:1"),
        ).state
        // `{ids:n.ids,chosen:t.id}` - ids unchanged, chosen moved.
        assertEquals(listOf("new:1", "new:2"), back.panes.getValue(SidePanel.Pane.SIDE).ids)
        assertEquals("new:1", back.panes.getValue(SidePanel.Pane.SIDE).chosen)
        // No new serial: select never appends.
        assertEquals(2, back.serial)
    }

    @Test
    fun selectNewOnATabThePaneDoesNotHoldIsANoOp() {
        // `!n.ids.includes(t.id)||n.chosen===t.id ? {state:e} : ...`
        val opened = SidePanel.reduce(state(), SidePanel.Event.Open(SidePanel.Pane.SIDE)).state
        val same = SidePanel.reduce(
            opened,
            SidePanel.Event.SelectNew(SidePanel.Pane.SIDE, "nope"),
        )
        assertSame(opened, same.state)

        val already = SidePanel.reduce(
            opened,
            SidePanel.Event.SelectNew(SidePanel.Pane.SIDE, "new:1"),
        )
        assertSame(opened, already.state)
    }

    // ---- select.tab ----------------------------------------------------------------------

    @Test
    fun selectTabGoesBackToTheList() {
        // `n.chosen===void 0 ? {state:e} : {chosen:void 0}`
        val opened = SidePanel.reduce(state(), SidePanel.Event.Open(SidePanel.Pane.SIDE)).state
        val list = SidePanel.reduce(opened, SidePanel.Event.SelectTab(SidePanel.Pane.SIDE)).state
        assertNull(list.panes.getValue(SidePanel.Pane.SIDE).chosen)
        assertEquals(listOf("new:1"), list.panes.getValue(SidePanel.Pane.SIDE).ids)

        val again = SidePanel.reduce(list, SidePanel.Event.SelectTab(SidePanel.Pane.SIDE))
        assertSame(list, again.state)
    }

    // ---- close ---------------------------------------------------------------------------

    @Test
    fun closeFallsForwardThenBack() {
        // `a = r.chosen===t.id ? r.ids[i+1] ?? r.ids[i-1] : r.chosen`
        val s0 = state()
        val s1 = SidePanel.reduce(s0, SidePanel.Event.Open(SidePanel.Pane.SIDE)).state
        val s2 = SidePanel.reduce(s1, SidePanel.Event.Open(SidePanel.Pane.SIDE)).state
        val s3 = SidePanel.reduce(s2, SidePanel.Event.Open(SidePanel.Pane.SIDE)).state

        // Closing the middle chosen tab picks the one after it.
        val mid = SidePanel.reduce(s3, SidePanel.Event.SelectNew(SidePanel.Pane.SIDE, "new:2")).state
        val closedMid = SidePanel.reduce(mid, SidePanel.Event.Close("new:2")).state
        assertEquals("new:3", closedMid.panes.getValue(SidePanel.Pane.SIDE).chosen)

        // Closing the last chosen tab has no successor, so it picks the one before.
        val closedLast = SidePanel.reduce(s3, SidePanel.Event.Close("new:3")).state
        assertEquals("new:2", closedLast.panes.getValue(SidePanel.Pane.SIDE).chosen)
    }

    @Test
    fun closingAnUnchosenTabKeepsTheChoice() {
        // `: r.chosen` - the false arm of the ternary.
        val s1 = SidePanel.reduce(state(), SidePanel.Event.Open(SidePanel.Pane.SIDE)).state
        val s2 = SidePanel.reduce(s1, SidePanel.Event.Open(SidePanel.Pane.SIDE)).state
        val closed = SidePanel.reduce(s2, SidePanel.Event.Close("new:1")).state
        assertEquals("new:2", closed.panes.getValue(SidePanel.Pane.SIDE).chosen)
        assertEquals(listOf("new:2"), closed.panes.getValue(SidePanel.Pane.SIDE).ids)
    }

    @Test
    fun closingAnIdNoPaneHoldsIsANoOp() {
        // `let n=K.find(...); if(!n) return {state:e,effects:[]}`
        val s = SidePanel.reduce(state(), SidePanel.Event.Open(SidePanel.Pane.SIDE)).state
        val miss = SidePanel.reduce(s, SidePanel.Event.Close("new:99"))
        assertSame(s, miss.state)
        assertTrue(miss.effects.isEmpty())
    }

    @Test
    fun theLastCloseEmitsEmptied() {
        // `effects: t.empty && !tC(o) ? [{type:"emptied"}] : []`
        val s = SidePanel.reduce(state(), SidePanel.Event.Open(SidePanel.Pane.SIDE)).state
        val last = SidePanel.reduce(s, SidePanel.Event.Close("new:1", empty = true))
        assertEquals(listOf<SidePanel.Effect>(SidePanel.Effect.Emptied), last.effects)

        // `empty = false` (the caller did not ask) stays silent even though it emptied.
        val quiet = SidePanel.reduce(s, SidePanel.Event.Close("new:1", empty = false))
        assertTrue(quiet.effects.isEmpty())
    }

    @Test
    fun dismissIsTheSameReducerPathAsClose() {
        // `N = e => A({type:"dismiss", id:e, empty:o(b)})`; `QS` has no `dismiss` case, so it
        // falls through to the shared `K.find(...)` tail.
        val s1 = SidePanel.reduce(state(), SidePanel.Event.Open(SidePanel.Pane.SIDE)).state
        val s2 = SidePanel.reduce(s1, SidePanel.Event.Open(SidePanel.Pane.SIDE)).state
        val closed = SidePanel.reduce(s2, SidePanel.Event.Close("new:1", empty = true))
        val dismissed = SidePanel.reduce(s2, SidePanel.Event.Dismiss("new:1", empty = true))
        assertEquals(closed.state.panes, dismissed.state.panes)
        assertEquals(closed.effects, dismissed.effects)
    }

    @Test
    fun emptiedIsNotEmittedWhileAnotherPaneStillHasTabs() {
        // `tC` = `K.some(t => e.panes[t].ids.length > 0)`
        val s1 = SidePanel.reduce(state(), SidePanel.Event.Open(SidePanel.Pane.SIDE)).state
        val s2 = SidePanel.reduce(s1, SidePanel.Event.Open(SidePanel.Pane.SPLIT)).state
        val out = SidePanel.reduce(s2, SidePanel.Event.Close("new:1", empty = true))
        assertTrue(out.effects.isEmpty())
    }

    // ---- sync: a different session -------------------------------------------------------

    @Test
    fun aNewSessionWipesThePanesAndOpensAnEmptySidePane() {
        // `r = t.sideOpen && t.empty; return r ? iC({...n, openedEmpty:r}, "side") : {...n, openedEmpty:r}`
        val old = SidePanel.reduce(state(), SidePanel.Event.Open(SidePanel.Pane.SIDE)).state
        val out = SidePanel.reduce(
            old,
            SidePanel.Event.Sync(snap(session = "s2", empty = true, sideOpen = true)),
        ).state
        assertEquals("s2", out.session)
        assertTrue(out.openedEmpty)
        assertEquals(listOf("new:2"), out.panes.getValue(SidePanel.Pane.SIDE).ids)
        assertEquals(2, out.serial)
    }

    @Test
    fun aNewSessionWithTheSideClosedJustResets() {
        val out = SidePanel.reduce(
            state(),
            SidePanel.Event.Sync(snap(session = "s2", empty = false, sideOpen = false)),
        ).state
        assertEquals("s2", out.session)
        assertFalse(out.openedEmpty)
        assertTrue(SidePanel.PANES.all { out.panes.getValue(it).ids.isEmpty() })
    }

    @Test
    fun openedEmptyIsOnlyHonouredOncePerSession() {
        // `c && !e.openedEmpty && eC(...) === void 0`
        val first = SidePanel.reduce(
            state(),
            SidePanel.Event.Sync(snap(session = "s1", empty = true, sideOpen = true)),
        ).state
        assertTrue(first.openedEmpty)
        assertEquals(1, first.serial)

        // A second sync with the same session must not make another tab.
        val second = SidePanel.reduce(
            first,
            SidePanel.Event.Sync(snap(session = "s1", empty = true, sideOpen = true)),
        ).state
        assertEquals(1, second.serial)
        assertEquals(listOf("new:1"), second.panes.getValue(SidePanel.Pane.SIDE).ids)
    }

    @Test
    fun anAlreadyChosenTabSuppressesTheEmptyOpen() {
        // `eC(s,"side",t.panes.side.active) === void 0` - a chosen tab means nothing to do.
        val opened = SidePanel.reduce(
            state(),
            SidePanel.Event.Sync(snap(session = "s1", empty = true, sideOpen = true)),
        ).state
        // Simulate the plugin claiming the tab: side.active is now a key the pane holds.
        val claimed = SidePanel.reduce(
            opened,
            SidePanel.Event.Sync(
                snap(
                    session = "s1",
                    empty = true,
                    sideOpen = true,
                    side = listOf("new:1"),
                    sideActive = "new:1",
                ),
            ),
        ).state
        assertEquals(1, claimed.serial)
    }

    // ---- sync: pruning -------------------------------------------------------------------

    @Test
    fun aNonSidePaneThatLosesEveryKeyIsCleared() {
        // `i !== "side" && o.keys.length === 0` -> `{ids:[],chosen:void 0}`
        // Session must match, or the wipe branch answers instead of the prune.
        val s1 = SidePanel.reduce(state(), SidePanel.Event.Open(SidePanel.Pane.SPLIT)).state
        val s2 = SidePanel.reduce(s1, SidePanel.Event.Sync(snap(session = ""))).state
        assertEquals(emptyList<String>(), s2.panes.getValue(SidePanel.Pane.SPLIT).ids)
    }

    @Test
    fun theSidePaneIsNeverClearedByTheEmptyKeysRule() {
        // The `i !== "side"` guard: side survives `keys.length === 0`.
        val s1 = SidePanel.reduce(state(), SidePanel.Event.Open(SidePanel.Pane.SIDE)).state
        val s2 = SidePanel.reduce(s1, SidePanel.Event.Sync(snap(session = ""))).state
        assertEquals(listOf("new:1"), s2.panes.getValue(SidePanel.Pane.SIDE).ids)
    }

    @Test
    fun aVanishedActiveTabIsDropped() {
        // rC: `t.active === void 0 || t.active === e.active ? false : !e.keys.includes(t.active) || ...`
        val s1 = SidePanel.reduce(state(), SidePanel.Event.Open(SidePanel.Pane.SIDE)).state
        // First sync teaches the region that "a" is active.
        val s2 = SidePanel.reduce(
            s1,
            SidePanel.Event.Sync(snap(session = "s1", side = listOf("a"), sideActive = "a")),
        ).state
        // Second sync moves to "b", which the pane has never held -> the stale tab goes.
        val s3 = SidePanel.reduce(
            s2,
            SidePanel.Event.Sync(snap(session = "s1", side = listOf("b"), sideActive = "b")),
        ).state
        assertEquals(emptyList<String>(), s3.panes.getValue(SidePanel.Pane.SIDE).ids)
        assertNull(s3.panes.getValue(SidePanel.Pane.SIDE).chosen)
    }

    @Test
    fun anUnchangedActiveTabPrunesNothing() {
        // `t.active === e.active` -> false, i.e. not vanished.
        assertFalse(
            SidePanel.vanished(
                SidePanel.PaneSnapshot(listOf("a"), "a"),
                SidePanel.PaneSnapshot(listOf("a"), "a"),
                null,
                setOf("a"),
            )
        )
        // `t.active === void 0` -> false, also not vanished.
        assertFalse(
            SidePanel.vanished(
                SidePanel.PaneSnapshot(listOf("a"), "a"),
                SidePanel.PaneSnapshot(listOf("a"), null),
                null,
                emptySet(),
            )
        )
    }

    @Test
    fun theSecondArmPrunesOnlyWhenTheOldKeySurvivesAndTheRegionStillContributes() {
        // `!e.keys.includes(t.active)` is false here, so the second arm decides:
        // `(e.active===void 0 || t.keys.includes(e.active)) && (n===void 0 || r.has(n))`
        val before = SidePanel.PaneSnapshot(listOf("a", "b"), "a")
        val after = SidePanel.PaneSnapshot(listOf("a"), "b")

        // The pane still holds the old active key and the region still contributes its own.
        assertTrue(SidePanel.vanished(before, after, null, setOf("b")))
        assertTrue(SidePanel.vanished(before, after, "b", setOf("b")))

        // The region stopped contributing its active key -> the tab is not ours to drop.
        assertFalse(SidePanel.vanished(before, after, "gone", setOf("b")))

        // The pane no longer holds the old active key -> the parent guard fails.
        assertFalse(
            SidePanel.vanished(
                SidePanel.PaneSnapshot(listOf("a", "b"), "a"),
                SidePanel.PaneSnapshot(emptyList(), "b"),
                null,
                setOf("b"),
            )
        )
    }

    // ---- chosenOf / anyTabs --------------------------------------------------------------

    @Test
    fun chosenOfFallsBackToTheLastTabOnlyWhenThePluginHasNoActiveKey() {
        // `e.chosen ?? (t === void 0 ? e.ids.at(-1) : void 0)`
        val pane = SidePanel.PaneState(listOf("a", "b"), null)
        assertEquals("b", SidePanel.chosenOf(pane, null))
        assertNull(SidePanel.chosenOf(pane, "a"))
        assertEquals("a", SidePanel.chosenOf(pane.copy(chosen = "a"), "b"))
    }

    @Test
    fun anyTabsIsTrueWhileAnyPaneHoldsOne() {
        assertFalse(SidePanel.anyTabs(state()))
        val s = SidePanel.reduce(state(), SidePanel.Event.Open(SidePanel.Pane.BOTTOM)).state
        assertTrue(SidePanel.anyTabs(s))
    }

    // ---- the `+` -------------------------------------------------------------------------

    @Test
    fun thePlusMakesATabUnlessTheModifierIsHeld() {
        // `intent: Qt(e, modifier) ? "menu" : "new"`
        assertEquals(SidePanel.Intent.NEW, SidePanel.intent(modifierHeld = false))
        assertEquals(SidePanel.Intent.MENU, SidePanel.intent(modifierHeld = true))
    }

    @Test
    fun thePlusOnlyTurnsIntoAChevronWhenThereIsSomethingToReopen() {
        assertEquals("plus", SidePanel.iconFor(SidePanel.Intent.NEW, hasRecent = true))
        assertEquals("plus", SidePanel.iconFor(SidePanel.Intent.MENU, hasRecent = false))
        assertEquals("chevron-down", SidePanel.iconFor(SidePanel.Intent.MENU, hasRecent = true))
    }

    // ---- the menu ------------------------------------------------------------------------

    @Test
    fun theFourRegistrationsAreInTheirOrder() {
        // `order: 10 / 15 / 20 / 30` and the icons from each `e.add(Oi, ...)`.
        assertEquals(
            listOf(10, 15, 20, 30),
            SidePanel.MENU.map { it.order },
        )
        assertEquals(
            listOf("review", "globe", "terminal", "folder"),
            SidePanel.MENU.map { it.icon },
        )
        assertEquals(
            listOf(
                SidePanel.Kind.REVIEW,
                SidePanel.Kind.BROWSER,
                SidePanel.Kind.TERMINAL,
                SidePanel.Kind.FILE,
            ),
            SidePanel.MENU.map { it.kind },
        )
        // `id: "open"` reuses a tab; terminal is the only `"new"`.
        assertEquals(
            listOf("open", "open", "new", "open"),
            SidePanel.MENU.map { it.id },
        )
    }

    @Test
    fun theRecentCapsAreTheirs() {
        // `vT=10,yT=5`
        assertEquals(5, SidePanel.RECENT_SHOWN)
        assertEquals(10, SidePanel.RECENT_IN_PALETTE)
    }

    // ---- the `+` machine, `sT` -----------------------------------------------------------

    @Test
    fun armingTheSameIntentIsTheSameState() {
        // `t.type===`arm` ? e.armed===t.intent ? e : {...e, armed:t.intent} : ...`
        val idle = SidePanel.PlusState()
        val armed = SidePanel.plusReduce(idle, SidePanel.PlusEvent.Arm(SidePanel.Intent.NEW))
        assertEquals(SidePanel.Intent.NEW, armed.first.armed)

        val again = SidePanel.plusReduce(armed.first, SidePanel.PlusEvent.Arm(SidePanel.Intent.NEW))
        assertSame(armed.first, again.first)

        val menu = SidePanel.plusReduce(armed.first, SidePanel.PlusEvent.Arm(SidePanel.Intent.MENU))
        assertEquals(SidePanel.Intent.MENU, menu.first.armed)
    }

    @Test
    fun aPlainTapMakesATabAndNeverShowsTheMenu() {
        // `(e.armed ?? t.defaultIntent) === "menu" && t.available ? open : closed + open.new-tab`
        val armed = SidePanel.plusReduce(
            SidePanel.PlusState(),
            SidePanel.PlusEvent.Arm(SidePanel.Intent.NEW),
        ).first
        val out = SidePanel.plusReduce(
            armed,
            SidePanel.PlusEvent.OpenChange(open = true, available = true),
        )
        assertEquals(SidePanel.PlusStatus.CLOSED, out.first.status)
        assertEquals(listOf<SidePanel.PlusEffect>(SidePanel.PlusEffect.OpenNewTab), out.second)
        // `lT` clears `armed`.
        assertNull(out.first.armed)
    }

    @Test
    fun aModifierHeldTapShowsTheRecentList() {
        val armed = SidePanel.plusReduce(
            SidePanel.PlusState(),
            SidePanel.PlusEvent.Arm(SidePanel.Intent.MENU),
        ).first
        val out = SidePanel.plusReduce(
            armed,
            SidePanel.PlusEvent.OpenChange(open = true, available = true),
        )
        assertEquals(SidePanel.PlusStatus.OPEN, out.first.status)
        assertTrue(out.second.isEmpty())
        assertTrue(SidePanel.plusOpen(out.first))
    }

    @Test
    fun anUnarmedOpenFallsBackToTheDefaultIntent() {
        // `e.armed ?? t.defaultIntent`
        val plain = SidePanel.plusReduce(
            SidePanel.PlusState(),
            SidePanel.PlusEvent.OpenChange(open = true, available = true, defaultIntent = SidePanel.Intent.NEW),
        )
        assertEquals(listOf<SidePanel.PlusEffect>(SidePanel.PlusEffect.OpenNewTab), plain.second)

        val fromMenu = SidePanel.plusReduce(
            SidePanel.PlusState(),
            SidePanel.PlusEvent.OpenChange(open = true, available = true, defaultIntent = SidePanel.Intent.MENU),
        )
        assertEquals(SidePanel.PlusStatus.OPEN, fromMenu.first.status)
    }

    @Test
    fun theMenuIsNotOfferedWhenThereIsNothingToReopen() {
        // `&& t.available` - with no closed tabs a modifier tap still makes a tab.
        val armed = SidePanel.plusReduce(
            SidePanel.PlusState(),
            SidePanel.PlusEvent.Arm(SidePanel.Intent.MENU),
        ).first
        val out = SidePanel.plusReduce(
            armed,
            SidePanel.PlusEvent.OpenChange(open = true, available = false),
        )
        assertEquals(SidePanel.PlusStatus.CLOSED, out.first.status)
        assertEquals(listOf<SidePanel.PlusEffect>(SidePanel.PlusEffect.OpenNewTab), out.second)
    }

    @Test
    fun closingThePopoverOnlyClearsTheArm() {
        // The disarm arm: `{armed: void 0}` and nothing else.
        val open = SidePanel.PlusState(status = SidePanel.PlusStatus.OPEN, armed = SidePanel.Intent.MENU)
        val out = SidePanel.plusReduce(open, SidePanel.PlusEvent.OpenChange(open = false, available = true))
        assertEquals(SidePanel.PlusStatus.OPEN, out.first.status)
        assertNull(out.first.armed)
    }

    @Test
    fun aContextMenuOpensTheListWhenThereIsSomethingToShow() {
        val out = SidePanel.plusReduce(SidePanel.PlusState(), SidePanel.PlusEvent.ContextMenu(available = true))
        assertEquals(SidePanel.PlusStatus.OPEN, out.first.status)

        val none = SidePanel.plusReduce(SidePanel.PlusState(), SidePanel.PlusEvent.ContextMenu(available = false))
        assertEquals(SidePanel.PlusStatus.CLOSED, none.first.status)
    }

    @Test
    fun theClosingTimerOnlyFiresForItsOwnAttempt() {
        // `cT`: attempt+1, shown, 50 ms.
        val (closing, effect) = SidePanel.plusClosing(0, hover = false, choice = "k")
        assertEquals(SidePanel.PlusStatus.CLOSING, closing.status)
        assertTrue(closing.shown)
        assertEquals("k", closing.choice)
        assertEquals(listOf<SidePanel.PlusEffect>(SidePanel.PlusEffect.TimerStart(1, 50)), effect)
        // `aT` - a closing menu with content still counts as open, so it can fade.
        assertTrue(SidePanel.plusOpen(closing))

        val stale = SidePanel.plusPopover(closing, SidePanel.PlusEvent.TimerFired(0))
        assertEquals(SidePanel.PlusStatus.CLOSING, stale.first.status)
        assertTrue(stale.second.isEmpty())

        val done = SidePanel.plusPopover(closing, SidePanel.PlusEvent.TimerFired(1))
        assertEquals(SidePanel.PlusStatus.CLOSED, done.first.status)
        assertFalse(SidePanel.plusOpen(done.first))
        // `run.choice` carries the row that was picked.
        assertEquals(listOf<SidePanel.PlusEffect>(SidePanel.PlusEffect.RunChoice("k")), done.second)
    }

    @Test
    fun aChosenRowClosesAfterFiftyMilliseconds() {
        val open = SidePanel.plusPopover(
            SidePanel.PlusState(),
            SidePanel.PlusEvent.ContextMenu(available = true),
        ).first
        assertEquals(SidePanel.PlusStatus.OPEN, open.status)

        val picked = SidePanel.plusPopover(open, SidePanel.PlusEvent.Select("file:a"))
        assertEquals(SidePanel.PlusStatus.CLOSING, picked.first.status)
        assertEquals(1, picked.first.attempt)
        assertTrue(picked.first.shown)
        assertEquals(listOf<SidePanel.PlusEffect>(SidePanel.PlusEffect.TimerStart(1, 50)), picked.second)
    }

    @Test
    fun aMenuThatReopensMidCloseStopsTheTimerAndRunsTheChoiceNow() {
        val closing = SidePanel.PlusState(
            status = SidePanel.PlusStatus.CLOSING,
            attempt = 1,
            shown = true,
            choice = "file:a",
        )
        val (next, effects) = SidePanel.plusPopover(
            closing,
            SidePanel.PlusEvent.ContextMenu(available = true),
        )
        assertEquals(SidePanel.PlusStatus.OPEN, next.status)
        assertEquals(2, next.attempt)
        // `{status:"open", attempt+1, hover}` is a fresh object: the old choice is gone.
        assertNull(next.choice)
        assertFalse(next.shown)
        assertEquals(
            listOf<SidePanel.PlusEffect>(
                SidePanel.PlusEffect.TimerStop,
                SidePanel.PlusEffect.RunChoice("file:a"),
            ),
            effects,
        )
    }

    @Test
    fun leavingTheControlForgetsTheArmedGesture() {
        val armed = SidePanel.PlusState(armed = SidePanel.Intent.MENU)
        val away = SidePanel.plusPopover(armed, SidePanel.PlusEvent.Hover(true)).first
        assertEquals(SidePanel.Intent.MENU, away.armed)
        val gone = SidePanel.plusPopover(away, SidePanel.PlusEvent.Hover(false)).first
        assertNull(gone.armed)
    }

    @Test
    fun disposingAClosingMenuStopsItsTimer() {
        val closing = SidePanel.PlusState(status = SidePanel.PlusStatus.CLOSING, attempt = 3, shown = true)
        val (next, effects) = SidePanel.plusPopover(closing, SidePanel.PlusEvent.Disposed)
        assertEquals(SidePanel.PlusStatus.DISPOSED, next.status)
        assertEquals(listOf<SidePanel.PlusEffect>(SidePanel.PlusEffect.TimerStop), effects)
        // And a disposed control ignores everything afterwards.
        val after = SidePanel.plusPopover(next, SidePanel.PlusEvent.Arm(SidePanel.Intent.NEW))
        assertEquals(next, after.first)
        assertTrue(after.second.isEmpty())
    }

    @Test
    fun theTooltipNeedsHoverAndALiveControl() {
        // `oT`: `status !== "disposed" && hover`
        assertTrue(SidePanel.plusHover(SidePanel.PlusState(hover = true)))
        assertFalse(SidePanel.plusHover(SidePanel.PlusState(hover = false)))
        assertFalse(
            SidePanel.plusHover(
                SidePanel.PlusState(status = SidePanel.PlusStatus.DISPOSED, hover = true),
            )
        )
    }
}
