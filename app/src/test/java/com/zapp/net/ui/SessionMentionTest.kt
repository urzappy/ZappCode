package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reference matcher of `util/src/session-mention.ts`, checked off-device.
 *
 * Every case below is a boundary the desktop regex draws on purpose; a "cleaner" regex that
 * drops the look-behind or the look-ahead breaks one of them.
 */
class SessionMentionTest {

    /** A complete id: `ses_` plus exactly 26 alphanumerics. */
    private val id = "ses_" + "a".repeat(26)

    @Test
    fun `a bare id is a reference`() {
        val hits = SessionMention.findAll(id)
        assertEquals(1, hits.size)
        assertEquals(id, hits[0].id)
        assertEquals(0, hits[0].start)
        assertEquals(id.length, hits[0].end)
    }

    @Test
    fun `the serialized form is a reference`() {
        val src = SessionMention.serialize(id)
        assertEquals("@session:$id", src)
        val hits = SessionMention.findAll(src)
        assertEquals(1, hits.size)
        assertEquals(id, hits[0].id)
        assertEquals(src, hits[0].text)
    }

    @Test
    fun `a path segment is not a reference`() {
        assertEquals(0, SessionMention.findAll("/home/x/$id").size)
    }

    @Test
    fun `a longer word is not a reference`() {
        assertEquals(0, SessionMention.findAll("foo_$id").size)
    }

    @Test
    fun `a trailing path is not a reference`() {
        assertEquals(0, SessionMention.findAll("$id/file.kt").size)
    }

    @Test
    fun `a shorter run is not a reference`() {
        assertEquals(0, SessionMention.findAll("ses_" + "a".repeat(25)).size)
        assertEquals(0, SessionMention.findAll("ses_" + "a".repeat(27)).size)
    }

    @Test
    fun `a non-alphanumeric character is not part of an id`() {
        assertEquals(0, SessionMention.findAll("ses_" + "a".repeat(25) + "_").size)
    }

    @Test
    fun `two references in one line are both found`() {
        val other = "ses_" + "b".repeat(26)
        val hits = SessionMention.findAll("see $id and $other")
        assertEquals(2, hits.size)
        assertEquals(id, hits[0].id)
        assertEquals(other, hits[1].id)
    }

    @Test
    fun `a reference after a colon is not matched`() {
        // `:` is in the look-behind set, so `session:ses_…` without the `@session:` prefix is
        // not a reference - only the exact prefixed form is.
        assertEquals(0, SessionMention.findAll("session:$id").size)
    }

    @Test
    fun `parse accepts only a whole-string reference`() {
        assertEquals(id, SessionMention.parse(id))
        assertEquals(id, SessionMention.parse("@session:$id"))
        assertNull(SessionMention.parse("look at $id please"))
    }

    @Test
    fun `display uses the title when the session is known`() {
        val out = SessionMention.display(
            "see $id now",
            { if (it == id) "Fix the parser" else null },
        )
        assertEquals("see @Fix the parser now", out)
    }

    @Test
    fun `display keeps the raw source when the session is unknown`() {
        val out = SessionMention.display("see $id now") { null }
        assertEquals("see $id now", out)
    }

    @Test
    fun `display leaves text without references untouched`() {
        val src = "nothing to see here"
        assertEquals(src, SessionMention.display(src) { "x" })
    }

    @Test
    fun `serialize swaps the inserted title for the wire token`() {
        val out = SessionMention.serialize("look at @Fix the parser please", listOf("@Fix the parser" to id))
        assertEquals("look at @session:$id please", out)
    }

    @Test
    fun `serialize leaves an edited title alone`() {
        val out = SessionMention.serialize("look at @Fix the parse please", listOf("@Fix the parser" to id))
        assertEquals("look at @Fix the parse please", out)
    }

    @Test
    fun `serialize prefers the longer literal`() {
        val short = "ses_" + "b".repeat(26)
        val out = SessionMention.serialize(
            "@Fix and @Fix the parser",
            listOf("@Fix" to short, "@Fix the parser" to id),
        )
        assertEquals("@session:$short and @session:$id", out)
    }

    @Test
    fun `serialize with nothing inserted is the text itself`() {
        assertEquals("hello", SessionMention.serialize("hello", emptyList()))
    }

    @Test
    fun `two sessions with the same title keep their own ids`() {
        val a = "ses_" + "a".repeat(26)
        val b = "ses_" + "b".repeat(26)
        val out = SessionMention.serialize(
            "compare @Greeting and @Greeting",
            listOf("@Greeting" to a, "@Greeting" to b),
        )
        assertEquals("compare @session:$a and @session:$b", out)
    }

    @Test
    fun `a literal typed twice but inserted once is only swapped once`() {
        val out = SessionMention.serialize("@Fix and @Fix", listOf("@Fix" to id))
        assertEquals("@session:$id and @Fix", out)
    }

    @Test
    fun `an empty text stays empty`() {
        assertEquals("", SessionMention.serialize("", listOf("@Fix" to id)))
    }
}
