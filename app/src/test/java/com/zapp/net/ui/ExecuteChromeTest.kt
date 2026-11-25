package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `execute` card's chrome.
 *
 * Every expectation is from the compiled renderer (`route-CMUQLRzL.js`, `dm(e)` at ~151 000)
 * or from a real `execute` record in the phone's own store.
 */
class ExecuteChromeTest {

    @Test
    fun theHeaderSaysExecuteThenItsState() {
        assertEquals("Execute", MessageCopy.EXECUTE)
        assertEquals("Completed", ExecuteChrome.status(failed = false, running = false))
        assertEquals("Running", ExecuteChrome.status(failed = false, running = true))
        // error wins over running - their ternary checks `e.error` first
        assertEquals("Failed", ExecuteChrome.status(failed = true, running = true))
        assertEquals("Failed", ExecuteChrome.status(failed = true, running = false))
    }

    @Test
    fun theLineCountIsAWordCount() {
        // `r = e => e.split(" ").length` - their own quirk, reproduced on purpose.
        assertEquals(4, ExecuteChrome.count("const a = 1"))
        assertEquals("4 lines", ExecuteChrome.lines("const a = 1"))
        assertEquals("1 line", ExecuteChrome.lines("return"))
        assertEquals("0 lines", ExecuteChrome.lines(""))
        // newlines do not add to it - a two-line block with no space is one token
        assertEquals(1, ExecuteChrome.count("a\nb"))
        // two spaces are two tokens, because split(" ") is not split(/\s+/)
        assertEquals(3, ExecuteChrome.count("a  b"))
    }

    @Test
    fun theSecondSectionIsOutputUntilItFails() {
        assertEquals("Output", ExecuteChrome.resultLabel(failed = false))
        assertEquals("Error", ExecuteChrome.resultLabel(failed = true))
        assertEquals("Copy output", ExecuteChrome.resultCopyLabel(failed = false))
        assertEquals("Copy error", ExecuteChrome.resultCopyLabel(failed = true))
    }

    @Test
    fun theSectionLabelsAreTheirs() {
        assertEquals("Code", MessageCopy.CODE)
        assertEquals("Copy code", MessageCopy.COPY_CODE)
        assertEquals("View code", MessageCopy.VIEW_CODE)
    }
}
