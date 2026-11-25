package com.zapp.net.ui

// Port of `app/src/session/requests/auto-approve-machine.ts` from OpenCode 2.3.2.
//
// Every constant, ordering rule and edge case below is theirs. What is new is only the
// shape: TypeScript discriminated unions become a sealed interface, and the identity test
// `result.state !== state` becomes an explicit `changed` flag so a caller can write the
// store exactly as often as they do.
//
// The two machines are independent on purpose. `AutoApproveMachine` owns one permission
// request's reply lifecycle; `AutoApproveSweepMachine` owns one connect's recovery sweep.
// A reply or timer from a cancelled or resolved cycle can never move a later one, because
// every event carries the `runId` and `attempt` it was scheduled under.

/** `AUTO_APPROVE_RETRY_LIMIT` - replies at attempt 0, 1 and 2, then the request is surfaced. */
const val AUTO_APPROVE_RETRY_LIMIT = 2

/** `AUTO_APPROVE_RETRY_DELAY_MS` - multiplied by the attempt, so 1000 ms then 2000 ms. */
const val AUTO_APPROVE_RETRY_DELAY_MS = 1_000L

/**
 * `AUTO_APPROVE_REPLY_TIMEOUT_MS`.
 *
 * A reply hides its request from the dock while it runs, so one that never answers gives up
 * on this attempt instead. Retrying is safe: the server answers a request it already settled
 * with not found, which the data layer counts as replied.
 */
const val AUTO_APPROVE_REPLY_TIMEOUT_MS = 15_000L

/**
 * `AUTO_APPROVE_SWEEP_TIMEOUT_MS`.
 *
 * One sweep lists active sessions and then every location's requests through the shared
 * request queue, so it gets more time than one reply before it counts as failed and retries.
 */
const val AUTO_APPROVE_SWEEP_TIMEOUT_MS = 30_000L

/** `AutoApproveState`. */
sealed interface AutoApproveState {
    /** Nothing tried to answer yet. Requests in this state have no entry in their store. */
    data object Pending : AutoApproveState

    data class Replying(
        val sessionID: String,
        val projectID: String?,
        val runId: String,
        val attempt: Int,
    ) : AutoApproveState

    data class WaitingRetry(
        val sessionID: String,
        val projectID: String?,
        val runId: String,
        val attempt: Int,
    ) : AutoApproveState

    data object Replied : AutoApproveState

    data class GaveUp(val projectID: String?) : AutoApproveState
}

/** `AutoApproveEvent`. */
sealed interface AutoApproveEvent {
    data class Approve(
        val sessionID: String,
        val projectID: String?,
        val runId: String,
    ) : AutoApproveEvent

    data class ReplySucceeded(val runId: String, val attempt: Int) : AutoApproveEvent

    data class ReplyFailed(val runId: String, val attempt: Int) : AutoApproveEvent

    data class ReplyTimedOut(val runId: String, val attempt: Int) : AutoApproveEvent

    data class RetryDue(val runId: String, val attempt: Int) : AutoApproveEvent

    data object Cancel : AutoApproveEvent

    data object Resolved : AutoApproveEvent
}

/** `AutoApproveEffect`. */
sealed interface AutoApproveEffect {
    data class Reply(
        val sessionID: String,
        val runId: String,
        val attempt: Int,
        val timeoutMs: Long,
    ) : AutoApproveEffect

    /** Stops waiting on the current reply. The request itself runs on; runId fences its late result. */
    data object ClearReplyTimer : AutoApproveEffect

    data class ScheduleRetry(
        val runId: String,
        val attempt: Int,
        val delayMs: Long,
    ) : AutoApproveEffect

    data object CancelRetry : AutoApproveEffect
}

/**
 * `AutoApproveTransition`.
 *
 * `changed` is `result.state !== state` from their code: false means the caller must not write
 * the store and the effects list is empty.
 */
data class AutoApproveTransition(
    val state: AutoApproveState,
    val effects: List<AutoApproveEffect>,
    val changed: Boolean,
)

/** `INITIAL_AUTO_APPROVE_STATE`. */
val INITIAL_AUTO_APPROVE_STATE: AutoApproveState = AutoApproveState.Pending

object AutoApproveMachine {

    fun transition(state: AutoApproveState, event: AutoApproveEvent): AutoApproveTransition {
        // A request nobody tried to approve has nothing to settle when another client answers it.
        if (event is AutoApproveEvent.Resolved) {
            if (state is AutoApproveState.Pending || state is AutoApproveState.Replied) return unchanged(state)
            return changed(AutoApproveState.Replied, stop(state))
        }

        // Turning auto-approve off ends the cycle, so a reply still in flight settles into
        // nothing and schedules no retry.
        if (event is AutoApproveEvent.Cancel) {
            if (state is AutoApproveState.Pending || state is AutoApproveState.Replied) return unchanged(state)
            return changed(INITIAL_AUTO_APPROVE_STATE, stop(state))
        }

        // Store scans and sweeps approve repeatedly; only a request no cycle owns starts one.
        // An exhausted request stays surfaced until someone answers it or auto-approve is
        // turned off and on again.
        if (event is AutoApproveEvent.Approve) {
            if (state !is AutoApproveState.Pending) return unchanged(state)
            return changed(
                AutoApproveState.Replying(event.sessionID, event.projectID, event.runId, 0),
                listOf(reply(event.sessionID, event.runId, 0)),
            )
        }

        if (event is AutoApproveEvent.ReplySucceeded) {
            if (state !is AutoApproveState.Replying || !current(state, event.runId, event.attempt)) {
                return unchanged(state)
            }
            return changed(AutoApproveState.Replied, listOf(AutoApproveEffect.ClearReplyTimer))
        }

        if (event is AutoApproveEvent.ReplyFailed || event is AutoApproveEvent.ReplyTimedOut) {
            val (runId, attempt) = fence(event)
            if (state !is AutoApproveState.Replying || !current(state, runId, attempt)) {
                return unchanged(state)
            }
            if (state.attempt >= AUTO_APPROVE_RETRY_LIMIT) {
                return changed(AutoApproveState.GaveUp(state.projectID), listOf(AutoApproveEffect.ClearReplyTimer))
            }
            val next = state.attempt + 1
            return changed(
                state.copy(attempt = next).toWaitingRetry(),
                listOf(
                    AutoApproveEffect.ClearReplyTimer,
                    AutoApproveEffect.ScheduleRetry(state.runId, next, AUTO_APPROVE_RETRY_DELAY_MS * next),
                ),
            )
        }

        // Only `retry.due` reaches here.
        val due = event as AutoApproveEvent.RetryDue
        if (state !is AutoApproveState.WaitingRetry || !current(state, due.runId, due.attempt)) {
            return unchanged(state)
        }
        return changed(
            AutoApproveState.Replying(state.sessionID, state.projectID, state.runId, state.attempt),
            listOf(reply(state.sessionID, state.runId, state.attempt)),
        )
    }

    /**
     * `needsUser` - whether a pending request waits on the user, so the permission dock shows it
     * and every badge counts it. Auto-approve hides it only while a cycle can still answer it.
     */
    fun needsUser(state: AutoApproveState, autoApprove: Boolean): Boolean = when (state) {
        is AutoApproveState.Replied -> false
        is AutoApproveState.GaveUp -> true
        else -> !autoApprove
    }

    private fun AutoApproveState.Replying.toWaitingRetry() =
        AutoApproveState.WaitingRetry(sessionID, projectID, runId, attempt)

    private fun fence(event: AutoApproveEvent): Pair<String, Int> = when (event) {
        is AutoApproveEvent.ReplyFailed -> event.runId to event.attempt
        is AutoApproveEvent.ReplyTimedOut -> event.runId to event.attempt
        else -> "" to -1
    }

    private fun reply(sessionID: String, runId: String, attempt: Int) =
        AutoApproveEffect.Reply(sessionID, runId, attempt, AUTO_APPROVE_REPLY_TIMEOUT_MS)

    private fun stop(state: AutoApproveState): List<AutoApproveEffect> = when (state) {
        is AutoApproveState.Replying -> listOf(AutoApproveEffect.ClearReplyTimer)
        is AutoApproveState.WaitingRetry -> listOf(AutoApproveEffect.CancelRetry)
        else -> emptyList()
    }
}

// ---------------------------------------------------------------------------------------
// The connect sweep
// ---------------------------------------------------------------------------------------

/**
 * `AutoApproveSweepState`.
 *
 * A finished and an exhausted sweep both rest in `idle`; nothing tells them apart until the
 * next start.
 */
sealed interface AutoApproveSweepState {
    data object Idle : AutoApproveSweepState

    data class Sweeping(val runId: String, val attempt: Int) : AutoApproveSweepState

    data class WaitingRetry(val runId: String, val attempt: Int) : AutoApproveSweepState
}

/** `AutoApproveSweepEvent`. */
sealed interface AutoApproveSweepEvent {
    data class Start(val runId: String) : AutoApproveSweepEvent

    data class Succeeded(val runId: String, val attempt: Int) : AutoApproveSweepEvent

    data class Failed(val runId: String, val attempt: Int) : AutoApproveSweepEvent

    data class TimedOut(val runId: String, val attempt: Int) : AutoApproveSweepEvent

    data class RetryDue(val runId: String, val attempt: Int) : AutoApproveSweepEvent

    data object Cancel : AutoApproveSweepEvent
}

/** `AutoApproveSweepEffect`. */
sealed interface AutoApproveSweepEffect {
    data class Run(val runId: String, val attempt: Int, val timeoutMs: Long) : AutoApproveSweepEffect

    /** Stops the current sweep's requests and its timeout. */
    data object Abort : AutoApproveSweepEffect

    data class ScheduleRetry(val runId: String, val attempt: Int, val delayMs: Long) : AutoApproveSweepEffect

    data object CancelRetry : AutoApproveSweepEffect
}

data class AutoApproveSweepTransition(
    val state: AutoApproveSweepState,
    val effects: List<AutoApproveSweepEffect>,
    val changed: Boolean,
)

/** `INITIAL_AUTO_APPROVE_SWEEP_STATE`. */
val INITIAL_AUTO_APPROVE_SWEEP_STATE: AutoApproveSweepState = AutoApproveSweepState.Idle

object AutoApproveSweepMachine {

    fun transition(
        state: AutoApproveSweepState,
        event: AutoApproveSweepEvent,
    ): AutoApproveSweepTransition {
        // A newer connect or setting change replaces the sweep in progress, or the retry it waits for.
        if (event is AutoApproveSweepEvent.Start) {
            return sweepChanged(
                AutoApproveSweepState.Sweeping(event.runId, 0),
                stopSweep(state) + run(event.runId, 0),
            )
        }

        if (event is AutoApproveSweepEvent.Cancel) {
            if (state is AutoApproveSweepState.Idle) return sweepUnchanged(state)
            return sweepChanged(INITIAL_AUTO_APPROVE_SWEEP_STATE, stopSweep(state))
        }

        if (event is AutoApproveSweepEvent.RetryDue) {
            if (state !is AutoApproveSweepState.WaitingRetry || !current(state, event.runId, event.attempt)) {
                return sweepUnchanged(state)
            }
            return sweepChanged(
                AutoApproveSweepState.Sweeping(state.runId, state.attempt),
                listOf(run(state.runId, state.attempt)),
            )
        }

        val (runId, attempt) = fenceSweep(event)
        if (state !is AutoApproveSweepState.Sweeping || !current(state, runId, attempt)) {
            return sweepUnchanged(state)
        }

        // An incomplete sweep leaves pending requests hidden with no later trigger to recover
        // them, so it retries a bounded number of times.
        if (event is AutoApproveSweepEvent.Succeeded || state.attempt >= AUTO_APPROVE_RETRY_LIMIT) {
            return sweepChanged(INITIAL_AUTO_APPROVE_SWEEP_STATE, listOf(AutoApproveSweepEffect.Abort))
        }
        val next = state.attempt + 1
        return sweepChanged(
            AutoApproveSweepState.WaitingRetry(state.runId, next),
            listOf(
                AutoApproveSweepEffect.Abort,
                AutoApproveSweepEffect.ScheduleRetry(state.runId, next, AUTO_APPROVE_RETRY_DELAY_MS * next),
            ),
        )
    }

    private fun fenceSweep(event: AutoApproveSweepEvent): Pair<String, Int> = when (event) {
        is AutoApproveSweepEvent.Succeeded -> event.runId to event.attempt
        is AutoApproveSweepEvent.Failed -> event.runId to event.attempt
        is AutoApproveSweepEvent.TimedOut -> event.runId to event.attempt
        else -> "" to -1
    }

    private fun run(runId: String, attempt: Int) =
        AutoApproveSweepEffect.Run(runId, attempt, AUTO_APPROVE_SWEEP_TIMEOUT_MS)

    private fun stopSweep(state: AutoApproveSweepState): List<AutoApproveSweepEffect> = when (state) {
        is AutoApproveSweepState.Sweeping -> listOf(AutoApproveSweepEffect.Abort)
        is AutoApproveSweepState.WaitingRetry -> listOf(AutoApproveSweepEffect.CancelRetry)
        else -> emptyList()
    }
}

// ---------------------------------------------------------------------------------------
// Scoping
// ---------------------------------------------------------------------------------------

/**
 * `runtime/server/scope.ts`. The separator is a null byte, so a project id can never collide
 * with a scope by concatenation.
 */
object ScopedKey {
    const val SEPARATOR: Char = '\u0000'

    fun from(scope: String, vararg parts: String): String =
        (listOf(scope) + parts).joinToString(SEPARATOR.toString())

    fun prefix(scope: String, vararg parts: String): String = from(scope, *parts) + SEPARATOR

    /** `projectSettingsKey` - undefined for an unknown project, which falls back to the default. */
    fun projectSettingsKey(scope: String, projectID: String?): String? =
        projectID?.let { from(scope, it) }
}

/**
 * `settings/model.tsx` `permissions.autoApproves` / `autoApprovesOnServer`.
 *
 * `autoApproves(project) = override(project) ?? default`, and a server is worth sweeping when
 * the default is on or any project under it is on.
 */
class PermissionScope(
    private val scope: String,
    private val default: Boolean,
    private val overrides: Map<String, Boolean> = emptyMap(),
) {
    fun autoApproves(projectID: String?): Boolean {
        val key = ScopedKey.projectSettingsKey(scope, projectID) ?: return default
        return overrides[key] ?: default
    }

    fun autoApprovesOnServer(): Boolean {
        if (default) return true
        val prefix = ScopedKey.prefix(scope)
        return overrides.any { (key, value) -> value && key.startsWith(prefix) }
    }
}

// ---------------------------------------------------------------------------------------

private fun current(state: AutoApproveState, runId: String, attempt: Int): Boolean = when (state) {
    is AutoApproveState.Replying -> state.runId == runId && state.attempt == attempt
    is AutoApproveState.WaitingRetry -> state.runId == runId && state.attempt == attempt
    else -> false
}

private fun current(state: AutoApproveSweepState, runId: String, attempt: Int): Boolean = when (state) {
    is AutoApproveSweepState.Sweeping -> state.runId == runId && state.attempt == attempt
    is AutoApproveSweepState.WaitingRetry -> state.runId == runId && state.attempt == attempt
    else -> false
}

private fun changed(
    state: AutoApproveState,
    effects: List<AutoApproveEffect> = emptyList(),
) = AutoApproveTransition(state, effects, true)

private fun unchanged(state: AutoApproveState) = AutoApproveTransition(state, emptyList(), false)

private fun sweepChanged(
    state: AutoApproveSweepState,
    effects: List<AutoApproveSweepEffect> = emptyList(),
) = AutoApproveSweepTransition(state, effects, true)

private fun sweepUnchanged(state: AutoApproveSweepState) =
    AutoApproveSweepTransition(state, emptyList(), false)
