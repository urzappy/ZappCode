package com.zapp.net.ui

import com.zapp.net.GateState

/**
 * PORT of `app/src/settings/servers/machine.ts` - the availability model every Settings surface
 * reads instead of its own predicate, and the Retry fence that goes with it.
 *
 * ## Why a model instead of a boolean
 *
 * The phone used to draw one dot from `connected`: green or red. Theirs answers four questions
 * separately - can the per-server pages load (`ready`), is it still coming up (`starting`), did it
 * refuse our credentials (`unauthorized`), or is it simply not there (`unreachable`, with a reason
 * of `offline` / `incompatible` / `stopped`). Search, the project route, the switcher dot and the
 * Retry button all read the same value, so they cannot disagree.
 *
 * ## What this host can actually observe
 *
 * Their `ServerHealth` comes from a health poll of a managed server. The phone has one remote
 * server and three facts about it: `connected`, `connecting`, and the gate verdict
 * (`Ok` / `Denied` / `Unreachable`). [healthOf] is that mapping. The `incompatible` and `stopped`
 * arms exist because their resolver distinguishes them; nothing on this phone produces them, and
 * nothing here pretends to.
 */

/** `SERVER_RETRY_TIMEOUT_MS` - longer than a health probe can take. */
const val SERVER_RETRY_TIMEOUT_MS = 35_000L

/** `ServerHealth` - the arms this phone can observe, plus the two only a managed server reports. */
sealed interface ServerHealth {
    data object Healthy : ServerHealth
    data object Checking : ServerHealth
    data object Starting : ServerHealth
    data object Unauthorized : ServerHealth
    data object Incompatible : ServerHealth
    data object Stopped : ServerHealth
    data object Unreachable : ServerHealth
}

/** `SettingsServerAvailability`. `connection` is the base URL the per-server pages render with. */
sealed interface ServerAvailability {
    data class Ready(val connection: String, val health: ServerHealth?) : ServerAvailability
    data object Starting : ServerAvailability
    data class Unauthorized(val connection: String?) : ServerAvailability

    data class Unreachable(
        val reason: Reason,
        val connection: String?,
    ) : ServerAvailability {
        enum class Reason(val wire: String) {
            OFFLINE("offline"),
            INCOMPATIBLE("incompatible"),
            STOPPED("stopped"),
        }
    }
}

object ServerAvailabilityMachine {

    /**
     * `resolveServerAvailability` - same order, same answers.
     *
     * The plugin-server branches are absent because this phone has no contributed servers; their
     * place in the order is the `connection == null` line, which is what those branches end in.
     */
    fun resolve(
        connection: String?,
        health: ServerHealth?,
        signedOut: Boolean = false,
    ): ServerAvailability {
        if (connection == null) {
            return ServerAvailability.Unreachable(ServerAvailability.Unreachable.Reason.OFFLINE, null)
        }
        if (signedOut || health == ServerHealth.Unauthorized) {
            return ServerAvailability.Unauthorized(connection)
        }
        if (health == ServerHealth.Starting) return ServerAvailability.Starting
        if (health == ServerHealth.Incompatible) {
            return ServerAvailability.Unreachable(
                ServerAvailability.Unreachable.Reason.INCOMPATIBLE, connection,
            )
        }
        if (health == ServerHealth.Stopped) {
            return ServerAvailability.Unreachable(
                ServerAvailability.Unreachable.Reason.STOPPED, connection,
            )
        }
        if (health == ServerHealth.Unreachable) {
            return ServerAvailability.Unreachable(
                ServerAvailability.Unreachable.Reason.OFFLINE, connection,
            )
        }
        return ServerAvailability.Ready(connection, health)
    }

    /** `serverPagesConnection` - the connection per-server pages render with. */
    fun pagesConnection(availability: ServerAvailability): String? =
        (availability as? ServerAvailability.Ready)?.connection

    /** `canIndexServerSettings` - search lists a server's settings and projects only while ready. */
    fun canIndex(availability: ServerAvailability): Boolean =
        availability is ServerAvailability.Ready

    /** `canHoldProjectRoute` - a starting server connects shortly, so its project page waits. */
    fun canHoldProjectRoute(availability: ServerAvailability): Boolean =
        availability is ServerAvailability.Ready || availability is ServerAvailability.Starting

    /** `selectSettingsServerHealth` - what the switcher's status dot shows. */
    fun selectHealth(availability: ServerAvailability): ServerHealth? = when (availability) {
        is ServerAvailability.Ready -> availability.health
        ServerAvailability.Starting -> ServerHealth.Starting
        is ServerAvailability.Unauthorized -> ServerHealth.Unauthorized
        is ServerAvailability.Unreachable -> when (availability.reason) {
            ServerAvailability.Unreachable.Reason.OFFLINE -> ServerHealth.Unreachable
            ServerAvailability.Unreachable.Reason.INCOMPATIBLE -> ServerHealth.Incompatible
            ServerAvailability.Unreachable.Reason.STOPPED -> ServerHealth.Stopped
        }
    }

    /** `isSettingsServerUnreachable`. */
    fun isUnreachable(availability: ServerAvailability): Boolean =
        availability is ServerAvailability.Unreachable ||
            availability is ServerAvailability.Unauthorized

    /** `unauthorizedHttpServer` - every connection this phone makes is HTTP. */
    fun unauthorizedConnection(availability: ServerAvailability): String? =
        (availability as? ServerAvailability.Unauthorized)?.connection

    // ---------------------------------------------------------------------------------------
    // their Retry fence
    // ---------------------------------------------------------------------------------------

    sealed interface RetryState {
        val server: String
        val attempt: Int

        data class Idle(override val server: String, override val attempt: Int = 0) : RetryState
        data class Retrying(override val server: String, override val attempt: Int) : RetryState
    }

    sealed interface RetryEvent {
        data class ServerSynced(val server: String) : RetryEvent
        data object RetryClicked : RetryEvent
        data class RetrySettled(val attempt: Int) : RetryEvent
        data class RetryTimedOut(val attempt: Int) : RetryEvent
        data object Disposed : RetryEvent
    }

    sealed interface RetryEffect {
        data class RetryRun(val attempt: Int, val timeoutMs: Long) : RetryEffect
        data object TimeoutCancel : RetryEffect
    }

    data class RetryTransition(
        val state: RetryState,
        val effects: List<RetryEffect> = emptyList(),
    )

    fun initialRetryState(server: String): RetryState = RetryState.Idle(server)

    /**
     * `transitionServerRetry` - every event is fenced to the server it was issued for and, while a
     * retry runs, to its attempt, so a probe that answers after the page moved on cannot settle
     * someone else's button.
     */
    fun transition(state: RetryState, event: RetryEvent): RetryTransition = when (event) {
        is RetryEvent.ServerSynced -> {
            if (event.server == state.server) {
                RetryTransition(state)
            } else {
                RetryTransition(
                    RetryState.Idle(event.server, state.attempt),
                    if (state is RetryState.Retrying) listOf(RetryEffect.TimeoutCancel) else emptyList(),
                )
            }
        }

        RetryEvent.RetryClicked -> {
            if (state is RetryState.Retrying) {
                RetryTransition(state)
            } else {
                val attempt = state.attempt + 1
                RetryTransition(
                    RetryState.Retrying(state.server, attempt),
                    listOf(RetryEffect.RetryRun(attempt, SERVER_RETRY_TIMEOUT_MS)),
                )
            }
        }

        RetryEvent.Disposed -> {
            if (state is RetryState.Retrying) {
                RetryTransition(RetryState.Idle(state.server, state.attempt), listOf(RetryEffect.TimeoutCancel))
            } else {
                RetryTransition(state)
            }
        }

        is RetryEvent.RetrySettled -> settle(state, event.attempt, cancelTimer = true)
        is RetryEvent.RetryTimedOut -> settle(state, event.attempt, cancelTimer = false)
    }

    private fun settle(state: RetryState, attempt: Int, cancelTimer: Boolean): RetryTransition {
        if (state !is RetryState.Retrying) return RetryTransition(state)
        if (attempt != state.attempt) return RetryTransition(state)
        return RetryTransition(
            RetryState.Idle(state.server, state.attempt),
            if (cancelTimer) listOf(RetryEffect.TimeoutCancel) else emptyList(),
        )
    }

    fun isRetrying(state: RetryState): Boolean = state is RetryState.Retrying

    // ---------------------------------------------------------------------------------------
    // the phone's own mapping
    // ---------------------------------------------------------------------------------------

    /**
     * The three facts the view model has, as the health their resolver expects.
     *
     * Their health poll has a `checking` arm, and a probe in flight is exactly that: no verdict
     * yet. So a probe on a connected server is `checking` (still ready, still indexed), a probe on
     * a disconnected one is `starting`, and only a finished probe produces `unauthorized` or
     * `unreachable`. The gate is the verdict record: `Ok` only after a probe accepted the
     * credential, `Denied` only after a 401/403.
     */
    fun healthOf(connected: Boolean, busy: Boolean, gate: GateState): ServerHealth = when {
        busy && connected -> ServerHealth.Checking
        busy -> ServerHealth.Starting
        gate is GateState.Denied -> ServerHealth.Unauthorized
        gate is GateState.Unreachable -> ServerHealth.Unreachable
        connected || gate is GateState.Ok -> ServerHealth.Healthy
        else -> ServerHealth.Unreachable
    }
}
