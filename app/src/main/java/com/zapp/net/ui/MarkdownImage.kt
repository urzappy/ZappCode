package com.zapp.net.ui

import java.net.URLDecoder

/**
 * PORT of `session-ui/src/components/markdown-image.ts` — the two pure resolvers,
 * `localImagePath` (`:23`) and `localLinkPath` (`:73`), plus `decodePath` (`:121`).
 *
 * Their contract, from the source comments:
 *
 *  - "Marked percent-encodes image sources, so `C:\tmp\chart.png` arrives as
 *    `C:%5Ctmp%5Cchart.png`." — hence the backslash/`%5c` fold before anything else.
 *  - "A link is local when it names a file on disk instead of a web resource. Fragment-only
 *    and query-only hrefs stay in-page; mailto and other schemes stay external. Line-range
 *    fragments such as `#L42` and `#L42-L58` stay attached so the file viewer can select the
 *    target lines."
 */
object MarkdownImage {

    /** Any C0 control character or DEL — a path with one is refused. */
    private val CONTROL = Regex("""[\u0000-\u001f\u007f]""")

    /** `:35` — `file:` with an authority is only accepted for `localhost`. */
    private val FILE_SCHEME = Regex("""^file:""", RegexOption.IGNORE_CASE)

    /** `:53` — any other scheme, but not a Windows drive (`C:/`). */
    private val ANY_SCHEME = Regex("""^[a-z][a-z\d+.-]*:""", RegexOption.IGNORE_CASE)
    private val DRIVE = Regex("""^[a-z]:/""", RegexOption.IGNORE_CASE)

    /**
     * `localImagePath` (`:23`) — the on-disk path an image source names, or null when the
     * source is a web resource (an `http(s)` image is loaded by the network, not by the file
     * API, which is why this returns null for it rather than a path).
     */
    fun localImagePath(source: String): String? {
        val value = source.trim().replace(Regex("""\\|%5c""", RegexOption.IGNORE_CASE), "/")
        if (value.isEmpty()) return null
        if (CONTROL.containsMatchIn(value)) return null
        if (value.startsWith("//")) return null

        if (FILE_SCHEME.containsMatchIn(value)) {
            // `URL.canParse` plus `url.hostname && hostname !== "localhost"`.
            val withoutScheme = value.substringAfter("file:", "")
            val authority = when {
                withoutScheme.startsWith("//") -> withoutScheme.removePrefix("//").substringBefore('/')
                else -> ""
            }
            if (authority.isNotEmpty() && !authority.equals("localhost", ignoreCase = true)) return null
            val pathname = when {
                withoutScheme.startsWith("//") -> "/" + withoutScheme.removePrefix("//").substringAfter('/', "")
                else -> withoutScheme
            }
            // `url.pathname.replace(/^\/([a-z]:\/)/i, "$1")`
            val unrooted = Regex("""^/([a-z]:/)""", RegexOption.IGNORE_CASE)
                .replace(pathname, "$1")
            return decodePath(unrooted)
        }

        // `:53` — a scheme is not a path, unless it is a drive letter.
        if (ANY_SCHEME.containsMatchIn(value) && !DRIVE.containsMatchIn(value)) return null

        return decodePath(value)
    }

    /**
     * `localLinkPath` (`:73`) — the on-disk target of a link, with a `#L42` range preserved so
     * the viewer can select it. Null means "not a file": an in-page fragment, a query, or any
     * external scheme.
     */
    fun localLinkPath(href: String): String? {
        val value = href.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("#") || value.startsWith("?")) return null

        val hashIndex = value.indexOf('#')
        val hash = if (hashIndex == -1) "" else value.substring(hashIndex)
        val target = value.substringBefore('?').substringBefore('#')
            .replace(Regex("""\\|%5c""", RegexOption.IGNORE_CASE), "/")

        // "`app.tsx:42` is a file and line, not a URL scheme. The `.` or `/` keeps
        // `tel:5551234` external."
        val cited = PathSuffix.parse(target)
        val line = if (cited.path != target && Regex("""[./]""").containsMatchIn(cited.path)) cited else null

        val base = localImagePath(line?.path ?: target) ?: return null

        val selection = line?.selection
        if (selection != null) {
            val range = if (selection.end == selection.start) "" else "-L${selection.end}"
            return "$base#L${selection.start}$range"
        }

        return base + if (isLineRangeHash(hash)) hash else ""
    }

    /** `:237` in `util/src/path.ts` — `isLineRangeHash`. */
    private val LINE_HASH = Regex("""^#L(\d+)(?:C\d+)?(?:-L?(\d+)(?:C\d+)?)?$""", RegexOption.IGNORE_CASE)

    fun isLineRangeHash(hash: String): Boolean = LINE_HASH.matches(hash)

    /** `decodePath` (`:121`). */
    private fun decodePath(value: String): String? {
        val path = try {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            return null
        }
        if (CONTROL.containsMatchIn(path)) return null
        if (path.startsWith("//") || path.startsWith("\\\\")) return null
        return path
    }
}
