package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ui.message.*` / `ui.tool.execute.*` parity.
 *
 * Every expected string is a key from the shipped PC bundle, and every expected behaviour is
 * from the compiled renderer chunk (`route-CMUQLRzL.js`) - not from a reading of the source,
 * which is absent from the extract.
 */
class MessageCopyTest {

    private fun caps(
        pending: Boolean = false,
        canPending: Boolean = false,
        canRevert: Boolean = false,
        canFork: Boolean = false,
    ) = MessageCopy.Capabilities(pending, canPending, canRevert, canFork)

    @Test
    fun theStringsAreTheirs() {
        assertEquals("Expand message", MessageCopy.EXPAND)
        assertEquals("Collapse message", MessageCopy.COLLAPSE)
        assertEquals("Read more", MessageCopy.READ_MORE)
        assertEquals("Show less", MessageCopy.SHOW_LESS)
        assertEquals("Copy", MessageCopy.COPY)
        assertEquals("Copy message", MessageCopy.COPY_MESSAGE)
        assertEquals("Copy full message", MessageCopy.COPY_FULL_MESSAGE)
        assertEquals("Fork from here", MessageCopy.FORK)
        assertEquals("Revert to here", MessageCopy.REVERT)
        assertEquals("Undo", MessageCopy.UNDO)
        assertEquals("More actions", MessageCopy.MORE_ACTIONS)
        assertEquals("Move to queue", MessageCopy.MOVE_TO_QUEUE)
        assertEquals("Pending", MessageCopy.PENDING)
        assertEquals("Delete", MessageCopy.DELETE)
        assertEquals("Copy response", MessageCopy.COPY_RESPONSE)
        assertEquals("Copy output", MessageCopy.COPY_OUTPUT)
        assertEquals("Copied", MessageCopy.COPIED)
        assertEquals("Thought", MessageCopy.THOUGHT)
        assertEquals("Interrupted", MessageCopy.INTERRUPTED)
        assertEquals("Queued", MessageCopy.QUEUED)
        assertEquals("attachment", MessageCopy.ATTACHMENT)
    }

    @Test
    fun theToolStringsAreTheirs() {
        assertEquals("Execute", MessageCopy.EXECUTE)
        assertEquals("View code", MessageCopy.VIEW_CODE)
        assertEquals("Code", MessageCopy.CODE)
        assertEquals("Output", MessageCopy.OUTPUT)
        assertEquals("Error", MessageCopy.ERROR)
        assertEquals("Copy code", MessageCopy.COPY_CODE)
        assertEquals("Running", MessageCopy.RUNNING)
        assertEquals("Completed", MessageCopy.COMPLETED)
        assertEquals("Failed", MessageCopy.FAILED)
        assertEquals("Copy error", MessageCopy.COPY_ERROR)
        assertEquals("Show more", MessageCopy.SHOW_MORE)
    }

    @Test
    fun theFoldIsTwelveInEightLines() {
        // `Vl = { over: 12, show: 8 }`
        assertEquals(12, MessageCopy.FOLD_OVER)
        assertEquals(8, MessageCopy.FOLD_SHOW)
        assertFalse(MessageCopy.foldable(12))
        assertTrue(MessageCopy.foldable(13))
        assertTrue(MessageCopy.folded(13, expanded = false))
        // expanding is what un-folds it, and the fold never disappears with the line count
        assertFalse(MessageCopy.folded(13, expanded = true))
        assertFalse(MessageCopy.folded(3, expanded = false))
    }

    @Test
    fun theFoldToggleSaysReadMoreUntilItIsOpen() {
        assertEquals("Read more", MessageCopy.foldLabel(expanded = false))
        assertEquals("Show less", MessageCopy.foldLabel(expanded = true))
    }

    @Test
    fun theCopyFeedbackLastsTwoSeconds() {
        // `copy-CBB0hBKp.js:1` - `var i = 2e3`
        assertEquals(2_000L, MessageCopy.COPY_RESET_MS)
    }

    @Test
    fun queueAndDeleteNeedAPendingMessage() {
        val idle = caps(pending = false, canPending = true)
        assertFalse(MessageCopy.allowed(MessageCopy.Action.QUEUE, idle))
        assertFalse(MessageCopy.allowed(MessageCopy.Action.REMOVE, idle))

        val pending = caps(pending = true, canPending = true)
        assertTrue(MessageCopy.allowed(MessageCopy.Action.QUEUE, pending))
        assertTrue(MessageCopy.allowed(MessageCopy.Action.REMOVE, pending))

        // a server that does not offer the capability cannot be talked into it
        val noCap = caps(pending = true, canPending = false)
        assertFalse(MessageCopy.allowed(MessageCopy.Action.QUEUE, noCap))
        assertFalse(MessageCopy.allowed(MessageCopy.Action.REMOVE, noCap))
    }

    @Test
    fun revertAndForkNeedADeliveredMessage() {
        val pending = caps(pending = true, canRevert = true, canFork = true)
        assertFalse(MessageCopy.allowed(MessageCopy.Action.REVERT, pending))
        assertFalse(MessageCopy.allowed(MessageCopy.Action.FORK, pending))

        val delivered = caps(pending = false, canRevert = true, canFork = true)
        assertTrue(MessageCopy.allowed(MessageCopy.Action.REVERT, delivered))
        assertTrue(MessageCopy.allowed(MessageCopy.Action.FORK, delivered))

        val noCap = caps(pending = false, canRevert = false, canFork = false)
        assertFalse(MessageCopy.allowed(MessageCopy.Action.REVERT, noCap))
        assertFalse(MessageCopy.allowed(MessageCopy.Action.FORK, noCap))
    }

    @Test
    fun aRunningActionDisablesTheWholeMenu() {
        // `n = n => state.status === "running" || !Gc(n, caps)` - the guard against a double tap
        // firing two forks.
        val ready = caps(pending = false, canRevert = true, canFork = true)
        assertFalse(MessageCopy.disabled(MessageCopy.Action.FORK, ready, running = false))
        assertTrue(MessageCopy.disabled(MessageCopy.Action.FORK, ready, running = true))
        assertTrue(MessageCopy.disabled(MessageCopy.Action.REVERT, ready, running = true))
    }

    @Test
    fun theRevertItemRenamesItselfForTheNewestMessage() {
        // `!!e.latest ? undoMessage : revertMessage`
        assertEquals("Undo", MessageCopy.revertLabel(latest = true))
        assertEquals("Revert to here", MessageCopy.revertLabel(latest = false))
    }

    @Test
    fun aMessageWithoutATimestampSaysPending() {
        assertEquals("Pending", MessageCopy.stamp(null))
        assertEquals("Pending", MessageCopy.stamp(0L))
    }

    @Test
    fun theTimestampsAreTheirFourRelativeKeys() {
        val now = 1_800_000_000_000L
        assertEquals("Just now", MessageCopy.timeAgo(now - 30_000L, now))
        assertEquals("5m ago", MessageCopy.timeAgo(now - 5 * 60_000L, now))
        assertEquals("3h ago", MessageCopy.timeAgo(now - 3 * 3_600_000L, now))
        assertEquals("2d ago", MessageCopy.timeAgo(now - 2 * 86_400_000L, now))
    }

    @Test
    fun durationsRoundBeforePickingAUnit() {
        // PC: `Math.round(ms / 1e3)` then `< 60 ? seconds : ...`
        assertEquals("41s", MessageCopy.duration(41_200L))
        assertEquals("1m 0s", MessageCopy.duration(59_600L))
        assertEquals("2m 3s", MessageCopy.duration(123_400L))
        assertEquals("1h 1m", MessageCopy.duration(3_660_000L))
        // no duration at all is an empty string, never "0s"
        assertEquals("", MessageCopy.duration(0L))
        assertEquals("", MessageCopy.duration(-5L))
    }

    @Test
    fun thePluralsMatchTheirOneAndOtherKeys() {
        assertEquals("1 call", MessageCopy.calls(1))
        assertEquals("4 calls", MessageCopy.calls(4))
        assertEquals("1 line", MessageCopy.lines(1))
        assertEquals("0 lines", MessageCopy.lines(0))
        assertEquals("1 file", MessageCopy.fileCount(1))
        assertEquals("2 files", MessageCopy.fileCount(2))
        assertEquals("1 question", MessageCopy.questionCount(1))
        assertEquals("3 questions", MessageCopy.questionCount(3))
    }

    @Test
    fun theModelVariantIsOneString() {
        assertEquals("DeepSeek V4.1", MessageCopy.modelVariant("DeepSeek V4.1", null))
        assertEquals("DeepSeek V4.1", MessageCopy.modelVariant("DeepSeek V4.1", ""))
        assertEquals("DeepSeek V4.1 (Max)", MessageCopy.modelVariant("DeepSeek V4.1", "Max"))
    }

    @Test
    fun theSpeedIsOneDecimal() {
        assertEquals("198.1 tok/s", MessageCopy.tokensPerSecond(198.14))
        assertEquals("0.0 tok/s", MessageCopy.tokensPerSecond(0.0))
    }
}
