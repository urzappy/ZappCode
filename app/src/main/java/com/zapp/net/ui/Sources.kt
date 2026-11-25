package com.zapp.net.ui

import com.zapp.net.api.Part
import java.net.URI

/**
 * PORT of `session-ui/src/sources/sources.ts` — the derivation behind a turn's sources chip.
 *
 * Their model, from the source:
 *
 *  - `SourceStep` is one `websearch` or `webfetch` tool call (`:39-43`).
 *  - `TurnSources.sources` is "distinct sources in the order the turn met them: search results
 *    and opened pages, deduped by URL" (`:51`).
 *  - `step` ignores a call that is still `streaming` (`:129`) and only recognises the two tool
 *    names (`:133-137`).
 *  - A search's results are not in the message at all: "The websearch tool keeps its structured
 *    results out of the message and writes each one as a `## [title](url)` heading in its text
 *    content (`packages/core/src/tool/plugin/websearch.ts`); a result without a title repeats
 *    its URL." (`:199-201`).
 *  - `identity` is "one source per page: the scheme, `www.`, a trailing slash and the fragment
 *    don't make another." (`:259`).
 *
 * The chip renderer and the side panel are not in the extract (`gui-plugins/src/sources/panel.tsx`
 * is lazy-loaded and absent), so the visual is ours while every decision above is theirs.
 */
object Sources {

    /** `:17-31` — `SourceResult`. `site` is the hostname without `www.`, `host` is the full one. */
    data class Result(
        val url: String,
        val title: String?,
        val site: String,
        val host: String,
    )

    /** `:35` */
    const val RUNNING = "running"
    const val COMPLETED = "completed"
    const val ERROR = "error"

    /** `:39-43` — a search carries its results; a fetch carries its own page and the path it read. */
    sealed interface Step {
        val id: String
        val status: String
        val error: String?

        data class Search(
            override val id: String,
            val query: String,
            override val status: String,
            val results: List<Result>,
            override val error: String?,
        ) : Step

        data class Fetch(
            override val id: String,
            val url: String,
            val title: String?,
            val site: String,
            val host: String,
            /** `:163` — `path(url)`, the pathname plus the query. */
            val path: String,
            override val status: String,
            override val error: String?,
        ) : Step
    }

    /** `:47-55` */
    data class Turn(val steps: List<Step>, val sources: List<Result>)

    /** `:61-93` — `turnSources(messages)`, over the assistant parts of one turn. */
    fun turn(parts: List<Part>): Turn {
        val steps = parts.flatMap(::step)

        val seen = HashSet<String>()
        val sources = steps
            .flatMap { item ->
                when (item) {
                    // `:71` — a search contributes every result it found.
                    is Step.Search -> item.results
                    // `:71` — an errored fetch contributes nothing.
                    is Step.Fetch -> if (item.status == ERROR) emptyList() else listOf(
                        Result(item.url, item.title, item.site, item.host),
                    )
                }
            }
            .filter { item ->
                val key = identity(item.url)
                // `:79-85`
                if (seen.contains(key)) false else {
                    seen.add(key)
                    true
                }
            }

        return Turn(steps, sources)
    }

    /**
     * `:99-123` — `turnMessages`. Their version slices the session's message list from the user
     * message that opened the turn up to the next user/shell message, keeping assistants.
     *
     * On the phone a turn is already the assistant parts between two user messages, so the slice
     * is the caller's list. Kept as a named function so the boundary rule is written down.
     */
    fun turnMessages(messages: List<List<Part>>): List<Part> = messages.flatten()

    /** `:127-173` */
    private fun step(content: Part): List<Step> {
        // `:129` — a call that is still streaming is not a step yet.
        if (content.type != "tool") return emptyList()
        val state = content.state ?: return emptyList()
        if (state.status == "streaming") return emptyList()

        // `:133`
        if (content.name == "websearch") return listOf(search(content))

        // `:137`
        if (content.name != "webfetch") return emptyList()

        // `:141-149` — a fetch with no parseable `input.url` is not a step.
        val url = state.inputString("url") ?: return emptyList()
        val page = source(url) ?: return emptyList()

        return listOf(
            Step.Fetch(
                id = content.id.orEmpty(),
                url = page.url,
                title = page.title,
                site = page.site,
                host = page.host,
                path = path(page.url),
                status = state.status.orEmpty(),
                error = toolError(content),
            ),
        )
    }

    /** `:177-195` */
    private fun search(tool: Part): Step {
        val state = tool.state
        val status = state?.status
        return Step.Search(
            id = tool.id.orEmpty(),
            // `:185` — a query that is not a string is the empty one.
            query = state?.inputString("query") ?: "",
            // `:187` — `streaming` is reported as `running`.
            status = if (status == "streaming") RUNNING else status ?: RUNNING,
            // `:189` — results only once the call completed.
            results = if (status == COMPLETED) results(toolOutput(tool) ?: "") else emptyList(),
            error = toolError(tool),
        )
    }

    /** `:203-235` — the `## [title](url)` headings of a search's text output. */
    fun results(text: String): List<Result> = text.split("\n").flatMap { line ->
        // `:207`
        if (!line.startsWith("## [") || !line.endsWith(")")) return@flatMap emptyList()

        // `:211` — the last `](`, so a title containing `](` still splits at the real link.
        val split = line.lastIndexOf("](")
        if (split < 4) return@flatMap emptyList()

        // `:219`
        val result = source(line.substring(split + 2, line.length - 1)) ?: return@flatMap emptyList()

        // `:227-231` — "a result without a title repeats its URL", so that title is dropped.
        val title = line.substring(4, split).trim()
        listOf(
            result.copy(title = if (title.isNotEmpty() && title != result.url) title else null),
        )
    }

    /** `:239-255` */
    fun source(url: String): Result? {
        val parsed = parse(url) ?: return null
        // `:249` — only the two web protocols.
        if (parsed.scheme != "https" && parsed.scheme != "http") return null
        val host = parsed.host ?: return null
        return Result(
            url = url,
            title = null,
            // `:253` — case-sensitive, exactly as `hostname.replace(/^www\./, "")`.
            site = host.removePrefix("www."),
            host = host,
        )
    }

    /** `:261-269` — "the scheme, `www.`, a trailing slash and the fragment don't make another". */
    fun identity(url: String): String {
        val parsed = parse(url) ?: return url
        val host = parsed.host ?: return url
        return host.removePrefix("www.") + stripSlash(parsed.rawPath) + query(parsed)
    }

    /** `:273-281` */
    fun path(url: String): String {
        val parsed = parse(url) ?: return url
        return stripSlash(parsed.rawPath) + query(parsed)
    }

    // ---- the two helpers their Effect schemas stand in for ----

    /**
     * `currentToolOutput` (`current-tool-state.ts:41-63`): while running, `metadata.output` if it
     * is a string; otherwise the joined `text` items of `state.content`.
     */
    private fun toolOutput(tool: Part): String? {
        val state = tool.state ?: return null
        if (state.status == RUNNING) {
            val output = state.metadata?.output
            if (output is kotlinx.serialization.json.JsonPrimitive && output.isString) return output.content
            return null
        }
        val content = state.content ?: return null
        val text = content.mapNotNull { if (it.type == "text") it.text else null }.joinToString("\n")
        return text.ifEmpty { null }
    }

    /** `currentToolError` (`:67-75`) — only when the status is `error`. */
    private fun toolError(tool: Part): String? {
        val state = tool.state ?: return null
        if (state.status != ERROR) return null
        return state.error?.message
    }

    /** `URL.canParse` plus a hostname: a relative string is not a source. */
    private fun parse(url: String): URI? = try {
        val uri = URI(url)
        if (uri.isAbsolute) uri else null
    } catch (_: Exception) {
        null
    }

    /** `pathname.replace(/\/+$/, "")` — and an empty pathname stays empty. */
    private fun stripSlash(path: String?): String = (path ?: "").trimEnd('/')

    /** `search`, with its `?` when it has one. */
    private fun query(uri: URI): String = uri.rawQuery?.let { "?$it" } ?: ""
}
