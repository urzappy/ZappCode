package com.zapp.net.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.ui.theme.AccentSoft
import com.zapp.net.ui.theme.CodePath
import com.zapp.net.ui.theme.InlineCodeBg
import com.zapp.net.ui.theme.Link
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.Surface2
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextMid
import com.zapp.net.ui.theme.TextPrimary
import kotlinx.coroutines.delay

/* The PC transcript's markdown renderer, measured off the shipped desktop bundle
   (`out/renderer/assets/main-BDySOyg3.css`, `[data-component=markdown]`):

     body        font-size 14px, line-height 160%
     p           margin-bottom 12
     h1          17/600, mt 28, mb 12
     h2          15/600, mt 24, mb 10
     h3          14/700, mt 20, mb 8
     h4..h6      14/500, colour text-muted, mt 20, mb 8
     ul/ol       padding-inline-start 32, mt 8, mb 12, li mb 8, marker muted
     blockquote  border-inline-start .5px, padding-inline-start 8, margin 16 0, muted
     hr          height 0, margin 32 0
     inline code mono/500, bg = text-base at 8%, radius 4, padding 0 4
     table       card: bg layer-01, inset .5px border, radius 6, padding 2;
                 table 13/440, th muted 530 pad 8/12, td pad 10/12, rule .5px
     code block  markdown-code-content margin 12 0 24

   Colours stay ours (themes are ZAPP's); every number above is theirs. */

private val BODY: TextUnit = 13.sp           // PC `--font-size-small`; 14px desktop is too wide on a phone
private val BODY_LINE: TextUnit = 20.sp      // PC `--line-height-base`
private val SMALL: TextUnit = 13.sp

/** languages their `Bzn()` classifies as `data-code-kind=shell` */
private val SHELL_LANGS = setOf(
    "bash", "sh", "shell", "zsh", "fish", "console", "shellscript", "shell-session", "ps1",
    "powershell", "cmd", "bat", "batch",
)

internal sealed class Block {
    data class Paragraph(val text: String) : Block()
    data class Heading(val text: String, val level: Int) : Block()
    data class Bullet(val text: String) : Block()
    data class Numbered(val n: Int, val text: String) : Block()
    data class Code(val lines: List<String>, val lang: String) : Block()
    data class Quote(val text: String) : Block()
    data class Table(val rows: List<String>) : Block()
    /** `![alt](src)` on its own line - the shape a model actually emits for a picture. */
    data class Image(val alt: String, val src: String) : Block()
    object Rule : Block()
}

@Composable
fun MarkdownText(
    src: String,
    modifier: Modifier = Modifier,
    textColor: Color = TextPrimary,
    fontSize: TextUnit = BODY,
    /**
     * `markdown-session-links.ts:9` - `markSessionLinks(root, title)`. Passing a title lookup
     * makes every `ses_...` in the prose a `@title` link; passing null leaves the text exactly
     * as it was, which is what every non-timeline caller wants.
     */
    sessionTitle: ((String) -> String?)? = null,
    onSession: ((String) -> Unit)? = null,
    /**
     * `inlineCodeKind` (`markdown-inline-code-kind.ts:45`) - a code span that names a file
     * opens it. Null leaves the span as plain code, which is what every non-timeline caller
     * wants.
     */
    onOpenFile: ((String) -> Unit)? = null,
    /**
     * `markdown-image.ts` - the reader behind `![alt](src)`. Null leaves the alt text, which is
     * their `failed` state anyway.
     */
    readImage: (suspend (String) -> ByteArray?)? = null,
) {
    val blocks = remember(src) { parseBlocks(src) }
    Column(modifier) {
        // CSS collapses adjacent vertical margins; Compose does not, so collapse by hand:
        // the gap between two blocks is max(bottom of the one above, top of the one below).
        var previousBottom = 0.dp
        blocks.forEachIndexed { index, b ->
            val (top, bottom) = margins(b)
            val gap = if (index == 0) 0.dp else maxOf(previousBottom, top)
            if (gap > 0.dp) Spacer(Modifier.height(gap))
            when (b) {
                is Block.Paragraph -> Text(
                    remember(b.text, textColor, sessionTitle, onSession, onOpenFile) { inlineSpan(b.text, textColor, false, onSession, sessionTitle, onOpenFile) },
                    fontSize = fontSize,
                    lineHeight = BODY_LINE,
                    color = textColor,
                    modifier = Modifier.fillMaxWidth(),
                )
                is Block.Heading -> {
                    // PC 17/15/14 against a 14px body; scaled to the 13px phone body so the
                    // heading steps keep their ratio instead of towering over the prose
                    val size = when (b.level) {
                        1 -> 16.sp
                        2 -> 14.sp
                        else -> 13.sp
                    }
                    val weight = when {
                        b.level <= 2 -> FontWeight.SemiBold
                        b.level == 3 -> FontWeight.Bold
                        else -> FontWeight.Medium
                    }
                    Text(
                        remember(b.text, textColor, sessionTitle, onSession, onOpenFile) { inlineSpan(b.text, textColor, true, onSession, sessionTitle, onOpenFile) },
                        fontSize = size,
                        lineHeight = 20.sp,
                        fontWeight = weight,
                        color = if (b.level >= 4) TextMid else textColor,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is Block.Bullet -> ListLine("\u2022", b.text, textColor, fontSize, sessionTitle, onSession, onOpenFile)
                is Block.Numbered -> ListLine("${b.n}.", b.text, textColor, fontSize, sessionTitle, onSession, onOpenFile)
                is Block.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    // border-inline-start: .5px solid --v2-border-border-base, padding-inline-start: .5rem
                    Box(
                        Modifier
                            .width(0.5.dp)
                            .fillMaxHeight()
                            .background(Line),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        remember(b.text, textColor, sessionTitle, onSession, onOpenFile) { inlineSpan(b.text, textColor, false, onSession, sessionTitle, onOpenFile) },
                        fontSize = fontSize,
                        lineHeight = BODY_LINE,
                        color = TextMid,
                    )
                }
                is Block.Code -> CodeFence(b)
                is Block.Image -> MarkdownImageBlock(b.alt, b.src, readImage)
                is Block.Table -> TableCard(b, textColor, sessionTitle, onSession, onOpenFile)
                Block.Rule -> Spacer(Modifier.height(0.dp))
            }
            previousBottom = bottom
        }
    }
}

/**
 * The user bubble's half of `markdown-session-links.ts`.
 *
 * `sessionReferenceText` (`:107`) runs on **plain** text: the user's own words are never
 * markdown-parsed, so `**x**` must stay literal there. This only turns references into
 * links and leaves every other character alone.
 */
/**
 * Every link in the app: body white, underlined, no colour of its own.
 *
 * the owner, 22 Nov: *"I DONT LIKE THE COLOR OF LINKS ADD A UNDERTAND LINE TO HIGHLIGHT IT THAT ITS
 * THE LINK"*. The underline is the signal - a link no longer has to be a different hue, which is
 * also what keeps the palette monochrome.
 */
internal val LinkStyle = SpanStyle(
    color = TextPrimary,
    textDecoration = TextDecoration.Underline,
)

fun plainSessionSpan(
    text: String,
    base: Color,
    sessionTitle: ((String) -> String?)? = null,
    onSession: ((String) -> Unit)? = null,
    /**
     * `@<path>` in his own words, made tappable.
     *
     * Their PC renders a file mention as a link you can click; ours drew the raw text, so a file
     * he had just sent could not be opened from the bubble - the owner: "AFTER SENDING IT I CANT TAP
     * THE FILES LINK ... HINDI SAME SA PC NA PWDE MA TAP ANG FILES".
     */
    onOpenFile: ((String) -> Unit)? = null,
): AnnotatedString {
    val hits = if (onSession != null) SessionMention.findAll(text) else emptyList()
    if (hits.isEmpty() && onOpenFile == null) {
        return AnnotatedString(text, SpanStyle(color = base))
    }
    return buildAnnotatedString {
        var at = 0
        hits.forEach { h ->
            appendWithFiles(text, at, h.start, onOpenFile)
            val title = sessionTitle?.invoke(h.id)
            if (title == null) {
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        background = InlineCodeBg,
                        color = base,
                    ),
                )
                append(h.text)
                pop()
            } else {
                addLink(
                    LinkAnnotation.Clickable(
                        tag = "ses:" + h.id,
                        styles = TextLinkStyles(style = LinkStyle),
                        linkInteractionListener = { onSession?.invoke(h.id) },
                    ),
                    length,
                    length + title.length + 1,
                )
                append("@" + title)
            }
            at = h.end
        }
        appendWithFiles(text, at, text.length, onOpenFile)
    }
}

/**
 * `@ZAPP-inbox/1795305838019-config.apk` and friends. A token only counts as a file when it
 * carries a `/` or a `.`, which is what keeps `@ses_0880b09fff` out of it.
 */
private val FileMention = Regex("""@([A-Za-z0-9._\-/~%+]*[/.][A-Za-z0-9._\-/~%+]*)""")

/** Appends `text[from until to]`, turning file mentions into tappable inline-code links. */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendWithFiles(
    text: String,
    from: Int,
    to: Int,
    onOpenFile: ((String) -> Unit)?,
) {
    if (onOpenFile == null || from >= to) {
        if (from < to) append(text, from, to)
        return
    }
    var i = from
    while (i < to) {
        val m = FileMention.find(text, i)
        if (m == null || m.range.first >= to) {
            append(text, i, to)
            return
        }
        if (m.range.first > i) append(text, i, m.range.first)
        val path = m.groupValues[1]
        addLink(
            LinkAnnotation.Clickable(
                tag = "file:" + path,
                styles = TextLinkStyles(
                    style = SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        background = InlineCodeBg,
                        color = TextPrimary,
                        // The underline is what says "this opens something". Without it a file
                        // mention read as a code span, which is why tapping it was not obvious.
                        textDecoration = TextDecoration.Underline,
                    ),
                ),
                linkInteractionListener = { onOpenFile(path) },
            ),
            length,
            length + m.value.length,
        )
        append(m.value)
        i = m.range.last + 1
    }
}

/**
 * Plain text with its dates, addresses, numbers, ids and field labels coloured.
 *
 * This is the whole of the "important details" pass: every place the transcript draws raw text -
 * tool output, reasoning, a system line, an error, the footer meta - goes through here instead of
 * a bare [Text], so a report reads as a table of coloured facts rather than a wall of grey.
 *
 * [color] is the base the caller wanted; everything the scanner does not recognise keeps it, so
 * the surrounding design is unchanged and only the meaningful tokens lift out of it.
 */
@Composable
fun SemanticText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    lineHeight: TextUnit = TextUnit.Unspecified,
    fontFamily: FontFamily? = null,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip,
) {
    val span = remember(text, color, fontFamily, fontWeight) {
        buildAnnotatedString {
            pushStyle(SpanStyle(color = color, fontFamily = fontFamily, fontWeight = fontWeight))
            appendSemantic(text)
            pop()
        }
    }
    Text(
        span,
        fontSize = fontSize,
        lineHeight = lineHeight,
        maxLines = maxLines,
        softWrap = softWrap,
        overflow = overflow,
        modifier = modifier,
    )
}

/** A single list line: 32dp of indent like `padding-inline-start:32px`, muted marker. */@Composable
private fun ListLine(
    marker: String,
    text: String,
    textColor: Color,
    fontSize: TextUnit,
    sessionTitle: ((String) -> String?)? = null,
    onSession: ((String) -> Unit)? = null,
    onOpenFile: ((String) -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            marker,
            fontSize = fontSize,
            lineHeight = BODY_LINE,
            color = TextDim,
            modifier = Modifier.width(20.dp),
        )
        Text(
            remember(text, textColor, sessionTitle, onSession, onOpenFile) { inlineSpan(text, textColor, false, onSession, sessionTitle, onOpenFile) },
            fontSize = fontSize,
            lineHeight = BODY_LINE,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/** PC's margin table, one entry per block kind. */
private fun margins(b: Block): Pair<Dp, Dp> = when (b) {
    is Block.Paragraph -> 0.dp to 12.dp
    is Block.Heading -> when (b.level) {
        1 -> 28.dp to 12.dp
        2 -> 24.dp to 10.dp
        else -> 20.dp to 8.dp
    }
    is Block.Bullet, is Block.Numbered -> 8.dp to 12.dp
    is Block.Quote -> 16.dp to 16.dp
    is Block.Code -> 12.dp to 24.dp
    is Block.Table -> 16.dp to 16.dp
    is Block.Image -> 12.dp to 16.dp
    Block.Rule -> 32.dp to 0.dp
}

internal fun inlineSpan(
    text: String,
    base: Color,
    boldAll: Boolean = false,
    onSession: ((String) -> Unit)? = null,
    sessionTitle: ((String) -> String?)? = null,
    onOpenFile: ((String) -> Unit)? = null,
): AnnotatedString {
    // pass 1: token scan into runs with live bold/italic/code flags
    data class Run(val text: String, val bold: Boolean, val italic: Boolean, val code: Boolean, val link: String?)
    val runs = mutableListOf<Run>()
    val sb = StringBuilder()
    var bold = boldAll
    var italic = false
    var code = false
    fun flush(link: String? = null) {
        if (sb.isNotEmpty()) { runs.add(Run(sb.toString(), bold, italic, code, link)); sb.clear() }
    }
    var i = 0
    while (i < text.length) {
        // marked's `Lexer.inlineTokens` tries `autolink` then `url` at every position,
        // after emStrong/codespan/link and before `inlineText`. The emphasis checks stay
        // first so a leading `_` still opens italics instead of being eaten as an e-mail
        // local part - that is their order too (`emStrong` runs before `url`).
        val auto = if (code) null else Autolink.at(text, i)
        when {
            text.startsWith("**", i) || text.startsWith("__", i) -> { flush(); bold = !bold; i += 2 }
            text[i] == '*' || text[i] == '_' -> { flush(); italic = !italic; i += 1 }
            text[i] == '`' -> { flush(); code = !code; i += 1 }
            auto != null -> {
                // A bare URL or `<scheme:...>`: marked emits a `link` token, so it is an
                // anchor, not text. `rawLength` is the backpedalled length, which is how
                // `see https://x.dev.` keeps the full stop outside the link.
                flush()
                runs.add(Run(auto.text, bold, italic, false, auto.href))
                i += auto.rawLength
            }
            text[i] == '[' -> {
                val close = text.indexOf(']', i)
                val paren = if (close != -1) text.indexOf("](", close) else -1
                val end = if (paren == close + 1) text.indexOf(')', paren + 2) else -1
                // `![alt](src)` is an image, not a link. `parseBlocks` already lifts a
                // paragraph-level image into its own `Block.Image`; inside a bullet, a quote
                // or a heading it is left as its source text rather than turned into a link
                // whose label is the alt text - PC would draw a picture there, and a wrong
                // link is worse than an honest literal.
                val isImage = i > 0 && text[i - 1] == '!'
                if (!isImage && close != -1 && end != -1) {
                    flush(); runs.add(Run(text.substring(i + 1, close), bold, italic, code, text.substring(paren + 2, end)))
                    i = end + 1
                } else { sb.append(text[i]); i += 1 }
            }
            else -> { sb.append(text[i]); i += 1 }
        }
    }
    flush()
    // pass 2: build annotated string
    return buildAnnotatedString {
        runs.forEach { r ->
            if (r.link != null) {
                // `a { color: var(--v2-text-text-accent); text-decoration: none }` - the dark
                // theme maps that token to `v2-blue-400` (#a2bcff), so the blue is the only
                // thing marking a link. Only http(s) is a target - `markdown.tsx:1055` refuses
                // every other protocol, and a phone has no document to scroll for a `#fragment`.
                val target = MarkdownLink.anchorTarget(r.link)
                if (target == null) {
                    pushStyle(LinkStyle)
                    append(r.text)
                    pop()
                } else {
                    addLink(
                        LinkAnnotation.Url(
                            url = target,
                            styles = TextLinkStyles(style = LinkStyle),
                        ),
                        length,
                        length + r.text.length,
                    )
                    append(r.text)
                }
                return@forEach
            }
            var style = SpanStyle(color = base)
            if (r.bold) style = style.merge(SpanStyle(fontWeight = FontWeight.Bold))
            if (r.italic) style = style.merge(SpanStyle(fontStyle = FontStyle.Italic))
            if (r.code) {
                // :not(pre)>code - mono, weight 500, bg text-base at 8%, radius 4, padding 0 4
                style = style.merge(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        background = InlineCodeBg,
                    ),
                )
            }
            // `markCodeLinks` (`markdown.tsx:973-1005`): an inline code span whose whole text
            // is a URL becomes the anchor. This runs before the session-reference pass because
            // a code span never carries a reference (`markdown-session-links.ts:55`).
            val codeTarget = if (r.code) MarkdownLink.codeUrl(r.text) else null
            if (codeTarget != null) {
                // `a code { color: inherit; background: 0 0 }` - the anchor's blue, no chip fill.
                pushStyle(style.copy(color = TextPrimary, background = Color.Transparent, textDecoration = TextDecoration.Underline))
                addLink(
                    LinkAnnotation.Url(
                        url = codeTarget,
                        styles = TextLinkStyles(style = LinkStyle),
                    ),
                    length,
                    length + r.text.length,
                )
                append(r.text)
                pop()
                return@forEach
            }
            // `inlineCodeKind` (`markdown-inline-code-kind.ts:45`): a code span that names a
            // file is a path link. Desktop hands it to the editor after the host confirms the
            // file exists; the phone hands it to `openFile`, which shows its own error row for
            // a miss, so the existence check stays where it already was.
            val codePath = if (r.code && onOpenFile != null) {
                InlineCodeKind.kind(r.text).takeIf { it == InlineCodeKind.Kind.Path }
                    ?.let { PathSuffix.parse(r.text).path }
            } else null
            if (codePath != null) {
                // `[data-inline-code-kind=path] { color: --v2-text-text-code-path;
                // background-color: #0000 }` - its own token and no chip fill.
                pushStyle(style.copy(color = CodePath, background = Color.Transparent, textDecoration = TextDecoration.Underline))
                addLink(
                    LinkAnnotation.Clickable(
                        tag = "file:$codePath",
                        styles = TextLinkStyles(style = SpanStyle(color = CodePath, textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = { onOpenFile?.invoke(codePath) },
                    ),
                    length,
                    length + r.text.length,
                )
                append(r.text)
                pop()
                return@forEach
            }
            pushStyle(style)
            // `markdown-session-links.ts:107-127` - a plain run may carry references, and each
            // one is either a `@title` button or, when the session is unknown, the raw source
            // as a code span (`:131-149`). Code spans never contain links (`:55`), which is
            // why this branch is skipped for `r.code`.
            val hits = if (onSession != null && !r.code) SessionMention.findAll(r.text) else emptyList()
            if (hits.isEmpty()) {
                appendSemantic(r.text)
            } else {
                var at = 0
                hits.forEach { h ->
                    appendSemantic(r.text.substring(at, h.start))
                    val title = sessionTitle?.invoke(h.id)
                    if (title == null) {
                        // `:133-147` - no title, no button: the reference stays its own source.
                        pushStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                background = InlineCodeBg,
                                color = base,
                            ),
                        )
                        append(h.text)
                        pop()
                    } else {
                        addLink(
                            LinkAnnotation.Clickable(
                                tag = "ses:" + h.id,
                                styles = TextLinkStyles(style = LinkStyle),
                                linkInteractionListener = { onSession?.invoke(h.id) },
                            ),
                            length,
                            length + title.length + 1,
                        )
                        append("@" + title)
                    }
                    at = h.end
                }
                appendSemantic(r.text.substring(at))
            }
            pop()
        }
    }
}

internal fun parseBlocks(src: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = src.lines()
    val para = mutableListOf<String>()
    val imageLine = Regex("""^\s*!\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)\s*$""")
    /**
     * `[data-component=markdown] img { display: block; margin: 1.5rem 0 }` - an image is a
     * **block** even when it sits mid-sentence on the desktop. So the faithful port of
     * `see ![chart](a.png) above` is three blocks in a row: the text, the image, the text -
     * not an inline bitmap. That is why this splits the paragraph instead of reaching for
     * `appendInlineContent`: PC never draws the image inside the line either.
     */
    val inlineImage = Regex("""!\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)""")
    fun flush() {
        if (para.isEmpty()) return
        val text = para.joinToString(" ").trim()
        para.clear()
        if (text.isEmpty()) return
        var at = 0
        inlineImage.findAll(text).forEach { m ->
            val before = text.substring(at, m.range.first).trim()
            if (before.isNotEmpty()) out.add(Block.Paragraph(before))
            out.add(Block.Image(m.groupValues[1], m.groupValues[2]))
            at = m.range.last + 1
        }
        val rest = text.substring(at).trim()
        if (rest.isNotEmpty()) out.add(Block.Paragraph(rest))
    }
    var i = 0
    while (i < lines.size) {
        val l = lines[i]
        when {
            l.trimStart().startsWith("```") -> {
                flush()
                val lang = l.trim().removePrefix("```").trim()
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.add(lines[i]); i++ }
                i++ // closing fence
                out.add(Block.Code(code, lang))
            }
            l.startsWith("#") -> {
                flush()
                val level = l.takeWhile { it == '#' }.length
                out.add(Block.Heading(l.dropWhile { it == '#' }.trim(), level)); i++
            }
            Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$").containsMatchIn(l) -> {
                flush(); out.add(Block.Rule); i++
            }
            // `![alt](src)` alone on a line. Handled here as well as in `flush()` so the
            // blank-line bookkeeping stays intact: this branch consumes the line before it
            // can join a paragraph at all.
            imageLine.containsMatchIn(l) -> {
                flush()
                val m = imageLine.find(l)!!
                out.add(Block.Image(m.groupValues[1], m.groupValues[2])); i++
            }
            Regex("^\\s*[-*+]\\s+").containsMatchIn(l) -> {
                flush()
                out.add(Block.Bullet(l.replaceFirst(Regex("^\\s*[-*+]\\s+"), ""))); i++
            }
            Regex("^\\s*\\d+\\.\\s+").containsMatchIn(l) -> {
                flush()
                val n = Regex("^\\s*(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                out.add(Block.Numbered(n, l.replaceFirst(Regex("^\\s*\\d+\\.\\s+"), ""))); i++
            }
            l.trimStart().startsWith(">") -> {
                flush()
                out.add(Block.Quote(l.trimStart().removePrefix(">").trim())); i++
            }
            l.trim().startsWith("|") && l.trim().endsWith("|") -> {
                flush()
                val rows = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith("|")) { rows.add(lines[i].trim()); i++ }
                out.add(Block.Table(rows.filter { !Regex("^\\|[-: |]+\\|$").containsMatchIn(it) }))
            }
            l.isBlank() -> { flush(); i++ }
            else -> { para.add(l.trim()); i++ }
        }
    }
    flush()
    return out
}

@Composable
private fun CodeFence(b: Block.Code) {
    val code = b.lines.joinToString("\n")
    // `[data-code-kind=shell]`: one colour for every token, and the block flips to the
    // layer-02 surface with the muted border instead of `.highlights`' #151515.
    val shell = b.lang.lowercase().substringBefore(' ').trim() in SHELL_LANGS
    val colored = remember(code, b.lang, shell) {
        if (shell) AnnotatedString(code) else highlightCode(code, b.lang)
    }
    // `copy-machine.ts` - 2 000 ms, and a click inside the window keeps the checkmark while
    // the next write settles. The old `var copied` + `delay(1400)` was 600 ms short.
    val feedback = rememberCopyFeedback()
    val copied = feedback.copied
    // Their block, measured from the desktop bundle:
    //   .highlights { background:#151515; border:.5px solid #ffffff1a; border-radius:6px;
    //                 padding:12px; font-size:13px; line-height:20px }
    //   [data-slot=markdown-copy-button] { position:absolute; top:4px; inset-inline-end:4px }
    //
    // Desktop reveals the copy control on :hover, so it may sit on top of the first line for
    // the instant the pointer crosses it. A finger has no hover: an always-visible icon
    // floating over the code hides the end of the first line permanently, which is what made
    // a one-line JSON block on 2026-11-20 read `...EQmHh` with a glyph on top of it. The
    // control therefore gets its own band at the top of the block and the code starts under
    // it - same corner, no overlap.
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (shell) Surface2 else Syn.BlockBg)
            .border(0.5.dp, if (shell) Line else Syn.BlockBorder, RoundedCornerShape(6.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Icon(
                if (copied) OcIcons.Check else OcIcons.Copy,
                contentDescription = if (copied) "Copied" else "Copy",
                tint = if (copied) AccentSoft else TextDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { feedback.copy(code) }
                    .padding(6.dp)
                    .size(13.dp),
            )
        }
        Text(
            colored,
            color = if (shell) TextMid else Color.Unspecified,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}

/** `[data-component=markdown-table]`: a 6dp card holding a 13px table. */
@Composable
private fun TableCard(
    b: Block.Table,
    textColor: Color,
    sessionTitle: ((String) -> String?)? = null,
    onSession: ((String) -> Unit)? = null,
    onOpenFile: ((String) -> Unit)? = null,
) {
    val rows = b.rows.map { r ->
        r.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
    }.filter { it.any { c -> c.isNotEmpty() } }
    if (rows.isEmpty()) return
    val cols = rows.maxOf { it.size }
    // column widths follow the longest cell, so "1" stays narrow and prose gets room
    val weights = (0 until cols).map { c ->
        rows.maxOf { it.getOrElse(c) { "" }.length }.coerceIn(2, 26).toFloat()
    }
    val total = weights.sum()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Surface2)
            .border(0.5.dp, Line, RoundedCornerShape(6.dp))
            .padding(2.dp),
    ) {
        rows.forEachIndexed { idx, cells ->
            if (idx > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(Line))
            Row(
                Modifier.fillMaxWidth().padding(
                    horizontal = 12.dp,
                    vertical = if (idx == 0) 8.dp else 10.dp,
                ),
                verticalAlignment = Alignment.Top,
            ) {
                for (c in 0 until cols) {
                    val cell = cells.getOrElse(c) { "" }
                    Text(
                        remember(cell, textColor, sessionTitle, onSession, onOpenFile) {
                            inlineSpan(cell, if (idx == 0) TextMid else textColor, idx == 0, onSession, sessionTitle, onOpenFile)
                        },
                        fontSize = SMALL,
                        lineHeight = 16.sp,
                        fontWeight = if (idx == 0) FontWeight.Medium else FontWeight(440),
                        modifier = Modifier
                            .weight(weights[c] / total)
                            .padding(end = 12.dp),
                    )
                }
            }
        }
    }
}


