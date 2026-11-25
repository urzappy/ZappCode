package com.zapp.net.ui

import androidx.compose.ui.graphics.Color
import com.zapp.net.ui.theme.TextMid

/**
 * Colour for the details that carry meaning inside ordinary prose.
 *
 * ## Why this exists
 *
 * Their desktop leaves prose alone: `marked` turns it into text nodes, the syntax highlighter
 * only ever runs inside a fenced block, and an assistant reply that reports
 *
 *     time : 2026-11-21 15:10:24 UTC / 2026-11-21 23:10:24 PHT   host : localhost / aarch64
 *     lan ip : 192.168.1.7   vpn ip : 172.16.0.2   tokens : not exposed
 *
 * renders as one flat grey wall. the owner's instruction was to colour exactly those: *"yung numbers
 * yung dates yung time ... lagyan mo ng colors yung mga highlight na mga important na details"*.
 *
 * There is nothing to copy here - the PC build has no such pass - so this is ours, built on their
 * tokens rather than invented hues, and each colour is assigned one meaning only:
 *
 *     date / time   Syn.Constant   #93E9F6   when something happened
 *     address       Syn.String     #97D6C8   an IP literal
 *     number        Syn.Number     #F799C6   a count, a size, a duration, a version
 *     identifier    Syn.Type       #9E99F7   a hash or a long hex id
 *     label         TextMid        #9A99A6   the `word :` that names a field
 *
 * Deliberately **not** coloured: anything inside a code span or a fenced block, anything that is
 * already a link, and anything that is a session reference. Those have their own treatment and a
 * second colour on top of them would fight it.
 */
internal enum class Sem(val color: Color, val priority: Int) {
    /** `2026-11-21`, and `15:10:24` / `23:10`. */
    DATE(Syn.Constant, 5),

    /** A dotted quad or a compressed IPv6 literal. */
    ADDRESS(Syn.String, 4),

    /** A long hex run - a session id, a commit, a content hash. */
    IDENTIFIER(Syn.Type, 3),

    /** A bare count, size, percentage, duration or dotted version. */
    NUMBER(Syn.Number, 2),

    /** The `word :` that opens a field, so a report reads as a table and not a sentence. */
    LABEL(TextMid, 1),
    ;

    /** Whether this kind is drawn with a heavier weight as well as a colour. */
    val medium: Boolean get() = this == LABEL
}

/** One coloured range inside a plain string. */
internal data class SemHit(val start: Int, val end: Int, val kind: Sem)

/**
 * The scanner.
 *
 * Ordering is resolved by [Sem.priority], not by rule order: a date beats the bare numbers inside
 * it, an address beats the quads and octets inside it, and a long hex id beats the digits it
 * contains. Without that, `192.168.1.7` would come out as four separate pink numbers.
 */
internal object Semantic {

    /**
     * Blocks longer than this are appended uncoloured.
     *
     * Ten regular expressions over a growing streaming buffer would otherwise be re-scanned on
     * every token. Forty thousand characters is far past any real report and still under the
     * point where the scan costs a frame.
     */
    const val MAX_SCAN = 40_000

    private class Rule(val regex: Regex, val kind: Sem, val group: Int = 0)

    private fun r(pattern: String) = Regex(pattern)

    private val rules: List<Rule> = listOf(
        // Compressed or full IPv6: at least two colons, hex only. A clock also has two colons,
        // so it matches here too - and is then outranked by DATE, which has the higher priority.
        Rule(
            r("""(?<![\w:])(?:[0-9a-fA-F]{0,4}:){2,}[0-9a-fA-F]{0,4}(?::[0-9a-fA-F]{1,4})?(?![\w:])"""),
            Sem.ADDRESS,
        ),
        // Dotted quad with each octet bounded, so `999.1.1.1` is not an address.
        Rule(
            r("""(?<![\w.])(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?:\.(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}(?![\w.])"""),
            Sem.ADDRESS,
        ),
        // A long hex run: session ids, hashes. Twelve is the floor so `deadbeef` stays a word.
        Rule(r("""(?<![\w])[0-9a-fA-F]{12,}(?![\w])"""), Sem.IDENTIFIER),
        // ISO date.
        Rule(r("""(?<![\w-])\d{4}-\d{2}-\d{2}(?![\w-])"""), Sem.DATE),
        // Clock, with or without seconds.
        Rule(r("""(?<![\w:])\d{1,2}:\d{2}(?::\d{2})?(?![\w:])"""), Sem.DATE),
        // `v4.1` - a version that is only two parts still reads as a version because of the `v`.
        Rule(r("""(?<![\w.])v\d+(?:\.\d+)+(?![\w.])"""), Sem.NUMBER),
        // Dotted version of three or more parts, which needs no `v` to be unambiguous.
        Rule(r("""(?<![\w.])\d+(?:\.\d+){2,}(?![\w.])"""), Sem.NUMBER),
        // Duration with a unit, including the `~` hedge and the run-on `~1h49m`. The unit must
        // not be followed by a letter, but a digit is fine - that is what starts the next unit.
        Rule(r("""(?<![\w.])~?\d+(?:\.\d+)?\s?(?:ms|s|m|h|d|w|B|KB|MB|GB|TB)(?![A-Za-z])"""), Sem.NUMBER),
        // Bare number, optionally decimal or a percentage.
        Rule(r("""(?<![\w.])\d+(?:\.\d+)?%?(?![\w.])"""), Sem.NUMBER),
        // `word :` or `two words :` at the start of a line - the field name of a report.
        Rule(r("""(?m)^[ \t]*([A-Za-z][A-Za-z0-9_ ]{0,24}?)[ \t]*:(?=\s)"""), Sem.LABEL, 1),
    )

    /**
     * Every coloured range in [text], non-overlapping, in ascending order.
     *
     * A candidate that starts inside an already accepted range is dropped, and when two rules
     * claim the same start the higher [Sem.priority] wins. That is what keeps `192.168.1.7` one
     * green address instead of four pink numbers.
     */
    fun hits(text: String): List<SemHit> {
        if (text.isEmpty()) return emptyList()
        val candidates = mutableListOf<SemHit>()
        for (rule in rules) {
            for (m in rule.regex.findAll(text)) {
                val g = m.groups[rule.group] ?: continue
                val s = g.range.first
                val e = g.range.last + 1
                if (s >= e) continue
                candidates.add(SemHit(s, e, rule.kind))
            }
        }
        if (candidates.isEmpty()) return emptyList()
        candidates.sortWith(compareBy({ it.start }, { -it.kind.priority }, { -(it.end - it.start) }))
        val out = ArrayList<SemHit>(candidates.size)
        var cursor = 0
        for (c in candidates) {
            if (c.start < cursor) continue
            out.add(c)
            cursor = c.end
        }
        return out
    }
}

/**
 * Appends [s] with its semantic ranges coloured.
 *
 * Only ever called for a run that is plain prose: not a code span, not a link, not a session
 * reference. The caller's own [androidx.compose.ui.text.SpanStyle] is already pushed, so each
 * span here changes colour (and weight, for a label) and nothing else.
 */
internal fun androidx.compose.ui.text.AnnotatedString.Builder.appendSemantic(s: String) {
    if (s.length > Semantic.MAX_SCAN) {
        append(s)
        return
    }
    val hits = Semantic.hits(s)
    if (hits.isEmpty()) {
        append(s)
        return
    }
    var at = 0
    for (h in hits) {
        if (h.start > at) append(s, at, h.start)
        pushStyle(
            androidx.compose.ui.text.SpanStyle(
                color = h.kind.color,
                fontWeight = if (h.kind.medium) androidx.compose.ui.text.font.FontWeight.Medium
                else null,
            ),
        )
        append(s, h.start, h.end)
        pop()
        at = h.end
    }
    if (at < s.length) append(s, at, s.length)
}
