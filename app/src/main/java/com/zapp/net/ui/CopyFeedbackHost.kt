package com.zapp.net.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Compose half of `copy.ts` (`:77` `createCopyFeedback`) — it runs the effects of
 * [CopyFeedback] against the phone's clipboard.
 *
 * Their `writeClipboard` has a textarea fallback because `navigator.clipboard` only exists in a
 * secure context. Android has no such split: `LocalClipboardManager.setText` is synchronous and
 * always succeeds, so every write resolves as `copy.succeeded` and the failure branch stays for
 * fidelity only.
 */
@Stable
class CopyFeedbackHost internal constructor(
    private val scope: CoroutineScope,
    /** Set once by [rememberCopyFeedback]; their `options.write`. */
    internal var writer: ((String) -> Unit)? = null,
) {

    var copied by mutableStateOf(false)
        private set

    private var state: CopyFeedback.State = CopyFeedback.INITIAL
    private var timer: Job? = null

    /** `copy.requested` (`:165`). */
    fun copy(text: String) = dispatch(CopyFeedback.Event.Requested(text))

    internal fun dispatch(event: CopyFeedback.Event) {
        val next = CopyFeedback.transition(state, event)
        if (next.state == state) return
        state = next.state
        copied = CopyFeedback.copied(state)
        next.effects.forEach(::run)
    }

    private fun run(effect: CopyFeedback.Effect) {
        when (effect) {
            // `:117` — `clear-reset`
            is CopyFeedback.Effect.ClearReset -> {
                timer?.cancel()
                timer = null
            }
            // `:131` — `schedule-reset`
            is CopyFeedback.Effect.ScheduleReset -> {
                timer?.cancel()
                timer = scope.launch {
                    delay(effect.delayMs.toLong())
                    dispatch(CopyFeedback.Event.ResetDue(effect.attempt))
                }
            }
            // `:143` — `write`, then report the outcome.
            is CopyFeedback.Effect.Write -> {
                val ok = runCatching { writer?.invoke(effect.text) }.isSuccess
                dispatch(
                    if (ok) CopyFeedback.Event.Succeeded(effect.attempt)
                    else CopyFeedback.Event.Failed(effect.attempt),
                )
            }
        }
    }
}

@Composable
fun rememberCopyFeedback(): CopyFeedbackHost {
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    val host = remember { CopyFeedbackHost(scope) }
    host.writer = { text -> clip.setText(AnnotatedString(text)) }
    DisposableEffect(host) {
        onDispose { host.dispatch(CopyFeedback.Event.Disposed) }
    }
    return host
}
