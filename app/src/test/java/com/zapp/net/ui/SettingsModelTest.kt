package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings catalogue and the touch table.
 *
 * The catalogue is the thing the renderer iterates, so a row with an action the renderer does
 * not know would be a silently dead control. These tests make that a build failure instead.
 */
class SettingsModelTest {

    @Test
    fun everyRowBelongsToAKnownSection() {
        for (e in SettingsModel.entries) {
            assertTrue("${e.key} has section '${e.section}'", e.section in SettingsModel.sections)
        }
    }

    @Test
    fun keysAreUnique() {
        val keys = SettingsModel.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    /** Every action is one the `when` in `SettingsScreen` handles. */
    @Test
    fun everyActionIsRenderable() {
        val known = setOf(
            SettingsModel.Action.ADVANCED,
            SettingsModel.Action.GESTURES,
            SettingsModel.Action.WORKSPACE,
            SettingsModel.Action.TURN_DIFFS,
            SettingsModel.Action.FOLD_USER,
            SettingsModel.Action.AUTO_APPROVE,
            SettingsModel.Action.VERSION,
            SettingsModel.Action.ATTRIBUTION,
        )
        for (e in SettingsModel.entries) {
            val a = e.action
            val ok = a in known ||
                a.startsWith(SettingsModel.Action.PRESET_PREFIX) ||
                a.startsWith(SettingsModel.Action.NOTIFY_PREFIX) ||
                a.startsWith(SettingsModel.Action.SOUND_PREFIX)
            assertTrue("${e.key} -> '$a' has no handler", ok)
        }
    }

    @Test
    fun theFivePresetsAreTheFirstFiveAppearanceRows() {
        val presets = SettingsModel.entries.filter { it.kind == SettingsModel.Kind.Preset }
        assertEquals(SettingsCopy.presets.map { it.first }, presets.map { it.key.removePrefix("preset.") })
        assertEquals(SettingsCopy.presets.map { it.second }, presets.map { it.title })
    }

    /** The advanced editor is reachable from the appearance section, not hidden. */
    @Test
    fun theAdvancedEditorIsReachable() {
        val row = SettingsModel.entries.first { it.action == SettingsModel.Action.ADVANCED }
        assertEquals(SettingsModel.Kind.Page, row.kind)
        assertEquals(SettingsCopy.ADVANCED_TITLE, row.title)
        assertEquals("Set placement and details for each activity category.", row.description)
    }

    @Test
    fun blankQueryReturnsEverythingInCatalogueOrder() {
        assertEquals(SettingsModel.entries, SettingsModel.filter(""))
        assertEquals(SettingsModel.entries, SettingsModel.filter("   "))
    }

    @Test
    fun searchIsCaseInsensitiveAndSpansTitleDescriptionAndSection() {
        // title
        assertEquals(
            listOf("permissions.autoApprove"),
            SettingsModel.filter("auto-accept").map { it.key },
        )
        // description
        assertTrue(
            SettingsModel.filter("first few lines").any { it.key == "timeline.foldUser" },
        )
        // section: "Permissions" catches the section heading's row too
        assertTrue(
            SettingsModel.filter("permissions").any { it.key == "permissions.autoApprove" },
        )
        // case
        assertEquals(
            SettingsModel.filter("AUTO-ACCEPT").map { it.key },
            SettingsModel.filter("auto-accept").map { it.key },
        )
    }

    @Test
    fun noMatchIsEmpty() {
        assertTrue(SettingsModel.filter("zzzz-no-such-setting").isEmpty())
        assertTrue(SettingsModel.grouped("zzzz-no-such-setting").isEmpty())
    }

    /** Grouping preserves `sections` order and never emits an empty heading. */
    @Test
    fun groupingDropsEmptySections() {
        val groups = SettingsModel.grouped("auto-accept")
        assertEquals(listOf("Permissions"), groups.map { it.first })
        assertEquals(1, groups.single().second.size)

        val all = SettingsModel.grouped("")
        assertEquals(SettingsModel.sections, all.map { it.first })
        assertTrue(all.all { it.second.isNotEmpty() })
    }

    // ---- the touch table -------------------------------------------------------------

    @Test
    fun everyGestureGroupHasRows() {
        for (g in SettingsModel.gestureGroups) {
            assertTrue("group '$g' is empty", SettingsModel.gesturesIn(g).isNotEmpty())
        }
        // and no row belongs to a group that is never rendered
        for (g in SettingsModel.gestures) {
            assertTrue("'${g.action}' has group '${g.group}'", g.group in SettingsModel.gestureGroups)
        }
    }

    /**
     * The page is a reference, so an action with no phone equivalent must say so rather than
     * carry an invented gesture. At least one does, which proves the `Unassigned` branch is
     * reachable and not dead code.
     */
    @Test
    fun actionsWithoutAGestureSayUnassigned() {
        val missing = SettingsModel.gestures.filter { it.gesture == null }
        assertTrue(missing.isNotEmpty())
        assertEquals("Unassigned", SettingsCopy.UNASSIGNED)
        assertTrue(missing.all { it.action.isNotBlank() })
    }

    @Test
    fun noGestureIsBlankOrDuplicated() {
        val named = SettingsModel.gestures.mapNotNull { it.gesture }
        assertFalse(named.any { it.isBlank() })
        // one action per row: the table must not list the same action twice in a group
        val keys = SettingsModel.gestures.map { it.group to it.action }
        assertEquals(keys.size, keys.toSet().size)
    }
}
