package com.zapp.net.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat's syntax palette.
 *
 * It used to be pinned byte-for-byte to the theme module in the shipped desktop bundle
 * (`oc-2-COeEKe_J.js`), whose dark block is:
 *
 * ```
 * syntax-comment    var(--v2-text-text-muted)
 * syntax-keyword    var(--v2-pink-400)
 * syntax-string     var(--v2-green-400)
 * syntax-primitive  var(--v2-pink-400)
 * syntax-property   var(--v2-orange-400)
 * syntax-type       var(--v2-purple-400)
 * syntax-constant   #93e9f6
 * syntax-critical   var(--v2-red-400)
 * ```
 *
 * That is no longer true, by the owner's instruction: *"i hate the color red gren and gray to the
 * files codes its not a green red color like magenda sa mata tignan same sa ibang color"*. The
 * mint string and the four flat greys were the only unsoftened colours left on the screen, so
 * they were moved onto the same axis as the pink/orange/purple/cyan. This test pins the
 * **relationships** the code block depends on, not the literals, so the palette can be retinted
 * again without the guard lying about what matters.
 *
 * `#5C5C5C` (grey-700) was the **light** theme's muted and read as almost-invisible on a dark
 * canvas. Comments are `text-muted`, which in the dark block is the same grey as every other
 * muted label.
 */
class SyntaxColorTest {

    private fun luminance(c: Color): Double {
        fun ch(v: Float): Double {
            val s = v.toDouble()
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun ratio(fg: Color, bg: Color): Double {
        val a = luminance(fg)
        val b = luminance(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun spread(c: Color): Float =
        maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)

    @Test
    fun thePinkAndTheOrangeAreStillTheirs() {
        // These two were never the problem, and they are the colours the owner named when he asked
        // for the code palette in the first place.
        assertEquals(Color(0xFFF799C6), Syn.Keyword)    // v2-pink-400
        assertEquals(Color(0xFFFFC1A4), Syn.Annotation) // v2-orange-400
        assertEquals(Color(0xFF9E99F7), Syn.Type)       // v2-purple-400
        assertEquals(Color(0xFF93E9F6), Syn.Constant)   // #93e9f6
    }

    @Test
    fun aNumberIsPrimitiveAndSharesTheKeywordPink() {
        // syntax-primitive -> v2-pink-400, the same row syntax-keyword uses.
        assertEquals(Syn.Keyword, Syn.Number)
    }

    @Test
    fun aCommentIsNotTheOldGrey() {
        // `syntax-comment: var(--v2-text-text-muted)` and the dark muted was `--v2-grey-500`
        // = #AEAEAE. That is the grey he rejected; the slot is now ink.
        assertNotEquals(Color(0xFFAEAEAE), Syn.Comment)
        assertNotEquals(Color(0xFF5C5C5C), Syn.Comment) // grey-700 is the LIGHT muted
        assertNotEquals(Color(0xFFA6A6A6), Syn.Comment) // that is punctuation, not comment
    }

    @Test
    fun noSyntaxTokenIsNeutralGrey() {
        // Same rule as `noTokenInOurRampIsNeutralGrey` in LinkColorTest: a neutral grey has
        // R = G = B, so its channel spread is zero. Code used to be four of them plus white.
        val tokens = mapOf(
            "Comment" to Syn.Comment,
            "Plain" to Syn.Plain,
            "Punct" to Syn.Punct,
            "Operator" to Syn.Operator,
        )
        for ((name, c) in tokens) {
            assertTrue("$name is a flat grey", spread(c) > 0f)
        }
    }

    @Test
    fun theStringIsGreenButNoLongerMint() {
        // Green is kept: every editor paints strings green, and moving the hue would cost
        // recognition. What changed is the chroma - the old value was 57% saturation and read as
        // a traffic light on top of a violet diff row.
        assertNotEquals(Color(0xFF96E3A6), Syn.String)
        assertTrue("String lost its green", Syn.String.green > Syn.String.red)
        assertTrue("String is not cool", Syn.String.blue > Syn.String.red)
        // Hue pulled toward teal: blue is now well clear of red, not a hair above it.
        assertTrue(
            "String is still mint (b-r = ${Syn.String.blue - Syn.String.red})",
            Syn.String.blue - Syn.String.red > 0.15f,
        )
    }

    @Test
    fun theNeutralOrderingIsTheirs() {
        // Their slots are strong > base > weak. That ordering is what makes punctuation recede
        // and plain text lead; only the hue moved.
        assertTrue("Plain is not brightest", luminance(Syn.Plain) > luminance(Syn.Punct))
        assertTrue("Punct is not above Comment", luminance(Syn.Punct) > luminance(Syn.Comment))
        assertTrue("Comment is not above Operator", luminance(Syn.Comment) > luminance(Syn.Operator))
    }

    @Test
    fun everySyntaxTokenClearsTheContrastFloorOnTheBlock() {
        val all = mapOf(
            "Keyword" to Syn.Keyword,
            "Annotation" to Syn.Annotation,
            "String" to Syn.String,
            "Number" to Syn.Number,
            "Comment" to Syn.Comment,
            "Function" to Syn.Function,
            "Type" to Syn.Type,
            "Constant" to Syn.Constant,
            "Plain" to Syn.Plain,
            "Punct" to Syn.Punct,
            "Operator" to Syn.Operator,
        )
        for ((name, c) in all) {
            val r = ratio(c, Syn.BlockBg)
            assertTrue("$name on BlockBg is $r:1", r >= 4.5)
        }
    }
}
