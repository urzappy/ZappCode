package com.zapp.net.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The body of their `Import session`, against the rule in `index-BWsziAR3.js` @ 83 741:
 * `{...n, location:{directory:e.worktree}}` - document first, `location` last.
 */
class SessionImportTest {

    private fun parse(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private val doc = """{"info":{"id":"ses_1","title":"Old"},"messages":[{"id":"msg_1"}]}"""

    @Test
    fun `the directory is written last and overrides the document's own location`() {
        val withLocation = """{"info":{"id":"ses_1"},"messages":[],"location":{"directory":"C:\\old"}}"""
        val body = parse(SessionImport.body(withLocation, "C:\\new"))
        assertEquals("C:\\new", body["location"]!!.jsonObject["directory"]!!.jsonPrimitive.content)
        // the spread did not drop the document's own keys
        assertEquals("ses_1", body["info"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a plain export keeps info and messages untouched`() {
        val body = parse(SessionImport.body(doc, "C:\\w"))
        assertEquals("ses_1", body["info"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(1, body["messages"]!!.let { (it as kotlinx.serialization.json.JsonArray).size })
        assertEquals("C:\\w", body["location"]!!.jsonObject["directory"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a blank directory omits location entirely`() {
        for (dir in listOf(null, "", "   ")) {
            val body = parse(SessionImport.body(doc, dir))
            assertTrue("dir=$dir", !body.containsKey("location"))
        }
    }

    @Test
    fun `a byte order mark and trailing whitespace are tolerated`() {
        val body = parse(SessionImport.body("\uFEFF" + doc + "\n\n  ", "C:\\w"))
        assertEquals("ses_1", body["info"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `something that is not an object is refused`() {
        assertNull(SessionImport.parse("[1,2,3]"))
        assertNull(SessionImport.parse("not json"))
        var threw = false
        try {
            SessionImport.body("[1,2,3]", "C:\\w")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `an unknown key survives the merge`() {
        val body = parse(SessionImport.body("""{"info":{},"messages":[],"extra":42}""", "C:\\w"))
        assertEquals(42, body["extra"]!!.jsonPrimitive.content.toInt())
    }
}
