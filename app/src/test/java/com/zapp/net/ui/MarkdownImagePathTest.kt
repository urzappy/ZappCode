package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `session-ui/src/components/markdown-image.ts` — `localImagePath` (`:23`), `localLinkPath`
 * (`:73`) and `decodePath` (`:121`).
 */
class MarkdownImagePathTest {

    @Test
    fun `an absolute unix path is local`() {
        assertEquals("/tmp/chart.png", MarkdownImage.localImagePath("/tmp/chart.png"))
    }

    @Test
    fun `a windows path survives the marked encoding`() {
        // "Marked percent-encodes image sources, so `C:\tmp\chart.png` arrives as
        // `C:%5Ctmp%5Cchart.png`."
        assertEquals("C:/tmp/chart.png", MarkdownImage.localImagePath("""C:\tmp\chart.png"""))
        assertEquals("C:/tmp/chart.png", MarkdownImage.localImagePath("C:%5Ctmp%5Cchart.png"))
        assertEquals("C:/tmp/chart.png", MarkdownImage.localImagePath("C:%5ctmp%5cchart.png"))
    }

    @Test
    fun `a file url keeps its path and drops the root slash before a drive`() {
        assertEquals("C:/tmp/a.png", MarkdownImage.localImagePath("file:///C:/tmp/a.png"))
        assertEquals("/tmp/a.png", MarkdownImage.localImagePath("file:///tmp/a.png"))
    }

    @Test
    fun `a file url with a host is refused unless it is localhost`() {
        assertNull(MarkdownImage.localImagePath("file://server/share/a.png"))
        assertEquals("/tmp/a.png", MarkdownImage.localImagePath("file://localhost/tmp/a.png"))
    }

    @Test
    fun `a percent-encoded path is decoded`() {
        assertEquals("/tmp/a b.png", MarkdownImage.localImagePath("/tmp/a%20b.png"))
        assertEquals("/tmp/a+b.png", MarkdownImage.localImagePath("/tmp/a+b.png"))
    }

    @Test
    fun `a web image is not a local path`() {
        assertNull(MarkdownImage.localImagePath("https://x.dev/a.png"))
        assertNull(MarkdownImage.localImagePath("http://x.dev/a.png"))
    }

    @Test
    fun `a network share is refused`() {
        assertNull(MarkdownImage.localImagePath("//server/share/a.png"))
        assertNull(MarkdownImage.localImagePath("\\\\server\\share\\a.png"))
    }

    @Test
    fun `a control character is refused`() {
        assertNull(MarkdownImage.localImagePath("/tmp/a\u0000.png"))
        assertNull(MarkdownImage.localImagePath("/tmp/a\u007f.png"))
    }

    @Test
    fun `an empty source is nothing`() {
        assertNull(MarkdownImage.localImagePath(""))
        assertNull(MarkdownImage.localImagePath("   "))
    }

    @Test
    fun `a link to a file is local`() {
        assertEquals("app.tsx", MarkdownImage.localLinkPath("app.tsx"))
        assertEquals("src/main/a.kt", MarkdownImage.localLinkPath("src/main/a.kt"))
    }

    @Test
    fun `a link with a line suffix keeps a range the viewer can select`() {
        assertEquals("app.tsx#L42", MarkdownImage.localLinkPath("app.tsx:42"))
        assertEquals("app.tsx#L42-L58", MarkdownImage.localLinkPath("app.tsx:42-58"))
    }

    @Test
    fun `a line-range hash already on the link is kept`() {
        assertEquals("app.tsx#L42", MarkdownImage.localLinkPath("app.tsx#L42"))
        assertEquals("app.tsx#L42-L58", MarkdownImage.localLinkPath("app.tsx#L42-L58"))
    }

    @Test
    fun `a non-range hash is dropped`() {
        assertEquals("app.tsx", MarkdownImage.localLinkPath("app.tsx#install"))
    }

    @Test
    fun `an in-page fragment is not a file`() {
        assertNull(MarkdownImage.localLinkPath("#install"))
        assertNull(MarkdownImage.localLinkPath("?q=1"))
    }

    @Test
    fun `a query is dropped from the path`() {
        assertEquals("a.kt", MarkdownImage.localLinkPath("a.kt?v=2"))
    }

    @Test
    fun `an external scheme stays external`() {
        assertNull(MarkdownImage.localLinkPath("https://x.dev/a"))
        assertNull(MarkdownImage.localLinkPath("mailto:a@b.c"))
        // "The `.` or `/` keeps `tel:5551234` external."
        assertNull(MarkdownImage.localLinkPath("tel:5551234"))
    }

    @Test
    fun `a path with a colon and no separator stays external`() {
        // `cited.path !== target` is true for `tel:5551234`, but `[./]` fails on `tel`.
        assertNull(MarkdownImage.localLinkPath("tel:5551234"))
    }

    @Test
    fun `isLineRangeHash is the github form`() {
        assertEquals(true, MarkdownImage.isLineRangeHash("#L42"))
        assertEquals(true, MarkdownImage.isLineRangeHash("#L42-L58"))
        assertEquals(true, MarkdownImage.isLineRangeHash("#L42C3"))
        assertEquals(false, MarkdownImage.isLineRangeHash("#install"))
        assertEquals(false, MarkdownImage.isLineRangeHash(""))
    }
}
