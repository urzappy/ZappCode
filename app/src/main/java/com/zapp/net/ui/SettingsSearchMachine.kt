package com.zapp.net.ui

/**
 * PORT of `settings/search-machine.ts` - their settings search, verbatim.
 *
 * ## Why a machine and not a string
 *
 * Their search is not a filter box. It is a session with an origin, an activation counter, a
 * remembered scroll position, an expanded flag, and a reveal that walks four phases. Back does not
 * clear it; Back returns you to the results you came from, with the query still typed. Opening a
 * second result reuses the page but bumps the activation so the first result's reveal can never
 * highlight the second result's row.
 *
 * The phone had `var query by remember { mutableStateOf("") }` and `onOpen = { page = it; query =
 * "" }` - open a result and the search was gone.
 *
 * ## The two constants
 *
 *     SEARCH_REVEAL_WAIT_MS       5 000   how long a reveal looks for a row that may mount late
 *     SEARCH_REVEAL_ANIMATION_MS  2 500   how long a found row keeps its highlight
 *
 * ## The fence
 *
 * `activation` is the last activation issued, and only `opened` and `revealing` own it. Every
 * reveal event carries the activation it was issued under and is discarded if it does not match.
 * That is what stops a timer armed for one result from settling the next one.
 *
 * ## Purity
 *
 * No Android imports. [transition] returns the effects the caller must perform rather than
 * performing them, so the whole ladder can be walked from a test with no screen attached.
 */
object SettingsSearchMachine {

    /** How long a reveal looks for its target, which a page may mount late or never. */
    const val SEARCH_REVEAL_WAIT_MS = 5_000L

    /** How long a found row keeps its highlight when its animation never ends. */
    const val SEARCH_REVEAL_ANIMATION_MS = 2_500L

    // ---------------------------------------------------------------------------------------
    // state
    // ---------------------------------------------------------------------------------------

    enum class RevealPhase { NAVIGATING, SEEKING, HIGHLIGHTING, DONE }

    /**
     * One activation's reveal. Their `done` variant drops the view; the view is kept here because
     * nothing reads it once the phase is `DONE`, and a nullable field would be one more branch.
     */
    data class SearchReveal(val phase: RevealPhase, val view: SettingsView)

    sealed interface State {
        val activation: Int
    }

    /** The fields only a live search session carries. */
    sealed interface Active : State {
        val query: String
        val origin: SettingsView
        val highlighted: String
        val scrollTop: Int
        val expanded: Boolean
    }

    /** A state that owns a reveal: `revealing`, or `opened` before its reveal is done. */
    sealed interface Carrying : State {
        val reveal: SearchReveal
    }

    /** No query. [query] is non-empty only on the path where text was typed and then cleared. */
    data class Idle(
        override val activation: Int,
        val query: String = "",
    ) : State

    /** A Command Palette jump: it reveals its target without a sidebar search. */
    data class Revealing(
        override val activation: Int,
        override val reveal: SearchReveal,
    ) : Carrying

    data class Browsing(
        override val activation: Int,
        override val query: String,
        override val origin: SettingsView,
        override val highlighted: String,
        override val scrollTop: Int,
        override val expanded: Boolean,
    ) : Active

    data class Opened(
        override val activation: Int,
        override val query: String,
        override val origin: SettingsView,
        val selected: String,
        override val highlighted: String,
        override val scrollTop: Int,
        override val expanded: Boolean,
        override val reveal: SearchReveal,
    ) : Active, Carrying

    fun initialState(): State = Idle(activation = 0)

    // ---------------------------------------------------------------------------------------
    // events
    // ---------------------------------------------------------------------------------------

    sealed interface Event {
        data class InputChanged(val query: String, val view: SettingsView) : Event
        data object Expanded : Event
        data class Highlighted(val id: String) : Event
        data class Scrolled(val scrollTop: Int) : Event
        data class ResultOpened(val id: String, val destination: SettingsView) : Event
        data class PaletteOpened(val destination: SettingsView) : Event
        data class BackRequested(val view: SettingsView) : Event
        data class EscapePressed(val view: SettingsView) : Event
        data object Cleared : Event
        data class ViewSynced(val view: SettingsView) : Event
        data object ScreenUnmounted : Event
        data object SettingsClosed : Event
        data class RevealHighlighted(val activation: Int) : Event
        data class RevealCompleted(val activation: Int) : Event
        data class RevealTimedOut(val activation: Int) : Event
    }

    // ---------------------------------------------------------------------------------------
    // effects
    // ---------------------------------------------------------------------------------------

    sealed interface Effect {
        data class Navigate(val view: SettingsView) : Effect
        data object Exit : Effect
        data class RevealStart(
            val activation: Int,
            val view: SettingsView,
            val timeoutMs: Long,
        ) : Effect

        data class RevealHighlight(val activation: Int, val timeoutMs: Long) : Effect
        data object RevealCancel : Effect
    }

    data class Transition(val state: State, val effects: List<Effect> = emptyList())

    // ---------------------------------------------------------------------------------------
    // transition
    // ---------------------------------------------------------------------------------------

    fun transition(state: State, event: Event): Transition = when (event) {
        is Event.InputChanged -> input(state, event.query, event.view)

        Event.Expanded -> {
            val active = state as? Active
            if (active == null || active.expanded) unchanged(state)
            else changed(withExpanded(active, true))
        }

        is Event.Highlighted -> {
            val active = state as? Active
            if (active == null || event.id.isEmpty() || event.id == active.highlighted) unchanged(state)
            else changed(withHighlighted(active, event.id))
        }

        is Event.Scrolled -> {
            val active = state as? Active
            if (active == null || event.scrollTop == active.scrollTop) unchanged(state)
            else changed(withScroll(active, event.scrollTop))
        }

        is Event.ResultOpened -> {
            val active = state as? Active
            if (active == null) {
                unchanged(state)
            } else {
                val activation = state.activation + 1
                val view = event.destination.copy(searchActivation = activation)
                changed(
                    Opened(
                        activation = activation,
                        query = active.query,
                        origin = active.origin,
                        selected = event.id,
                        highlighted = event.id,
                        scrollTop = active.scrollTop,
                        expanded = false,
                        reveal = SearchReveal(RevealPhase.NAVIGATING, view),
                    ),
                    cancel(state) + Effect.Navigate(view),
                )
            }
        }

        is Event.PaletteOpened -> {
            val activation = state.activation + 1
            val view = event.destination.copy(searchActivation = activation)
            changed(
                Revealing(activation, SearchReveal(RevealPhase.NAVIGATING, view)),
                cancel(state) + Effect.Navigate(view),
            )
        }

        is Event.BackRequested -> {
            val opened = state as? Opened
            if (opened == null || !canReturnToSearchOrigin(opened, event.view)) unchanged(state)
            else returnToOrigin(opened)
        }

        is Event.EscapePressed -> {
            val opened = state as? Opened
            when {
                opened != null && canReturnToSearchOrigin(opened, event.view) -> returnToOrigin(opened)
                // The sidebar search only shows on root pages, so only there does Escape clear it.
                isSearchActive(state) && event.view.type == ViewType.RAIL ->
                    changed(idle(state), cancel(state))

                else -> Transition(state, listOf(Effect.Exit))
            }
        }

        Event.Cleared -> {
            if (state is Idle && state.query.isEmpty()) unchanged(state)
            else changed(idle(state), cancel(state))
        }

        is Event.ViewSynced -> synced(state, event.view)

        Event.ScreenUnmounted -> {
            // The page that held the reveal is gone; a remounted page starts it again.
            val carrying = state as? Carrying
            if (carrying == null || carrying.reveal.phase == RevealPhase.NAVIGATING) {
                unchanged(state)
            } else {
                changed(
                    withReveal(state, SearchReveal(RevealPhase.NAVIGATING, carrying.reveal.view)),
                    listOf(Effect.RevealCancel),
                )
            }
        }

        Event.SettingsClosed -> {
            if (state is Idle && state.query.isEmpty()) unchanged(state)
            else changed(idle(state), cancel(state))
        }

        is Event.RevealHighlighted -> {
            val carrying = state as? Carrying
            if (carrying == null ||
                event.activation != state.activation ||
                carrying.reveal.phase != RevealPhase.SEEKING
            ) {
                unchanged(state)
            } else {
                changed(
                    withReveal(state, SearchReveal(RevealPhase.HIGHLIGHTING, carrying.reveal.view)),
                    listOf(Effect.RevealHighlight(state.activation, SEARCH_REVEAL_ANIMATION_MS)),
                )
            }
        }

        is Event.RevealCompleted -> settle(state, event.activation)
        is Event.RevealTimedOut -> settle(state, event.activation)
    }

    /** `reveal.completed` and `reveal.timed-out` share one body in their switch. */
    private fun settle(state: State, activation: Int): Transition {
        val carrying = state as? Carrying
        if (carrying == null ||
            activation != state.activation ||
            carrying.reveal.phase == RevealPhase.NAVIGATING
        ) {
            return unchanged(state)
        }

        if (state is Revealing) return changed(idle(state), listOf(Effect.RevealCancel))

        return changed(
            withReveal(state, SearchReveal(RevealPhase.DONE, carrying.reveal.view)),
            listOf(Effect.RevealCancel),
        )
    }

    // ---------------------------------------------------------------------------------------
    // the two helpers their file keeps private
    // ---------------------------------------------------------------------------------------

    private fun input(state: State, query: String, view: SettingsView): Transition {
        if (query.isBlank()) {
            if (state is Idle) {
                return if (query == state.query) unchanged(state) else changed(state.copy(query = query))
            }
            return changed(Idle(state.activation, query), cancel(state))
        }

        // Typing on an opened result refines the results and keeps the opened page, its reveal,
        // and its way back.
        if (state is Opened) {
            return changed(
                state.copy(query = query, highlighted = "", scrollTop = 0, expanded = true),
            )
        }

        val origin = if (state is Browsing) state.origin else view.asOrigin()

        return changed(
            Browsing(
                activation = state.activation,
                query = query,
                origin = origin,
                highlighted = "",
                scrollTop = 0,
                expanded = true,
            ),
            cancel(state),
        )
    }

    private fun synced(state: State, view: SettingsView): Transition {
        if (state !is Opened && state !is Revealing) return unchanged(state)

        // A page reached another way (a link inside the page, history) ends the activation.
        if (view.searchActivation != state.activation) {
            if (state is Revealing) return changed(idle(state), listOf(Effect.RevealCancel))

            val opened = state as Opened
            return changed(
                Browsing(
                    activation = opened.activation,
                    query = opened.query,
                    origin = opened.origin,
                    highlighted = opened.highlighted,
                    scrollTop = opened.scrollTop,
                    expanded = opened.expanded,
                ),
                cancel(state),
            )
        }

        val reveal = (state as Carrying).reveal
        if (reveal.phase != RevealPhase.NAVIGATING) return unchanged(state)

        return changed(
            withReveal(state, SearchReveal(RevealPhase.SEEKING, reveal.view)),
            listOf(Effect.RevealStart(state.activation, reveal.view, SEARCH_REVEAL_WAIT_MS)),
        )
    }

    private fun returnToOrigin(state: Opened): Transition = changed(
        Browsing(
            activation = state.activation,
            query = state.query,
            origin = state.origin,
            highlighted = state.selected,
            scrollTop = state.scrollTop,
            expanded = true,
        ),
        cancel(state) + Effect.Navigate(state.origin),
    )

    private fun idle(state: State): State = Idle(state.activation, "")

    // ---------------------------------------------------------------------------------------
    // selectors - theirs, one for one
    // ---------------------------------------------------------------------------------------

    /** `selectSearchQuery` - a palette jump shows an empty field. */
    fun selectSearchQuery(state: State): String = when (state) {
        is Idle -> state.query
        is Browsing -> state.query
        is Opened -> state.query
        is Revealing -> ""
    }

    fun isSearchActive(state: State): Boolean = state is Browsing || state is Opened

    /** Whether the results are expanded; opening a result collapses them. */
    fun isSearchExpanded(state: State): Boolean = (state as? Active)?.expanded ?: false

    fun selectSearchSelected(state: State): String? = (state as? Opened)?.selected

    fun selectSearchHighlighted(state: State): String? =
        (state as? Active)?.highlighted?.ifEmpty { null }

    fun selectSearchScrollTop(state: State): Int = (state as? Active)?.scrollTop ?: 0

    /**
     * Whether Back and Escape on a page opened from a result return to where that search started.
     *
     * Their test is `view.type !== "root" && view.searchActivation === state.activation`, so a rail
     * page never returns to the search - correct on desktop, where the rail is always visible and
     * Back from it closes Settings.
     */
    fun canReturnToSearchOrigin(state: State, view: SettingsView): Boolean =
        state is Opened && view.type != ViewType.RAIL && view.searchActivation == state.activation

    // ---------------------------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------------------------

    private fun hasActiveReveal(state: State): Boolean =
        state is Revealing || (state is Opened && state.reveal.phase != RevealPhase.DONE)

    // A reveal still navigating has started nothing in the page yet.
    private fun cancel(state: State): List<Effect> {
        val carrying = state as? Carrying ?: return emptyList()
        return if (carrying.reveal.phase != RevealPhase.NAVIGATING) {
            listOf(Effect.RevealCancel)
        } else {
            emptyList()
        }
    }

    private fun withExpanded(state: Active, value: Boolean): State = when (state) {
        is Browsing -> state.copy(expanded = value)
        is Opened -> state.copy(expanded = value)
    }

    private fun withHighlighted(state: Active, value: String): State = when (state) {
        is Browsing -> state.copy(highlighted = value)
        is Opened -> state.copy(highlighted = value)
    }

    private fun withScroll(state: Active, value: Int): State = when (state) {
        is Browsing -> state.copy(scrollTop = value)
        is Opened -> state.copy(scrollTop = value)
    }

    private fun withReveal(state: State, reveal: SearchReveal): State = when (state) {
        is Revealing -> state.copy(reveal = reveal)
        is Opened -> state.copy(reveal = reveal)
        else -> state
    }

    private fun changed(state: State, effects: List<Effect> = emptyList()): Transition =
        Transition(state, effects)

    private fun unchanged(state: State): Transition = Transition(state, emptyList())
}
