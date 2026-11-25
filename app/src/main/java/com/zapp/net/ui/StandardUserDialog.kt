package com.zapp.net.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.ui.theme.Border
import com.zapp.net.ui.theme.Ink
import com.zapp.net.ui.theme.Layer1
import com.zapp.net.ui.theme.Layer2
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextPrimary

/**
 * The card that greets every launch.
 *
 * the owner, 2026-11-20: "lagyan mo din ng standard user dialog parang pang ads pero every time
 * na nag open sila sa apk ... tapos medyo blured yung sa likod".
 *
 * So it is built like an interstitial and not like a settings sheet: one hero block that
 * reads at arm's length, one short list, one full-width call to action. What it says is the
 * truth about the tier - standard, free, no account - because an ad that lies is the one
 * thing this app cannot afford.
 *
 * The blur behind it is NOT applied here. A Compose `Dialog` owns a separate window and
 * cannot touch the pixels underneath, so [StandardUserOverlay] blurs the real content and
 * draws this card on top of it.
 */
private val CardBg = Layer1
private val HeroBg = Ink
private val CtaInk = Ink

/** One step above [CardBg], so the Discord block reads as raised instead of printed. */
private val DiscordBg = Layer2
private val DiscordEdge = Border

/**
 * Full-screen layer: blurred content behind, dimmed, then the card.
 *
 * Call it as the last child of a `Box` whose first child is the real UI, and pass the same
 * `visible` to the blur. `onDismiss` fires on the CTA and on a tap outside the card.
 */
@Composable
fun StandardUserOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    onDiscord: () -> Unit,
) {
    if (!visible) return

    Box(
        Modifier
            .fillMaxSize()
            // The dim is deliberate: `Modifier.blur` is a no-op below API 31, and a card
            // floating on unmodified content looks pasted on. The scrim carries the depth
            // on its own, so the effect survives on old handsets.
            //
            // Deliberately NOT clickable. the owner, 2026-11-20: "yung tap dapat sa exact
            // dialog hindi yung buong screen pwde ma tap". A full-screen dismiss target
            // turns every accidental palm touch into a lost message, so the scrim is dead
            // pixels and only the card answers.
            .background(Color.Black.copy(alpha = 0.46f)),
        contentAlignment = Alignment.Center,
    ) {
        StandardUserCard(
            onContinue = onDismiss,
            onDiscord = onDiscord,
            modifier = Modifier
                .fillMaxWidth(0.90f)
                // The whole card is the target, the same shape as [LaunchOpencodeDialog]:
                // one obvious place to press, no dead margins inside the frame.
                .clickable(onClick = onDismiss),
        )
    }
}

/** The card itself, separated so it can be previewed or reused without the overlay. */
@Composable
fun StandardUserCard(
    onContinue: () -> Unit,
    onDiscord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(CardBg, RoundedCornerShape(16.dp))
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // --- hero: the part that reads like a creative ---
        Column(
            Modifier
                .fillMaxWidth()
                .background(HeroBg, RoundedCornerShape(11.dp))
                .border(1.dp, Line, RoundedCornerShape(11.dp))
                .padding(vertical = 22.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Z A P P",
                fontSize = 26.sp,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp,
            )
            Spacer(Modifier.height(10.dp))
            Box(Modifier.width(46.dp).height(1.dp).background(Line))
            Spacer(Modifier.height(10.dp))
            Text(
                "STANDARD USER",
                fontSize = 11.sp,
                color = TextDim,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "free · no account · no subscription",
            fontSize = 11.5.sp,
            color = TextDim,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(16.dp))
        Bullet("unlimited chats on this phone")
        Bullet("your own rotation, your own address")
        Bullet("nothing leaves this device")

        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(TextPrimary, RoundedCornerShape(10.dp))
                .clickable(onClick = onContinue),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Continue",
                fontSize = 15.sp,
                color = CtaInk,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(14.dp))
        DiscordBlock(onDiscord)
    }
}

/**
 * The server, given the weight it deserves.
 *
 * the owner, 2026-11-20: "pwde pala e highlight mo din yung discord natin tapos add ka maliit na
 * discription like para sa updates etc".
 *
 * A 10 sp footnote under a white button is invisible; people tap Continue and never learn
 * there is a place to look. So this is a bordered block with its own surface, a brighter
 * mark, and one line that says what is actually inside - which is the only reason anyone
 * would tap it.
 *
 * Still monochrome. The highlight comes from a lifted surface and a brighter mark, not from
 * Discord's blurple, which would break the palette this app uses everywhere else.
 */
@Composable
private fun DiscordBlock(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(DiscordBg, RoundedCornerShape(10.dp))
            .border(1.dp, DiscordEdge, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DiscordMark(Modifier.size(width = 19.dp, height = 14.dp), TextPrimary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "ZAPP OPENCODE CP",
                fontSize = 12.sp,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "updates · guides · new builds",
                fontSize = 10.5.sp,
                color = TextDim,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text("›", fontSize = 20.sp, color = TextDim)
    }
}

@Composable
private fun Bullet(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(4.dp).background(TextPrimary, RoundedCornerShape(1.dp)))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 12.5.sp, color = TextPrimary.copy(alpha = 0.86f))
    }
}
