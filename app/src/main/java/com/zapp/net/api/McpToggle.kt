package com.zapp.net.api

/**
 * Their MCP toggle machine, verbatim.
 *
 * Source: `panel-QKwfqHQW.js` @ 6 853-7 400 - `Te` (initial state), `Ee` (dispatch), `ke` (reduce),
 * `Ae` (toggle-result reducer), `je` (settle), `Me` (busy), `De` (busy/pending), `J` (close-dialog
 * effects). Their Settings -> Extensions panel is the surface; ours is the Extensions overlay.
 *
 * The three rules that are easy to lose:
 *
 * 1. **A second tap is dropped, not queued.** `if (Me(e,t.name) || t.status === 'pending') return
 *    {state:e,effects:K}` - a server already toggling is ignored, and so is every tap while the
 *    whole row reports `pending`.
 * 2. **A stale result is dropped.** `Ae` opens with `if (!e.toggles.some(e=>e.name===t.name &&
 *    e.attempt===t.attempt)) return {state:e,effects:K}`. Results are matched on name **and**
 *    attempt, so a reply from a toggle the user already abandoned cannot move the UI.
 * 3. **The machine disposes itself.** `je` flips to `disposed` once nothing is mounted, nothing is
 *    in flight and no dialog is open - and a disposed machine ignores every later event,
 *    `mounted` included. That is why a re-open has to build a fresh state.
 *
 * The 60 s `we` timeout is the caller's job: [Effect.RunToggle] carries it, and the harness that
 * runs the effect feeds back [Event.TimedOut] when it expires.
 */
object McpToggle {

    /** `we = 6e4` - how long a single toggle may take before the UI gives up on it. */
    const val TIMEOUT_MS = 60_000L

    /** `q = {status:"closed"}` - the empty dialog. */
    val CLOSED_DIALOG = Dialog()

    /** The error dialog. `status` is `open` or `closed`; the rest is only meaningful when open. */
    data class Dialog(
        val status: String = "closed",
        val attempt: Int = 0,
        val name: String = "",
        val error: String? = null,
        /** `copied === error` means the copy button already fired for this exact text. */
        val copied: String? = null,
    ) {
        val open: Boolean get() = status == "open"
        val copiedNow: Boolean get() = error != null && copied == error
    }

    /** One in-flight toggle. `attempt` is the identity a result is matched against. */
    data class Toggle(val name: String, val attempt: Int)

    /** `Te` - `{status:'active', mounts:0, issued:0, toggles:[], dialog:{status:'closed'}}`. */
    data class State(
        val status: String = "active",
        val mounts: Int = 0,
        val issued: Int = 0,
        val toggles: List<Toggle> = emptyList(),
        val dialog: Dialog = CLOSED_DIALOG,
    ) {
        val disposed: Boolean get() = status == "disposed"

        /** `Me(e,t)` - is a toggle for this name already in flight. */
        fun busy(name: String): Boolean = !disposed && toggles.any { it.name == name }

        /** `De(e,t,n)` - the row's disabled state. */
        fun busyOrPending(name: String, status: String?): Boolean =
            busy(name) || status == "pending"
    }

    sealed interface Event {
        data object Mounted : Event
        data object Unmounted : Event

        /** `toggle.requested` - `status` is the server's current `status.status`. */
        data class Requested(val name: String, val enabled: Boolean, val status: String?) : Event

        data class Authorizing(
            val name: String,
            val attempt: Int,
            val url: String,
            /** Their `signIn` payload, present only when the server asked for one. */
            val signIn: String? = null,
        ) : Event

        data class Succeeded(val name: String, val attempt: Int) : Event
        data class Failed(
            val name: String,
            val attempt: Int,
            val error: String,
            /** `true` when the error came from the server rather than the transport. */
            val server: Boolean = false,
        ) : Event

        data class TimedOut(val name: String, val attempt: Int) : Event

        data class ErrorOpened(val name: String, val error: String) : Event
        data class ErrorDismissed(val attempt: Int) : Event
        data class ErrorCopied(val attempt: Int, val error: String) : Event

        /** Their dialog's live re-read of the server's status. */
        data class ServerUpdated(
            val attempt: Int,
            val loaded: Boolean,
            val status: String?,
            val error: String? = null,
        ) : Event

        data object Disposed : Event
    }

    sealed interface Effect {
        data class RunToggle(
            val name: String,
            val attempt: Int,
            val enabled: Boolean,
            val timeoutMs: Long,
        ) : Effect

        data class AbortToggle(val attempt: Int) : Effect
        data class OpenDialog(val attempt: Int, val name: String) : Effect
        data class CloseDialog(val attempt: Int) : Effect
        data class OpenExternal(val url: String) : Effect
        data class OpenSignIn(val name: String, val signIn: String) : Effect
        data class ToastFailed(val message: String) : Effect
        data class ToastTimedOut(val name: String) : Effect
    }

    data class Result(val state: State, val effects: List<Effect> = emptyList())

    /** `Ee` - dispatch, then settle. */
    fun dispatch(state: State, event: Event): Result = settle(reduce(state, event))

    /** `ke` - the reducer. */
    fun reduce(state: State, event: Event): Result {
        if (state.disposed) return Result(state)

        when (event) {
            is Event.Mounted -> return Result(state.copy(mounts = state.mounts + 1))
            is Event.Unmounted ->
                return Result(state.copy(mounts = maxOf(0, state.mounts - 1)))

            is Event.Requested -> {
                if (state.busy(event.name) || event.status == "pending") return Result(state)
                val issued = state.issued + 1
                return Result(
                    state.copy(
                        issued = issued,
                        toggles = state.toggles + Toggle(event.name, issued),
                    ),
                    listOf(
                        Effect.RunToggle(event.name, issued, event.enabled, TIMEOUT_MS),
                    ),
                )
            }

            is Event.Authorizing, is Event.Succeeded, is Event.Failed, is Event.TimedOut ->
                return toggleResult(state, event)

            is Event.ErrorOpened -> {
                val issued = state.issued + 1
                return Result(
                    state.copy(
                        issued = issued,
                        dialog = Dialog("open", issued, event.name, event.error, null),
                    ),
                    closeDialog(state.dialog) + Effect.OpenDialog(issued, event.name),
                )
            }

            is Event.Disposed -> return Result(
                State(status = "disposed"),
                state.toggles.map { Effect.AbortToggle(it.attempt) } + closeDialog(state.dialog),
            )

            else -> Unit
        }

        // Everything below is dialog-scoped: a result for a dialog that is not the open one is
        // dropped (`let n=e.dialog; if(n.status!==\`open\`||n.attempt!==t.attempt) return`).
        val dialog = state.dialog
        when (event) {
            is Event.ErrorDismissed -> {
                if (!dialog.open || dialog.attempt != event.attempt) return Result(state)
                return Result(state.copy(dialog = CLOSED_DIALOG))
            }

            is Event.ErrorCopied -> {
                if (!dialog.open || dialog.attempt != event.attempt) return Result(state)
                return Result(state.copy(dialog = dialog.copy(copied = event.error)))
            }

            is Event.ServerUpdated -> {
                if (!dialog.open || dialog.attempt != event.attempt) return Result(state)
                if (!event.loaded) return Result(state)
                val s = event.status
                if (s == null || s == "connected" || s == "disabled") {
                    // `{state:{...e,dialog:q},effects:J(n)}` - the dialog closes **and** the
                    // harness is told to close it, because theirs is a separate mounted dialog.
                    return Result(state.copy(dialog = CLOSED_DIALOG), closeDialog(dialog))
                }
                if (s != "failed" || event.error == dialog.error) return Result(state)
                return Result(state.copy(dialog = dialog.copy(error = event.error)))
            }

            else -> return Result(state)
        }
    }

    /** `Ae` - a toggle finished, failed, timed out or asked for a browser. */
    private fun toggleResult(state: State, event: Event): Result {
        val name: String
        val attempt: Int
        when (event) {
            is Event.Authorizing -> { name = event.name; attempt = event.attempt }
            is Event.Succeeded -> { name = event.name; attempt = event.attempt }
            is Event.Failed -> { name = event.name; attempt = event.attempt }
            is Event.TimedOut -> { name = event.name; attempt = event.attempt }
            else -> return Result(state)
        }
        if (state.toggles.none { it.name == name && it.attempt == attempt }) return Result(state)

        val dialog = state.dialog
        val dialogOpen = dialog.open && dialog.name == name

        if (event is Event.Authorizing) {
            val effects = buildList {
                if (dialogOpen) add(Effect.CloseDialog(dialog.attempt))
                add(Effect.OpenExternal(event.url))
                event.signIn?.let { add(Effect.OpenSignIn(name, it)) }
            }
            return Result(
                if (dialogOpen) state.copy(dialog = CLOSED_DIALOG) else state,
                effects,
            )
        }

        val without = state.copy(toggles = state.toggles.filter { it.attempt != attempt })
        val abort = Effect.AbortToggle(attempt)
        return when (event) {
            is Event.Succeeded ->
                Result(
                    if (dialogOpen) without.copy(dialog = CLOSED_DIALOG) else without,
                    listOf(abort) + closeDialog(dialog),
                )

            is Event.TimedOut ->
                Result(without, listOf(abort, Effect.ToastTimedOut(name)))

            is Event.Failed -> when {
                dialogOpen && event.server ->
                    Result(without.copy(dialog = dialog.copy(error = event.error)), listOf(abort))

                else -> Result(
                    without,
                    listOf(
                        abort,
                        Effect.ToastFailed(
                            if (event.server) "$name: ${event.error}" else event.error,
                        ),
                    ),
                )
            }

            else -> Result(without, listOf(abort))
        }
    }

    /** `je` - a machine with nothing mounted, nothing in flight and no dialog is disposed. */
    fun settle(result: Result): Result {
        val s = result.state
        if (s.status != "active") return result
        if (s.mounts > 0 || s.toggles.isNotEmpty() || s.dialog.open) return result
        return Result(State(status = "disposed"), result.effects)
    }

    /** `J(e)` - the close-dialog effect, present only when one is open. */
    private fun closeDialog(dialog: Dialog): List<Effect> =
        if (dialog.open) listOf(Effect.CloseDialog(dialog.attempt)) else emptyList()
}
