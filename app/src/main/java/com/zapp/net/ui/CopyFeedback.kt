package com.zapp.net.ui

/**
 * PORT of `session-ui/src/components/copy-machine.ts` — the copy button's feedback machine.
 *
 * Their doc comment: "`copying` keeps the checkmark of a click inside the feedback window
 * while the next write settles, and `attempt` lets only the latest write and its own reset
 * timer move the button."
 *
 * The phone previously used a bare `var copied` plus `delay(1400)`, which is wrong twice: the
 * checkmark vanished 600 ms too early, and a second tap inside the window cleared it before the
 * write was acknowledged. This is the exact transition table instead.
 */
object CopyFeedback {

    /** `:1` — `export const COPY_FEEDBACK_MS = 2_000`. */
    const val MS = 2_000

    sealed interface State {
        /** `:15` — `attempts` is the running count carried across disposals. */
        data class Idle(val attempts: Int) : State

        /** `:17` — `copied` keeps the checkmark of a click inside the window. */
        data class Copying(val attempt: Int, val copied: Boolean) : State

        /** `:19` */
        data class Copied(val attempt: Int) : State
    }

    sealed interface Event {
        /** `:25` */
        data class Requested(val text: String) : Event

        /** `:27` */
        data class Succeeded(val attempt: Int) : Event

        /** `:29` */
        data class Failed(val attempt: Int) : Event

        /** `:31` */
        data class ResetDue(val attempt: Int) : Event

        /** `:33` */
        data object Disposed : Event
    }

    sealed interface Effect {
        /** `:39` */
        data class Write(val attempt: Int, val text: String) : Effect

        /** `:41` */
        data class ScheduleReset(val attempt: Int, val delayMs: Int) : Effect

        /** `:43` */
        data object ClearReset : Effect
    }

    data class Transition(val state: State, val effects: List<Effect>)

    /** `:57` */
    val INITIAL: State = State.Idle(0)

    /** `:69` */
    fun transition(state: State, event: Event): Transition {
        if (event is Event.Requested) {
            // `:73` — an empty copy is not an attempt at all.
            if (event.text.isEmpty()) return Transition(state, emptyList())
            val attempt = when (state) {
                is State.Idle -> state.attempts
                is State.Copying -> state.attempt
                is State.Copied -> state.attempt
            } + 1
            val write = Effect.Write(attempt, event.text)
            // `:81` — a click inside the feedback window keeps the checkmark up.
            if (state is State.Copied) {
                return Transition(State.Copying(attempt, copied = true), listOf(Effect.ClearReset, write))
            }
            return Transition(
                State.Copying(attempt, copied = state is State.Copying && state.copied),
                listOf(write),
            )
        }

        // `:99` — from idle nothing else can move it.
        if (state is State.Idle) return Transition(state, emptyList())

        // `:103`
        if (event is Event.Disposed) {
            val attempt = when (state) {
                is State.Copying -> state.attempt
                is State.Copied -> state.attempt
                is State.Idle -> state.attempts
            }
            return Transition(
                State.Idle(attempt),
                if (state is State.Copied) listOf(Effect.ClearReset) else emptyList(),
            )
        }

        val attempt = when (state) {
            is State.Copying -> state.attempt
            is State.Copied -> state.attempt
            is State.Idle -> state.attempts
        }
        val eventAttempt = when (event) {
            is Event.Succeeded -> event.attempt
            is Event.Failed -> event.attempt
            is Event.ResetDue -> event.attempt
            else -> return Transition(state, emptyList())
        }

        // `:115` — only the latest write and its own timer may move the button.
        if (eventAttempt != attempt) return Transition(state, emptyList())

        // `:119`
        if (event is Event.Succeeded) {
            if (state !is State.Copying) return Transition(state, emptyList())
            return Transition(
                State.Copied(state.attempt),
                listOf(Effect.ScheduleReset(state.attempt, MS)),
            )
        }

        // `:137`
        if (event is Event.Failed && state !is State.Copying) return Transition(state, emptyList())

        // `:141`
        if (event is Event.ResetDue && state !is State.Copied) return Transition(state, emptyList())

        // `:145` — a failure, or a reset that is due, drops back to idle.
        return Transition(State.Idle(attempt), emptyList())
    }

    /** `:151` — `viewCopyFeedback`. */
    fun copied(state: State): Boolean =
        state is State.Copied || (state is State.Copying && state.copied)
}
