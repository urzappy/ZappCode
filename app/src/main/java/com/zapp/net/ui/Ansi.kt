package com.zapp.net.ui

/**
 * A small terminal screen: the part of xterm.js that decides what characters land on which line
 * and in which colour.
 *
 * **This is not xterm.js.** Their panel renders with `@xterm/xterm` (`panel-BOR33-HA.js`:
 * `term.write`, `term.textarea`, `proposeDimensions`), a full VT emulator with alternate-screen
 * buffers, scroll regions, mouse reporting and a dozen other modes. Porting it is not a port, it
 * is a rewrite, so this is a deliberately smaller machine and the difference is listed here
 * rather than hidden:
 *
 * | xterm.js does                              | this does                            |
 * |--------------------------------------------|--------------------------------------|
 * | CSI SGR (16/256/truecolour)                | yes                                  |
 * | `\r`, `\n`, `\b`, `\t`                     | yes                                  |
 * | erase-line `K`, erase-display `J`          | yes, 0/1/2 forms                     |
 * | alternate screen (`?1049h/l`)              | not tracked - one buffer             |
 * | scroll regions, insert/delete line         | ignored                              |
 * | mouse reporting, bracketed paste           | ignored                              |
 * | wide-character cells (CJK double width)    | rendered, not width-corrected        |
 *
 * Everything it *does* do is a pure function of the byte stream, which is why it lives apart from
 * the composable and can be tested without a screen.
 */
object Ansi {

    /** One run of characters sharing a style. */
    data class Span(val text: String, val style: Style)

    /** A terminal cell style. Only what SGR can set. */
    data class Style(
        /** 0-15 indexed, `256 + n` for the 256-colour cube, or packed `0xRRGGBB`. */
        val fg: Int? = null,
        val bg: Int? = null,
        val bold: Boolean = false,
        val dim: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val inverse: Boolean = false,
    )

    data class Line(val spans: List<Span>) {
        val text: String get() = spans.joinToString("") { it.text }
    }

    /** One cell: the terminal is a grid, so a line is an array of cells, not a string. */
    private class Cell(var ch: Char, var style: Style)

    /**
     * Render [output] into lines.
     *
     * [maxLines] keeps the tail. A terminal has no left gutter and no wrapping here: a line is
     * however long the process made it, and the view scrolls horizontally, which is what a
     * narrow phone needs.
     */
    fun render(output: String, maxLines: Int = 4000): List<Line> {
        val lines = ArrayList<ArrayList<Cell>>()
        var cur = ArrayList<Cell>()
        lines += cur
        var col = 0
        var style = Style()

        fun put(ch: Char) {
            while (cur.size < col) cur += Cell(' ', Style())
            if (col < cur.size) {
                cur[col] = Cell(ch, style)
            } else {
                cur += Cell(ch, style)
            }
            col++
        }

        var i = 0
        while (i < output.length) {
            val c = output[i]
            when {
                // CSI
                c == '\u001b' && i + 1 < output.length && output[i + 1] == '[' -> {
                    var j = i + 2
                    while (j < output.length && (output[j].isDigit() || output[j] in ";?<>=")) j++
                    if (j >= output.length) { i = output.length; break }
                    val params = output.substring(i + 2, j)
                    when (output[j]) {
                        'm' -> style = sgr(style, params)
                        'K' -> {
                            val mode = params.toIntOrNull() ?: 0
                            when (mode) {
                                0 -> if (col < cur.size) cur.subList(col, cur.size).clear()
                                1 -> for (x in 0..minOf(col, cur.size - 1)) if (x >= 0) cur[x] = Cell(' ', Style())
                                2 -> cur.clear()
                            }
                        }
                        'J' -> {
                            val mode = params.toIntOrNull() ?: 0
                            when (mode) {
                                0 -> if (col < cur.size) cur.subList(col, cur.size).clear()
                                1 -> {
                                    for (l in lines) l.clear()
                                    if (col < cur.size) cur.subList(col, cur.size).clear()
                                }
                                2, 3 -> {
                                    lines.clear(); cur = ArrayList(); lines += cur; col = 0
                                }
                            }
                        }
                        // modes (`h`/`l`) and everything else: nothing here changes rendering
                    }
                    i = j + 1
                }
                // OSC ... BEL or ST
                c == '\u001b' && i + 1 < output.length && output[i + 1] == ']' -> {
                    var j = i + 2
                    while (j < output.length && output[j] != '\u0007' &&
                        !(output[j] == '\u001b' && j + 1 < output.length && output[j + 1] == '\\')
                    ) j++
                    i = when {
                        j >= output.length -> output.length
                        output[j] == '\u0007' -> j + 1
                        else -> j + 2
                    }
                }
                c == '\u001b' -> i += 2
                c == '\r' -> { col = 0; i++ }
                c == '\n' -> { cur = ArrayList(); lines += cur; col = 0; i++ }
                c == '\b' -> { if (col > 0) col--; i++ }
                c == '\t' -> {
                    val to = ((col / 8) + 1) * 8
                    while (col < to) put(' ')
                    i++
                }
                c.code < 0x20 || c.code == 0x7F -> i++
                else -> { put(c); i++ }
            }
        }

        val all = lines.map { l ->
            val spans = ArrayList<Span>()
            val sb = StringBuilder()
            var st: Style? = null
            for (cell in l) {
                if (st == null || cell.style != st) {
                    if (sb.isNotEmpty()) spans += Span(sb.toString(), st ?: Style())
                    sb.setLength(0)
                    st = cell.style
                }
                sb.append(cell.ch)
            }
            if (sb.isNotEmpty()) spans += Span(sb.toString(), st ?: Style())
            Line(spans)
        }
        return if (all.size > maxLines) all.subList(all.size - maxLines, all.size) else all
    }

    /**
     * `SGR`. Indexed colours are 0-15; the 256 cube is `256 + n`; truecolour is packed RGB, so
     * the palette stays theirs rather than being reduced to sixteen approximations.
     */
    fun sgr(start: Style, params: String): Style {
        if (params.isEmpty()) return Style()
        var s = start
        val parts = params.split(';').map { it.toIntOrNull() ?: 0 }
        var k = 0
        while (k < parts.size) {
            when (val p = parts[k]) {
                0 -> s = Style()
                1 -> s = s.copy(bold = true)
                2 -> s = s.copy(dim = true)
                3 -> s = s.copy(italic = true)
                4 -> s = s.copy(underline = true)
                7 -> s = s.copy(inverse = true)
                22 -> s = s.copy(bold = false, dim = false)
                23 -> s = s.copy(italic = false)
                24 -> s = s.copy(underline = false)
                27 -> s = s.copy(inverse = false)
                in 30..37 -> s = s.copy(fg = p - 30)
                39 -> s = s.copy(fg = null)
                in 40..47 -> s = s.copy(bg = p - 40)
                49 -> s = s.copy(bg = null)
                in 90..97 -> s = s.copy(fg = p - 90 + 8)
                in 100..107 -> s = s.copy(bg = p - 100 + 8)
                38, 48 -> {
                    val isFg = p == 38
                    when {
                        k + 2 < parts.size && parts[k + 1] == 5 -> {
                            val idx = parts[k + 2]
                            s = if (isFg) s.copy(fg = 256 + idx) else s.copy(bg = 256 + idx)
                            k += 2
                        }
                        k + 4 < parts.size && parts[k + 1] == 2 -> {
                            val rgb = (parts[k + 2] shl 16) or (parts[k + 3] shl 8) or parts[k + 4]
                            s = if (isFg) s.copy(fg = rgb) else s.copy(bg = rgb)
                            k += 4
                        }
                    }
                }
            }
            k++
        }
        return s
    }
}
