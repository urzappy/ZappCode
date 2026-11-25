package com.zapp.net.ui

import com.zapp.net.api.Part
import com.zapp.net.api.PartContent
import com.zapp.net.api.PartMeta
import com.zapp.net.api.PartState
import com.zapp.net.api.WireError
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `session-ui/src/sources/sources.ts` — the whole derivation behind the sources chip.
 *
 * The cases chosen are the ones a plausible re-implementation gets wrong: a title that repeats
 * its URL, a `](` inside the title, a `www.`/trailing-slash/fragment that must not make a second
 * source, an errored fetch that must contribute nothing, and the streaming guard.
 */
class SourcesTest {

    private fun tool(
        name: String,
        status: String,
        input: Map<String, String> = emptyMap(),
        text: String? = null,
        error: String? = null,
        output: String? = null,
        id: String = "prt_$name",
    ) = Part(
        id = id,
        type = "tool",
        name = name,
        state = PartState(
            status = status,
            input = kotlinx.serialization.json.JsonObject(
                input.mapValues { JsonPrimitive(it.value) },
            ),
            content = text?.let { listOf(PartContent(type = "text", text = it)) },
            metadata = PartMeta(output = output?.let { JsonPrimitive(it) }),
            error = error?.let { WireError(type = "error", message = it) },
        ),
    )

    // ---- results(): the `## [title](url)` headings ----

    @Test
    fun `a heading becomes a source with its title`() {
        val r = Sources.results("## [Docs](https://x.dev/a)")
        assertEquals(1, r.size)
        assertEquals("Docs", r[0].title)
        assertEquals("https://x.dev/a", r[0].url)
        assertEquals("x.dev", r[0].site)
        assertEquals("x.dev", r[0].host)
    }

    @Test
    fun `a title that repeats the url is dropped`() {
        // ":201 — a result without a title repeats its URL."
        val r = Sources.results("## [https://x.dev/a](https://x.dev/a)")
        assertEquals(1, r.size)
        assertNull(r[0].title)
    }

    @Test
    fun `an empty title is dropped`() {
        assertNull(Sources.results("## [](https://x.dev/a)")[0].title)
    }

    @Test
    fun `a bracket inside the title splits at the last link`() {
        val r = Sources.results("## [a ](b) c](https://x.dev/z)")
        assertEquals(1, r.size)
        assertEquals("a ](b) c", r[0].title)
        assertEquals("https://x.dev/z", r[0].url)
    }

    @Test
    fun `lines that are not headings are ignored`() {
        assertTrue(Sources.results("no results\n\nSomething else").isEmpty())
        assertTrue(Sources.results("## not a link").isEmpty())
    }

    @Test
    fun `a non-web scheme in a heading is ignored`() {
        assertTrue(Sources.results("## [F](ftp://x.dev/a)").isEmpty())
        assertTrue(Sources.results("## [R](not a url)").isEmpty())
    }

    @Test
    fun `several headings become several results in order`() {
        val r = Sources.results(
            """
            Intro line
            ## [A](https://a.dev/1)
            ## [B](https://b.dev/2)
            """.trimIndent(),
        )
        assertEquals(listOf("A", "B"), r.map { it.title })
    }

    // ---- source(): what counts as a page ----

    @Test
    fun `site drops www and host keeps it`() {
        val r = Sources.source("https://www.x.dev/a")!!
        assertEquals("x.dev", r.site)
        assertEquals("www.x.dev", r.host)
    }

    @Test
    fun `only http and https are pages`() {
        assertNull(Sources.source("ftp://x.dev/a"))
        assertNull(Sources.source("mailto:a@b.c"))
        assertNull(Sources.source("/relative/path"))
        assertNull(Sources.source(""))
    }

    // ---- identity() and path() ----

    @Test
    fun `the scheme, www, a trailing slash and the fragment do not make another source`() {
        val a = Sources.identity("https://x.dev/a")
        assertEquals(a, Sources.identity("http://x.dev/a"))
        assertEquals(a, Sources.identity("https://www.x.dev/a"))
        assertEquals(a, Sources.identity("https://x.dev/a/"))
        assertEquals(a, Sources.identity("https://x.dev/a#install"))
    }

    @Test
    fun `the query is part of the identity`() {
        assertEquals("x.dev/a?q=1", Sources.identity("https://x.dev/a?q=1"))
        assertEquals("x.dev/a?q=2", Sources.identity("https://x.dev/a?q=2"))
    }

    @Test
    fun `path is the pathname plus the query`() {
        assertEquals("/a?q=1", Sources.path("https://x.dev/a/?q=1#f"))
        assertEquals("", Sources.path("https://x.dev"))
        assertEquals("/a/b", Sources.path("https://x.dev/a/b/"))
    }

    // ---- step(): which tool calls become steps ----

    @Test
    fun `a streaming tool is not a step yet`() {
        val turn = Sources.turn(listOf(tool("websearch", "streaming", mapOf("query" to "kotlin"))))
        assertTrue(turn.steps.isEmpty())
    }

    @Test
    fun `a completed search reads its results from the text content`() {
        val turn = Sources.turn(
            listOf(
                tool(
                    "websearch",
                    "completed",
                    mapOf("query" to "kotlin coroutines"),
                    text = "## [Docs](https://kotlinlang.org/docs)",
                ),
            ),
        )
        val step = turn.steps.single() as Sources.Step.Search
        assertEquals("kotlin coroutines", step.query)
        assertEquals("completed", step.status)
        assertEquals(1, step.results.size)
        assertEquals(1, turn.sources.size)
    }

    @Test
    fun `a search that has not completed has no results`() {
        val turn = Sources.turn(
            listOf(tool("websearch", "running", mapOf("query" to "x"), text = "## [A](https://a.dev/1)")),
        )
        val step = turn.steps.single() as Sources.Step.Search
        assertEquals("running", step.status)
        assertTrue(step.results.isEmpty())
    }

    @Test
    fun `an errored search carries its message and no results`() {
        val turn = Sources.turn(
            listOf(tool("websearch", "error", mapOf("query" to "x"), error = "quota")),
        )
        val step = turn.steps.single() as Sources.Step.Search
        assertEquals("error", step.status)
        assertEquals("quota", step.error)
        assertTrue(step.results.isEmpty())
        assertTrue(turn.sources.isEmpty())
    }

    @Test
    fun `a fetch is a step with its path`() {
        val turn = Sources.turn(
            listOf(tool("webfetch", "completed", mapOf("url" to "https://x.dev/a/?q=1"))),
        )
        val step = turn.steps.single() as Sources.Step.Fetch
        assertEquals("https://x.dev/a/?q=1", step.url)
        assertEquals("/a?q=1", step.path)
        assertEquals(1, turn.sources.size)
    }

    @Test
    fun `a fetch without a parseable url is not a step`() {
        assertTrue(Sources.turn(listOf(tool("webfetch", "completed", mapOf("url" to "nope")))).steps.isEmpty())
        assertTrue(Sources.turn(listOf(tool("webfetch", "completed"))).steps.isEmpty())
    }

    @Test
    fun `an errored fetch is a step but not a source`() {
        // ":71 — item.status === \"error\" ? [] : [item]"
        val turn = Sources.turn(
            listOf(tool("webfetch", "error", mapOf("url" to "https://x.dev/a"), error = "404")),
        )
        assertEquals(1, turn.steps.size)
        assertTrue(turn.sources.isEmpty())
    }

    @Test
    fun `another tool is not a step`() {
        assertTrue(Sources.turn(listOf(tool("shell", "completed", mapOf("command" to "ls")))).steps.isEmpty())
        assertTrue(Sources.turn(listOf(Part(id = "p", type = "text", text = "hi"))).steps.isEmpty())
    }

    // ---- turn(): the deduped source list ----

    @Test
    fun `sources are deduped by identity and the first one wins`() {
        val turn = Sources.turn(
            listOf(
                tool("websearch", "completed", text = "## [A](https://x.dev/a)"),
                tool("webfetch", "completed", mapOf("url" to "https://www.x.dev/a/"), id = "prt_2"),
            ),
        )
        assertEquals(2, turn.steps.size)
        assertEquals(1, turn.sources.size)
        assertEquals("https://x.dev/a", turn.sources[0].url)
    }

    @Test
    fun `search results come before a fetch that ran later`() {
        val turn = Sources.turn(
            listOf(
                tool("websearch", "completed", text = "## [A](https://a.dev/1)", id = "prt_1"),
                tool("webfetch", "completed", mapOf("url" to "https://b.dev/2"), id = "prt_2"),
            ),
        )
        assertEquals(listOf("a.dev", "b.dev"), turn.sources.map { it.site })
    }

    @Test
    fun `a turn with no web tools has no chip`() {
        assertTrue(Sources.turn(emptyList()).sources.isEmpty())
    }

    // ---- the two helpers their Effect schemas stand in for ----

    @Test
    fun `a running tool reads metadata output, not content`() {
        val turn = Sources.turn(
            listOf(
                tool(
                    "websearch",
                    "running",
                    mapOf("query" to "x"),
                    text = "## [From content](https://content.dev/1)",
                    output = "## [From metadata](https://meta.dev/1)",
                ),
            ),
        )
        // results are only read when the status is completed, so neither is used here
        assertTrue((turn.steps.single() as Sources.Step.Search).results.isEmpty())
    }

    @Test
    fun `text parts of the content are joined with a newline`() {
        val part = Part(
            id = "p",
            type = "tool",
            name = "websearch",
            state = PartState(
                status = "completed",
                input = kotlinx.serialization.json.JsonObject(mapOf("query" to JsonPrimitive("x"))),
                content = listOf(
                    PartContent(type = "text", text = "## [A](https://a.dev/1)"),
                    PartContent(type = "reasoning", text = "## [Ignored](https://ignored.dev/1)"),
                    PartContent(type = "text", text = "## [B](https://b.dev/2)"),
                ),
            ),
        )
        val step = Sources.turn(listOf(part)).steps.single() as Sources.Step.Search
        assertEquals(listOf("a.dev", "b.dev"), step.results.map { it.site })
    }

    @Test
    fun `a missing query is the empty string`() {
        val turn = Sources.turn(listOf(tool("websearch", "completed")))
        assertEquals("", (turn.steps.single() as Sources.Step.Search).query)
    }
}
