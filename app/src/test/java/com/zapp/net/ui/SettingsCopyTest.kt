package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * `settings.*` parity, against `app/src/runtime/i18n/en.ts` in the shipped bundle.
 *
 * The strings below are copied from that file by line number, so a typo in `SettingsCopy`
 * fails here rather than on the owner's screen. Line numbers refer to the extracted
 * `en.ts` (1 470 lines).
 */
class SettingsCopyTest {

    @Test
    fun theChromeStringsAreTheirs() {
        assertEquals("Back to app", SettingsCopy.BACK_TO_APP)      // :860
        assertEquals("Settings", SettingsCopy.TITLE)                 // :812
        assertEquals("Search", SettingsCopy.SEARCH)                  // :863
        assertEquals("Version 0.1", SettingsCopy.version("0.1"))     // :948
        assertEquals("Released under the MIT License", SettingsCopy.LICENSE) // :951
        assertEquals("Help & feedback", SettingsCopy.HELP_FEEDBACK)  // :980
        // ours, not theirs: ZAPP has no LICENSE file, so the About section states provenance
        assertNotEquals(SettingsCopy.LICENSE, SettingsCopy.ATTRIBUTION)
        assertEquals("Behaviour ported from OpenCode, MIT licensed", SettingsCopy.ATTRIBUTION)    }

    @Test
    fun theSectionHeadingsAreTheirs() {
        assertEquals("Appearance", SettingsCopy.SECTION_APPEARANCE)          // :1075
        assertEquals("General", SettingsCopy.SECTION_GENERAL)                // :1076
        assertEquals("Desktop notifications", SettingsCopy.SECTION_NOTIFICATIONS) // :1077
        assertEquals("Sound effects", SettingsCopy.SECTION_SOUNDS)           // :1078
        assertEquals(
            "Choose when to receive notifications and hear sounds",
            SettingsCopy.NOTIFICATIONS_DESCRIPTION,                          // :999
        )
    }

    @Test
    fun theTimelineStringsAreTheirs() {
        assertEquals("Timeline", SettingsCopy.TIMELINE_TITLE)            // :1080
        assertEquals("Timeline detail", SettingsCopy.TIMELINE_DETAIL)    // :1092
        assertEquals(
            "Choose how much detail appears in the session timeline.",
            SettingsCopy.TIMELINE_DESCRIPTION,                           // :1093
        )
        assertEquals("Custom", SettingsCopy.TIMELINE_CUSTOM)             // :1107
        assertEquals("Changed files", SettingsCopy.TURN_DIFFS_TITLE)     // :1110
        assertEquals(
            "List the files each turn changed when it finishes",
            SettingsCopy.TURN_DIFFS_DESCRIPTION,                         // :1111
        )
        assertEquals("Collapse long messages", SettingsCopy.FOLD_TITLE)  // :1108
        assertEquals(
            "Show the first few lines. Expand to read your full message.",
            SettingsCopy.FOLD_DESCRIPTION,                               // :1109
        )
    }

    /**
     * `timelinePresets` order, and `settings.timeline.preset.*` verbatim (:1095-1099).
     * The order is load-bearing: `defaultSettings.general.timelineDetail` is
     * `timelinePresets[2]`, so slot 2 has to be `compact` or the default changes.
     */
    @Test
    fun thePresetsAreInTheirOrder() {
        assertEquals(
            listOf("everything", "detailed", "compact", "quiet", "text-only"),
            SettingsCopy.presets.map { it.first },
        )
        assertEquals(
            listOf("Everything", "Detailed", "Compact", "Quiet", "Messages only"),
            SettingsCopy.presets.map { it.second },
        )
        assertEquals("compact", SettingsCopy.presets[2].first)
    }

    @Test
    fun everyPresetHasItsOwnDescription() {
        assertEquals(
            "Show all activity separately. Expand shell output, edits, and thinking.",
            SettingsCopy.presetDescription("everything"),   // :1100
        )
        assertEquals(
            "Expand shell output and edits. Show subagents separately and group other activity.",
            SettingsCopy.presetDescription("detailed"),     // :1101-1102
        )
        assertEquals(
            "Show subagents separately. Group other activity with details collapsed.",
            SettingsCopy.presetDescription("compact"),      // :1103
        )
        assertEquals(
            "Show subagents separately and group edits. Hide other activity.",
            SettingsCopy.presetDescription("quiet"),        // :1104
        )
        assertEquals("Hide all activity.", SettingsCopy.presetDescription("text-only")) // :1105
        assertEquals("Uses advanced settings.", SettingsCopy.presetDescription("custom")) // :1106
    }

    @Test
    fun anUnknownPresetIsCustom() {
        assertEquals("Custom", SettingsCopy.presetTitle("no-such-preset"))
        assertEquals("Uses advanced settings.", SettingsCopy.presetDescription("no-such-preset"))
    }

    /** `settings.timeline.summary` = `{{preset}}:` - interpolation, not concatenation. */
    @Test
    fun theSummaryIsThePresetPlusAColon() {
        assertEquals("Compact:", SettingsCopy.summary("compact"))
        assertEquals("Messages only:", SettingsCopy.summary("text-only"))
        assertEquals("Custom:", SettingsCopy.summary("nope"))
    }

    @Test
    fun theNotificationRowsAreTheirs() {
        assertEquals("Agent", SettingsCopy.NOTIFY_AGENT)              // :1207
        assertEquals(
            "Show system notification when the agent is complete or needs attention",
            SettingsCopy.NOTIFY_AGENT_DESC,                           // :1208-1209
        )
        assertEquals("Permissions", SettingsCopy.NOTIFY_PERMISSIONS)  // :1210
        assertEquals(
            "Show system notification when a permission is required",
            SettingsCopy.NOTIFY_PERMISSIONS_DESC,                     // :1211
        )
        assertEquals("Questions", SettingsCopy.NOTIFY_QUESTIONS)      // :1212
        assertEquals(
            "Show system notification when the agent asks a question",
            SettingsCopy.NOTIFY_QUESTIONS_DESC,                       // :1213
        )
        assertEquals("Errors", SettingsCopy.NOTIFY_ERRORS)            // :1214
        assertEquals(
            "Show system notification when an error occurs",
            SettingsCopy.NOTIFY_ERRORS_DESC,                          // :1215
        )
    }

    /**
     * The sound rows share their titles with the notification rows on purpose - PC reuses
     * `settings.general.sounds.<key>.title` and only the description differs (:1217-1224).
     */
    @Test
    fun theSoundRowsAreTheirs() {
        assertEquals(
            "Play sound when the agent is complete or needs attention",
            SettingsCopy.SOUND_AGENT_DESC,                            // :1218
        )
        assertEquals(
            "Play sound when a permission is required",
            SettingsCopy.SOUND_PERMISSIONS_DESC,                      // :1220
        )
        assertEquals(
            "Play sound when the agent asks a question",
            SettingsCopy.SOUND_QUESTIONS_DESC,                        // :1222
        )
        assertEquals("Play sound when an error occurs", SettingsCopy.SOUND_ERRORS_DESC) // :1224

        // the descriptions must differ from their notification twins, or the two sections
        // read identically and one of them is a lie
        assertNotEquals(SettingsCopy.NOTIFY_AGENT_DESC, SettingsCopy.SOUND_AGENT_DESC)
        assertNotEquals(SettingsCopy.NOTIFY_PERMISSIONS_DESC, SettingsCopy.SOUND_PERMISSIONS_DESC)
        assertNotEquals(SettingsCopy.NOTIFY_QUESTIONS_DESC, SettingsCopy.SOUND_QUESTIONS_DESC)
        assertNotEquals(SettingsCopy.NOTIFY_ERRORS_DESC, SettingsCopy.SOUND_ERRORS_DESC)
    }
}
