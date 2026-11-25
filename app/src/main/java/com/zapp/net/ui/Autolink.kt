package com.zapp.net.ui

import java.util.regex.Pattern

/**
 * PORT of marked's two inline link tokenizers, as they are shipped inside
 * `markdown-QX4Pt8NM.js`.
 *
 * The chat's prose is rendered by **marked**, not by a hand-rolled parser. Two rules in
 * its tokenizer turn raw text into an anchor before `inlineText` ever sees it:
 *
 *     autolink(e) {                                        // @ 38 052
 *       let t = this.rules.inline.autolink.exec(e);
 *       if (t) {
 *         let e, n;
 *         return t[2] === `@` ? (e = t[1], n = `mailto:` + e) : (e = t[1], n = e),
 *         { type:`link`, raw:t[0], text:e, href:n, ... }
 *       }
 *     }
 *
 *     url(e) {                                             // @ 38 052
 *       let t;
 *       if (t = this.rules.inline.url.exec(e)) {
 *         let e, n;
 *         if (t[2] === `@`) e = t[0], n = `mailto:` + e;
 *         else {
 *           let r;
 *           do { r = t[0]; t[0] = this.rules.inline._backpedal.exec(t[0])?.[0] ?? ``; }
 *           while (r !== t[0]);
 *           e = t[0], n = t[1] === `www.` ? `http://` + t[0] : t[0];
 *         }
 *         return { type:`link`, raw:t[0], text:e, href:n, ... }
 *       }
 *     }
 *
 * and the rules themselves (`markdown-QX4Pt8NM.js` @ 22 901 / 24 662):
 *
 *     autolink: /^<(scheme:[^\s\x00-\x1f<>]*|email)>/
 *     url:      /^((?:protocol):\/\/|www\.)(?:[a-zA-Z0-9\-]+\.?)+[^\s<]*|^email/
 *     _backpedal: /(?:[^?!.,:;*_'"~()&]+|\([^)]*\)|&(?![a-zA-Z0-9]+;$)|[?!.,:;*_'"~)]+(?!$))+/
 *
 *     protocol: /[hH][tT][tT][pP][sS]?|[fF][tT][pP]/
 *     email:    /[A-Za-z0-9._+-]+(@)[a-zA-Z0-9-_]+(?:\.[a-zA-Z0-9-_]*[a-zA-Z0-9])+(?![-_])/
 *
 * `Lexer.inlineTokens` tries `autolink` then `url` at every position (`@ 44 301`):
 *
 *     if (o = this.tokenizer.autolink(e)) { ... }
 *     if (!this.state.inLink && (o = this.tokenizer.url(e))) { ... }
 *
 * ZAPP had neither, so every bare URL in a reply was drawn as plain text. the owner, on the
 * phone, 2026-11-21: *"no the text link that i can click automatically"*.
 *
 * ## What this rule does and does not catch
 *
 * Faithfully, because it is their regex and not a nicer one:
 *
 *  - **A scheme is required**, or a leading `www.`. `127.0.0.1:8090` is **not** a link on
 *    the PC either: none of `http://`, `https://`, `ftp://` or `www.` starts it, and the
 *    e-mail branch needs an `@`. Do not "fix" that here; it would be a rule the PC does
 *    not have.
 *  - `WWW.` is **not** matched. The rule spells the literal `www\.` in lowercase and the
 *    whole regex carries no `i` flag - only the scheme alternation is written out
 *    case-insensitively (`[hH][tT]...`).
 *  - Trailing `?!.,:;*'"~)` are stripped by `_backpedal`, so `see https://x.dev.` links
 *    the address without the full stop, and `**https://x**` links `https://x` and leaves
 *    the closing `**` for the emphasis pass. An `_` is **not** stripped - it is in
 *    `_backpedal`'s *second* class `[?!.,:;*_'"~)]`, so `https://x.dev/a_b` links whole.
 *    Verified against the regex itself, not read off it: node gives
 *    `"https://x.dev/a_b" -> "https://x.dev/a_b"` and `"https://x.dev." -> "https://x.dev"`.
 *  - A **trailing** HTML entity is protected: `&(?![a-zA-Z0-9]+;$)` leaves `&amp;` at the
 *    end of the string outside the link, while the same `&` in the middle is swallowed.
 *  - A bare e-mail becomes `mailto:`. Our renderer colours it but does not make it
 *    tappable - `MarkdownLink.anchorTarget` only admits http(s), the same gate as their
 *    `markExternalLinkFavicons` (`markdown.tsx:1055`). Disclosed deviation.
 *
 * ## The one rule here that is NOT marked's
 *
 * [LOCAL] is ours. Their `url` rule cannot match `127.0.0.1:8090` or `localhost:4096`,
 * because it insists on a scheme or a leading `www.`. On a desktop that is fine - you
 * paste the address into a browser tab. On a phone it is the difference between a
 * sentence you can act on and a sentence you cannot: the owner, 2026-11-21, reading a reply
 * that said *"check for a new browser tab at 127.0.0.1:8090"*, had nothing to tap.
 *
 * The rule is deliberately narrow so it cannot eat code or prose:
 *
 *  - the host must be a dotted quad (`1.2.3.4`), `localhost`, or a dotted name with a
 *    letter-only TLD, **and**
 *  - it must be followed by `:port` with 2-5 digits.
 *
 * A bare `foo.bar` is *not* a link, so `MainActivity.kt`, `src/main/java` and `v2.3.2`
 * are untouched; `android:label` has no dot in the host and no digits after the colon, so
 * it is untouched too. `http://x:80` never reaches this branch - marked's rule runs first
 * and wins.
 */
object Autolink {

    /** One matched token: what to draw, where it goes, and how much input it ate. */
    data class Hit(
        /** `text` - the label, which for a backpedalled URL is shorter than `raw`. */
        val text: String,
        /** `href` - already normalised (`www.x` -> `http://www.x`, e-mail -> `mailto:`). */
        val href: String,
        /** `raw.length` - how far the scanner advances. */
        val rawLength: Int,
    )

    private const val PROTOCOL = "[hH][tT][tT][pP][sS]?|[fF][tT][pP]"

    private const val EMAIL =
        "[A-Za-z0-9._+-]+(@)[a-zA-Z0-9-_]+(?:\\.[a-zA-Z0-9-_]*[a-zA-Z0-9])+(?![-_])"

    /** `cn` - `^<(scheme:[^\s\x00-\x1f<>]*|email)>`. */
    private val AUTOLINK: Pattern = Pattern.compile(
        "^<([a-zA-Z][a-zA-Z0-9+.-]{1,31}:[^\\s\\x00-\\x1f<>]*" +
            "|[a-zA-Z0-9.!#${'$'}%&'*+/=?^_`{|}~-]+(@)[a-zA-Z0-9]" +
            "(?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?" +
            "(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+(?![-_]))>",
    )

    /** The `url` rule with `protocol` and `email` substituted in. */
    private val URL: Pattern = Pattern.compile(
        "^((?:$PROTOCOL):\\/\\/|www\\.)(?:[a-zA-Z0-9\\-]+\\.?)+[^\\s<]*|^$EMAIL",
    )

    /** `_backpedal`, verbatim. */
    private val BACKPEDAL: Pattern = Pattern.compile(
        "(?:[^?!.,:;*_'\"~()&]+|\\([^)]*\\)|&(?![a-zA-Z0-9]+;${'$'})|[?!.,:;*_'\"~)]+(?!${'$'}))+",
    )

    /**
     * OURS, not marked's - see the class doc. A host that carries an explicit port, so a
     * local dev server in a reply is tappable. Never consulted when [URL] already matched.
     */
    private val LOCAL: Pattern = Pattern.compile(
        "^(\\d{1,3}(?:\\.\\d{1,3}){3}|localhost|(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,})" +
            ":(\\d{2,5})(/[^\\s<>\"'`]*)?",
    )

    /** `[),.;!?]+$` - the same strip `codeUrl` uses, applied after [LOCAL]. */
    private val LOCAL_TRAILING = Regex("""[),.;!?]+$""")

    /**
     * The token that starts at [index], or `null`.
     *
     * `region` + `lookingAt` is the equivalent of marked's `exec` against `e`, the string
     * left from the current position: `^` then anchors at the region's start.
     */
    fun at(text: String, index: Int): Hit? {
        if (index >= text.length) return null
        val c = text[index]
        // Cheap gate before touching a regex. The rules can only start with `<`, a letter,
        // a digit, or one of `_ + - .` (the e-mail branch's leading class).
        if (c != '<' && !(c in 'a'..'z') && !(c in 'A'..'Z') && !(c in '0'..'9') &&
            c != '_' && c != '+' && c != '-' && c != '.'
        ) return null

        if (c == '<') {
            val m = AUTOLINK.matcher(text).region(index, text.length)
            if (!m.lookingAt()) return null
            val inner = m.group(1)
            val mail = m.group(2) == "@"
            return Hit(inner, if (mail) "mailto:$inner" else inner, m.group(0).length)
        }

        val m = URL.matcher(text).region(index, text.length)
        if (!m.lookingAt()) return local(text, index)
        val raw = m.group(0)
        // The e-mail branch never backpedals - marked returns `t[0]` unchanged.
        if (m.group(2) == "@") return Hit(raw, "mailto:$raw", raw.length)

        var cur = raw
        while (true) {
            val next = backpedal(cur)
            if (next == cur) break
            cur = next
        }
        if (cur.isEmpty()) return null
        val href = if (m.group(1) == "www.") "http://$cur" else cur
        return Hit(cur, href, cur.length)
    }

    /** `this.rules.inline._backpedal.exec(t[0])?.[0] ?? ""` - anchored at 0. */
    private fun backpedal(s: String): String {
        val m = BACKPEDAL.matcher(s)
        return if (m.lookingAt()) m.group(0) else ""
    }

    /**
     * The one rule that is ours: `host:port`, with the scheme assumed to be `http`.
     *
     * A WebView and an `Intent` both need a scheme, so the href is always spelled out;
     * the label stays exactly what the writer typed.
     */
    private fun local(text: String, index: Int): Hit? {
        val m = LOCAL.matcher(text).region(index, text.length)
        if (!m.lookingAt()) return null
        val label = m.group(0).replace(LOCAL_TRAILING, "")
        if (label.isEmpty()) return null
        return Hit(label, "http://$label", label.length)
    }
}
