package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The advanced timeline editor's model half.
 *
 * `TimelineDetail.Detail` is a fixed seven-field record, so the editor can only move one of
 * seven rules - never invent a category. [TimelineDetail.encode] / [decode] are **ours** (PC
 * keeps the object in its settings JSON); the test's job is to prove the wire form can only
 * ever produce a detail the renderer already understands.
 */
class TimelineCodecTest {

    @Test
    fun everyPresetRoundTrips() {
        for ((id, value) in TimelineDetail.presets) {
            val text = TimelineDetail.encode(value)
            val back = TimelineDetail.decode(text)
            assertEquals("preset $id did not survive the codec", value, back)
            // and the id is still readable from the decoded value, which is what makes the
            // preset list able to highlight itself after a restart
            assertEquals(id, TimelineDetail.presetId(back!!))
        }
    }

    @Test
    fun theWireFormNamesEveryCategory() {
        val text = TimelineDetail.encode(TimelineDetail.default)
        for (c in TimelineDetail.categories) {
            assertTrue("$c missing from $text", text.contains("$c="))
        }
        assertEquals(7, text.split(",").size)
    }

    @Test
    fun decodeRejectsIncompleteInput() {
        assertNull(TimelineDetail.decode(""))
        assertNull(TimelineDetail.decode("garbage"))
        assertNull(TimelineDetail.decode("shell=S1"))
        // six of seven
        assertNull(
            TimelineDetail.decode(
                "shell=S1,edit=S1,thinking=S1,subagents=S-,notices=S-,tools=S-",
            ),
        )
    }

    @Test
    fun decodeRejectsUnknownTokens() {
        val good = TimelineDetail.encode(TimelineDetail.default)
        // an unknown placement letter
        assertNull(TimelineDetail.decode(good.replace("shell=G0", "shell=X0")))
        // an unknown details letter
        assertNull(TimelineDetail.decode(good.replace("shell=G0", "shell=G9")))
        // an unknown category is ignored on purpose, not rejected: a future build that adds
        // an eighth category must still be readable here, and the seven it names win.
        assertEquals(TimelineDetail.default, TimelineDetail.decode("$good,nope=S1"))
        // an unknown category cannot stand in for a missing one, though
        assertNull(TimelineDetail.decode("nope=S1"))
    }

    /** The only thing the editor does: move one category, leave the other six alone. */
    @Test
    fun withMovesExactlyOneCategory() {
        val base = TimelineDetail.default
        val moved = TimelineDetail.with(
            base,
            "shell",
            TimelineDetail.Rule(TimelineDetail.Placement.Separate, TimelineDetail.Expansion.Expanded),
        )
        assertEquals(TimelineDetail.Placement.Separate, moved.shell.placement)
        assertEquals(TimelineDetail.Expansion.Expanded, moved.shell.details)
        for (c in TimelineDetail.categories - "shell") {
            assertEquals("$c moved", base.of(c), moved.of(c))
        }
    }

    /** A hand edit that matches no preset is `custom` - `timelinePreset(value)` returning null. */
    @Test
    fun aHandEditBecomesCustom() {
        val base = TimelineDetail.default
        val edited = TimelineDetail.with(
            base,
            "commentary",
            TimelineDetail.Rule(TimelineDetail.Placement.Hidden),
        )
        assertNull(TimelineDetail.presetId(edited))
        // and it still round-trips, so the editor can persist it
        assertEquals(edited, TimelineDetail.decode(TimelineDetail.encode(edited)))
    }

    /**
     * Their comparison ignores the expansion of a hidden rule, so hiding a category cannot
     * make the value stop matching its preset on account of details alone.
     */
    @Test
    fun hiddenRulesIgnoreTheirExpansion() {
        val quiet = TimelineDetail.presets.first { it.first == "quiet" }.second
        val same = TimelineDetail.with(
            quiet,
            "shell",
            TimelineDetail.Rule(TimelineDetail.Placement.Hidden, TimelineDetail.Expansion.Expanded),
        )
        assertEquals("quiet", TimelineDetail.presetId(same))
    }

    @Test
    fun onlyThreeCategoriesCarryDetails() {
        assertEquals(
            setOf("shell", "edit", "thinking"),
            TimelineDetail.categories.filter { TimelineDetail.hasDetails(it) }.toSet(),
        )
        // derived from the presets, not hard-coded
        for (c in TimelineDetail.categories) {
            val anyDetails = TimelineDetail.presets.any { it.second.of(c).details != null }
            assertEquals(anyDetails, TimelineDetail.hasDetails(c))
        }
    }

    @Test
    fun everyCategoryIsNameable() {
        assertEquals(TimelineDetail.categories, SettingsModel.categoryNames.map { it.first })
        assertFalse(SettingsModel.categoryNames.any { it.second.isBlank() })
        // prose has no category, so the editor must not offer one for it
        assertNull(TimelineDetail.category("text", null))
        assertEquals("shell", TimelineDetail.category("tool", "bash"))
        assertEquals("thinking", TimelineDetail.category("reasoning", "reasoning"))
    }
}
