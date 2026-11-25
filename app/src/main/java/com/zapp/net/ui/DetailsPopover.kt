package com.zapp.net.ui

/**
 * PORT of `gui-plugins/src/details/popover-machine.ts` - the whole file.
 *
 * Their popover has one non-obvious rule: **how** it opened decides **what** gets focus. A
 * pointer open focuses the popover itself, so the first row does not show a focus ring next to a
 * finger; a keyboard open lets the first row take focus. A deferred focus carries an attempt
 * number, and a focus whose attempt the state no longer holds is stale and does nothing.
 *
 * On a phone every open is a pointer open, so `Via.Keyboard` never occurs in the app - but the
 * branch stays, because the machine is theirs and a port that deletes a branch is not a port.
 * The tests exercise both.
 */
object DetailsPopover {

    /** `DetailsPopoverVia` */
    enum class Via { Pointer, Keyboard }

    /** `DetailsPopoverState` */
    sealed class State {
        /** `{status:"closed", issued}` */
        data class Closed(val issued: Int = 0) : State()

        /** `{status:"open", issued, attempt, via, focusPending}` */
        data class Open(
            val issued: Int,
            val attempt: Int,
            val via: Via,
            val focusPending: Boolean,
        ) : State()

        /** `{status:"disposed"}` */
        object Disposed : State()
    }

    /** `DetailsPopoverEvent` */
    sealed class Event {
        /** The trigger opened the popover. */
        data class Opened(val via: Via) : Event()

        /** The shortcut toggled it. */
        data class Toggled(val via: Via) : Event()

        /** Escape, an outside press, the trigger again, an action, or the end of the visit. */
        object Closed : Event()

        /** The content is about to focus its first row. */
        object AutofocusIntercepted : Event()

        data class FocusDue(val attempt: Int) : Event()

        object Disposed : Event()
    }

    /** `DetailsPopoverEffect` */
    sealed class Effect {
        data class ScheduleFocus(val attempt: Int) : Effect()
        object CancelFocus : Effect()
        object FocusContent : Effect()
    }

    /** `DetailsPopoverTransition` */
    data class Transition(val state: State, val effects: List<Effect>)

    /** `initialDetailsPopoverState` */
    val initial: State = State.Closed(0)

    private val NONE = emptyList<Effect>()
    private val CANCEL_FOCUS = listOf<Effect>(Effect.CancelFocus)
    private val FOCUS_CONTENT = listOf<Effect>(Effect.FocusContent)

    /** `transitionDetailsPopover(state, event)` - branch for branch, in their order. */
    fun transition(state: State, event: Event): Transition {
        // A disposed popover is inert: every later event returns the same state with no effects.
        if (state is State.Disposed) return Transition(state, NONE)

        if (event is Event.Disposed) {
            return Transition(
                State.Disposed,
                if (state is State.Open && state.focusPending) CANCEL_FOCUS else NONE,
            )
        }

        if (state is State.Closed) {
            val via = when (event) {
                is Event.Opened -> event.via
                is Event.Toggled -> event.via
                else -> return Transition(state, NONE)
            }
            // Each open takes the next attempt number from `issued`.
            val attempt = state.issued + 1
            return Transition(
                State.Open(issued = attempt, attempt = attempt, via = via, focusPending = false),
                NONE,
            )
        }

        val open = state as State.Open

        if (event is Event.Closed || event is Event.Toggled) {
            return Transition(
                State.Closed(open.issued),
                if (open.focusPending) CANCEL_FOCUS else NONE,
            )
        }

        if (event is Event.AutofocusIntercepted) {
            // Only a pointer open defers focus, and only once.
            if (open.via != Via.Pointer || open.focusPending) return Transition(open, NONE)
            return Transition(
                open.copy(focusPending = true),
                listOf(Effect.ScheduleFocus(open.attempt)),
            )
        }

        if (event is Event.FocusDue && open.focusPending && event.attempt == open.attempt) {
            return Transition(open.copy(focusPending = false), FOCUS_CONTENT)
        }

        return Transition(open, NONE)
    }

    /** `detailsPopoverOpen(state)` */
    fun isOpen(state: State): Boolean = state is State.Open

    /** `detailsPopoverFocusesContent(state)` - a pointer open focuses the content, not a row. */
    fun focusesContent(state: State): Boolean = state is State.Open && state.via == Via.Pointer
}
