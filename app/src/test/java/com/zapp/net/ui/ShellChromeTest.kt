package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ui.tool.shell.*` parity.
 *
 * Every expectation below is a key from the shipped PC bundle, not a preference:
 *
 *   writingCommand = "Writing command…"      exit = "Command exited with code {{code}}"
 *   timeout        = "Command timed out"     cancelled = "Command cancelled"
 *
 * and the absence of a "finished" key is itself the contract: a clean run prints nothing.
 */
class ShellChromeTest {

    @Test
    fun inFlightStatesPrintTheWritingLine() {
        for (s in listOf("pending", "streaming", "running", "writing", "queued")) {
            assertEquals("state=$s", ShellChrome.WRITING, ShellChrome.label(s))
        }
    }

    @Test
    fun aCleanExitPrintsNothing() {
        // There is no "Command finished" key anywhere in the bundle.
        assertEquals("", ShellChrome.label("completed", exit = 0))
        assertEquals("", ShellChrome.label("completed"))
        assertEquals("", ShellChrome.label(""))
    }

    @Test
    fun aNonZeroExitPrintsTheCode() {
        assertEquals("Command exited with code 1", ShellChrome.label("completed", exit = 1))
        assertEquals("Command exited with code 127", ShellChrome.label("completed", exit = 127))
        assertEquals("Command exited with code -1", ShellChrome.label("completed", exit = -1))
    }

    @Test
    fun aTimeoutOutranksEverythingBelowIt() {
        assertEquals(ShellChrome.TIMEOUT, ShellChrome.label("completed", timeout = true))
        assertEquals(ShellChrome.TIMEOUT, ShellChrome.label("completed", exit = 1, timeout = true))
        assertEquals(ShellChrome.TIMEOUT, ShellChrome.label("timeout"))
        assertEquals(ShellChrome.TIMEOUT, ShellChrome.label("timedout"))
    }

    @Test
    fun aCancelPrintsTheCancelLine() {
        assertEquals(ShellChrome.CANCELLED, ShellChrome.label("cancelled"))
        assertEquals(ShellChrome.CANCELLED, ShellChrome.label("canceled"))
        assertEquals(ShellChrome.CANCELLED, ShellChrome.label("interrupted"))
        assertEquals(ShellChrome.CANCELLED, ShellChrome.label("killed"))
        // the server's own flag, even when the status word is something else
        assertEquals(ShellChrome.CANCELLED, ShellChrome.label("completed", interrupted = true))
    }

    @Test
    fun writingOutranksAStaleOutcome() {
        // A retry that is already streaming must not show the previous attempt's exit code.
        assertEquals(ShellChrome.WRITING, ShellChrome.label("running", exit = 1, timeout = true))
    }

    @Test
    fun stateMatchingIsCaseInsensitive() {
        assertEquals(ShellChrome.WRITING, ShellChrome.label("RUNNING"))
        assertEquals(ShellChrome.CANCELLED, ShellChrome.label("Cancelled"))
    }

    @Test
    fun onlyARealFailureTurnsTheLineRed() {
        assertTrue(ShellChrome.failed("error"))
        assertTrue(ShellChrome.failed("failed"))
        assertTrue(ShellChrome.failed("completed", exit = 1))
        assertTrue(ShellChrome.failed("completed", timeout = true))
        // a stop he asked for is not a failure on the PC
        assertFalse(ShellChrome.failed("cancelled"))
        assertFalse(ShellChrome.failed("completed", exit = 0))
        assertFalse(ShellChrome.failed("running"))
    }

    @Test
    fun theNameIsTheirs() {
        assertEquals("Shell", ShellChrome.NAME)
    }
}
