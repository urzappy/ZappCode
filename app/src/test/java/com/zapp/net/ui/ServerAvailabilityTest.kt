package com.zapp.net.ui

import com.zapp.net.GateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `settings/servers/machine.ts`, one consequence at a time.
 *
 * Their resolver is a priority list, so the tests are written as "this input wins over that one"
 * rather than as a table of every combination: the order is the whole point of the function.
 */
class ServerAvailabilityTest {

    private val base = "http://192.168.1.7:4096"

    // ---- resolveServerAvailability -----------------------------------------------------------

    @Test
    fun `no connection is offline, whatever the health says`() {
        val a = ServerAvailabilityMachine.resolve(null, ServerHealth.Healthy)
        assertTrue(a is ServerAvailability.Unreachable)
        assertEquals(
            ServerAvailability.Unreachable.Reason.OFFLINE,
            (a as ServerAvailability.Unreachable).reason,
        )
        assertNull(a.connection)
    }

    @Test
    fun `a refused credential is unauthorized and outranks starting`() {
        val a = ServerAvailabilityMachine.resolve(base, ServerHealth.Starting, signedOut = true)
        assertTrue(a is ServerAvailability.Unauthorized)
        assertEquals(base, (a as ServerAvailability.Unauthorized).connection)
    }

    @Test
    fun `the unauthorized health is enough on its own`() {
        assertTrue(
            ServerAvailabilityMachine.resolve(base, ServerHealth.Unauthorized) is
                ServerAvailability.Unauthorized,
        )
    }

    @Test
    fun `starting is its own state, not a failure`() {
        assertTrue(ServerAvailabilityMachine.resolve(base, ServerHealth.Starting) is ServerAvailability.Starting)
    }

    @Test
    fun `each unreachable reason survives the round trip`() {
        val incompatible = ServerAvailabilityMachine.resolve(base, ServerHealth.Incompatible)
        assertEquals(
            ServerAvailability.Unreachable.Reason.INCOMPATIBLE,
            (incompatible as ServerAvailability.Unreachable).reason,
        )
        val stopped = ServerAvailabilityMachine.resolve(base, ServerHealth.Stopped)
        assertEquals(
            ServerAvailability.Unreachable.Reason.STOPPED,
            (stopped as ServerAvailability.Unreachable).reason,
        )
        val offline = ServerAvailabilityMachine.resolve(base, ServerHealth.Unreachable)
        assertEquals(
            ServerAvailability.Unreachable.Reason.OFFLINE,
            (offline as ServerAvailability.Unreachable).reason,
        )
    }

    @Test
    fun `a healthy or checking server is ready and keeps its health`() {
        val healthy = ServerAvailabilityMachine.resolve(base, ServerHealth.Healthy)
        assertTrue(healthy is ServerAvailability.Ready)
        assertEquals(ServerHealth.Healthy, (healthy as ServerAvailability.Ready).health)
        val checking = ServerAvailabilityMachine.resolve(base, ServerHealth.Checking)
        assertEquals(ServerHealth.Checking, (checking as ServerAvailability.Ready).health)
    }

    @Test
    fun `ready with no health is still ready`() {
        val a = ServerAvailabilityMachine.resolve(base, null)
        assertTrue(a is ServerAvailability.Ready)
        assertNull((a as ServerAvailability.Ready).health)
    }

    // ---- the selectors -----------------------------------------------------------------------

    @Test
    fun `only a ready server contributes to search`() {
        assertTrue(ServerAvailabilityMachine.canIndex(ServerAvailabilityMachine.resolve(base, ServerHealth.Healthy)))
        assertFalse(ServerAvailabilityMachine.canIndex(ServerAvailabilityMachine.resolve(base, ServerHealth.Starting)))
        assertFalse(ServerAvailabilityMachine.canIndex(ServerAvailabilityMachine.resolve(base, ServerHealth.Unreachable)))
        assertFalse(ServerAvailabilityMachine.canIndex(ServerAvailabilityMachine.resolve(null, ServerHealth.Healthy)))
    }

    @Test
    fun `a starting server holds its project route, an unavailable one does not`() {
        assertTrue(
            ServerAvailabilityMachine.canHoldProjectRoute(
                ServerAvailabilityMachine.resolve(base, ServerHealth.Starting),
            ),
        )
        assertTrue(
            ServerAvailabilityMachine.canHoldProjectRoute(
                ServerAvailabilityMachine.resolve(base, ServerHealth.Healthy),
            ),
        )
        assertFalse(
            ServerAvailabilityMachine.canHoldProjectRoute(
                ServerAvailabilityMachine.resolve(base, ServerHealth.Unreachable),
            ),
        )
    }

    @Test
    fun `unreachable covers both the failed and the refused server`() {
        assertTrue(
            ServerAvailabilityMachine.isUnreachable(
                ServerAvailabilityMachine.resolve(base, ServerHealth.Unreachable),
            ),
        )
        assertTrue(
            ServerAvailabilityMachine.isUnreachable(
                ServerAvailabilityMachine.resolve(base, ServerHealth.Unauthorized),
            ),
        )
        assertFalse(
            ServerAvailabilityMachine.isUnreachable(
                ServerAvailabilityMachine.resolve(base, ServerHealth.Starting),
            ),
        )
    }

    @Test
    fun `the dot is selected from the availability, not the other way round`() {
        assertEquals(
            ServerHealth.Starting,
            ServerAvailabilityMachine.selectHealth(
                ServerAvailabilityMachine.resolve(base, ServerHealth.Starting),
            ),
        )
        assertEquals(
            ServerHealth.Unauthorized,
            ServerAvailabilityMachine.selectHealth(
                ServerAvailabilityMachine.resolve(base, ServerHealth.Unauthorized),
            ),
        )
        assertEquals(
            ServerHealth.Incompatible,
            ServerAvailabilityMachine.selectHealth(
                ServerAvailabilityMachine.resolve(base, ServerHealth.Incompatible),
            ),
        )
        assertEquals(
            ServerHealth.Stopped,
            ServerAvailabilityMachine.selectHealth(
                ServerAvailabilityMachine.resolve(base, ServerHealth.Stopped),
            ),
        )
        assertEquals(
            ServerHealth.Unreachable,
            ServerAvailabilityMachine.selectHealth(
                ServerAvailabilityMachine.resolve(base, ServerHealth.Unreachable),
            ),
        )
    }

    // ---- their Retry fence -------------------------------------------------------------------

    @Test
    fun `a click arms one run with their timeout`() {
        val start = ServerAvailabilityMachine.initialRetryState(base)
        val t = ServerAvailabilityMachine.transition(start, ServerAvailabilityMachine.RetryEvent.RetryClicked)
        assertTrue(ServerAvailabilityMachine.isRetrying(t.state))
        assertEquals(1, t.state.attempt)
        assertEquals(
            listOf(ServerAvailabilityMachine.RetryEffect.RetryRun(1, 35_000L)),
            t.effects,
        )
    }

    @Test
    fun `a second click while retrying does nothing`() {
        val one = ServerAvailabilityMachine.transition(
            ServerAvailabilityMachine.initialRetryState(base),
            ServerAvailabilityMachine.RetryEvent.RetryClicked,
        )
        val two = ServerAvailabilityMachine.transition(
            one.state, ServerAvailabilityMachine.RetryEvent.RetryClicked,
        )
        assertEquals(one.state, two.state)
        assertTrue(two.effects.isEmpty())
    }

    @Test
    fun `a settle from an older attempt is ignored`() {
        val one = ServerAvailabilityMachine.transition(
            ServerAvailabilityMachine.initialRetryState(base),
            ServerAvailabilityMachine.RetryEvent.RetryClicked,
        )
        val stale = ServerAvailabilityMachine.transition(
            one.state, ServerAvailabilityMachine.RetryEvent.RetrySettled(99),
        )
        assertEquals(one.state, stale.state)
        assertTrue(stale.effects.isEmpty())
    }

    @Test
    fun `a settle from this attempt returns to idle and cancels the timeout`() {
        val one = ServerAvailabilityMachine.transition(
            ServerAvailabilityMachine.initialRetryState(base),
            ServerAvailabilityMachine.RetryEvent.RetryClicked,
        )
        val settled = ServerAvailabilityMachine.transition(
            one.state, ServerAvailabilityMachine.RetryEvent.RetrySettled(1),
        )
        assertFalse(ServerAvailabilityMachine.isRetrying(settled.state))
        assertEquals(1, settled.state.attempt)
        assertEquals(listOf(ServerAvailabilityMachine.RetryEffect.TimeoutCancel), settled.effects)
    }

    @Test
    fun `a timeout returns to idle without cancelling itself`() {
        val one = ServerAvailabilityMachine.transition(
            ServerAvailabilityMachine.initialRetryState(base),
            ServerAvailabilityMachine.RetryEvent.RetryClicked,
        )
        val timedOut = ServerAvailabilityMachine.transition(
            one.state, ServerAvailabilityMachine.RetryEvent.RetryTimedOut(1),
        )
        assertFalse(ServerAvailabilityMachine.isRetrying(timedOut.state))
        assertTrue(timedOut.effects.isEmpty())
    }

    @Test
    fun `another server's page cancels the run and starts enabled`() {
        val one = ServerAvailabilityMachine.transition(
            ServerAvailabilityMachine.initialRetryState(base),
            ServerAvailabilityMachine.RetryEvent.RetryClicked,
        )
        val moved = ServerAvailabilityMachine.transition(
            one.state, ServerAvailabilityMachine.RetryEvent.ServerSynced("http://other:4096"),
        )
        assertFalse(ServerAvailabilityMachine.isRetrying(moved.state))
        assertEquals("http://other:4096", moved.state.server)
        assertEquals(1, moved.state.attempt)
        assertEquals(listOf(ServerAvailabilityMachine.RetryEffect.TimeoutCancel), moved.effects)
    }

    @Test
    fun `syncing the same server changes nothing`() {
        val start = ServerAvailabilityMachine.initialRetryState(base)
        val same = ServerAvailabilityMachine.transition(
            start, ServerAvailabilityMachine.RetryEvent.ServerSynced(base),
        )
        assertEquals(start, same.state)
        assertTrue(same.effects.isEmpty())
    }

    @Test
    fun `disposing a retry cancels its timer`() {
        val one = ServerAvailabilityMachine.transition(
            ServerAvailabilityMachine.initialRetryState(base),
            ServerAvailabilityMachine.RetryEvent.RetryClicked,
        )
        val gone = ServerAvailabilityMachine.transition(one.state, ServerAvailabilityMachine.RetryEvent.Disposed)
        assertFalse(ServerAvailabilityMachine.isRetrying(gone.state))
        assertEquals(listOf(ServerAvailabilityMachine.RetryEffect.TimeoutCancel), gone.effects)
    }

    // ---- the phone's own mapping -------------------------------------------------------------

    @Test
    fun `a probe in flight has no verdict yet`() {
        // Their health poll has a `checking` arm; a probe is exactly that, so an older `Denied`
        // must not be shown while a fresh attempt is running.
        assertEquals(
            ServerHealth.Starting,
            ServerAvailabilityMachine.healthOf(
                connected = false, busy = true, gate = GateState.Denied,
            ),
        )
    }

    @Test
    fun `a refused credential is unauthorized once no probe is running`() {
        assertEquals(
            ServerHealth.Unauthorized,
            ServerAvailabilityMachine.healthOf(
                connected = false, busy = false, gate = GateState.Denied,
            ),
        )
    }

    @Test
    fun `a probe on a connected server is checking, not starting`() {
        assertEquals(
            ServerHealth.Checking,
            ServerAvailabilityMachine.healthOf(
                connected = true, busy = true, gate = GateState.Ok,
            ),
        )
        // Checking is still ready, so the server keeps contributing to search.
        assertTrue(
            ServerAvailabilityMachine.canIndex(
                ServerAvailabilityMachine.resolve(
                    "http://192.168.1.7:4096", ServerHealth.Checking,
                ),
            ),
        )
    }

    @Test
    fun `a recorded gate verdict outranks a stale connection flag`() {
        assertEquals(
            ServerHealth.Unreachable,
            ServerAvailabilityMachine.healthOf(
                connected = true, busy = false, gate = GateState.Unreachable("boom"),
            ),
        )
    }

    @Test
    fun `connected and idle is healthy`() {
        assertEquals(
            ServerHealth.Healthy,
            ServerAvailabilityMachine.healthOf(
                connected = true, busy = false, gate = GateState.Ok,
            ),
        )
    }

    @Test
    fun `not connected and not busy is unreachable`() {
        assertEquals(
            ServerHealth.Unreachable,
            ServerAvailabilityMachine.healthOf(
                connected = false, busy = false, gate = GateState.Idle,
            ),
        )
    }
}
