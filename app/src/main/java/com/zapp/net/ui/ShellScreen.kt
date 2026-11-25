package com.zapp.net.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.shizuku.Shell
import com.zapp.net.ui.theme.Accent
import com.zapp.net.ui.theme.Bg
import com.zapp.net.ui.theme.Border
import com.zapp.net.ui.theme.DiffAdd
import com.zapp.net.ui.theme.DiffDel
import com.zapp.net.ui.theme.Ink
import com.zapp.net.ui.theme.Surface2
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextMid
import com.zapp.net.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/**
 * The hands panel.
 *
 * Shizuku gives this process the same rights `adb shell` has, so everything here is the
 * real thing: `pm install`, `input tap`, `screencap`, `dumpsys`. Not a mock, not a WebView.
 *
 * Design is ours - the same card/hairline shape as the Guard tab - the capability is the
 * server's.
 */
@Composable
fun ShellScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var alive by remember { mutableStateOf(Shell.alive()) }
    var granted by remember { mutableStateOf(Shell.granted()) }
    var cmd by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    // The binder can arrive late (server starts after us) or die on reboot. Poll it instead
    // of trusting the value we read on first composition.
    LaunchedEffect(Unit) {
        while (true) {
            alive = Shell.alive()
            granted = Shell.granted()
            kotlinx.coroutines.delay(2_000)
        }
    }

    fun run(text: String) {
        val c = text.trim()
        if (c.isEmpty() || busy) return
        busy = true
        log.add("\$ $c")
        scope.launch {
            val out = Shell.exec(ctx, c)
            when {
                out == null -> log.add(if (!alive) "no binder - server not running" else "no permission - tap Grant")
                out.text.isBlank() -> log.add("rc=${out.code} (no output)")
                else -> log.add(out.text.trimEnd() + if (out.code != 0) "\nrc=${out.code}" else "")
            }
            busy = false
            listState.animateScrollToItem((log.size - 1).coerceAtLeast(0))
        }
    }

    // The title was drawn from y=18 dp, i.e. underneath the status bar. Chat, Settings and the
    // Shell list all clear it; this screen and the pty view were the two that did not.
    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
        Column(Modifier.fillMaxWidth().padding(20.dp, 18.dp, 20.dp, 0.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    OcIcons.Terminal,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.width(22.dp).height(22.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text("Shell", color = TextPrimary, fontSize = 22.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "uid shell over Shizuku - the same rights adb has",
                color = TextDim, fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))

            // ---- status ----
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface2)
                    .border(1.dp, Border, RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                StatusRow("server", if (alive) "running" else "not running", alive)
                Spacer(Modifier.height(6.dp))
                StatusRow("permission", if (granted) "granted" else "not granted", granted)
                if (!alive) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Start it from a PC once:",
                        color = TextMid, fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        Shell.startCommand(ctx),
                        color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    )
                } else if (!granted) {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Accent)
                            .clickable { Shell.request { granted = it } }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Grant permission", color = Ink, fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---- quick actions ----
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(QUICK) { q ->
                    Box(
                        Modifier.clip(RoundedCornerShape(9.dp))
                            .background(Surface2)
                            .border(1.dp, Border, RoundedCornerShape(9.dp))
                            .clickable { run(q.second) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(q.first, color = TextMid, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- composer ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Surface2)
                        .border(1.dp, Border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (cmd.isEmpty()) {
                        Text("pm list packages | head", color = TextDim, fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                    BasicTextField(
                        value = cmd,
                        onValueChange = { cmd = it },
                        textStyle = TextStyle(
                            color = TextPrimary, fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onGo = { run(cmd) },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (busy || !granted) Border else Accent)
                        .clickable(enabled = !busy && granted) { run(cmd) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(if (busy) "..." else "Run", color = Ink, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
        }

        // ---- output ----
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
            if (log.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("nothing run yet", color = TextDim, fontSize = 12.sp)
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 20.dp),
                ) {
                    items(log) { line ->
                        Text(
                            line,
                            color = if (line.startsWith("\$ ")) Accent else TextPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
            }
        }

        // ---- clear ----
        Row(
            Modifier.fillMaxWidth().padding(20.dp, 4.dp, 20.dp, 10.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                "clear",
                color = TextDim, fontSize = 12.sp,
                modifier = Modifier.clickable { log.clear() }.padding(6.dp),
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(6.dp).height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (ok) DiffAdd else DiffDel),
        )
        Spacer(Modifier.width(9.dp))
        Text(label, color = TextMid, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = if (ok) TextPrimary else TextDim, fontSize = 12.sp)
    }
}

/** Small, useful, and each one proves a different part of the capability. */
private val QUICK = listOf(
    "uid" to "id",
    "battery" to "dumpsys battery | grep -E 'level|temperature'",
    "thermal" to "dumpsys thermalservice | head -20",
    "foreground" to "dumpsys activity activities | grep -m1 topResumedActivity",
    "screenshot" to "screencap -p /sdcard/z_$(date +%H%M%S).png && ls -l /sdcard/z_*.png | tail -1",
    "zapp logs" to "logcat -d -v time -s 'ZAPP:*' | tail -30",
    "storage" to "df -h /data | tail -1",
    "packages" to "pm list packages -3 | wc -l",
)
