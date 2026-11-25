package com.zapp.net.ui

/**
 * The Settings catalogue, and the touch table.
 *
 * Everything here is data, so the screen can be rendered from a list and the search can be
 * tested without Android. Two things live in this file:
 *
 *  - [entries] - every row Settings can show, in section order, with a stable key. A row's
 *    `action` is what the renderer dispatches on; the row itself carries no state, because
 *    state lives in `DisplayPrefs` / `SessionPrefs`.
 *  - [gestures] - PC's `settings.shortcuts.group.*` groups, filled with the phone's real
 *    gestures instead of key chords.
 *
 * ## Why the gesture table is ours
 *
 * Their shortcuts page lists `Ctrl+U -> Add images and files`. A phone has no keyboard, so the
 * *shape* of the page survives (groups, action titles, one input per action) while the input
 * column changes to taps, long-presses and swipes. The action titles are their `command.*`
 * strings verbatim, because the action is the same action. Where the phone has no equivalent
 * the row says `Unassigned` - their own word for it (`settings.shortcuts.unassigned`) - rather
 * than inventing a gesture that does nothing.
 */
object SettingsModel {

    /** `settings.timeline.category.*` - their category names, in `timelineCategories` order. */
    val categoryNames: List<Pair<String, String>> = listOf(
        "shell" to "Shell",
        "edit" to "Edits",
        "thinking" to "Thinking",
        "subagents" to "Subagents",
        "notices" to "Notices",
        "tools" to "Other tools",
        "commentary" to "Commentary",
    )

    enum class Kind {
        /** A switch, bound to an action id. */
        Toggle,

        /** One of the five `timelinePresets`, plus `custom`. */
        Preset,

        /** Opens a sub-page inside Settings. */
        Page,

        /** A line of text with no control. */
        Info,
    }

    /**
     * One row.
     *
     * `key` is stable and used by the tests; `section` is the heading it renders under;
     * `action` is the semantic id the renderer maps to behaviour, so search can find a row
     * without the row knowing what it does.
     */
    data class Entry(
        val key: String,
        val section: String,
        val title: String,
        val description: String,
        val kind: Kind,
        val action: String,
    )

    // ---- section names, in the order they render ----

    val sections: List<String> = listOf(
        SettingsCopy.SECTION_APPEARANCE,
        "Permissions",
        SettingsCopy.SECTION_NOTIFICATIONS,
        SettingsCopy.SECTION_SOUNDS,
        "This phone",
        "About",
    )

    /** The action ids the renderer understands. Anything else is a bug, not a no-op. */
    object Action {
        const val PRESET_PREFIX = "preset:"
        const val ADVANCED = "page:advanced"
        const val GESTURES = "page:gestures"
        const val WORKSPACE = "page:workspace"
        const val TURN_DIFFS = "toggle:turnDiffs"
        const val FOLD_USER = "toggle:foldUser"
        const val AUTO_APPROVE = "toggle:autoApprove"
        const val NOTIFY_PREFIX = "notify:"
        const val SOUND_PREFIX = "sound:"
        const val VERSION = "info:version"
        const val ATTRIBUTION = "info:attribution"
    }

    private const val APPEARANCE = SettingsCopy.SECTION_APPEARANCE
    private const val PERMISSIONS = "Permissions"
    private const val NOTIFICATIONS = SettingsCopy.SECTION_NOTIFICATIONS
    private const val SOUNDS = SettingsCopy.SECTION_SOUNDS
    private const val PHONE = "This phone"
    private const val ABOUT = "About"

    val entries: List<Entry> = buildList {
        // ---- appearance: the five presets, then the advanced editor ----
        for ((id, title) in SettingsCopy.presets) {
            add(
                Entry(
                    key = "preset.$id",
                    section = APPEARANCE,
                    title = title,
                    description = SettingsCopy.presetDescription(id),
                    kind = Kind.Preset,
                    action = Action.PRESET_PREFIX + id,
                ),
            )
        }
        add(
            Entry(
                key = "timeline.advanced",
                section = APPEARANCE,
                title = SettingsCopy.ADVANCED_TITLE,
                description = SettingsCopy.ADVANCED_DESCRIPTION,
                kind = Kind.Page,
                action = Action.ADVANCED,
            ),
        )
        add(
            Entry(
                key = "timeline.turnDiffs",
                section = APPEARANCE,
                title = SettingsCopy.TURN_DIFFS_TITLE,
                description = SettingsCopy.TURN_DIFFS_DESCRIPTION,
                kind = Kind.Toggle,
                action = Action.TURN_DIFFS,
            ),
        )
        add(
            Entry(
                key = "timeline.foldUser",
                section = APPEARANCE,
                title = SettingsCopy.FOLD_TITLE,
                description = SettingsCopy.FOLD_DESCRIPTION,
                kind = Kind.Toggle,
                action = Action.FOLD_USER,
            ),
        )

        // ---- permissions ----
        add(
            Entry(
                key = "permissions.autoApprove",
                section = PERMISSIONS,
                title = SettingsCopy.AUTO_APPROVE_TITLE,
                description = SettingsCopy.AUTO_APPROVE_DESCRIPTION,
                kind = Kind.Toggle,
                action = Action.AUTO_APPROVE,
            ),
        )

        // ---- notifications: their four, in their order ----
        add(
            Entry(
                "notify.agent", NOTIFICATIONS, SettingsCopy.NOTIFY_AGENT,
                SettingsCopy.NOTIFY_AGENT_DESC, Kind.Toggle, Action.NOTIFY_PREFIX + "agent",
            ),
        )
        add(
            Entry(
                "notify.permissions", NOTIFICATIONS, SettingsCopy.NOTIFY_PERMISSIONS,
                SettingsCopy.NOTIFY_PERMISSIONS_DESC, Kind.Toggle,
                Action.NOTIFY_PREFIX + "permissions",
            ),
        )
        add(
            Entry(
                "notify.questions", NOTIFICATIONS, SettingsCopy.NOTIFY_QUESTIONS,
                SettingsCopy.NOTIFY_QUESTIONS_DESC, Kind.Toggle,
                Action.NOTIFY_PREFIX + "questions",
            ),
        )
        add(
            Entry(
                "notify.errors", NOTIFICATIONS, SettingsCopy.NOTIFY_ERRORS,
                SettingsCopy.NOTIFY_ERRORS_DESC, Kind.Toggle, Action.NOTIFY_PREFIX + "errors",
            ),
        )

        // ---- sounds: the same four events, a separate switch each ----
        add(
            Entry(
                "sound.agent", SOUNDS, SettingsCopy.NOTIFY_AGENT,
                SettingsCopy.SOUND_AGENT_DESC, Kind.Toggle, Action.SOUND_PREFIX + "agent",
            ),
        )
        add(
            Entry(
                "sound.permissions", SOUNDS, SettingsCopy.NOTIFY_PERMISSIONS,
                SettingsCopy.SOUND_PERMISSIONS_DESC, Kind.Toggle,
                Action.SOUND_PREFIX + "permissions",
            ),
        )
        add(
            Entry(
                "sound.questions", SOUNDS, SettingsCopy.NOTIFY_QUESTIONS,
                SettingsCopy.SOUND_QUESTIONS_DESC, Kind.Toggle,
                Action.SOUND_PREFIX + "questions",
            ),
        )
        add(
            Entry(
                "sound.errors", SOUNDS, SettingsCopy.NOTIFY_ERRORS,
                SettingsCopy.SOUND_ERRORS_DESC, Kind.Toggle, Action.SOUND_PREFIX + "errors",
            ),
        )

        // ---- this phone: the two pages that replace three desktop tabs ----
        add(
            Entry(
                "phone.gestures", PHONE, SettingsCopy.GESTURES_TITLE,
                SettingsCopy.GESTURES_DESCRIPTION, Kind.Page, Action.GESTURES,
            ),
        )
        add(
            Entry(
                "phone.workspace", PHONE, SettingsCopy.WORKSPACE_TITLE,
                SettingsCopy.WORKSPACE_DESCRIPTION, Kind.Page, Action.WORKSPACE,
            ),
        )

        // ---- about ----
        add(
            Entry(
                "about.version", ABOUT, "Version", "", Kind.Info, Action.VERSION,
            ),
        )
        add(
            Entry(
                "about.attribution", ABOUT, "License", SettingsCopy.ATTRIBUTION,
                Kind.Info, Action.ATTRIBUTION,
            ),
        )
    }

    /**
     * `settings.search-machine.ts` filters a settings tree by a query; ours filters this flat
     * catalogue. Matching is case-insensitive across the title, the description and the
     * section heading, which is what makes "shell" find both the Shell category and the
     * shell-mode gesture.
     *
     * A blank query returns everything, in catalogue order.
     */
    fun filter(query: String, all: List<Entry> = entries): List<Entry> {
        val q = query.trim()
        if (q.isEmpty()) return all
        return all.filter { e ->
            e.title.contains(q, ignoreCase = true) ||
                e.description.contains(q, ignoreCase = true) ||
                e.section.contains(q, ignoreCase = true)
        }
    }

    /** Filtered rows bucketed by section, in [sections] order, empty sections dropped. */
    fun grouped(query: String, all: List<Entry> = entries): List<Pair<String, List<Entry>>> {
        val hits = filter(query, all)
        return sections.mapNotNull { s ->
            val rows = hits.filter { it.section == s }
            if (rows.isEmpty()) null else s to rows
        }
    }

    // ---- gestures -------------------------------------------------------------------

    /** `settings.shortcuts.group.*`, in their order. */
    val gestureGroups: List<String> = listOf(
        "General", "Session", "Navigation", "Model and agent", "Terminal", "Prompt",
    )

    /**
     * One action and the phone's gesture for it.
     *
     * `gesture` is null when the phone has no equivalent; the row then renders
     * `settings.shortcuts.unassigned` ("Unassigned") instead of a made-up gesture.
     */
    data class Gesture(val group: String, val action: String, val gesture: String?)

    /**
     * Every mapping below was read off the code, not guessed:
     *
     *  - `ChatScreen.kt:387` - the sessions button; `:391` - the finder button.
     *  - `ChatScreen.kt:353` - the drawer button, and `ModalNavigationDrawer`'s edge swipe.
     *  - `ChatScreen.kt:280` - `+` in the drawer header.
     *  - `ChatScreen.kt:1033` - the shells chip; `:4814` - the shell-mode chip.
     *  - `ChatScreen.kt:4819` / `:4838` - the agent and model chips.
     *  - `ChatScreen.kt:4566` - the `/` palette's session actions (`CommandSheet` rows).
     *  - `CommandSheet.kt:219` - `SheetAction`, which is what those rows dispatch.
     */
    val gestures: List<Gesture> = listOf(
        Gesture("General", "Open settings", "Drawer, then Settings"),
        Gesture("General", "Toggle sidebar", "The menu button, or swipe in from the left edge"),
        Gesture("General", "Search everything", "The magnifier in the tab strip"),
        Gesture("General", "Choose model", "The model chip above the composer"),

        Gesture("Session", "New session", "The + in the drawer header"),
        Gesture("Session", "Search sessions", "The field at the top of the drawer"),
        Gesture("Session", "Undo", "Long-press a message, then Undo"),
        Gesture("Session", "Fork from message", "Long-press a message, then Fork"),
        Gesture("Session", "Compact session", "/ then Compact conversation"),
        Gesture("Session", "Auto-accept permissions", "Settings, then Permissions"),

        Gesture("Navigation", "Open file", "Tap a file path in a message"),
        Gesture("Navigation", "Close tab", "The cross on the tab"),
        Gesture("Navigation", "Previous message", null),
        Gesture("Navigation", "Next message", null),
        Gesture("Navigation", "Reopen closed tab", null),

        Gesture("Model and agent", "Choose agent", "The agent chip above the composer"),
        Gesture("Model and agent", "Cycle model variant", "The model chip, then a variant"),
        Gesture("Model and agent", "Cycle agent", "The agent chip, then an agent"),

        Gesture("Terminal", "Toggle bottom panel", "The shells chip above the composer"),
        Gesture("Terminal", "Toggle shell mode", "The Shell chip in the composer"),

        Gesture("Prompt", "Add images and files", "The + in the composer"),
        Gesture("Prompt", "Search commands", "/ in the composer"),
        Gesture("Prompt", "Mention a file or session", "@ in the composer"),
    )

    fun gesturesIn(group: String): List<Gesture> = gestures.filter { it.group == group }
}
