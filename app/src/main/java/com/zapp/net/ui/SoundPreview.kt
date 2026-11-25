package com.zapp.net.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PORT of `app/src/settings/general/sound-preview-machine.ts` (218 lines) and the controller half
 * of `settings/general/behavior.ts` (`createSoundPreviewController`).
 *
 * ## What it is for
 *
 * Their sound picker auditions. Moving through the list does not fire a sound per row - it fires
 * one, for the row the pointer **settles** on. That is the whole reason this is a machine and not a
 * `play(id)` call: 100 ms of silence after the last move, then one load, one play, and a 5 s leash
 * on a file that never loads.
 *
 * Their comments, which are the specification:
 *
 *  - `SOUND_PREVIEW_DEBOUNCE_MS = 100` - "Moving through the sound list plays only the sound it
 *    settles on."
 *  - `SOUND_PREVIEW_LOAD_TIMEOUT_MS = 5_000` - "A sound file that never loads gives up rather than
 *    playing long after the user moved on."
 *  - "A sound that loads after it was superseded, stopped or unmounted is stopped as it arrives."
 *
 * ## The order that matters
 *
 * `transitionSoundPreview` checks a late `load.succeeded` **before** it checks `disposed`, so a
 * sound that arrives after the page unmounted is still stopped. Reordering those two lines leaks a
 * playing sound. The guards below keep their order for exactly that reason.
 */
object SoundPreviewMachine {

    /** `SOUND_PREVIEW_DEBOUNCE_MS` */
    const val DEBOUNCE_MS = 100L

    /** `SOUND_PREVIEW_LOAD_TIMEOUT_MS` */
    const val LOAD_TIMEOUT_MS = 5_000L

    /** `Stop` - their `() => void` that silences the sound. */
    fun interface Stop {
        fun stop()
    }

    sealed interface State {
        val attempt: Int

        data class Idle(override val attempt: Int) : State
        data class Debouncing(override val attempt: Int, val id: String) : State
        data class Loading(override val attempt: Int, val id: String) : State
        data class Playing(override val attempt: Int, val id: String, val active: Stop) : State
        data object Disposed : State {
            override val attempt: Int = 0
        }
    }

    sealed interface Event {
        data class PlayRequested(val id: String?) : Event
        data object StopRequested : Event
        data class DebounceElapsed(val attempt: Int) : Event

        /** `active` stops the sound; null when nothing played. */
        data class LoadSucceeded(val attempt: Int, val active: Stop?) : Event
        data class LoadFailed(val attempt: Int) : Event
        data class LoadTimedOut(val attempt: Int) : Event
        data object Disposed : Event
    }

    sealed interface Effect {
        data class TimerStart(val kind: TimerKind, val attempt: Int, val delayMs: Long) : Effect
        data object TimerStop : Effect
        data class AudioLoad(val attempt: Int, val id: String) : Effect
        data class AudioStop(val active: Stop) : Effect
    }

    enum class TimerKind { DEBOUNCE, LOAD }

    data class Transition(val state: State, val effects: List<Effect> = emptyList())

    fun initial(): State = State.Idle(0)

    fun transition(state: State, event: Event): Transition {
        // Before anything else: a load that lands after it was superseded, stopped or unmounted is
        // stopped as it arrives. Their line 50 runs ahead of their `disposed` guard on purpose.
        if (event is Event.LoadSucceeded &&
            (state !is State.Loading || state.attempt != event.attempt)
        ) {
            return Transition(
                state,
                if (event.active == null) emptyList() else listOf(Effect.AudioStop(event.active)),
            )
        }

        if (state is State.Disposed) return Transition(state)

        if (event is Event.Disposed) return Transition(State.Disposed, release(state))

        if (event is Event.PlayRequested || event is Event.StopRequested) {
            val id = (event as? Event.PlayRequested)?.id
            // Their `if (!id)`: undefined **and** the empty string are "no id".
            if (id.isNullOrEmpty()) {
                if (state is State.Idle) return Transition(state)
                return Transition(State.Idle(state.attempt + 1), release(state))
            }
            val attempt = state.attempt + 1
            return Transition(
                State.Debouncing(attempt, id),
                buildList {
                    if (state is State.Playing) addAll(release(state))
                    add(Effect.TimerStart(TimerKind.DEBOUNCE, attempt, DEBOUNCE_MS))
                },
            )
        }

        if (event is Event.DebounceElapsed) {
            if (state !is State.Debouncing || state.attempt != event.attempt) {
                return Transition(state)
            }
            return Transition(
                State.Loading(state.attempt, state.id),
                listOf(
                    Effect.AudioLoad(state.attempt, state.id),
                    Effect.TimerStart(TimerKind.LOAD, state.attempt, LOAD_TIMEOUT_MS),
                ),
            )
        }

        // Everything below is a load outcome, and only the current attempt may settle.
        if (state !is State.Loading || state.attempt != event.attemptForSettle()) {
            return Transition(state)
        }

        if (event is Event.LoadSucceeded && event.active != null) {
            return Transition(
                State.Playing(state.attempt, state.id, event.active),
                listOf(Effect.TimerStop),
            )
        }

        // A failed load, a timeout, or a success that produced nothing playable: back to idle on
        // the **same** attempt, exactly as their `{ status: "idle", attempt: state.attempt }`.
        return Transition(State.Idle(state.attempt), listOf(Effect.TimerStop))
    }

    private fun Event.attemptForSettle(): Int = when (this) {
        is Event.LoadSucceeded -> attempt
        is Event.LoadFailed -> attempt
        is Event.LoadTimedOut -> attempt
        else -> -1
    }

    /** "What leaving `state` must stop: its timer or its sound." */
    private fun release(state: State): List<Effect> = when (state) {
        is State.Debouncing, is State.Loading -> listOf(Effect.TimerStop)
        is State.Playing -> listOf(Effect.AudioStop(state.active))
        else -> emptyList()
    }
}

/**
 * `createSoundPreviewController` - one state, one timer, and a player that hands back its own stop.
 *
 * Their version keeps `timer` as a single `setTimeout` handle and clears it on every
 * `timer.start`; this keeps one `Job` and cancels it, which is the same rule.
 *
 * [player] returns null when there is nothing to play, or throws when the file cannot be decoded -
 * both settle as `load.failed`, which is exactly what their `Promise.try(...).then(ok, fail)` does.
 */
class SoundPreviewController(
    private val scope: CoroutineScope,
    private val player: suspend (String) -> SoundPreviewMachine.Stop?,
) {
    private var state: SoundPreviewMachine.State = SoundPreviewMachine.initial()
    private var timer: Job? = null

    /** `play(id)` - `null` is their "no id", which stops. */
    fun play(id: String?) = dispatch(SoundPreviewMachine.Event.PlayRequested(id))

    /** `stop()` */
    fun stop() = dispatch(SoundPreviewMachine.Event.StopRequested)

    /** Their `onCleanup(() => dispatch({ type: "disposed" }))`. */
    fun dispose() = dispatch(SoundPreviewMachine.Event.Disposed)

    private fun dispatch(event: SoundPreviewMachine.Event) {
        val next = SoundPreviewMachine.transition(state, event)
        state = next.state
        next.effects.forEach(::run)
    }

    private fun run(effect: SoundPreviewMachine.Effect) {
        when (effect) {
            is SoundPreviewMachine.Effect.TimerStart -> {
                timer?.cancel()
                timer = scope.launch {
                    delay(effect.delayMs)
                    dispatch(
                        if (effect.kind == SoundPreviewMachine.TimerKind.DEBOUNCE) {
                            SoundPreviewMachine.Event.DebounceElapsed(effect.attempt)
                        } else {
                            SoundPreviewMachine.Event.LoadTimedOut(effect.attempt)
                        },
                    )
                }
            }

            SoundPreviewMachine.Effect.TimerStop -> {
                timer?.cancel()
                timer = null
            }

            is SoundPreviewMachine.Effect.AudioLoad -> {
                val attempt = effect.attempt
                scope.launch {
                    val active = try {
                        player(effect.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        dispatch(SoundPreviewMachine.Event.LoadFailed(attempt))
                        return@launch
                    }
                    dispatch(SoundPreviewMachine.Event.LoadSucceeded(attempt, active))
                }
            }

            is SoundPreviewMachine.Effect.AudioStop -> effect.active.stop()
        }
    }
}
