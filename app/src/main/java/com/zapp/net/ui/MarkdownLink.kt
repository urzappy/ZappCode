package com.zapp.net.ui

/**
 * PORT of the two link rules in `session-ui/src/components/markdown.tsx`.
 *
 * Their renderer post-processes the DOM twice:
 *
 *  - `markExternalLinkFavicons` (`:1041`) only ever looks at `a.external-link[href]`, and
 *    refuses anything whose protocol is not `http:`/`https:` (`:1055`).
 *  - `markCodeLinks` (`:973`) turns an inline code span that *is* a URL into an anchor,
 *    using `codeUrl` (`:329`) — trim, strip trailing `[),.;!?]`, then match
 *    `/^https?:\/\/[^\s<>()`"']+$/`.
 *
 * On the phone a link is a `LinkAnnotation.Url`, which the activity's `UriHandler` opens in
 * the system browser. Anything that is not http(s) — a `#fragment`, a relative path, a
 * `mailto:` — stays plain accent text, because there is no in-app document to scroll and no
 * router to hand it to.
 */
object MarkdownLink {

    /** `urlPattern` (`:325`), verbatim. */
    private val URL_PATTERN = Regex("""^https?://[^\s<>()`"']+$""")

    /** `[),.;!?]+$` from `codeUrl` (`:331`). */
    private val TRAILING = Regex("""[),.;!?]+$""")

    /**
     * `codeUrl(text)` — the URL an inline code span stands for, or null.
     *
     * The trailing-punctuation strip is what makes a sentence like `` `see https://x.dev.` ``
     * work: the full stop is the writer's, not the address's.
     */
    fun codeUrl(text: String): String? {
        val href = text.trim().replace(TRAILING, "")
        if (!URL_PATTERN.matches(href)) return null
        return href
    }

    /**
     * The target for a markdown anchor `[text](href)`.
     *
     * Same protocol gate as `:1055`. Their `new URL(href).toString()` normalisation is not
     * reproduced: it only changes the cosmetic form (`https://x.dev` vs `https://x.dev/`),
     * and the browser accepts both.
     */
    fun anchorTarget(href: String?): String? {
        val raw = href?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (!URL_PATTERN.matches(raw)) return null
        return raw
    }
}
