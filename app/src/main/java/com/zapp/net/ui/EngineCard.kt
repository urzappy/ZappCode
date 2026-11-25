package com.zapp.net.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.MainViewModel
import com.zapp.net.termux.TermuxSetup
import com.zapp.net.ui.theme.Accent
import com.zapp.net.ui.theme.Danger
import com.zapp.net.ui.theme.Layer1
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextPrimary
import kotlinx.coroutines.delay

/**
 * The engine's own state, on the connect screen.
 *
 * the owner, 23 Nov: "build a indicator bout this login". The first connect on a cold phone is
 * 40+ seconds of real work - measured the same day: relay 17:55:03, `zapp_lane` 17:55:13,
 * `opencode serve` 17:55:45 - and the only feedback was a 12 sp grey sentence at the bottom,
 * which reads exactly like a hang.
 *
 * Every number drawn here is measured. [MainViewModel.EngineWait] is filled from
 * `$HOME/.zapp/status` (written by `serve.sh` at each boundary it actually reaches) plus
 * elapsed wall clock, and the bar is determinate **only** when the phone reported a real total.
 * Nothing here interpolates a percentage to look busy.
 */
@Composable
fun EngineCard(vm: MainViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val wait by vm.engineWait.collectAsState()
    // A socket connect on loopback, off the main thread. This is the one honest definition of
    // "up": the port answered. The status file cannot know it, and the relay's return value
    // only means Termux accepted the handshake.
    var up by remember { mutableStateOf(false) }
    // Re-read with the port: the user is sent to Setup and comes back to this same screen, so a
    // value captured once at composition would keep saying "Termux missing" after they installed
    // it. Both reads are cheap package-manager/permission calls, not IO.
    var installed by remember { mutableStateOf(TermuxSetup.installed(ctx)) }
    var permitted by remember { mutableStateOf(installed && TermuxSetup.granted(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            installed = TermuxSetup.installed(ctx)
            permitted = installed && TermuxSetup.granted(ctx)
            up = permitted && TermuxSetup.engineListeningNow()
            delay(2_000)
        }
    }

    val color = when {
        !installed || !permitted -> Danger
        up -> Accent
        wait.active || wait.id.isNotBlank() -> Accent
        else -> TextDim
    }
    val title = when {
        !installed -> "Termux missing"
        !permitted -> "Permission needed"
        up -> "Engine ready"
        wait.active || wait.id.isNotBlank() -> "Engine starting"
        else -> "Engine not running"
    }
    val detail = when {
        !installed -> "Install Termux from Setup before connecting"
        !permitted -> "Allow the RunCommand permission in Setup"
        up -> "Answering on 127.0.0.1:4096"
        wait.active -> wait.label.ifBlank { "Waking Termux" }
        else -> "Tap Connect - this app starts it for you"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Layer1, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(6.dp))
                Text("4096", fontSize = 10.sp, color = TextDim)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                if (wait.active && wait.seconds > 0) "$detail  ${wait.seconds}s" else detail,
                fontSize = 11.sp,
                color = if (color == Danger) Danger else TextDim,
            )
            // Determinate only with a real denominator; otherwise a bounded pulse, never a
            // fake percentage.
            val f = wait.fraction
            if (f != null && wait.active) {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.fillMaxWidth().height(2.dp)
                        .background(Line, RoundedCornerShape(1.dp)),
                ) {
                    Box(
                        Modifier.fillMaxWidth(f.coerceIn(0.02f, 1f)).height(2.dp)
                            .background(Accent, RoundedCornerShape(1.dp)),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${mb(wait.bytes)} MB of ${mb(wait.total)} MB",
                    fontSize = 10.sp,
                    color = TextDim,
                )
            }
        }
    }
}

private fun mb(b: Long) = String.format(java.util.Locale.US, "%.1f", b / 1048576.0)
