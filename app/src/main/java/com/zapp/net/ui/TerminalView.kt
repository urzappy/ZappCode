package com.zapp.net.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.api.PtySession
import com.zapp.net.ui.theme.Bg
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.Surface2
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextMid
import com.zapp.net.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The terminal tab, driven by [PtySession].
 *
 * The renderer is [Ansi] - a smaller machine than the `@xterm/xterm` their panel uses, and the
 * differences are listed on that object. What is theirs, exactly: the protocol (the 5-byte input
 * frame, the connect URL, the event names), the 16-colour palette below (xterm's own dark theme),
 * and the numbered `Terminal N` title.
 *
 * A phone has no Ctrl, so the key strip carries the four chords a terminal actually needs and the
 * arrow keys, because a TUI without arrows is unusable.
 */
@Composable
fun TerminalView(session: PtySession, modifier: Modifier = Modifier) {
    val st by session.state.collectAsState()
    val list = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    // Paint off the main thread: `Ansi.render` walks the whole buffer, and the buffer is bounded
    // at 400 000 characters.
    val lines by produceState(emptyList<Ansi.Line>(), st.output) {
        value = withContext(Dispatchers.Default) { Ansi.render(st.output) }
    }

    // Follow the tail the way a terminal does - but only when the user is already at the bottom,
    // so scrolling back to read does not get yanked away by new output.
    LaunchedEffect(lines.size) {
        val info = list.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lines.isNotEmpty() && lastVisible >= lines.size - 2) {
            list.scrollToItem(lines.lastIndex)
        }
    }

    Column(modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (lines.isEmpty() || lines.all { it.text.isEmpty() }) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Text(
                        when (st.status) {
                            PtySession.State.Status.Connecting -> "Connecting\u2026"
                            PtySession.State.Status.Reconnecting ->
                                "Reconnecting\u2026 (attempt ${st.attempt})"
                            PtySession.State.Status.Failed ->
                                st.error ?: "Connection failed"
                            PtySession.State.Status.Exited ->
                                "Process exited" + (st.exitCode?.let { " ($it)" } ?: "")
                            else -> "Terminal ready"
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextDim,
                    )
                }
            } else {
                val measurer = rememberTextMeasurer()
                val density = LocalDensity.current
                val termStyle = remember {
                    TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace)
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val viewportPx = with(density) { maxWidth.toPx() }
                    val contentPx = remember(lines) {
                        val longest = lines.maxByOrNull { it.text.length }
                        val w = if (longest == null) 0f else
                            measurer.measure(longest.text, termStyle).size.width.toFloat()
                        w + with(density) { 16.dp.toPx() }
                    }
                    // One horizontal owner for the whole terminal: a per-row scroller stole the
                    // vertical drag on whatever line his finger drifted across.
                    val scroll = rememberCodeScroll(contentPx, viewportPx)
                    LazyColumn(
                        state = list,
                        modifier = Modifier.fillMaxSize().codeHorizontalDrag(scroll),
                    ) {
                        itemsIndexed(lines) { _, line ->
                            Box(Modifier.fillMaxWidth().clipToBounds()) {
                                Text(
                                    annotated(line),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    softWrap = false,
                                    color = TextPrimary,
                                    modifier = Modifier
                                        .wrapContentWidth(Alignment.Start, unbounded = true)
                                        .padding(horizontal = 8.dp)
                                        .then(scroll.row),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (st.status == PtySession.State.Status.Exited) {
            Row(
                Modifier.fillMaxWidth().background(Surface2).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Process exited" + (st.exitCode?.let { " ($it)" } ?: ""),
                    fontSize = 11.sp,
                    color = TextMid,
                )
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))

        // Key strip. Their terminal gets these from a physical keyboard; a phone needs them as
        // buttons, and they are the only input affordance added over the desktop.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Key("Esc") { session.write(byteArrayOf(0x1b)) }
            Key("Tab") { session.write(byteArrayOf(0x09)) }
            Key("^C") { session.write(byteArrayOf(0x03)) }
            Key("^D") { session.write(byteArrayOf(0x04)) }
            Key("\u2190") { session.write("\u001b[D".toByteArray()) }
            Key("\u2191") { session.write("\u001b[A".toByteArray()) }
            Key("\u2193") { session.write("\u001b[B".toByteArray()) }
            Key("\u2192") { session.write("\u001b[C".toByteArray()) }
            Spacer(Modifier.weight(1f))
            Text(
                "${st.cols}x${st.rows}",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = TextDim,
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Surface2)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextDim)
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f)) {
                if (input.isEmpty()) {
                    Text("send a command", fontSize = 11.sp, color = TextDim)
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextPrimary,
                    ),
                    cursorBrush = SolidColor(TextPrimary),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = input.isNotEmpty()) {
                        session.write((input + "\r").toByteArray())
                        input = ""
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("\u21b5", fontSize = 12.sp, color = if (input.isNotEmpty()) TextPrimary else TextDim)
            }
        }
    }
}

@Composable
private fun Key(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(5.dp))
            .clickable { onClick() }
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = TextMid,
        )
    }
}

/** Their default foreground/background plus xterm's 16-colour dark palette. */
private val PALETTE = intArrayOf(
    0x000000, 0xcd3131, 0x0dbc79, 0xe5e510,
    0x2472c8, 0xbc3fbc, 0x11a8cd, 0xe5e5e5,
    0x666666, 0xf14c4c, 0x23d18b, 0xf5f543,
    0x3b8eea, 0xd670d6, 0x29b8db, 0xe5e5e5,
)

private fun indexedColor(n: Int): Color {
    if (n < 16) return Color(0xFF000000 or PALETTE[n].toLong())
    if (n < 256) {
        // xterm's 256-colour cube: 16 + 36r + 6g + b for 16..231, then a grey ramp.
        val i = n - 256
        if (i < 216) {
            val r = i / 36
            val g = (i % 36) / 6
            val b = i % 6
            fun c(v: Int) = if (v == 0) 0 else 55 + v * 40
            return Color(0xFF000000 or ((c(r).toLong() shl 16) or (c(g).toLong() shl 8) or c(b).toLong()))
        }
        val grey = 8 + (i - 216) * 10
        return Color(0xFF000000 or ((grey.toLong() shl 16) or (grey.toLong() shl 8) or grey.toLong()))
    }
    return Color(0xFF000000 or (n.toLong() and 0xFFFFFF))
}

private fun annotated(line: Ansi.Line): AnnotatedString = buildAnnotatedString {
    for (span in line.spans) {
        val st = span.style
        val fg = st.fg?.let { indexedColor(it) } ?: TextPrimary
        val bg = st.bg?.let { indexedColor(it) }
        val weight = if (st.bold) FontWeight.Bold else FontWeight.Normal
        val effectiveFg = if (st.inverse) bg ?: Bg else fg
        val effectiveBg = if (st.inverse) fg else bg
        pushStyle(
            SpanStyle(
                color = if (st.dim) effectiveFg.copy(alpha = 0.6f) else effectiveFg,
                background = effectiveBg ?: Color.Transparent,
                fontWeight = weight,
                textDecoration = if (st.underline) TextDecoration.Underline else null,
            )
        )
        append(span.text)
        pop()
    }
}
