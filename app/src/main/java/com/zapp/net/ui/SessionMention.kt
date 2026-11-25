package com.zapp.net.ui

/**
 * PORT of `util/src/session-mention.ts` — the only place that decides what a session
 * reference is.
 *
 * Their pattern, verbatim:
 *
 *     /(?<![\w/:@-])(?:@session:)?(ses_[A-Za-z0-9]{26})(?![\w/-])/g
 *
 * Three parts matter and none may be "simplified":
 *
 *  - `(?<![\w/:@-])` — the id must not continue a word, a path, a URL or another `@`. Without
 *    it `/home/x/ses_…` and `foo_ses_…` would become buttons.
 *  - `(?:@session:)?` — both `ses_…` and `@session:ses_…` are references. The prefixed form is
 *    what the composer writes (`serializeSessionMention`).
 *  - `(?![\w/-])` — a longer alphanumeric run is not an id, and `ses_…/file` is a path.
 *
 * `{26}` is exact: a still-streaming id must not match half-way.
 */
object SessionMention {

    private const val PREFIX = "ses_"

    /** `ses_` + 26 alphanumerics, optionally preceded by `@session:`. */
    private val RE = Regex(
        """(?<![\w/:@-])(?:@session:)?(ses_[A-Za-z0-9]{26})(?![\w/-])""",
    )

    /** One hit: `id` is the bare `ses_…`, `text` is the matched source, `start`/`end` are offsets. */
    data class Hit(val id: String, val text: String, val start: Int, val end: Int)

    /** `sessionMentions(text)`. */
    fun findAll(text: String): List<Hit> = RE.findAll(text).map { m ->
        Hit(
            id = m.groupValues[1],
            text = m.value,
            start = m.range.first,
            end = m.range.last + 1,
        )
    }.toList()

    /**
     * `parseSessionMention(source)` — the id only when the **whole** string is one reference.
     * That is what lets a rendered link keep its source and be re-labelled on a rename.
     */
    fun parse(source: String): String? {
        val first = findAll(source).firstOrNull() ?: return null
        return if (first.text == source) first.id else null
    }

    /** `serializeSessionMention` — what the wire gets instead of the editor's title. */
    fun serialize(id: String): String = "@session:$id"

    /**
     * `serializePrompt` (`composer/prompt-parts.ts:7`) — the editor shows a title, the wire
     * gets `@session:<id>`.
     *
     * The phone's composer is plain text, so the two forms are bridged by remembering the
     * exact literal that was inserted. Each remembered literal is consumed **once**, in the
     * order the references appear, which is what keeps two sessions with the same title
     * (`@Greeting` appears three times in his list) pointing at the rows he actually picked.
     * A literal that no longer appears verbatim — he edited it, or the session was renamed —
     * is left alone and sends as the text he can see.
     *
     * Longest literal first: `@Fix` must not eat the front of `@Fix the parser`.
     */
    fun serialize(text: String, inserted: List<Pair<String, String>>): String {
        if (inserted.isEmpty() || text.isEmpty()) return text
        val pool = inserted
            .filter { it.first.isNotBlank() }
            .sortedByDescending { it.first.length }
            .toMutableList()
        if (pool.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val hit = pool.firstOrNull { text.startsWith(it.first, i) }
            if (hit == null) {
                sb.append(text[i])
                i++
            } else {
                sb.append(serialize(hit.second))
                i += hit.first.length
                pool.remove(hit)
            }
        }
        return sb.toString()
    }

    /**
     * `sessionReferenceText` — the display string for a run of text, with each reference
     * replaced by `@<title>`, or left as its raw source when the session is unknown.
     *
     * `title` returning null is their `title === undefined` branch: the reference stays a
     * plain code span instead of becoming a button that opens nothing.
     */
    fun display(text: String, title: (String) -> String?): String {
        val hits = findAll(text)
        if (hits.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var at = 0
        hits.forEach { h ->
            sb.append(text, at, h.start)
            val t = title(h.id)
            sb.append(if (t == null) h.text else "@$t")
            at = h.end
        }
        sb.append(text, at, text.length)
        return sb.toString()
    }
}
