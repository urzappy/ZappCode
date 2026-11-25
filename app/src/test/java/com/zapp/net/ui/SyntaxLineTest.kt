package com.zapp.net.ui

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file viewer paints one line at a time so a big file can live in a `LazyColumn` without
 * putting tens of thousands of spans into one `SpannableString` on the UI thread. That is only
 * safe if the line painter and the block painter agree exactly - same characters, same spans.
 */
class SyntaxLineTest {

    private val sources = listOf(
        "fun main() { println(\"hi\") }",
        "val x = 1 // trailing\nval y = 2",
        "/* block\n   still block\n*/ val z = 3",
        "/* unterminated\nval q = 4",
        "val s = \"\"\"\nmulti\nline\n\"\"\"",
        "# hash comment\nkey = value",
        "class Foo(val a: Int) : Bar()",
        "@Composable fun A() = Unit",
        "if (a > 1) { b() } else { c() }",
        "",
        "\n\n\n",
        "val n = 0x1F + 42_000 + 3.14",
        "x = 'c' + 'd'",
        "/* a */ val b = 1 /* c */",
        "text with an escaped \\\" quote",
    )

    @Test
    fun linePainterMatchesBlockPainter() {
        for (lang in listOf("kt", "py", "c", "")) {
            for (source in sources) {
                val block = highlightCode(source, lang)
                val split = source.split('\n')
                val lines = highlightLines(split, lang)
                val rebuilt = AnnotatedString.Builder().apply {
                    lines.forEachIndexed { index, line ->
                        if (index > 0) append('\n')
                        append(line)
                    }
                }.toAnnotatedString()
                assertEquals("text for <$source> [$lang]", block.text, rebuilt.text)
                assertEquals("spans for <$source> [$lang]", block.spanStyles, rebuilt.spanStyles)
            }
        }
    }

    @Test
    fun blockCommentSurvivesTheLineBreak() {
        val lines = highlightLines(listOf("/* open", "middle", "close */ tail"), "kt")
        assertEquals(3, lines.size)
        for (i in 0..2) {
            val first = lines[i].spanStyles.first()
            assertEquals("line $i", Syn.Comment, first.item.color)
        }
        // `tail` after the close is no longer comment.
        val tail = lines[2].spanStyles.last()
        assertTrue("tail is not comment", tail.item.color != Syn.Comment)
    }

    @Test
    fun theOpenMarkerIsNotDropped() {
        // The old tokenizer advanced past `/*` without appending it, so those two characters
        // disappeared from the painted code.
        val line = highlightLines(listOf("/* note */ x"), "kt").single()
        assertTrue("keeps the opening marker", line.text.startsWith("/* note */"))
    }

    @Test
    fun tripleQuoteSurvivesTheLineBreak() {
        val lines = highlightLines(listOf("val s = \"\"\"", "still a string", "\"\"\" + 1"), "kt")
        assertEquals(Syn.String, lines[1].spanStyles.single().item.color)
    }
}
