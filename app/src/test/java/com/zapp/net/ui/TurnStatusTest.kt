package com.zapp.net.ui

import com.zapp.net.api.Part
import com.zapp.net.api.PartState
import com.zapp.net.api.PartTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `session.timeline.status.*` and `ui.sessionTurn.status.*` parity.
 *
 * Every expected string below is a key from the shipped PC bundle. The point of these tests is
 * that the footer can no longer drift back to a hard-coded "Working": the gate states are the
 * reason the band exists at all.
 */
class TurnStatusTest {

    private fun tool(name: String, running: Boolean = true) = Part(
        id = "p1",
        type = "tool",
        name = name,
        state = PartState(status = if (running) "running" else "completed"),
    )

    private fun reasoning() = Part(id = "r1", type = "reasoning", text = "hmm")

    @Test
    fun aBlockedTurnSaysWhatItIsWaitingFor() {
        assertEquals(TurnStatus.WAITING_APPROVAL, TurnStatus.footer(null, false, approvals = 1))
        assertEquals(TurnStatus.WAITING_ANSWER, TurnStatus.footer(null, false, answers = 1))
        assertEquals(TurnStatus.WAITING_SHELL, TurnStatus.footer(null, false, shells = 1))
    }

    @Test
    fun aGateOutranksTheStepLabel() {
        // "Running commands" over a pending approval would be a lie: the command is not running,
        // it is waiting for him.
        assertEquals(
            TurnStatus.WAITING_APPROVAL,
            TurnStatus.footer(tool("shell"), running = true, approvals = 1),
        )
        assertEquals(
            TurnStatus.WAITING_ANSWER,
            TurnStatus.footer(reasoning(), running = true, answers = 2),
        )
    }

    @Test
    fun approvalOutranksAnswerWhichOutranksShell() {
        assertEquals(
            TurnStatus.WAITING_APPROVAL,
            TurnStatus.footer(null, false, approvals = 1, answers = 1, shells = 1),
        )
        assertEquals(
            TurnStatus.WAITING_ANSWER,
            TurnStatus.footer(null, false, answers = 1, shells = 1),
        )
    }

    @Test
    fun noPartsYetIsGatheringThoughts() {
        // `ui.sessionTurn.status.gatheringThoughts` - the turn exists but nothing has arrived.
        assertEquals(TurnStatus.GATHERING_THOUGHTS, TurnStatus.footer(null, running = false))
        assertEquals(TurnStatus.GATHERING_THOUGHTS, TurnStatus.footer(null, running = true))
    }

    @Test
    fun aRunningStepUsesItsOwnLabel() {
        assertEquals(TurnStatus.RUNNING_COMMANDS, TurnStatus.footer(tool("shell"), true))
        assertEquals(TurnStatus.RUNNING_COMMANDS, TurnStatus.footer(tool("bash"), true))
        assertEquals(TurnStatus.MAKING_EDITS, TurnStatus.footer(tool("edit"), true))
        assertEquals(TurnStatus.MAKING_EDITS, TurnStatus.footer(tool("apply_patch"), true))
        assertEquals(TurnStatus.SEARCHING_WEB, TurnStatus.footer(tool("webfetch"), true))
        assertEquals(TurnStatus.DELEGATING, TurnStatus.footer(tool("task"), true))
        assertEquals(TurnStatus.PLANNING, TurnStatus.footer(tool("todowrite"), true))
        assertEquals(TurnStatus.THINKING, TurnStatus.footer(reasoning(), true))
    }

    @Test
    fun searchingAndExploringAreDifferentSteps() {
        // PC has both keys and they are not interchangeable: reading explores, looking searches.
        assertEquals(TurnStatus.SEARCHING_CODEBASE, TurnStatus.footer(tool("grep"), true))
        assertEquals(TurnStatus.SEARCHING_CODEBASE, TurnStatus.footer(tool("glob"), true))
        assertEquals(TurnStatus.SEARCHING_CODEBASE, TurnStatus.footer(tool("find"), true))
        assertEquals(TurnStatus.GATHERING_CONTEXT, TurnStatus.footer(tool("read"), true))
        assertEquals(TurnStatus.GATHERING_CONTEXT, TurnStatus.footer(tool("list"), true))
    }

    @Test
    fun anUnknownToolFallsBackToWorking() {
        assertEquals(TurnStatus.WORKING, TurnStatus.footer(tool("something_new"), true))
        assertEquals(TurnStatus.WORKING, TurnStatus.footer(Part(type = "mystery"), true))
    }

    @Test
    fun everyPartLandedMeansConsideringNextSteps() {
        // The turn is still live but nothing is producing: the model is deciding what next.
        // Leaving "Working" up here is the state he cannot distinguish from a stall.
        assertEquals(
            TurnStatus.CONSIDERING_NEXT_STEPS,
            TurnStatus.footer(tool("shell", running = false), running = false),
        )
        assertEquals(
            TurnStatus.CONSIDERING_NEXT_STEPS,
            TurnStatus.footer(Part(type = "text", text = "done"), running = false),
        )
    }

    @Test
    fun aCompactionHasItsOwnLabel() {
        // `ui.messagePart.compaction.running` = "Compacting". Without this the band says
        // "Gathering thoughts" while the server is rewriting the whole history.
        assertEquals(TurnStatus.COMPACTING, TurnStatus.footer(null, false, compacting = true))
        assertEquals(
            TurnStatus.COMPACTING,
            TurnStatus.footer(tool("shell"), running = true, compacting = true),
        )
        // a real gate still wins: he is the one who has to move
        assertEquals(
            TurnStatus.WAITING_APPROVAL,
            TurnStatus.footer(null, false, approvals = 1, compacting = true),
        )
    }

    @Test
    fun thinkingWithTopicFillsThePlaceholder() {
        assertEquals("Thinking - auth flow", TurnStatus.thinkingWithTopic("auth flow"))
        // no topic: their base key, not "Thinking - "
        assertEquals(TurnStatus.THINKING, TurnStatus.thinkingWithTopic(""))
        assertEquals(TurnStatus.THINKING, TurnStatus.thinkingWithTopic("   "))
    }

    @Test
    fun theFinishedFormOfAContextStepIsExplored() {
        // `ui.sessionTurn.status.gatheredContext` = "Explored"
        assertEquals(TurnStatus.GATHERED_CONTEXT, TurnStatus.finished(tool("read", false)))
        assertEquals(TurnStatus.GATHERED_CONTEXT, TurnStatus.finished(tool("grep", false)))
        // everything else keeps its own label
        assertEquals(TurnStatus.RUNNING_COMMANDS, TurnStatus.finished(tool("shell", false)))
        assertEquals(TurnStatus.MAKING_EDITS, TurnStatus.finished(tool("edit", false)))
    }

    @Test
    fun theStringsAreTheirs() {
        assertEquals("Working", TurnStatus.WORKING)
        assertEquals("Waiting for your answer", TurnStatus.WAITING_ANSWER)
        assertEquals("Waiting for approval", TurnStatus.WAITING_APPROVAL)
        assertEquals("Waiting for shell", TurnStatus.WAITING_SHELL)
        assertEquals("Gathering thoughts", TurnStatus.GATHERING_THOUGHTS)
        assertEquals("Considering next steps", TurnStatus.CONSIDERING_NEXT_STEPS)
        assertEquals("Searching the codebase", TurnStatus.SEARCHING_CODEBASE)
        assertEquals("Exploring", TurnStatus.GATHERING_CONTEXT)
        assertEquals("Explored", TurnStatus.GATHERED_CONTEXT)
    }
}
