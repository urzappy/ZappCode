package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `util/src/path.ts` — `parsePathLineSuffix` (`:245`) and `toLineRange` (`:201`).
 *
 * The two suffix forms are GitHub's `#L12-L40` and the compiler's `:12:5`. Everything else is
 * a path with no selection.
 */
class PathSuffixTest {

    @Test
    fun `a plain path has no selection`() {
        val p = PathSuffix.parse("src/main/Main.kt")
        assertEquals("src/main/Main.kt", p.path)
        assertNull(p.selection)
    }

    @Test
    fun `a colon line becomes a single-line range`() {
        val p = PathSuffix.parse("MainActivity.kt:299")
        assertEquals("MainActivity.kt", p.path)
        assertEquals(PathSuffix.LineRange(299, 299), p.selection)
    }

    @Test
    fun `a colon column is dropped`() {
        val p = PathSuffix.parse("MainActivity.kt:299:17")
        assertEquals("MainActivity.kt", p.path)
        assertEquals(PathSuffix.LineRange(299, 299), p.selection)
    }

    @Test
    fun `a colon range keeps both ends`() {
        val p = PathSuffix.parse("a.kt:12-40")
        assertEquals("a.kt", p.path)
        assertEquals(PathSuffix.LineRange(12, 40), p.selection)
    }

    @Test
    fun `a colon range with columns keeps both ends`() {
        val p = PathSuffix.parse("a.kt:12:3-40:9")
        assertEquals("a.kt", p.path)
        assertEquals(PathSuffix.LineRange(12, 40), p.selection)
    }

    @Test
    fun `the hash form is github's`() {
        val p = PathSuffix.parse("MarkdownText.kt#L97")
        assertEquals("MarkdownText.kt", p.path)
        assertEquals(PathSuffix.LineRange(97, 97), p.selection)
    }

    @Test
    fun `the hash range form`() {
        val p = PathSuffix.parse("MarkdownText.kt#L97-L120")
        assertEquals("MarkdownText.kt", p.path)
        assertEquals(PathSuffix.LineRange(97, 120), p.selection)
    }

    @Test
    fun `the hash form with a column`() {
        val p = PathSuffix.parse("MarkdownText.kt#L97C4")
        assertEquals("MarkdownText.kt", p.path)
        assertEquals(PathSuffix.LineRange(97, 97), p.selection)
    }

    @Test
    fun `a reversed range is sorted`() {
        val p = PathSuffix.parse("a.kt:40-12")
        assertEquals(PathSuffix.LineRange(12, 40), p.selection)
    }

    @Test
    fun `a drive letter is not a line number`() {
        // `! /^[a-z]:?$/i.test(colonMatch[1])` — the guard that stops `c:12` becoming a range.
        val p = PathSuffix.parse("C:")
        assertEquals("C:", p.path)
        assertNull(p.selection)
        val q = PathSuffix.parse("c:12")
        assertEquals("c:12", q.path)
        assertNull(q.selection)
    }

    @Test
    fun `line zero is not a range`() {
        val p = PathSuffix.parse("a.kt:0")
        assertNull(p.selection)
    }

    @Test
    fun `a line past the cap is not a range`() {
        assertNull(PathSuffix.parse("a.kt:1000001").selection)
        assertEquals(PathSuffix.LineRange(1000000, 1000000), PathSuffix.parse("a.kt:1000000").selection)
    }

    @Test
    fun `a url is left whole`() {
        val p = PathSuffix.parse("https://x.dev:4096/a")
        assertEquals("https://x.dev:4096/a", p.path)
        assertNull(p.selection)
    }
}
