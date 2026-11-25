package com.zapp.net.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zapp.net.discord.DiscordApi
import com.zapp.net.discord.DiscordInvite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The two doors out of setup, both drawn natively.
 *
 * the owner, 2026-11-20: "remove mo muna yung discord leave and join. lagay mo nalang yung link
 * natin sa discord dialog and icon nong mismong discord official and profile kasama yung
 * link natin lagyan mo ng optional skip tapos LAUNCH OPENCODE dialog tappable".
 *
 * So there is no membership gate here at all. No join check, no leave check, no token, no
 * bot. The Discord card is an invitation and nothing else:
 *
 *     [ Discord mark ]
 *     ZAPP OPENCODE CP  ✓
 *     @zapp  ·  opencode cp
 *     ┌──────────────────────────────┐
 *     │ discord.gg/RQ6NVspT2u        │
 *     └──────────────────────────────┘
 *     [        Open Discord        ]
 *              Skip
 *
 * and after it, one tappable card:
 *
 *     ┌──────────────────────────────┐
 *     │  LAUNCH OPENCODE          →  │
 *     └──────────────────────────────┘
 *
 * Colours are Discord's own because the point is recognition - the user has seen this
 * surface a thousand times and should not have to read it to know what it is.
 */
private val DiscordCard = Color(0xFF313338)
private val DiscordPanel = Color(0xFF2B2D31)
private val DiscordText = Color(0xFFF2F3F5)
private val DiscordMuted = Color(0xFFB5BAC1)
private val DiscordBlurple = Color(0xFF5865F2)
private val DiscordGreen = Color(0xFF23A55A)
private val DiscordDanger = Color(0xFFF23F43)

/**
 * Discord's own mark, from their brand kit, viewBox `0 0 127.14 96.36`.
 *
 * Parsed with `PathParser` rather than shipped as a PNG: one vector stays sharp at every
 * density, adds no drawable per bucket, and cannot be blurry on this 480 dpi panel.
 */
private const val DISCORD_MARK =
    "M107.7,8.07A105.15,105.15,0,0,0,81.47,0a72.06,72.06,0,0,0-3.36,6.83A97.68,97.68,0,0," +
        "0,49,6.83,72.37,72.37,0,0,0,45.64,0,105.89,105.89,0,0,0,19.39,8.09C2.79,32.65-1.71," +
        "56.6.54,80.21h0A105.73,105.73,0,0,0,32.71,96.36,77.7,77.7,0,0,0,39.6,85.25a68.42," +
        "68.42,0,0,1-10.85-5.18c.91-.66,1.8-1.34,2.66-2a75.57,75.57,0,0,0,64.32,0c.87.71," +
        "1.76,1.39,2.66,2a68.68,68.68,0,0,1-10.87,5.19,77,77,0,0,0,6.89,11.1A105.25,105.25," +
        "0,0,0,126.6,80.22h0C129.24,52.84,122.09,29.11,107.7,8.07ZM42.45,65.69C36.18,65.69," +
        "31,60,31,53s5-12.74,11.43-12.74S54,46,53.89,53,48.84,65.69,42.45,65.69Zm42.24,0C78." +
        "41,65.69,73.25,60,73.25,53s5-12.74,11.44-12.74S96.23,46,96.12,53,91.08,65.69,84.69," +
        "65.69Z"

private const val MARK_W = 127.14f
private const val MARK_H = 96.36f

/**
 * The official Discord mark, in whatever colour and size the caller wants.
 *
 * Nothing here is decoration: a "join our Discord" prompt that does not carry Discord's real
 * logo reads as phishing, and the whole point of using their chrome is that it does not.
 */
@Composable
fun DiscordMark(
    modifier: Modifier = Modifier,
    color: Color = DiscordBlurple,
) {
    val path = remember { PathParser().parsePathString(DISCORD_MARK).toPath() }
    Canvas(modifier) {
        val s = minOf(size.width / MARK_W, size.height / MARK_H)
        scale(s, s, pivot = Offset.Zero) {
            drawPath(path, color)
        }
    }
}

/**
 * The invite card. Optional by construction - `Skip` is always on screen, and nothing about
 * the rest of the app depends on this having been seen.
 *
 * [onOpen] fires when they tap `Open Discord`; the caller decides whether that means a deep
 * link, a browser, or nothing at all. The dialog never launches anything by itself.
 */
@Composable
fun DiscordInviteDialog(
    inviteCode: String,
    onSkip: () -> Unit,
    onOpen: (DiscordInvite?) -> Unit,
) {
    var invite by remember(inviteCode) { mutableStateOf<DiscordInvite?>(null) }
    var icon by remember(inviteCode) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(inviteCode) { mutableStateOf("") }
    var loading by remember(inviteCode) { mutableStateOf(true) }

    LaunchedEffect(inviteCode) {
        loading = true
        when (val res = withContext(Dispatchers.IO) { DiscordApi.fetch(inviteCode) }) {
            is DiscordApi.Result.Ok -> {
                invite = res.invite
                val url = res.invite.iconUrl
                if (url != null) {
                    icon = withContext(Dispatchers.IO) {
                        DiscordApi.iconBytes(url)?.let { bytes ->
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        }
                    }
                }
                loading = false
            }
            is DiscordApi.Result.Err -> {
                error = res.reason
                loading = false
            }
        }
    }

    val ctx = LocalContext.current
    val name = invite?.guildName?.takeIf { it.isNotBlank() } ?: "ZAPP OPENCODE CP"
    val link = "discord.gg/$inviteCode"

    Dialog(
        onDismissRequest = onSkip,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .background(DiscordCard, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DiscordMark(Modifier.size(width = 46.dp, height = 35.dp), DiscordBlurple)
            Spacer(Modifier.height(16.dp))

            Text(
                "COMMUNITY",
                fontSize = 10.5.sp,
                color = DiscordMuted,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp,
            )
            Spacer(Modifier.height(18.dp))

            // --- the profile row: avatar, display name, handle ---
            Row(
                Modifier.fillMaxWidth().background(DiscordPanel, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(46.dp).background(Color(0xFF1E1F22), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        icon != null -> Image(
                            bitmap = icon!!,
                            contentDescription = null,
                            modifier = Modifier.size(46.dp),
                        )
                        else -> DiscordMark(Modifier.size(width = 24.dp, height = 18.dp))
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (loading) "…" else name,
                            fontSize = 15.sp,
                            color = DiscordText,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (invite?.verified == true) {
                            Spacer(Modifier.width(5.dp))
                            Box(
                                Modifier.size(14.dp).background(DiscordGreen, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("✓", fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when {
                            invite?.members != null -> "${fmt(invite!!.members)} members"
                            else -> "opencode community"
                        },
                        fontSize = 11.5.sp,
                        color = DiscordMuted,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // --- the link itself, selectable-looking, monospace so it reads as a code ---
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF1E1F22), RoundedCornerShape(9.dp))
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "#",
                    fontSize = 13.sp,
                    color = DiscordMuted,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    link,
                    fontSize = 12.5.sp,
                    color = DiscordText,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (error.isNotBlank()) {
                Spacer(Modifier.height(9.dp))
                Text(error, fontSize = 11.sp, color = DiscordDanger, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { onOpen(invite) },
                modifier = Modifier.fillMaxWidth().height(45.dp),
                shape = RoundedCornerShape(9.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DiscordBlurple,
                    contentColor = Color.White,
                ),
            ) { Text("Open Discord", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold) }

            TextButton(onClick = onSkip) {
                Text("Skip", fontSize = 12.5.sp, color = DiscordMuted)
            }
            Text(
                "optional - nothing here is required to use ZAPP",
                fontSize = 9.5.sp,
                color = Color(0xFF6D6F78),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The last screen of setup: one card, the whole surface tappable.
 *
 * Deliberately a card and not a button inside a card - the owner asked for a tappable dialog, and
 * a 56 dp row that responds anywhere is easier to hit with a thumb than a 45 dp button.
 */
@Composable
fun LaunchOpencodeDialog(onLaunch: () -> Unit) {
    Dialog(
        onDismissRequest = onLaunch,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.90f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(DiscordCard, RoundedCornerShape(14.dp))
                    .clickable(onClick = onLaunch)
                    .padding(horizontal = 18.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DiscordMark(Modifier.size(width = 34.dp, height = 26.dp), DiscordBlurple)
                Spacer(Modifier.height(14.dp))
                Text(
                    "LAUNCH OPENCODE",
                    fontSize = 15.sp,
                    color = DiscordText,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "tap anywhere on this card",
                    fontSize = 11.sp,
                    color = DiscordMuted,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().background(DiscordBlurple, RoundedCornerShape(9.dp))
                        .padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Open", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text("→", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** `51619` -> `311,947`. */
private fun fmt(n: Int?): String = if (n == null) "—" else "%,d".format(n)

/**
 * Opens an invite link the way a phone should: the Discord app if it is installed, the
 * browser otherwise. `discord.gg` is a real deep link, so no interception is attempted here.
 */
fun openDiscordInvite(ctx: android.content.Context, code: String) {
    if (code.isBlank()) return
    val uri = Uri.parse("https://discord.gg/$code")
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
