package com.zapp.net.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.discord.InviteCode
import com.zapp.net.onboarding.Onboarding
import com.zapp.net.ui.theme.Accent
import com.zapp.net.ui.theme.Border
import com.zapp.net.ui.theme.Danger
import com.zapp.net.ui.theme.Surface
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextMid
import com.zapp.net.ui.theme.TextPrimary

/**
 * The Discord block in the settings panel.
 *
 * Two states, because there are two different things a person can be holding:
 *
 *  - nothing saved -> a field for the invite link, and only the *code* is kept
 *  - a code saved  -> the card can be opened, or the code replaced
 *
 * Deliberately not a WebView and not a link. the owner's correction on 2026-11-20: "not a
 * webview, a dialog same as dialog of discord invite, logo, the icons etc." The card itself
 * lives in [DiscordInviteDialog]; this is only the switch that opens it.
 */
@Composable
fun DiscordCardSection() {
    val ctx = LocalContext.current
    var code by remember { mutableStateOf(Onboarding.discordInvite(ctx)) }
    var draft by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var bad by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(12.dp)).padding(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Discord", fontSize = 13.sp, color = TextPrimary)
            if (code.isNotBlank()) {
                Text(
                    code,
                    fontSize = 10.sp, color = TextDim, fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "your server's invite card, drawn natively - no browser, no link to click through",
            fontSize = 10.5.sp, color = TextDim, lineHeight = 15.sp,
        )
        Spacer(Modifier.height(10.dp))

        if (code.isBlank()) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it; bad = "" },
                placeholder = { Text("discord.gg/yourcode", fontSize = 12.sp, color = TextDim) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(9.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Border,
                ),
            )
            if (bad.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(bad, fontSize = 10.5.sp, color = Danger)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    // Accepts a full link, `discord.gg/x`, `discord.com/invite/x` or the bare
                    // code, because making someone trim a URL is how a working flow reads as
                    // broken.
                    val parsed = InviteCode.parse(draft)
                    if (parsed == null) bad = "that does not look like an invite"
                    else {
                        Onboarding.setDiscordInvite(ctx, parsed)
                        code = parsed
                    }
                },
                enabled = draft.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(42.dp),
                shape = RoundedCornerShape(9.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Color.Black,
                    disabledContainerColor = Color(0xFF1B1B21),
                    disabledContentColor = TextDim,
                ),
            ) { Text("Save invite", fontSize = 13.sp) }
        } else {
            Button(
                onClick = { show = true },
                modifier = Modifier.fillMaxWidth().height(42.dp),
                shape = RoundedCornerShape(9.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF5865F2),
                    contentColor = Color.White,
                ),
            ) { Text("Open invite card", fontSize = 13.sp) }
            TextButton(
                onClick = { Onboarding.setDiscordInvite(ctx, ""); code = ""; draft = "" },
            ) { Text("Change invite", fontSize = 11.sp, color = TextMid) }
        }
    }

    if (show && code.isNotBlank()) {
        DiscordInviteDialog(
            inviteCode = code,
            onSkip = { show = false },
            // The dialog never launches anything itself - the link is opened here, by the
            // screen that owns the context, so the card stays testable in isolation.
            onOpen = {
                show = false
                openDiscordInvite(ctx, code)
            },
        )
    }
}
