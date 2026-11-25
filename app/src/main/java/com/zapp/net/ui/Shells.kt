package com.zapp.net.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.api.Part
import com.zapp.net.api.ShellEntry
import com.zapp.net.ui.theme.Accent
import com.zapp.net.ui.theme.Bg
import com.zapp.net.ui.theme.DiffAdd
import com.zapp.net.ui.theme.DiffDel
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.Surface2
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextMid
import com.zapp.net.ui.theme.TextPrimary
import kotlinx.coroutines.delay

/* Their background-task card, for real: every entry here comes from GET /api/shell
   (verified live — POST creates, /output tails, DELETE kills). */

/** The `1 shell` pill they render under a turn. */
@Composable
fun ShellChip(count: Int, running: Boolean, onClick: () -> Unit, label: String? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShellDots(if (running) TextMid else TextDim)
        Spacer(Modifier.width(7.dp))
        Text(
            label ?: when {
                count == 0 -> "shell"
                count == 1 -> "1 shell"
                else -> "$count shells"
            },
            fontSize = 11.sp,
            color = if (label != null || count > 0) TextPrimary else TextDim,
            fontFamily = if (label != null) FontFamily.Default else FontFamily.Monospace,
        )
    }
}

/** The detached turn: the status the timeline stopped showing, parked above the composer.
    Tap opens the background list, the cross stops the turn. */
@Composable
fun DetachedChip(
    label: String,
    thinking: Boolean,
    started: Long,
    onOpen: () -> Unit,
    onStop: () -> Unit,
) {
    val breathe = rememberInfiniteTransition(label = "detached")
    val dotAlpha by breathe.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "dotAlpha",
    )
    val secs by produceState(0L, started) {
        while (true) {
            value = if (started > 0L) (System.currentTimeMillis() - started) / 1000L else 0L
            delay(500)
        }
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Surface2)
            .clickable { onOpen() }
            .padding(start = 9.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .graphicsLayer { alpha = dotAlpha }
                .background(Accent, CircleShape),
        )
        Spacer(Modifier.width(7.dp))
        Text(label, fontSize = 11.sp, color = TextPrimary, maxLines = 1)
        if (thinking) {
            Spacer(Modifier.width(5.dp))
            Text("${secs}s", fontSize = 10.sp, color = TextDim)
        }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).clickable { onStop() },
            contentAlignment = Alignment.Center,
        ) { Text("\u2715", fontSize = 10.sp, color = TextDim) }
    }
}

/** The pill's glyph, traced from the desktop app at 8x: a 4x4 dot matrix, dim,
    sitting immediately left of the mono count. */
@Composable
private fun ShellDots(color: androidx.compose.ui.graphics.Color, sizeDp: Int = 12) {
    Canvas(Modifier.size(sizeDp.dp)) {
        val n = 4
        val dot = this.size.width * 0.15f
        val step = (this.size.width - dot) / (n - 1)
        for (r in 0 until n) {
            for (c in 0 until n) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(c * step, r * step),
                    size = Size(dot, dot),
                    cornerRadius = CornerRadius(dot * 0.18f),
                )
            }
        }
    }
}

@Composable
fun ShellsPanel(
    shells: List<ShellEntry>,
    visible: Boolean,
    onKill: (String) -> Unit,
    output: suspend (String) -> String,
    onClose: () -> Unit,
    live: List<Part> = emptyList(),
    onRun: (String) -> Unit = {},
    /**
     * Their `Move to background` for work the agent itself started.
     *
     * `POST /api/session/:id/shell {id?, command}` (verified live: 204) takes the command out
     * of the message's inline tool card and makes it a real `/api/shell` entry, still attached
     * to the turn. Desktop puts this on the running tool card, so a five-minute command can be
     * let go without stopping the turn - which is the only way to keep working while it runs.
     */
    onDetach: (command: String, partId: String?) -> Unit = { _, _ -> },
    /** False inside the side pane, where the tab strip already carries the title and the close. */
    chrome: Boolean = true,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) + slideInVertically(tween(170)) { it / 12 },
        exit = fadeOut(tween(100)) + slideOutVertically(tween(120)) { it / 14 },
    ) {
        Column(
            Modifier.fillMaxSize().background(Bg)
                .then(if (chrome) Modifier.statusBarsPadding() else Modifier),
        ) {
            if (chrome) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(ActivitySummary.SECTION_SHELLS, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(7.dp))
                    Text("${shells.size + live.size}", fontSize = 10.5.sp, color = TextDim)
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).clickable { onClose() },
                        contentAlignment = Alignment.Center,
                    ) { Text("\u2715", fontSize = 13.sp, color = TextDim) }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            }

            // Their `run in background`: POST /api/shell — no session, no message, just a command.
            var cmd by remember { mutableStateOf("") }
            Row(
                Modifier.fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 7.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Surface2)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("$", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextDim)
                Spacer(Modifier.width(7.dp))
                Box(Modifier.weight(1f)) {
                    if (cmd.isEmpty()) {
                        Text("run in background…", fontSize = 11.sp, color = TextDim)
                    }
                    BasicTextField(
                        value = cmd,
                        onValueChange = { cmd = it },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TextPrimary,
                        ),
                        cursorBrush = SolidColor(Accent),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(26.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (cmd.isNotBlank()) Accent.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable(enabled = cmd.isNotBlank()) {
                            onRun(cmd.trim())
                            cmd = ""
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("\u2191", fontSize = 13.sp, color = if (cmd.isNotBlank()) Accent else TextDim)
                }
            }

            if (shells.isEmpty() && live.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No background shells", fontSize = 12.5.sp, color = TextMid)
                        Spacer(Modifier.height(4.dp))
                        Text("Commands the agent runs in the background show up here", fontSize = 10.5.sp, color = TextDim)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // shells the agent itself is running inside the turn: same list the
                    // desktop counts, and they have no /api/shell id to kill by id
                    items(live, key = { "t:" + (it.id ?: it.hashCode().toString()) }) { p ->
                        LiveToolCard(p, onDetach)
                    }
                    items(shells, key = { it.id }) { s ->
                        ShellCard(s, output, onKill)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShellCard(s: ShellEntry, output: suspend (String) -> String, onKill: (String) -> Unit) {
    var open by remember(s.id) { mutableStateOf(false) }
    // `session.activity.shell.stopping` - theirs prints "Stopping." between the tap and the
    // exit event, so the card does not look like it ignored him.
    var stopping by remember(s.id, s.status) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .clickable { open = !open }
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (s.running) DiffAdd else TextDim, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                s.command.orEmpty().replace("\n", " "),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                // Their card names only the end state: "Exited", "Exited with code 1",
                // "Timed out", "Stopped". Their dictionary has no "running" word (the dot
                // carries that) and no "done" - both were ours.
                if (stopping) ActivitySummary.STOPPING
                else ActivitySummary.shellStatus(s.running, s.exitCode, s.status),
                fontSize = 9.5.sp,
                color = when {
                    s.running -> DiffAdd
                    // Their `data-failed`: a timeout, or an exit that is not zero. `!!e().exit`
                    // means exit 0 is a clean run and stays in the normal colour.
                    ActivitySummary.failed(s.status, s.exitCode) -> DiffDel
                    else -> TextDim
                },
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.id, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextDim, maxLines = 1)
            Spacer(Modifier.weight(1f))
            // their card carries time.started/completed — we show it as a running clock
            s.time?.started?.let { started ->
                val end = s.time?.completed ?: System.currentTimeMillis()
                val secs = ((end - started) / 1000).coerceAtLeast(0)
                Text("${secs}s", fontSize = 9.sp, color = TextDim)
                Spacer(Modifier.width(8.dp))
            }
            s.pid?.let { Text("pid $it", fontSize = 9.sp, color = TextDim) }
        }
        if (open) {
            val text by produceState("", s.id, open) {
                if (open) {
                    value = output(s.id)
                    // running shells keep tailing through the cursor; a finished one is read once
                    repeat(if (s.running) 300 else 20) {
                        delay(700)
                        if (!open) return@produceState
                        value = output(s.id)
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .padding(top = 7.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    // Their tail, verbatim: CRLF folded to LF, trailing whitespace trimmed,
                    // `slice(-14)` - the last 14 lines only (`route.js` 649 100), auto-scrolled
                    // to the bottom on every change. This is a preview, not a scrollback.
                    ActivitySummary.tail(text).ifBlank { ActivitySummary.NO_OUTPUT },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.5.sp,
                    lineHeight = 13.sp,
                    color = TextMid,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                Text(
                    // `session.activity.shell.stop` = "Stop". "Kill" was ours.
                    ActivitySummary.STOP,
                    fontSize = 10.5.sp,
                    color = DiffDel,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            stopping = true
                            onKill(s.id)
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** A shell the agent is running inside the live turn: command + its output so far. */
@Composable
private fun LiveToolCard(p: Part, onDetach: (command: String, partId: String?) -> Unit = { _, _ -> }) {
    var open by remember(p.id) { mutableStateOf(false) }
    val command = p.state?.inputString("command").orEmpty()
    val out = p.state?.content?.joinToString("\n") { it.text.orEmpty() }.orEmpty()
    val done = p.state?.status?.lowercase()?.let { it != "running" && it != "pending" && it != "started" } == true
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .clickable { open = !open }
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (done) TextDim else DiffAdd, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                command.ifBlank { p.state?.title.orEmpty().ifBlank { "shell" } }.replace("\n", " "),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                // `session.activity.shell.exited` = "Exited". "done" was ours.
                if (done) ActivitySummary.EXITED else "",
                fontSize = 9.5.sp,
                color = TextDim,
            )
        }
        if (!done && command.isNotBlank()) {
            // Their `session.background.moveRunning` on the running tool card. Tapping it does
            // NOT stop the command - it hands it to `/api/shell`, so the turn stops being
            // pinned to it and the work keeps running in the background panel.
            Spacer(Modifier.height(6.dp))
            Text(
                "Move to background",
                fontSize = 10.5.sp,
                color = Accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onDetach(command, p.id) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        if (open) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .padding(top = 7.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    ActivitySummary.tail(out).ifBlank { ActivitySummary.NO_OUTPUT },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.5.sp,
                    lineHeight = 13.sp,
                    color = TextMid,
                )
            }
        }
    }
}
