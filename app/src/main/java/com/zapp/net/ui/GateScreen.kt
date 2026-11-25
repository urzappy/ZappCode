package com.zapp.net.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.ui.theme.Accent
import com.zapp.net.ui.theme.Bg
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextMid
import com.zapp.net.ui.theme.TextPrimary

/**
 * The frame shown while the app is deciding whether it can go straight to the chat.
 *
 * the owner's question was whether the loading screen is cosmetic. It was worse than cosmetic:
 * it did not exist, and what he saw was the **login form** flashing on every cold start
 * because `connected` begins `false`. This frame replaces that flash, and it is only ever
 * on screen while a real reconnect is in flight - if nothing is saved, it never appears at
 * all.
 */
@Composable
fun BootFrame(detail: String = "checking your saved login") {
    Column(
        modifier = Modifier.fillMaxSize().background(Bg).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ZAPP-Net", fontSize = 30.sp, color = Accent)
        Spacer(Modifier.height(26.dp))
        PulseBar()
        Spacer(Modifier.height(18.dp))
        Text(detail, fontSize = 12.sp, color = TextDim)
    }
}

/** A single travelling segment. Cheaper than a spinner and it reads as "work is happening". */
@Composable
private fun PulseBar() {
    val t = rememberInfiniteTransition(label = "pulse")
    val x by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_100),
            repeatMode = RepeatMode.Restart,
        ),
        label = "x",
    )
    Box(Modifier.fillMaxWidth(0.6f).height(2.dp).background(Line, RoundedCornerShape(1.dp))) {
        Box(
            Modifier
                .fillMaxWidth(0.34f)
                .height(2.dp)
                .padding(start = (x * 200).dp)
                .background(Accent, RoundedCornerShape(1.dp)),
        )
    }
}

/**
 * The server answered and refused this device.
 *
 * This is the only gate state that means "access was taken away", so it is the only one
 * that offers nothing but a way back in. No chat, no session list, no tunnel controls.
 */
@Composable
fun DeniedFrame(onJoinAgain: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Bg).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Access ended", fontSize = 24.sp, color = TextPrimary)
        Spacer(Modifier.height(10.dp))
        Text(
            "This device is no longer allowed on the server. Your sessions are still there; " +
                "they open again as soon as access is restored.",
            fontSize = 13.sp, color = TextMid, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(22.dp))
        Text(
            "server answered 401 · credential refused",
            fontSize = 11.sp, color = TextDim, fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onJoinAgain,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent,
                contentColor = Color.Black,
            ),
        ) { Text("Join again", fontSize = 15.sp) }
    }
}

/**
 * No verdict was possible. Deliberately separate from [DeniedFrame]: a lost route is not a
 * revocation, and wiping a working credential because the carrier blinked would lock the owner
 * out of his own phone.
 */
@Composable
fun UnreachableFrame(detail: String, onRetry: () -> Unit, onJoinAgain: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Bg).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Server unreachable", fontSize = 24.sp, color = TextPrimary)
        Spacer(Modifier.height(10.dp))
        Text(
            "ZAPP could not reach the server, so it cannot confirm your access yet. " +
                "Nothing was deleted.",
            fontSize = 13.sp, color = TextMid, lineHeight = 20.sp,
        )
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(
                detail,
                fontSize = 10.sp, color = TextDim, fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp,
            )
        }
        Spacer(Modifier.height(26.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent,
                contentColor = Color.Black,
            ),
        ) { Text("Try again", fontSize = 15.sp) }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onJoinAgain,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMid),
        ) { Text("Join again", fontSize = 15.sp) }
    }
}
