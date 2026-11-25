package com.zapp.net.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dark theme's link tokens, read out of the shipped bundle's colour module.
 *
 * `--v2-text-text-accent` is `ref("v2-blue-400")` in the dark block and `v2-blue-600` in the
 * light one; `--v2-text-text-accent-hover` is `v2-blue-300` / `v2-blue-700`. The palette rows are
 * `v2-blue-300 #c3d4fd`, `v2-blue-400 #a2bcff`, `v2-blue-600 #3b5cf6`.
 *
 * This test exists because links were drawn in `Accent` (#B7A8D2) for weeks and looked like body
 * text on a near-black canvas. If anyone ever "simplifies" the palette again, this fails.
 */
class LinkColorTest {

    @Test
    fun darkLinksAreBlue400() {
        assertEquals(Color(0xFFA2BCFF), Link)
    }

    @Test
    fun darkLinkHoverIsBlue300() {
        assertEquals(Color(0xFFC3D4FD), LinkHover)
    }

    @Test
    fun aLinkIsNeverTheAccent() {
        // The accent is our violet by the owner's order; a link is theirs, and theirs is blue.
        assertNotEquals(Accent, Link)
        assertNotEquals(AccentSoft, Link)
        // Hover is lighter than the resting link, not the other way round.
        assertNotEquals(Link, LinkHover)
    }

    @Test
    fun aPathCodeSpanUsesItsOwnToken() {
        // `--v2-text-text-code-path` (dark) = `v2-blue-300`.
        assertEquals(Color(0xFFC3D4FD), CodePath)
        // Same hex as the hover link, different meaning - both rows must stay.
        assertEquals(LinkHover, CodePath)
    }

    @Test
    fun theInlineCodeChipIsBodyTextAtEightPercent() {
        // `:not(pre)>code { background: var(--v2-text-text-base) }` with `--markdown-inline-code-bg-mix: 8%`
        assertEquals(Color(0x14FAFAFA), InlineCodeBg)
        assertEquals(0x14 / 255f, InlineCodeBg.alpha, 0.0001f)
    }

    @Test
    fun statusColoursAreTheStateTokensNotTheDiffTokens() {
        // --v2-state-fg-* (dark block)
        assertEquals(Color(0xFF6BD586), Success)  // v2-green-500
        assertEquals(Color(0xFFF2CF76), Warning)  // v2-yellow-500
        assertEquals(Color(0xFFF17471), Danger)   // v2-red-500
        assertEquals(Color(0xFF7698FD), Info)     // v2-blue-500
        // An error line is state-fg-danger; a removed diff line is the faded diff token. They were
        // never the same colour on PC, and since the diff palette is ours they are not close either.
        assertNotEquals(DiffDel, Danger)
    }

    @Test
    fun theDiffIsRedAndTealFromHisPalette() {
        // the owner's four passes over this, in order:
        //   1. "i hate the color red gren and gray to the files codes"          -> PC traffic light
        //   2. "hindi bagay yung color sa pinalit mo"                            -> warm sand read brown
        //   3. "gawin mo nalang red and green ... yung green parang green grass"  -> grass green
        //   4. a five-swatch palette: *"ganito sana and then yung -1 +1 numbers din"*
        //
        // The palette is #981C2D / #D62839 / #C73B59 / #0F5E55 / #2DB6A3. So the "green" side is
        // teal now, not grass, and the red side is a rose, not a soft coral.
        assertTrue("DiffAdd is not teal", DiffAdd.green > DiffAdd.red && DiffAdd.blue > DiffAdd.red)
        assertTrue("DiffBarAdd is not teal", DiffBarAdd.green > DiffBarAdd.red && DiffBarAdd.blue > DiffBarAdd.red)
        assertTrue("DiffDel is not red", DiffDel.red > DiffDel.green && DiffDel.red > DiffDel.blue)
        assertTrue("DiffBarDel is not red", DiffBarDel.red > DiffBarDel.green && DiffBarDel.red > DiffBarDel.blue)

        // Teal carries real blue (`#2DB6A3` is 0.90 of its green); grass was 0.47 and mint 0.73.
        assertTrue(
            "DiffAdd is not teal (b/g = ${DiffAdd.blue / DiffAdd.green})",
            DiffAdd.blue / DiffAdd.green > 0.75f,
        )
        // The rose leans pink: blue beats green, which no orange ever does. That is the real
        // anti-orange invariant - an orange has b/g well under 1.
        assertTrue(
            "DiffDel lost its rose lean (b/g = ${DiffDel.blue / DiffDel.green})",
            DiffDel.blue / DiffDel.green > 1.15f,
        )
        // `g/r` is NOT a hue test. Lifting `#C73B59` to clear 4.5:1 raises green and blue
        // together, so this ratio drifts from 0.296 (the swatch) to 0.453 (the lift) while the
        // hue stays at 350 deg. It only has to stay clear of a fire-engine red's yellow.
        assertTrue(
            "DiffDel is orange again (g/r = ${DiffDel.green / DiffDel.red})",
            DiffDel.green / DiffDel.red < 0.5f,
        )
    }

    @Test
    fun theDiffRowsAreSolidNotAWash() {
        // "dapat solid" - the rows are opaque fills now, not the PC's 10% alpha over #151515.
        assertEquals("DiffAddBg is not opaque", 1f, DiffAddBg.alpha, 0.0001f)
        assertEquals("DiffDelBg is not opaque", 1f, DiffDelBg.alpha, 0.0001f)
        // A solid fill has to actually read as a fill, not as a rounding error on the canvas.
        assertTrue("DiffAddBg is not a fill", luminance(DiffAddBg) > luminance(DiffBg) * 2.0)
        assertTrue("DiffDelBg is not a fill", luminance(DiffDelBg) > luminance(DiffBg) * 2.0)
        // ...and each fill has to sit on its own side of the wheel.
        assertTrue("DiffAddBg is not green", DiffAddBg.green > DiffAddBg.red)
        assertTrue("DiffDelBg is not red", DiffDelBg.red > DiffDelBg.green)
    }

    @Test
    fun everyDiffTextPairClearsTheContrastFloor() {
        // The row washes are dark, so the ink on them is what has to be readable. 4.5:1 is the
        // small-text floor; the two `+N` / `-N` pairs are 12 px.
        fun ratio(fg: Color, bg: Color): Double {
            val a = luminance(fg)
            val b = luminance(bg)
            return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
        }
        assertTrue("DiffAdd on DiffAddBg", ratio(DiffAdd, DiffAddBg) >= 4.5)
        assertTrue("DiffDel on DiffDelBg", ratio(DiffDel, DiffDelBg) >= 4.5)
        // The rails are graphics, not text, so the floor does not apply to them. On the delete side
        // the numeral is the lifted rose, brighter than the rail it sits beside; on the add side the
        // palette teal is already above the floor, so text and rail are the same swatch.
        assertEquals("DiffAdd should stay the palette swatch", DiffBarAdd, DiffAdd)
        assertTrue("DiffDel is not brighter than its rail", luminance(DiffDel) > luminance(DiffBarDel))
        assertTrue("DiffNum on DiffBg", ratio(DiffNum, DiffBg) >= 4.5)
        assertTrue("DiffHunkText on DiffHunkBg", ratio(DiffHunkText, DiffHunkBg) >= 4.5)
        // The washes have to be visible against the diff canvas, or an add and a delete are just
        // text with a coloured `+` in front of them.
        assertTrue("DiffAddBg is not a wash", luminance(DiffAddBg) > luminance(DiffBg))
        assertTrue("DiffDelBg is not a wash", luminance(DiffDelBg) > luminance(DiffBg))
        // ...but the diff is a raised sheet, not a panel: it stays within a hair of the page so the
        // code is still the brightest thing in the block.
        assertTrue(
            "DiffBg drifted away from the page",
            luminance(DiffBg) - luminance(Bg) < 0.02,
        )
    }

    @Test
    fun theUsagePillIsFaintAndItsRingIsNotTheHairline() {
        // The hexes are ours now (grey removal, 2026-11-22: the owner rejected the neutral ramp), so
        // this pins the *relationships* the UI depends on instead of the shipped literals.
        //
        // The pill is faint, never body text and never the accent.
        assertNotEquals(TextPrimary, TextFaint)
        assertNotEquals(Accent, TextFaint)
        // Their ramp order: faint reads brighter than the dim chrome, both below body text.
        assertTrue(luminance(TextFaint) > luminance(TextDim))
        assertTrue(luminance(TextMid) > luminance(TextFaint))
        assertTrue(luminance(TextPrimary) > luminance(TextMid))
        // The regression this pins: the track was `Line`, i.e. an invisible ring.
        assertNotEquals(Line, RingTrack)
        assertTrue(luminance(RingTrack) > luminance(Line))
        assertTrue(luminance(RingArc) > luminance(RingTrack))
    }

    @Test
    fun noTokenInOurRampIsNeutralGrey() {
        // the owner, 22 Nov: "i dont like the gray color all gray ... remove it". A neutral grey has
        // R = G = B, so its channel spread is zero. Every token below carries the accent's hue at
        // low saturation instead; a spread of zero means the flat grey came back.
        //
        // Deliberately exempt, and each for a stated reason:
        //  - TextPrimary/Bg/Ink: the black and white skin itself.
        //  - RingArc: their `--v2-icon-icon-base`, a near-white arc.
        //  - Syntax.kt: retinted in Pass 32 alongside the diff palette; it has its own
        //    `noSyntaxTokenIsNeutralGrey` guard, so it is not repeated here.
        val ours = mapOf(
            "Surface" to Surface,
            "Surface2" to Surface2,
            "Line" to Line,
            "Border" to Border,
            "TextMid" to TextMid,
            "TextDim" to TextDim,
            "TextFaint" to TextFaint,
            "Layer1" to Layer1,
            "Layer2" to Layer2,
            "RingTrack" to RingTrack,
            "Warn" to Warn,
            "Bad" to Bad,
            // The diff palette joined the ramp in Pass 31 - it was the last place three flat greys
            // were still shipping (`#151515`, `#1e1e1e`, `#808080`).
            "DiffBg" to DiffBg,
            "DiffAddBg" to DiffAddBg,
            "DiffDelBg" to DiffDelBg,
            "DiffHunkBg" to DiffHunkBg,
            "DiffNum" to DiffNum,
            "DiffHunkText" to DiffHunkText,
        )
        for ((name, colour) in ours) {
            val spread = channelSpread(colour)
            assertTrue("$name is neutral grey (spread 0)", spread > 0)
        }
    }

    private fun channelSpread(c: Color): Int {
        val r = (c.red * 255f).toInt()
        val g = (c.green * 255f).toInt()
        val b = (c.blue * 255f).toInt()
        return maxOf(r, g, b) - minOf(r, g, b)
    }

    /** WCAG relative luminance, enough to compare two tokens' brightness. */
    private fun luminance(c: Color): Double {
        fun lin(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }
}
