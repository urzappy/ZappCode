package com.zapp.net.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle

/* Exact oc-2 dark tokens, lifted from `oc-2-COeEKe_J.js` (the theme module) and the bundle's
   primitives: keyword/primitive pink-400 #F799C6, string green-400 #96E3A6, property/function
   orange-400 #FFC1A4, type purple-400 #9E99F7, constant #93E9F6, comment = text-muted (dark),
   punctuation text-base, operator text-weak. The dark token block, verbatim:

     syntax-comment    var(--v2-text-text-muted)
     syntax-keyword    var(--v2-pink-400)
     syntax-string     var(--v2-green-400)
     syntax-primitive  var(--v2-pink-400)
     syntax-property   var(--v2-orange-400)
     syntax-type       var(--v2-purple-400)
     syntax-constant   #93e9f6
     syntax-critical   var(--v2-red-400)

   **Changed since Pass 31.** the owner, 2026-11-22: *"i hate the color red gren and gray to the files
   codes its not a green red color like maganda sa mata tignan same sa ibang color"*. He is pointing
   at a diff, where the code inside it was painted with this palette on top of the new violet/sand
   rows - and what he saw was a mint-green string, a fire-engine red, and four flat greys.

   Three of those were the only *unsoftened* colours left on the screen. Pink, orange, purple and
   cyan all sit at 60-90% lightness with modest chroma; the greys sat at R=G=B, and the mint sat at
   S=57%. The fix keeps every token's **role** and its ordering, and moves the outliers onto the
   same axis as the rest:

     token        was        now        role
     Keyword      #F799C6    #F799C6    unchanged - the pink is his
     Annotation   #FFC1A4    #FFC1A4    unchanged
     String       #96E3A6    #97D6C8    green kept, hue pulled 24 deg to teal, chroma 57% -> 41%
     Comment      #AEAEAE    #8A8894    neutral grey -> ink (violet-cast, 5.45:1 on BlockBg)
     Plain        #F0F0F0    #EFEEF3    violet-cast near-white, 15.7:1
     Punct        #A6A6A6    #9C9BA8    ink, 6.9:1
     Operator     #787878    #7E7D88    ink, 4.65:1

   `String` is still green because every editor on earth paints strings green and moving it would
   cost recognition for no gain; what changed is that it is no longer the loudest thing in the
   block. Nothing here is R=G=B any more, and the four greys that made code read as dead text are
   gone.
*/

object Syn {
    val Keyword = Color(0xFFF799C6)    // --v2-pink-400      (oc-2 dark syntax-keyword)
    val Annotation = Color(0xFFFFC1A4) // --v2-orange-400
    val String = Color(0xFF97D6C8)     // was --v2-green-400 #96E3A6 - sea-glass, see above
    val Number = Color(0xFFF799C6)     // --v2-pink-400      (syntax-primitive)
    /*
     * `syntax-comment: var(--v2-text-text-muted)` and the **dark** muted is
     * `--v2-grey-500` = `#AEAEAE`, not `#A6A6A6`. `#A6A6A6` is not a token at all: it is
     * `--text-base` (`#ffffff9e`) composited over `#151515`, which is a *different* slot
     * (punctuation). Corrected 2026-11-21 while auditing the owner's "all same as PC" question.
     *
     * Retinted 2026-11-22: the grey is now ink, one step below `TextMid`, so a comment still
     * recedes behind the code without being the only neutral thing in the block.
     */
    val Comment = Color(0xFF8A8894)    // was #AEAEAE - ink, 5.45:1 on BlockBg
    val Function = Color(0xFFFFC1A4)   // --v2-orange-400    (syntax-property)
    val Type = Color(0xFF9E99F7)       // --v2-purple-400    (syntax-type)
    val Constant = Color(0xFF93E9F6)   // syntax-constant
    /*
     * The three below are the diff/syntax library's own slots, which are **alpha over
     * #151515**, not opaque hex. Composited exactly:
     *
     *   --text-strong #ffffffef  a=239/255 -> 239 + 21*(1-0.937) = 240 -> #F0F0F0
     *   --text-base   #ffffff9e  a=158/255 -> 158 + 21*(1-0.620) = 166 -> #A6A6A6
     *   --text-weak   #ffffff6c  a=108/255 -> 108 + 21*(1-0.424) = 120 -> #787878
     *
     * Drawing the alpha values as opaque hex is what made these drift. The *ordering* is theirs
     * and is preserved (strong > base > weak); the hue is now the app's ink.
     */
    val Plain = Color(0xFFEFEEF3)      // was #F0F0F0
    val Punct = Color(0xFF9C9BA8)      // was #A6A6A6
    val Operator = Color(0xFF7E7D88)   // was #787878

    /* Their `.highlights` background is `--color-background-stronger` (#151515 in dark), which is
       a flat neutral grey and reads as a dirty slab next to our blue-grey page. the owner, 20 Nov:
       *"ang pangit nong background color ng mga codes"*. It is now one step above the canvas and
       inside the same family as every other card: `#101012` fill, `#1F1F23` hairline. */
    val BlockBg = Color(0xFF101012)
    val BlockBorder = Color(0xFF1F1F23)
}

private val KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "typealias", "constructor",
    "private", "public", "internal", "protected", "override", "open", "abstract",
    "data", "sealed", "enum", "companion", "inline", "suspend", "operator", "infix",
    "import", "package", "return", "if", "else", "when", "for", "while", "do",
    "try", "catch", "finally", "throw", "is", "in", "as", "null", "true", "false",
    "this", "super", "init", "by", "where", "out", "reified", "lateinit", "const",
    "new", "static", "final", "void", "int", "long", "double", "float", "boolean",
    "char", "byte", "short", "extends", "implements", "instanceof", "synchronized",
    "struct", "typedef", "unsigned", "signed", "sizeof", "extern", "volatile",
    "function", "let", "async", "await", "yield", "lambda", "with", "pass", "elif",
    "except", "raise", "global", "nonlocal", "and", "or", "not", "None", "True",
    "False", "self", "assert", "del", "from",
    "pub", "fn", "impl", "mut", "match", "use", "mod", "crate", "loop", "break",
    "continue", "trait", "dyn", "unsafe", "ref", "move",
    "echo", "then", "fi", "done", "esac", "case", "export", "local", "source",
    "alias", "exit", "set", "unset", "readonly", "declare",
    "select", "insert", "update", "delete", "create", "table", "join", "group",
    "order", "by", "limit", "distinct", "values", "into", "set", "where",
    "def", "end", "module", "require", "include", "puts", "nil", "elsif", "unless",
)

private val HASH_LANGS = setOf(
    "py", "python", "sh", "bash", "zsh", "fish", "yaml", "yml", "toml", "rb",
    "ruby", "r", "pl", "perl", "conf", "ini", "dockerfile", "makefile", "ps1",
    "powershell", "properties", "env", "gitignore",
)

/**
 * The two constructs that may cross a newline: an open block comment and an open triple quote.
 * Everything else in the tokenizer is line-local, which is what lets a file be painted one line
 * at a time inside a `LazyColumn` instead of as one enormous `AnnotatedString`.
 *
 * Why this exists: opening a 6 000-line file as a single `Text` put every span into one
 * `SpannableString` on the main thread, and Android killed the app with an ANR
 * (`Input dispatching timed out ... Waited 5001ms for MotionEvent`, dropbox 2026-11-21 17:47:59,
 * main thread inside `SpannableStringInternal.setSpan`).
 */
internal class LineState {
    var block = false
    var triple: String? = null
}

/** One line, painted. `state` carries the block comment and triple quote across the call. */
internal fun highlightLine(line: String, hash: Boolean, state: LineState): AnnotatedString =
    buildAnnotatedString {
        fun paint(text: String, color: Color, italic: Boolean = false) {
            if (text.isEmpty()) return
            withStyle(SpanStyle(color = color, fontStyle = if (italic) FontStyle.Italic else null)) {
                append(text)
            }
        }

        val n = line.length
        var i = 0

        while (i < n) {
            val c = line[i]

            val open = state.triple
            if (open != null) {
                val end = line.indexOf(open, i)
                val stop = if (end == -1) n else end + open.length
                paint(line.substring(i, stop), Syn.String)
                if (end != -1) state.triple = null
                i = stop
                continue
            }

            if (state.block) {
                val end = line.indexOf("*/", i)
                val stop = if (end == -1) n else end + 2
                paint(line.substring(i, stop), Syn.Comment, italic = true)
                if (end != -1) state.block = false
                i = stop
                continue
            }

            when {
                c == '/' && i + 1 < n && line[i + 1] == '/' -> {
                    paint(line.substring(i, n), Syn.Comment, italic = true)
                    i = n
                }
                c == '/' && i + 1 < n && line[i + 1] == '*' -> {
                    // The old tokenizer skipped these two characters, so `/*` vanished from the
                    // painted line. They are comment text and are appended as such now.
                    state.block = true
                    paint("/*", Syn.Comment, italic = true)
                    i += 2
                }
                hash && c == '#' -> {
                    paint(line.substring(i, n), Syn.Comment, italic = true)
                    i = n
                }
                c == '"' || c == '\'' || c == '`' -> {
                    val triple = line.startsWith("$c$c$c", i)
                    val quote = if (triple) "$c$c$c" else c.toString()
                    var j = i + quote.length
                    var closed = false
                    while (j < n) {
                        if (!triple && line[j] == '\\') { j += 2; continue }
                        if (line.startsWith(quote, j)) { j += quote.length; closed = true; break }
                        j++
                    }
                    val stop = j.coerceAtMost(n)
                    paint(line.substring(i, stop), Syn.String)
                    if (triple && !closed) state.triple = quote
                    i = stop
                }
                c.isDigit() -> {
                    var j = i
                    while (j < n && (line[j].isLetterOrDigit() || line[j] == '.' || line[j] == '_')) j++
                    paint(line.substring(i, j), Syn.Number)
                    i = j
                }
                c.isLetter() || c == '_' || c == '@' || c == '$' -> {
                    var j = i + 1
                    while (j < n &&
                        (line[j].isLetterOrDigit() || line[j] == '_' || line[j] == '@' || line[j] == '$')
                    ) j++
                    val word = line.substring(i, j)
                    val bare = word.removePrefix("$").removePrefix("@")
                    if (word.startsWith("@")) {
                        paint(word, Syn.Annotation)
                    } else if (bare in KEYWORDS) {
                        paint(word, Syn.Keyword)
                    } else {
                        var k = j
                        while (k < n && line[k] == ' ') k++
                        when {
                            k < n && line[k] == '(' -> paint(word, Syn.Function)
                            bare.length > 1 && bare.all { it.isUpperCase() || it.isDigit() || it == '_' } ->
                                paint(word, Syn.Constant)
                            bare.isNotEmpty() && bare[0].isUpperCase() && bare.length > 1 ->
                                paint(word, Syn.Type)
                            else -> paint(word, Syn.Plain)
                        }
                    }
                    i = j
                }
                c == ' ' || c == '\t' -> {
                    var j = i
                    while (j < n && (line[j] == ' ' || line[j] == '\t')) j++
                    append(line.substring(i, j))
                    i = j
                }
                else -> { paint(c.toString(), Syn.Punct); i++ }
            }
        }
    }

/**
 * The whole block, one `AnnotatedString`. Still the right call for a fenced code block in a
 * message; a **file** goes through [highlightLines] so each line can live in its own list item.
 */
fun highlightCode(code: String, lang: String): AnnotatedString = buildAnnotatedString {
    val hash = lang.lowercase() in HASH_LANGS
    val state = LineState()
    code.split('\n').forEachIndexed { index, line ->
        if (index > 0) append('\n')
        append(highlightLine(line, hash, state))
    }
}

/** Every line painted separately. Used by the file viewer, which renders one item per line. */
internal fun highlightLines(lines: List<String>, lang: String): List<AnnotatedString> {
    val hash = lang.lowercase() in HASH_LANGS
    val state = LineState()
    return lines.map { highlightLine(it, hash, state) }
}
