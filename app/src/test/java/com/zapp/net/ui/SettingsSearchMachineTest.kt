package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walks `settings/search-machine.ts` event by event.
 *
 * The point of these tests is not coverage for its own sake: their machine has four states and
 * fourteen events, and several events are deliberately ignored in the wrong state. A port that
 * "helpfully" handles them everywhere behaves differently - Back clears a search it should have
 * returned to, or a timer armed for one result settles the next one. Each assertion below names the
 * behaviour that would break if the branch were widened.
 */
class SettingsSearchMachineTest {

    private val rail = SettingsView(type = ViewType.RAIL, tab = Page.PREFERENCES)
    private val timeline = SettingsView(
        type = ViewType.PAGE,
        tab = Page.PREFERENCES,
        target = "settings-timeline-detail",
    )

    private fun send(
        state: SettingsSearchMachine.State,
        event: SettingsSearchMachine.Event,
    ): SettingsSearchMachine.Transition = SettingsSearchMachine.transition(state, event)

    /** Type a query from the rail, the way the field does. */
    private fun browsing(query: String = "timeline"): SettingsSearchMachine.State =
        send(SettingsSearchMachine.initialState(), SettingsSearchMachine.Event.InputChanged(query, rail)).state

    /** Type, then open one result. Returns the state and the view it navigated to. */
    private fun opened(
        id: String = "settings-timeline-detail",
        destination: SettingsView = timeline,
    ): Pair<SettingsSearchMachine.State, SettingsView> {
        val t = send(browsing(), SettingsSearchMachine.Event.ResultOpened(id, destination))
        val navigate = t.effects.filterIsInstance<SettingsSearchMachine.Effect.Navigate>().single()
        return t.state to navigate.view
    }

    // ---- idle ------------------------------------------------------------------------------

    @Test
    fun `the initial state is idle with an empty query`() {
        val state = SettingsSearchMachine.initialState()
        assertTrue(state is SettingsSearchMachine.Idle)
        assertEquals("", SettingsSearchMachine.selectSearchQuery(state))
        assertFalse(SettingsSearchMachine.isSearchActive(state))
        assertFalse(SettingsSearchMachine.isSearchExpanded(state))
        assertNull(SettingsSearchMachine.selectSearchSelected(state))
        assertNull(SettingsSearchMachine.selectSearchHighlighted(state))
        assertEquals(0, SettingsSearchMachine.selectSearchScrollTop(state))
    }

    @Test
    fun `a cleared idle query keeps its activation`() {
        val state = SettingsSearchMachine.Idle(activation = 4, query = "")
        val t = send(state, SettingsSearchMachine.Event.Cleared)
        assertEquals(state, t.state)
        assertTrue(t.effects.isEmpty())
    }

    // ---- input -----------------------------------------------------------------------------

    @Test
    fun `typing starts browsing with the rail as origin`() {
        val state = browsing("timeline")
        assertTrue(state is SettingsSearchMachine.Browsing)
        val active = state as SettingsSearchMachine.Browsing
        assertEquals("timeline", active.query)
        assertEquals(rail.asOrigin(), active.origin)
        assertTrue(active.expanded)
        assertTrue(SettingsSearchMachine.isSearchActive(state))
    }

    @Test
    fun `clearing the query goes back to idle`() {
        val t = send(browsing("timeline"), SettingsSearchMachine.Event.InputChanged("   ", rail))
        assertTrue(t.state is SettingsSearchMachine.Idle)
        assertEquals("   ", SettingsSearchMachine.selectSearchQuery(t.state))
    }

    @Test
    fun `typing while a result is open refines and keeps the selection`() {
        val (state, view) = opened()
        val t = send(state, SettingsSearchMachine.Event.InputChanged("timeline detail", view))
        val next = t.state as SettingsSearchMachine.Opened
        assertEquals("timeline detail", next.query)
        assertEquals("settings-timeline-detail", next.selected)
        assertTrue(next.expanded)
        assertEquals(0, next.scrollTop)
        assertTrue("refining must not navigate", t.effects.isEmpty())
    }

    // ---- browsing-only events ---------------------------------------------------------------

    @Test
    fun `highlight and scroll are ignored outside a live search`() {
        val idle = SettingsSearchMachine.initialState()
        assertEquals(idle, send(idle, SettingsSearchMachine.Event.Highlighted("x")).state)
        assertEquals(idle, send(idle, SettingsSearchMachine.Event.Scrolled(40)).state)
        assertEquals(idle, send(idle, SettingsSearchMachine.Event.Expanded).state)
    }

    @Test
    fun `highlight is stored and an empty id is ignored`() {
        val state = browsing()
        val first = send(state, SettingsSearchMachine.Event.Highlighted("settings-theme")).state
        assertEquals("settings-theme", SettingsSearchMachine.selectSearchHighlighted(first))
        assertEquals(first, send(first, SettingsSearchMachine.Event.Highlighted("")).state)
        assertEquals(first, send(first, SettingsSearchMachine.Event.Highlighted("settings-theme")).state)
    }

    @Test
    fun `scroll position is remembered`() {
        val state = send(browsing(), SettingsSearchMachine.Event.Scrolled(320)).state
        assertEquals(320, SettingsSearchMachine.selectSearchScrollTop(state))
    }

    // ---- opening ---------------------------------------------------------------------------

    @Test
    fun `opening a result bumps the activation and navigates with it stamped`() {
        val (state, view) = opened()
        assertTrue(state is SettingsSearchMachine.Opened)
        assertEquals(1, state.activation)
        assertEquals(1, view.searchActivation)
        assertEquals(view, (state as SettingsSearchMachine.Opened).reveal.view)
        assertEquals(
            SettingsSearchMachine.RevealPhase.NAVIGATING,
            (state as SettingsSearchMachine.Opened).reveal.phase,
        )
        assertFalse("opening collapses the results", SettingsSearchMachine.isSearchExpanded(state))
        assertEquals("settings-timeline-detail", SettingsSearchMachine.selectSearchSelected(state))
    }

    @Test
    fun `a second result gets a new activation so the first reveal cannot settle it`() {
        val (first, _) = opened()
        val second = send(
            first,
            SettingsSearchMachine.Event.ResultOpened("settings-theme", SettingsView(ViewType.PAGE, Page.APPEARANCE, target = "settings-theme")),
        ).state
        assertEquals(2, second.activation)

        // The timer armed for result 1 fires late; it must not touch result 2.
        val stale = send(second, SettingsSearchMachine.Event.RevealTimedOut(1))
        assertEquals(second, stale.state)
        assertTrue(stale.effects.isEmpty())
    }

    @Test
    fun `opening a result from idle is ignored`() {
        val idle = SettingsSearchMachine.initialState()
        val t = send(idle, SettingsSearchMachine.Event.ResultOpened("settings-theme", timeline))
        assertEquals(idle, t.state)
        assertTrue(t.effects.isEmpty())
    }

    // ---- the reveal ladder ------------------------------------------------------------------

    @Test
    fun `a synced view moves the reveal to seeking and arms the wait`() {
        val (state, view) = opened()
        val t = send(state, SettingsSearchMachine.Event.ViewSynced(view))
        val next = t.state as SettingsSearchMachine.Opened
        assertEquals(SettingsSearchMachine.RevealPhase.SEEKING, next.reveal.phase)
        val start = t.effects.filterIsInstance<SettingsSearchMachine.Effect.RevealStart>().single()
        assertEquals(1, start.activation)
        assertEquals(5_000L, start.timeoutMs)
        assertEquals(SettingsSearchMachine.SEARCH_REVEAL_WAIT_MS, start.timeoutMs)
    }

    @Test
    fun `a view that lost its activation ends the reveal`() {
        val (state, view) = opened()
        val stray = view.copy(searchActivation = null)
        val t = send(state, SettingsSearchMachine.Event.ViewSynced(stray))
        assertTrue("a page reached another way is browsing, not opened", t.state is SettingsSearchMachine.Browsing)
        assertEquals("timeline", (t.state as SettingsSearchMachine.Browsing).query)
    }

    @Test
    fun `highlighting arms the animation timer`() {
        val (state, view) = opened()
        val seeking = send(state, SettingsSearchMachine.Event.ViewSynced(view)).state
        val t = send(seeking, SettingsSearchMachine.Event.RevealHighlighted(1))
        assertEquals(
            SettingsSearchMachine.RevealPhase.HIGHLIGHTING,
            (t.state as SettingsSearchMachine.Opened).reveal.phase,
        )
        val highlight = t.effects.filterIsInstance<SettingsSearchMachine.Effect.RevealHighlight>().single()
        assertEquals(2_500L, highlight.timeoutMs)
        assertEquals(SettingsSearchMachine.SEARCH_REVEAL_ANIMATION_MS, highlight.timeoutMs)
    }

    @Test
    fun `a highlight for the wrong activation is dropped`() {
        val (state, view) = opened()
        val seeking = send(state, SettingsSearchMachine.Event.ViewSynced(view)).state
        val t = send(seeking, SettingsSearchMachine.Event.RevealHighlighted(7))
        assertEquals(seeking, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `a highlight before seeking is dropped`() {
        val (state, _) = opened()
        val t = send(state, SettingsSearchMachine.Event.RevealHighlighted(1))
        assertEquals(state, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `a timed out reveal settles an opened page to done`() {
        val (state, view) = opened()
        val seeking = send(state, SettingsSearchMachine.Event.ViewSynced(view)).state
        val t = send(seeking, SettingsSearchMachine.Event.RevealTimedOut(1))
        assertEquals(
            SettingsSearchMachine.RevealPhase.DONE,
            (t.state as SettingsSearchMachine.Opened).reveal.phase,
        )
        assertEquals(listOf(SettingsSearchMachine.Effect.RevealCancel), t.effects)
        // The page is still open and the query is still there.
        assertEquals("timeline", SettingsSearchMachine.selectSearchQuery(t.state))
    }

    @Test
    fun `a timeout while still navigating is dropped`() {
        val (state, _) = opened()
        val t = send(state, SettingsSearchMachine.Event.RevealTimedOut(1))
        assertEquals(state, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `unmounting mid reveal restarts it from navigating`() {
        val (state, view) = opened()
        val seeking = send(state, SettingsSearchMachine.Event.ViewSynced(view)).state
        val t = send(seeking, SettingsSearchMachine.Event.ScreenUnmounted)
        assertEquals(
            SettingsSearchMachine.RevealPhase.NAVIGATING,
            (t.state as SettingsSearchMachine.Opened).reveal.phase,
        )
        assertEquals(listOf(SettingsSearchMachine.Effect.RevealCancel), t.effects)
    }

    // ---- palette jump -----------------------------------------------------------------------

    @Test
    fun `a palette jump reveals with an empty query and no origin`() {
        val t = send(
            SettingsSearchMachine.initialState(),
            SettingsSearchMachine.Event.PaletteOpened(timeline),
        )
        assertTrue(t.state is SettingsSearchMachine.Revealing)
        assertEquals("", SettingsSearchMachine.selectSearchQuery(t.state))
        assertFalse(SettingsSearchMachine.isSearchActive(t.state))
        assertEquals(1, t.state.activation)

        val synced = send(t.state, SettingsSearchMachine.Event.ViewSynced(timeline.copy(searchActivation = 1))).state
        val start = send(synced, SettingsSearchMachine.Event.ViewSynced(timeline.copy(searchActivation = 1)))
        assertEquals(
            SettingsSearchMachine.RevealPhase.SEEKING,
            (start.state as SettingsSearchMachine.Revealing).reveal.phase,
        )
    }

    @Test
    fun `a finished palette reveal returns to idle`() {
        val revealing = send(
            SettingsSearchMachine.initialState(),
            SettingsSearchMachine.Event.PaletteOpened(timeline),
        ).state
        val seeking = send(
            revealing,
            SettingsSearchMachine.Event.ViewSynced(timeline.copy(searchActivation = 1)),
        ).state
        val done = send(seeking, SettingsSearchMachine.Event.RevealCompleted(1))
        assertTrue(done.state is SettingsSearchMachine.Idle)
        assertEquals(listOf(SettingsSearchMachine.Effect.RevealCancel), done.effects)
    }

    // ---- back and escape --------------------------------------------------------------------

    @Test
    fun `back on a result returns to the results with the query intact`() {
        val (state, view) = opened()
        val t = send(state, SettingsSearchMachine.Event.BackRequested(view))
        val next = t.state as SettingsSearchMachine.Browsing
        assertEquals("timeline", next.query)
        assertEquals("settings-timeline-detail", next.highlighted)
        assertTrue(next.expanded)
        assertEquals(listOf(SettingsSearchMachine.Effect.Navigate(rail)), t.effects)
    }

    @Test
    fun `back on a page reached another way is not ours to handle`() {
        val (state, view) = opened()
        val stray = view.copy(searchActivation = null)
        assertFalse(SettingsSearchMachine.canReturnToSearchOrigin(state, stray))
        val t = send(state, SettingsSearchMachine.Event.BackRequested(stray))
        assertEquals(state, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `back on the rail never returns to the search`() {
        val (state, _) = opened()
        assertFalse(SettingsSearchMachine.canReturnToSearchOrigin(state, rail))
        val t = send(state, SettingsSearchMachine.Event.BackRequested(rail))
        assertEquals(state, t.state)
    }

    @Test
    fun `escape on the rail clears the query instead of closing settings`() {
        val t = send(browsing("timeline"), SettingsSearchMachine.Event.EscapePressed(rail))
        assertTrue(t.state is SettingsSearchMachine.Idle)
        assertFalse(t.effects.any { it == SettingsSearchMachine.Effect.Exit })
    }

    @Test
    fun `escape with no search asks the caller to exit`() {
        val t = send(SettingsSearchMachine.initialState(), SettingsSearchMachine.Event.EscapePressed(rail))
        assertEquals(listOf(SettingsSearchMachine.Effect.Exit), t.effects)
    }

    @Test
    fun `closing settings clears the search`() {
        val t = send(browsing("timeline"), SettingsSearchMachine.Event.SettingsClosed)
        assertTrue(t.state is SettingsSearchMachine.Idle)
        assertEquals("", SettingsSearchMachine.selectSearchQuery(t.state))
    }
}
