package com.zapp.net.ui

/**
 * PORT of `util/src/path.ts` — `parsePathLineSuffix` (`:245`), `toLineRange` (`:201`) and
 * the three patterns above it.
 *
 * Their comment on `inlineCodeKind` is the reason this exists: "A `path` only becomes a link
 * once the host confirms the file exists, so this stays a cheap syntax check." The suffix is
 * what a timeline card or a compiler message carries — `MainActivity.kt:299`, or GitHub's
 * `MarkdownText.kt#L97`, or a range `a.ts:12-40`.
 */
object PathSuffix {

    /** `:185` — `const MAX_LINE_NUMBER = 1_000_000`. */
    private const val MAX_LINE = 1_000_000

    /** `:193` — `lineHashSuffixPattern`, verbatim. */
    private val HASH_SUFFIX = Regex("""^(.*?)#L(\d+)(?:C\d+)?(?:-L?(\d+)(?:C\d+)?)?$""", RegexOption.IGNORE_CASE)

    /** `:197` — `lineColonSuffixPattern`, verbatim. */
    private val COLON_SUFFIX = Regex("""^(.*?):(\d+)(?::\d+)?(?:-(\d+)(?::\d+)?)?:?$""")

    /** `:275` — the guard that keeps `C:` and `c:` from being read as `path:line`. */
    private val DRIVE_OR_LETTER = Regex("""^[a-z]:?$""", RegexOption.IGNORE_CASE)

    data class Parsed(val path: String, val selection: LineRange?)

    data class LineRange(val start: Int, val end: Int)

    /** `toLineRange` (`:201`): both ends must be safe integers inside `1..1_000_000`, then sorted. */
    private fun range(first: Long, second: Long): LineRange? {
        if (first < 1 || second < 1) return null
        if (first > MAX_LINE || second > MAX_LINE) return null
        return LineRange(minOf(first, second).toInt(), maxOf(first, second).toInt())
    }

    fun parse(input: String): Parsed {
        HASH_SUFFIX.matchEntire(input)?.let { m ->
            val first = m.groupValues[2].toLongOrNull() ?: return@let
            val second = m.groupValues[3].toLongOrNull() ?: first
            return Parsed(m.groupValues[1], range(first, second))
        }
        COLON_SUFFIX.matchEntire(input)?.let { m ->
            val head = m.groupValues[1]
            // `! /^[a-z]:?$/i.test(colonMatch[1])` — `C:` is a drive, not a file called `C`.
            if (DRIVE_OR_LETTER.matches(head)) return@let
            val first = m.groupValues[2].toLongOrNull() ?: return@let
            val second = m.groupValues[3].toLongOrNull() ?: first
            return Parsed(head, range(first, second))
        }
        return Parsed(input, null)
    }
}
