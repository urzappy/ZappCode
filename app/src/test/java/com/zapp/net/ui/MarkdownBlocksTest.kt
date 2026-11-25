package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `parseBlocks` - the block splitter behind every markdown surface.
 *
 * The image rule is the interesting one. PC's stylesheet says
 * `[data-component=markdown] img { display: block; margin: 1.5rem 0 }`, so an image is a block
 * even when it sits mid-sentence: `see ![chart](a.png) above` is three blocks on the desktop,
 * never an inline bitmap. These tests pin that.
 */
class MarkdownBlocksTest {

    private fun blocks(src: String) = parseBlocks(src)

    @Test
    fun anImageAloneOnItsLineIsOneImageBlock() {
        val b = blocks("![alt text](chart.png)")
        assertEquals(1, b.size)
        val img = b[0] as Block.Image
        assertEquals("alt text", img.alt)
        assertEquals("chart.png", img.src)
    }

    @Test
    fun anImageInsideASentenceBecomesItsOwnBlock() {
        val b = blocks("see ![chart](a.png) above")
        assertEquals(3, b.size)
        assertEquals("see", (b[0] as Block.Paragraph).text)
        assertEquals("chart", (b[1] as Block.Image).alt)
        assertEquals("a.png", (b[1] as Block.Image).src)
        assertEquals("above", (b[2] as Block.Paragraph).text)
    }

    @Test
    fun anImageAtTheEndLeavesOneParagraph() {
        val b = blocks("here it is ![x](y.png)")
        assertEquals(2, b.size)
        assertEquals("here it is", (b[0] as Block.Paragraph).text)
        assertEquals("y.png", (b[1] as Block.Image).src)
    }

    @Test
    fun anImageWithATitleKeepsItsSource() {
        // `![a](b.png "Title")` - the title is dropped, the source is not.
        val b = blocks("![a](b.png \"Title\")")
        assertEquals(1, b.size)
        assertEquals("b.png", (b[0] as Block.Image).src)
    }

    @Test
    fun twoImagesInOneParagraphAreTwoBlocks() {
        val b = blocks("a ![one](1.png) b ![two](2.png) c")
        assertEquals(5, b.size)
        assertEquals("1.png", (b[1] as Block.Image).src)
        assertEquals("2.png", (b[3] as Block.Image).src)
    }

    @Test
    fun aPlainLinkIsStillAParagraph() {
        // Only `![` is an image. `[text](url)` must not be split, or every link would break
        // its sentence in two.
        val b = blocks("read [the docs](https://opencode.ai/docs) now")
        assertEquals(1, b.size)
        assertEquals("read [the docs](https://opencode.ai/docs) now", (b[0] as Block.Paragraph).text)
    }

    @Test
    fun consecutiveLinesJoinIntoOneParagraph() {
        val b = blocks("line one\nline two")
        assertEquals(1, b.size)
        assertEquals("line one line two", (b[0] as Block.Paragraph).text)
    }

    @Test
    fun aBlankLineStillSplitsParagraphs() {
        val b = blocks("first\n\nsecond")
        assertEquals(2, b.size)
        assertEquals("first", (b[0] as Block.Paragraph).text)
        assertEquals("second", (b[1] as Block.Paragraph).text)
    }

    @Test
    fun headingsAndFencesSurviveTheSplit() {
        val b = blocks("# Title\n\n![a](p.png)\n\n```kotlin\nval x = 1\n```")
        assertEquals(3, b.size)
        assertTrue(b[0] is Block.Heading)
        assertTrue(b[1] is Block.Image)
        assertTrue(b[2] is Block.Code)
    }

    @Test
    fun aParagraphBetweenTwoBlocksKeepsItsText() {
        val b = blocks("intro\n\nsee ![c](a.png) here\n\noutro")
        assertEquals(5, b.size)
        assertEquals("intro", (b[0] as Block.Paragraph).text)
        assertEquals("see", (b[1] as Block.Paragraph).text)
        assertTrue(b[2] is Block.Image)
        assertEquals("here", (b[3] as Block.Paragraph).text)
        assertEquals("outro", (b[4] as Block.Paragraph).text)
    }
}
