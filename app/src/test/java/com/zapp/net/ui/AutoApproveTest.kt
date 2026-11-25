package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Differential tests for `app/src/session/requests/auto-approve-machine.ts` (OpenCode 2.3.2).
 *
 * One test per branch of their transition function, plus the full retry ladder walked end to
 * end so the attempt counting is proven rather than assumed. If a number here changes, the
 * port has drifted from the shipped desktop behaviour.
 */
class AutoApproveTest {

    private val reply0 = AutoApproveEffect.Reply("ses_a", "run-1", 0, AUTO_APPROVE_REPLY_TIMEOUT_MS)

    private fun replying(attempt: Int = 0, projectID: String? = "proj") =
        AutoApproveState.Replying("ses_a", projectID, "run-1", attempt)

    private fun waiting(attempt: Int = 1, projectID: String? = "proj") =
        AutoApproveState.WaitingRetry("ses_a", projectID, "run-1", attempt)

    // ---- constants: theirs, verbatim ----

    @Test
    fun `constants match the shipped machine`() {
        assertEquals(2, AUTO_APPROVE_RETRY_LIMIT)
        assertEquals(1_000L, AUTO_APPROVE_RETRY_DELAY_MS)
        assertEquals(15_000L, AUTO_APPROVE_REPLY_TIMEOUT_MS)
        assertEquals(30_000L, AUTO_APPROVE_SWEEP_TIMEOUT_MS)
    }

    // ---- approve ----

    @Test
    fun `approve from pending starts the cycle at attempt zero`() {
        val t = AutoApproveMachine.transition(
            AutoApproveState.Pending,
            AutoApproveEvent.Approve("ses_a", "proj", "run-1"),
        )
        assertEquals(replying(0), t.state)
        assertEquals(listOf(reply0), t.effects)
        assertTrue(t.changed)
    }

    @Test
    fun `approve is ignored once a cycle owns the request`() {
        for (state in listOf(
            replying(),
            waiting(),
            AutoApproveState.Replied,
            AutoApproveState.GaveUp("proj"),
        )) {
            val t = AutoApproveMachine.transition(state, AutoApproveEvent.Approve("ses_a", "proj", "run-2"))
            assertEquals("state must not move", state, t.state)
            assertTrue("no effects", t.effects.isEmpty())
            assertFalse(t.changed)
        }
    }

    // ---- reply outcomes ----

    @Test
    fun `reply succeeded settles and clears the timer`() {
        val t = AutoApproveMachine.transition(replying(), AutoApproveEvent.ReplySucceeded("run-1", 0))
        assertEquals(AutoApproveState.Replied, t.state)
        assertEquals(listOf(AutoApproveEffect.ClearReplyTimer), t.effects)
    }

    @Test
    fun `a stale run id or attempt cannot move the cycle`() {
        val staleRun = AutoApproveMachine.transition(replying(), AutoApproveEvent.ReplySucceeded("run-OLD", 0))
        assertFalse(staleRun.changed)
        assertEquals(replying(), staleRun.state)

        val staleAttempt = AutoApproveMachine.transition(replying(), AutoApproveEvent.ReplySucceeded("run-1", 7))
        assertFalse(staleAttempt.changed)
        assertEquals(replying(), staleAttempt.state)
    }

    @Test
    fun `failure retries with the attempt multiplied delay`() {
        val first = AutoApproveMachine.transition(replying(0), AutoApproveEvent.ReplyFailed("run-1", 0))
        assertEquals(waiting(1), first.state)
        assertEquals(
            listOf(
                AutoApproveEffect.ClearReplyTimer,
                AutoApproveEffect.ScheduleRetry("run-1", 1, 1_000L),
            ),
            first.effects,
        )

        val second = AutoApproveMachine.transition(replying(1), AutoApproveEvent.ReplyFailed("run-1", 1))
        assertEquals(waiting(2), second.state)
        assertEquals(
            listOf(
                AutoApproveEffect.ClearReplyTimer,
                AutoApproveEffect.ScheduleRetry("run-1", 2, 2_000L),
            ),
            second.effects,
        )
    }

    @Test
    fun `a timeout is a failure`() {
        val t = AutoApproveMachine.transition(replying(0), AutoApproveEvent.ReplyTimedOut("run-1", 0))
        assertEquals(waiting(1), t.state)
        assertEquals(2, t.effects.size)
    }

    @Test
    fun `exhausting the limit gives up and keeps the project`() {
        val t = AutoApproveMachine.transition(replying(2), AutoApproveEvent.ReplyFailed("run-1", 2))
        assertEquals(AutoApproveState.GaveUp("proj"), t.state)
        // No retry is scheduled - only the timer is cleared.
        assertEquals(listOf(AutoApproveEffect.ClearReplyTimer), t.effects)
    }

    @Test
    fun `the ladder is exactly three replies`() {
        var state: AutoApproveState = AutoApproveState.Pending
        val replies = mutableListOf<AutoApproveEffect.Reply>()
        val delays = mutableListOf<Long>()

        var t = AutoApproveMachine.transition(state, AutoApproveEvent.Approve("ses_a", "proj", "run-1"))
        state = t.state
        replies += t.effects.filterIsInstance<AutoApproveEffect.Reply>().single()

        repeat(2) {
            val attempt = (state as AutoApproveState.Replying).attempt
            t = AutoApproveMachine.transition(state, AutoApproveEvent.ReplyFailed("run-1", attempt))
            state = t.state
            delays += t.effects.filterIsInstance<AutoApproveEffect.ScheduleRetry>().single().delayMs

            val due = (state as AutoApproveState.WaitingRetry).attempt
            t = AutoApproveMachine.transition(state, AutoApproveEvent.RetryDue("run-1", due))
            state = t.state
            replies += t.effects.filterIsInstance<AutoApproveEffect.Reply>().single()
        }

        assertEquals(listOf(1_000L, 2_000L), delays)
        assertEquals(listOf(0, 1, 2), replies.map { it.attempt })

        t = AutoApproveMachine.transition(state, AutoApproveEvent.ReplyFailed("run-1", 2))
        assertTrue(t.state is AutoApproveState.GaveUp)
        assertEquals(3, replies.size)
    }

    @Test
    fun `retry due re-sends the same attempt`() {
        val t = AutoApproveMachine.transition(waiting(2), AutoApproveEvent.RetryDue("run-1", 2))
        assertEquals(replying(2), t.state)
        assertEquals(listOf(AutoApproveEffect.Reply("ses_a", "run-1", 2, AUTO_APPROVE_REPLY_TIMEOUT_MS)), t.effects)
    }

    @Test
    fun `retry due with a stale attempt is ignored`() {
        val t = AutoApproveMachine.transition(waiting(2), AutoApproveEvent.RetryDue("run-1", 1))
        assertFalse(t.changed)
    }

    // ---- resolved / cancel ----

    @Test
    fun `resolved from pending or replied changes nothing`() {
        for (state in listOf(AutoApproveState.Pending, AutoApproveState.Replied)) {
            val t = AutoApproveMachine.transition(state, AutoApproveEvent.Resolved)
            assertEquals(state, t.state)
            assertFalse(t.changed)
        }
    }

    @Test
    fun `resolved from a live cycle settles it`() {
        val fromReplying = AutoApproveMachine.transition(replying(), AutoApproveEvent.Resolved)
        assertEquals(AutoApproveState.Replied, fromReplying.state)
        assertEquals(listOf(AutoApproveEffect.ClearReplyTimer), fromReplying.effects)

        val fromWaiting = AutoApproveMachine.transition(waiting(), AutoApproveEvent.Resolved)
        assertEquals(AutoApproveState.Replied, fromWaiting.state)
        assertEquals(listOf(AutoApproveEffect.CancelRetry), fromWaiting.effects)

        // Nothing to stop once it gave up.
        val fromGaveUp = AutoApproveMachine.transition(AutoApproveState.GaveUp("proj"), AutoApproveEvent.Resolved)
        assertEquals(AutoApproveState.Replied, fromGaveUp.state)
        assertTrue(fromGaveUp.effects.isEmpty())
    }

    @Test
    fun `cancel returns a live cycle to pending`() {
        val fromReplying = AutoApproveMachine.transition(replying(), AutoApproveEvent.Cancel)
        assertEquals(AutoApproveState.Pending, fromReplying.state)
        assertEquals(listOf(AutoApproveEffect.ClearReplyTimer), fromReplying.effects)

        val fromWaiting = AutoApproveMachine.transition(waiting(), AutoApproveEvent.Cancel)
        assertEquals(AutoApproveState.Pending, fromWaiting.state)
        assertEquals(listOf(AutoApproveEffect.CancelRetry), fromWaiting.effects)

        val fromGaveUp = AutoApproveMachine.transition(AutoApproveState.GaveUp("proj"), AutoApproveEvent.Cancel)
        assertEquals(AutoApproveState.Pending, fromGaveUp.state)
        assertTrue(fromGaveUp.effects.isEmpty())
    }

    @Test
    fun `cancel on pending or replied is a no-op`() {
        for (state in listOf(AutoApproveState.Pending, AutoApproveState.Replied)) {
            assertFalse(AutoApproveMachine.transition(state, AutoApproveEvent.Cancel).changed)
        }
    }

    // ---- needsUser ----

    @Test
    fun `needsUser is false once replied, true once exhausted`() {
        assertFalse(AutoApproveMachine.needsUser(AutoApproveState.Replied, autoApprove = false))
        assertTrue(AutoApproveMachine.needsUser(AutoApproveState.GaveUp(null), autoApprove = true))
    }

    @Test
    fun `needsUser inverts the switch while a cycle can still answer`() {
        for (state in listOf(AutoApproveState.Pending, replying(), waiting())) {
            assertFalse("auto-approve on hides it", AutoApproveMachine.needsUser(state, autoApprove = true))
            assertTrue("auto-approve off shows it", AutoApproveMachine.needsUser(state, autoApprove = false))
        }
    }

    // ---- sweep ----

    @Test
    fun `sweep start runs immediately with the long timeout`() {
        val t = AutoApproveSweepMachine.transition(AutoApproveSweepState.Idle, AutoApproveSweepEvent.Start("s1"))
        assertEquals(AutoApproveSweepState.Sweeping("s1", 0), t.state)
        assertEquals(listOf(AutoApproveSweepEffect.Run("s1", 0, AUTO_APPROVE_SWEEP_TIMEOUT_MS)), t.effects)
    }

    @Test
    fun `a newer start replaces the sweep in progress`() {
        val t = AutoApproveSweepMachine.transition(
            AutoApproveSweepState.Sweeping("s1", 0),
            AutoApproveSweepEvent.Start("s2"),
        )
        assertEquals(AutoApproveSweepState.Sweeping("s2", 0), t.state)
        assertEquals(
            listOf(AutoApproveSweepEffect.Abort, AutoApproveSweepEffect.Run("s2", 0, 30_000L)),
            t.effects,
        )
    }

    @Test
    fun `a newer start replaces a scheduled retry`() {
        val t = AutoApproveSweepMachine.transition(
            AutoApproveSweepState.WaitingRetry("s1", 1),
            AutoApproveSweepEvent.Start("s2"),
        )
        assertEquals(
            listOf(AutoApproveSweepEffect.CancelRetry, AutoApproveSweepEffect.Run("s2", 0, 30_000L)),
            t.effects,
        )
    }

    @Test
    fun `an incomplete sweep retries then rests idle`() {
        val failed0 = AutoApproveSweepMachine.transition(
            AutoApproveSweepState.Sweeping("s1", 0),
            AutoApproveSweepEvent.Failed("s1", 0),
        )
        assertEquals(AutoApproveSweepState.WaitingRetry("s1", 1), failed0.state)
        assertEquals(
            listOf(AutoApproveSweepEffect.Abort, AutoApproveSweepEffect.ScheduleRetry("s1", 1, 1_000L)),
            failed0.effects,
        )

        val failed2 = AutoApproveSweepMachine.transition(
            AutoApproveSweepState.Sweeping("s1", 2),
            AutoApproveSweepEvent.Failed("s1", 2),
        )
        assertEquals(AutoApproveSweepState.Idle, failed2.state)
        assertEquals(listOf(AutoApproveSweepEffect.Abort), failed2.effects)
    }

    @Test
    fun `a completed sweep rests idle`() {
        val t = AutoApproveSweepMachine.transition(
            AutoApproveSweepState.Sweeping("s1", 1),
            AutoApproveSweepEvent.Succeeded("s1", 1),
        )
        assertEquals(AutoApproveSweepState.Idle, t.state)
        assertEquals(listOf(AutoApproveSweepEffect.Abort), t.effects)
    }

    @Test
    fun `sweep retry due runs the same attempt`() {
        val t = AutoApproveSweepMachine.transition(
            AutoApproveSweepState.WaitingRetry("s1", 2),
            AutoApproveSweepEvent.RetryDue("s1", 2),
        )
        assertEquals(AutoApproveSweepState.Sweeping("s1", 2), t.state)
        assertEquals(listOf(AutoApproveSweepEffect.Run("s1", 2, 30_000L)), t.effects)
    }

    @Test
    fun `sweep ignores a stale run`() {
        val t = AutoApproveSweepMachine.transition(
            AutoApproveSweepState.Sweeping("s1", 0),
            AutoApproveSweepEvent.Failed("s0", 0),
        )
        assertFalse(t.changed)
    }

    @Test
    fun `sweep cancel stops whatever is running`() {
        assertFalse(
            AutoApproveSweepMachine.transition(AutoApproveSweepState.Idle, AutoApproveSweepEvent.Cancel).changed,
        )
        assertEquals(
            listOf(AutoApproveSweepEffect.Abort),
            AutoApproveSweepMachine.transition(
                AutoApproveSweepState.Sweeping("s1", 0),
                AutoApproveSweepEvent.Cancel,
            ).effects,
        )
        assertEquals(
            listOf(AutoApproveSweepEffect.CancelRetry),
            AutoApproveSweepMachine.transition(
                AutoApproveSweepState.WaitingRetry("s1", 1),
                AutoApproveSweepEvent.Cancel,
            ).effects,
        )
    }

    // ---- scoping ----

    @Test
    fun `scoped keys use a null byte`() {
        assertEquals('\u0000', ScopedKey.SEPARATOR)
        val key = ScopedKey.from("local", "proj-1")!!
        assertEquals("local\u0000proj-1", key)
        assertEquals("local\u0000", ScopedKey.prefix("local"))
        assertNotEquals(ScopedKey.from("local", "a"), ScopedKey.from("local", "b"))
    }

    @Test
    fun `an unknown project has no scoped key`() {
        assertEquals(null, ScopedKey.projectSettingsKey("local", null))
    }

    @Test
    fun `the default covers every project unless one overrides it`() {
        val on = PermissionScope("local", default = true)
        assertTrue(on.autoApproves("proj-1"))
        assertTrue(on.autoApproves(null))
        assertTrue(on.autoApprovesOnServer())

        val off = PermissionScope(
            "local",
            default = true,
            overrides = mapOf("local\u0000proj-1" to false),
        )
        assertTrue("other projects still default on", off.autoApproves("proj-2"))
        assertFalse("the override wins", off.autoApproves("proj-1"))
    }

    @Test
    fun `a project override can turn approval on under a global off`() {
        val scope = PermissionScope(
            "local",
            default = false,
            overrides = mapOf("local\u0000proj-1" to true),
        )
        assertFalse(scope.autoApproves("proj-2"))
        assertFalse(scope.autoApproves(null))
        assertTrue(scope.autoApproves("proj-1"))
        assertTrue("the server is worth sweeping", scope.autoApprovesOnServer())
    }

    @Test
    fun `another server's override does not arm this one`() {
        val scope = PermissionScope(
            "local",
            default = false,
            overrides = mapOf("remote\u0000proj-1" to true),
        )
        assertFalse(scope.autoApprovesOnServer())
    }
}
