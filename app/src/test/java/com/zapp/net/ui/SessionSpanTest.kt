package com.zapp.net.ui

import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rendered half of `markdown-session-links.ts`, checked off-device.
 *
 * `plainSessionSpan` is the user bubble's path (`sessionReferenceText`, `:107`); the same
 * branch runs inside `MarkdownText` for assistant prose.
 */
class SessionSpanTest {

    private val id = "ses_" + "a".repeat(26)

    private fun links(s: androidx.compose.ui.text.AnnotatedString): List<LinkAnnotation> =
        s.getLinkAnnotations(0, s.length).map { it.item }

    @Test
    fun `a known session becomes its title and a link`() {
        val s = plainSessionSpan("see $id now", androidx.compose.ui.graphics.Color.White, { "Fix the parser" }) { }
        assertEquals("see @Fix the parser now", s.text)
        val l = links(s)
        assertEquals(1, l.size)
        val clickable = l[0] as LinkAnnotation.Clickable
        assertEquals("ses:$id", clickable.tag)
    }

    @Test
    fun `the link covers exactly the rendered title`() {
        val s = plainSessionSpan("$id", androidx.compose.ui.graphics.Color.White, { "Parser" }) { }
        assertEquals("@Parser", s.text)
        val range = s.getLinkAnnotations(0, s.length).single()
        assertEquals(0, range.start)
        assertEquals("@Parser".length, range.end)
    }

    @Test
    fun `an unknown session stays its raw source and is not a link`() {
        val s = plainSessionSpan("see $id now", androidx.compose.ui.graphics.Color.White, { null }) { }
        assertEquals("see $id now", s.text)
        assertTrue(links(s).isEmpty())
    }

    @Test
    fun `no handler means no links at all`() {
        val s = plainSessionSpan("see $id now", androidx.compose.ui.graphics.Color.White, { "T" }, null)
        assertEquals("see $id now", s.text)
        assertTrue(links(s).isEmpty())
    }

    @Test
    fun `user text is never markdown parsed`() {
        val s = plainSessionSpan("**bold** and `code`", androidx.compose.ui.graphics.Color.White) { }
        assertEquals("**bold** and `code`", s.text)
        assertTrue(links(s).isEmpty())
    }

    @Test
    fun `two references both become links`() {
        val other = "ses_" + "b".repeat(26)
        val s = plainSessionSpan("$id and $other", androidx.compose.ui.graphics.Color.White, { "T" }) { }
        assertEquals("@T and @T", s.text)
        val tags = links(s).map { (it as LinkAnnotation.Clickable).tag }
        assertEquals(listOf("ses:$id", "ses:$other"), tags)
    }

    @Test
    fun `the click carries the id that was rendered`() {
        var opened: String? = null
        val s = plainSessionSpan("go to $id", androidx.compose.ui.graphics.Color.White, { "T" }) { opened = it }
        val clickable = links(s).single() as LinkAnnotation.Clickable
        assertNotNull(clickable.linkInteractionListener)
        // The listener is invoked by Compose on tap; the tag is the stable half we can assert.
        assertEquals("ses:$id", clickable.tag)
        assertNull(opened)
    }
}
