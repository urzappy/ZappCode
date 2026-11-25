package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen machine, checked on the sequences that actually change a phone terminal: colour,
 * carriage return, erase, and the escapes that must disappear rather than print.
 */
class AnsiTest {

    private fun lines(s: String) = Ansi.render(s).map { it.text }

    @Test
    fun `plain text becomes one line per newline`() {
        assertEquals(listOf("one", "two", "three"), lines("one\r\ntwo\r\nthree"))
        assertEquals(listOf("a", "", "b"), lines("a\n\nb"))
    }

    @Test
    fun `carriage return overwrites from the start of the line`() {
        // A progress bar: "10%" then "\r50%" then "\r100%"
        assertEquals(listOf("100%"), lines("10%\r50%\r100%"))
        // a shorter write leaves the tail alone - a real terminal does not truncate on CR
        assertEquals(listOf("xbc"), lines("abc\rx"))
    }

    @Test
    fun `backspace moves the cursor without deleting`() {
        // BS only moves; the cell keeps its character until something is written over it
        assertEquals(listOf("abc"), lines("abc\b"))
        assertEquals(listOf("abd"), lines("abc\bd"))
    }

    @Test
    fun `tab advances to the next multiple of eight`() {
        assertEquals(listOf("a       b"), lines("a\tb"))
        assertEquals(listOf("12345678        x"), lines("12345678\tx"))
    }

    @Test
    fun `SGR produces spans with the right style`() {
        val l = Ansi.render("\u001b[31mred\u001b[0m plain").single()
        assertEquals(2, l.spans.size)
        assertEquals("red", l.spans[0].text)
        assertEquals(1, l.spans[0].style.fg) // 31 -> red
        assertEquals(" plain", l.spans[1].text)
        assertNull(l.spans[1].style.fg)
    }

    @Test
    fun `SGR 256 and truecolour survive as their own values`() {
        val a = Ansi.render("\u001b[38;5;196mx").single()
        assertEquals(256 + 196, a.spans[0].style.fg)
        val b = Ansi.render("\u001b[38;2;18;52;86mx").single()
        assertEquals((18 shl 16) or (52 shl 8) or 86, b.spans[0].style.fg)
    }

    @Test
    fun `SGR attributes accumulate and reset`() {
        val s = Ansi.sgr(Ansi.Style(), "1;4;3")
        assertTrue(s.bold); assertTrue(s.underline); assertTrue(s.italic)
        assertEquals(Ansi.Style(), Ansi.sgr(s, "0"))
        assertEquals(false, Ansi.sgr(s, "22").bold)
    }

    @Test
    fun `erase line clears from the cursor`() {
        // "\r" then three spaces overwrite "abc", leaving "   def"; then K clears from col 3.
        assertEquals(listOf("   "), lines("abcdef\r   \u001b[K"))
        assertEquals(listOf(""), lines("abcdef\u001b[2K"))
    }

    @Test
    fun `erase display 2 clears everything`() {
        assertEquals(listOf("after"), lines("before\nmore\n\u001b[2Jafter"))
    }

    @Test
    fun `OSC titles and hyperlinks do not print`() {
        assertEquals(listOf("x"), lines("\u001b]0;my title\u0007x"))
        assertEquals(listOf("y"), lines("\u001b]8;;http://e\u001b\\y"))
    }

    @Test
    fun `an unterminated escape does not hang or print garbage`() {
        assertEquals(listOf("a"), lines("a\u001b[38;5"))
        assertEquals(listOf("a"), lines("a\u001b"))
    }

    @Test
    fun `maxLines keeps the tail`() {
        val many = (1..50).joinToString("\n") { "l$it" }
        val kept = Ansi.render(many, maxLines = 3).map { it.text }
        assertEquals(listOf("l48", "l49", "l50"), kept)
    }

    @Test
    fun `a realistic ls --color line keeps its colours and loses its escapes`() {
        // what `ls --color=always` writes for a directory, a file and a reset
        val raw = "\u001b[01;34msrc\u001b[0m  \u001b[0mmain.kt\u001b[0m\r\n"
        val l = Ansi.render(raw).first()
        assertEquals("src  main.kt", l.text)
        assertEquals(4, l.spans.first().style.fg) // 34 -> blue
    }
}
