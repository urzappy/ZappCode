package com.zapp.net.ui

/**
 * PORT of the Settings vocabulary ZAPP can honour.
 *
 * Every string is a key from the shipped bundle (`app/src/runtime/i18n/en.ts`, extracted from
 * `main-CLeW75FU.js.map` at byte ~3 920 000), copied verbatim - including PC's British
 * spellings and its missing full stops, because "copy their shape" means the words match.
 *
 * The Settings *surface* sources survive in the map (`settings/surface.tsx`, `route.ts`,
 * `model.tsx`, `general/behavior.ts`), so the routing model and every default below is read
 * from their code rather than inferred:
 *
 * ```
 * rootTabs: general appearance notifications shortcuts projects workspaces
 *           providers models extensions servers experimental about
 * defaultSettings.general.timelineDetail   = timelinePresets[2].value   // compact
 * defaultSettings.general.showTurnDiffs    = false
 * defaultSettings.general.foldUserMessages = false
 * ```
 *
 * Their page bodies (the JSX for each tab) are **not** in the map - only the models, the
 * route parser and the sound-preview machine are. So the rows below are their rows, the
 * grouping is their grouping, and the arrangement is ours.
 */
object SettingsCopy {

    /** `settings.backToApp` */
    const val BACK_TO_APP = "Back to app"

    /** `sidebar.settings` - the sidebar entry, and the heading of the surface it opens. */
    const val TITLE = "Settings"

    /** `settings.search.placeholder` - the field at the top of their dialog. */
    const val SEARCH = "Search"

    // ---- search results, their words ----

    /** `settings.search.results` */
    const val SEARCH_RESULTS = "Settings results"

    /** `settings.search.empty` = `No results for {{query}}` */
    fun searchEmpty(query: String): String = "No results for $query"

    /** `settings.search.empty.query` = `"{{query}}"` - the query is shown quoted. */
    fun quoted(query: String): String = "\"$query\""

    /** `settings.search.refine` */
    const val SEARCH_REFINE = "Narrow your search to see more specific results."

    // ---- section headers, in their own words ----

    /** `settings.general.section.appearance` */
    const val SECTION_APPEARANCE = "Appearance"

    /** `settings.general.section.general` */
    const val SECTION_GENERAL = "General"

    /** `settings.general.section.notifications` */
    const val SECTION_NOTIFICATIONS = "Desktop notifications"

    /** `settings.general.section.sounds` */
    const val SECTION_SOUNDS = "Sound effects"

    /** `settings.notifications.description` */
    const val NOTIFICATIONS_DESCRIPTION = "Choose when to receive notifications and hear sounds"

    // ---- timeline ----

    /** `settings.timeline.title` */
    const val TIMELINE_TITLE = "Timeline"

    /** `settings.timeline.description` */
    const val TIMELINE_DESCRIPTION = "Choose how much detail appears in the session timeline."

    /** `settings.timeline.detail` */
    const val TIMELINE_DETAIL = "Timeline detail"

    /** `settings.timeline.custom` - shown when a stored value equals no preset. */
    const val TIMELINE_CUSTOM = "Custom"

    /** `settings.timeline.row.turnDiffs.title` */
    const val TURN_DIFFS_TITLE = "Changed files"

    /** `settings.timeline.row.turnDiffs.description` */
    const val TURN_DIFFS_DESCRIPTION = "List the files each turn changed when it finishes"

    /** `settings.timeline.row.foldUserMessages.title` */
    const val FOLD_TITLE = "Collapse long messages"

    /** `settings.timeline.row.foldUserMessages.description` */
    const val FOLD_DESCRIPTION = "Show the first few lines. Expand to read your full message."

    /** `settings.timeline.advanced` */
    const val ADVANCED_TITLE = "Advanced"

    /** `settings.timeline.advanced.description` */
    const val ADVANCED_DESCRIPTION = "Set placement and details for each activity category."

    /** `settings.backToSettings` - the chevron on every sub-page. */
    const val BACK_TO_SETTINGS = "Back to settings"

    /** `settings.timeline.visibility.show` */
    const val SHOW = "Show"

    /** `settings.timeline.visibility.hide` */
    const val HIDE = "Hide"

    /** `settings.timeline.group` */
    const val GROUP = "Group"

    /** `settings.timeline.collapse` */
    const val COLLAPSE = "Collapse"

    /** `settings.timeline.grouped.label` = `{{activity}} grouped`. */
    fun groupedLabel(activity: String): String = "$activity grouped"

    /** `settings.timeline.collapsed.label` = `{{activity}} collapsed`. */
    fun collapsedLabel(activity: String): String = "$activity collapsed"

    /** `settings.timeline.visibility.label` = `{{activity}} visibility`. */
    fun visibilityLabel(activity: String): String = "$activity visibility"

    // ---- permissions ----

    /** `command.permissions.autoaccept.enable` */
    const val AUTO_APPROVE_TITLE = "Auto-accept permissions"

    /**
     * `settings.permissions.autoAccept.default.description`, with their second sentence
     * replaced.
     *
     * Theirs ends "Projects can override this in their settings." ZAPP has one project, so
     * there is no override to offer and saying there is would send the reader looking for a
     * control that does not exist. The first sentence is theirs, unchanged.
     */
    const val AUTO_APPROVE_DESCRIPTION =
        "Approve permission requests without asking. Every request is answered as soon as it arrives."

    // ---- this phone ----

    /** Ours: PC's page is `settings.tab.shortcuts`, "Keyboard shortcuts". A phone has none. */
    const val GESTURES_TITLE = "Gestures"

    /** Ours - the phone spelling of `settings.shortcuts.description`. */
    const val GESTURES_DESCRIPTION = "What every tap, long-press and swipe does"

    /** `settings.shortcuts.unassigned` */
    const val UNASSIGNED = "Unassigned"

    /** `settings.shortcuts.reset.button` - reused by the timeline editor, same words, same job. */
    const val RESET_TO_DEFAULTS = "Reset to defaults"

    /** Ours: PC splits this across `projects`, `servers` and `providers`. One of each here. */
    const val WORKSPACE_TITLE = "Workspace"

    /** Ours. */
    const val WORKSPACE_DESCRIPTION = "The server, the folder and the model this phone is using"

    /** `settings.server.section.connection` */
    const val CONNECTION = "Connection"

    /** `settings.server.switcher.label` */
    const val SERVER_LABEL = "Server"

    /** `settings.server.switcher.unreachable` */
    const val UNREACHABLE = "Unreachable"

    /** `settings.server.switcher.unreachable` is "Unreachable"; the positive state has no key. */
    const val CONNECTED = "Connected"

    /** `settings.server.unreachable.title`, their `{{server}}` filled in. */
    fun serverUnreachableTitle(server: String) = "Can't reach $server"

    /** `settings.server.unreachable.description`, their `{{server}}` filled in. */
    fun serverUnreachableDesc(server: String) =
        "$server isn't responding. Its settings will appear here once it reconnects."
    /** `settings.server.unreachable.retry` */
    const val RETRY = "Retry"

    /** `settings.projects.title` */
    const val PROJECTS_TITLE = "Projects"

    /** `settings.providers.title` */
    const val PROVIDERS_TITLE = "Providers"

    /** `command.model.choose` */
    const val CHOOSE_MODEL = "Choose model"

    /** `settings.projects.empty` - a project list with nothing in it. */
    const val NO_PROJECTS = "No projects found"

    // ---- notification and sound rows ----

    /** `settings.general.notifications.agent.title` */
    const val NOTIFY_AGENT = "Agent"

    /** `settings.general.notifications.agent.description` */
    const val NOTIFY_AGENT_DESC = "Show system notification when the agent is complete or needs attention"

    /** `settings.general.notifications.permissions.title` */
    const val NOTIFY_PERMISSIONS = "Permissions"

    /** `settings.general.notifications.permissions.description` */
    const val NOTIFY_PERMISSIONS_DESC = "Show system notification when a permission is required"

    /** `settings.general.notifications.questions.title` */
    const val NOTIFY_QUESTIONS = "Questions"

    /** `settings.general.notifications.questions.description` */
    const val NOTIFY_QUESTIONS_DESC = "Show system notification when the agent asks a question"

    /** `settings.general.notifications.errors.title` */
    const val NOTIFY_ERRORS = "Errors"

    /** `settings.general.notifications.errors.description` */
    const val NOTIFY_ERRORS_DESC = "Show system notification when an error occurs"

    /** `settings.general.sounds.agent.description` */
    const val SOUND_AGENT_DESC = "Play sound when the agent is complete or needs attention"

    /** `settings.general.sounds.permissions.description` */
    const val SOUND_PERMISSIONS_DESC = "Play sound when a permission is required"

    /** `settings.general.sounds.questions.description` */
    const val SOUND_QUESTIONS_DESC = "Play sound when the agent asks a question"

    /** `settings.general.sounds.errors.description` */
    const val SOUND_ERRORS_DESC = "Play sound when an error occurs"

    // ---- about ----

    /** `settings.about.version` = `Version {{version}}`. */
    fun version(name: String): String = "Version $name"

    /**
     * `settings.about.license` - stored, **not rendered**.
     *
     * PC prints it because OpenCode *is* MIT-licensed and ships a `LICENSE` file. ZAPP has no
     * licence file at all, so printing their line would be a claim this repository cannot back
     * up. What is true is the provenance, and that is what the About section shows instead.
     */
    const val LICENSE = "Released under the MIT License"

    /**
     * ZAPP's own About line, replacing theirs.
     *
     * Not a port - a disclosure. The behaviour in this app is a port of OpenCode, and OpenCode
     * is MIT-licensed (`settings.about.license` in their own table), so the attribution is
     * accurate even though ZAPP's own licence is undecided.
     */
    const val ATTRIBUTION = "Behaviour ported from OpenCode, MIT licensed"

    /** `settings.helpFeedback` */
    const val HELP_FEEDBACK = "Help & feedback"

    // ---- the presets, in `timelinePresets` order ----

    /**
     * `settings.timeline.preset.*`, keyed by the id in `timelinePresets`.
     *
     * The order is theirs: everything, detailed, compact, quiet, text-only.
     */
    val presets: List<Pair<String, String>> = listOf(
        "everything" to "Everything",
        "detailed" to "Detailed",
        "compact" to "Compact",
        "quiet" to "Quiet",
        "text-only" to "Messages only",
    )

    /** `settings.timeline.preset.<id>`, or `settings.timeline.custom`. */
    fun presetTitle(id: String): String =
        presets.firstOrNull { it.first == id }?.second ?: TIMELINE_CUSTOM

    /** `settings.timeline.description.<id>`, or `.custom`. */
    fun presetDescription(id: String): String = when (id) {
        "everything" -> "Show all activity separately. Expand shell output, edits, and thinking."
        "detailed" -> "Expand shell output and edits. Show subagents separately and group other activity."
        "compact" -> "Show subagents separately. Group other activity with details collapsed."
        "quiet" -> "Show subagents separately and group edits. Hide other activity."
        "text-only" -> "Hide all activity."
        else -> "Uses advanced settings."
    }

    /** `settings.timeline.summary` = `{{preset}}:` - the label a collapsed row leads with. */
    fun summary(id: String): String = presetTitle(id) + ":"

    // =====================================================================================
    // The full root-tab set (`settings/route.ts:48-61`), in the order the sidebar renders
    // them. Every label below is a key in `runtime/i18n/en.ts` unless the comment says it was
    // read off the shipped window instead - the Pairing page is new enough that its copy is
    // not in the extracted table, and guessing it would be worse than quoting the screenshot.
    // =====================================================================================

    const val PREFERENCES_TITLE = "Preferences"                       // settings.tab.preferences
    const val PREFERENCES_DESC = "Customize preferences and theme and default behavior"
    const val APPEARANCE_TITLE = "Appearance"                         // settings.appearance.*
    const val APPEARANCE_DESC = "Customize theme and fonts"
    const val SHORTCUTS_TITLE = "Shortcuts"                           // settings.tab.shortcuts
    const val SHORTCUTS_DESC = "Customize shortcuts for common actions"
    const val PROVIDERS_DESC = "Connect and manage model providers"
    const val MODELS_TITLE = "Models"                                 // settings.models.title
    const val MODELS_DESC = "Choose which models appear in model picker"
    const val EXTENSIONS_TITLE = "Extensions"                         // settings.tab.extensions
    const val EXTENSIONS_DESC = "Manage extensions available on this server"
    const val PROJECTS_DESC = "Manage projects and their settings"
    const val WORKTREES_TITLE = "Worktrees"                           // settings.tab.workspaces
    const val WORKTREES_DESC = "Review worktrees and manage disk usage"
    const val SERVER_DESC = "Manage this server's connection and preferences"
    const val EXPERIMENTAL_TITLE = "Experimental"                     // settings.tab.experimental
    const val EXPERIMENTAL_DESC = "Try experimental features"
    const val ABOUT_TITLE = "About"                                   // settings.tab.about

    // ---- Preferences --------------------------------------------------------------------

    const val INTERFACE_LAYOUT = "Interface layout"                   // settings.general.row.interfaceMode.title
    const val INTERFACE_LAYOUT_DESC = "Choose how you switch between sessions and keep track of what's running"
    const val TOP_TABS = "Top tabs"                                   // .interfaceMode.top
    const val SIDE_TABS = "Side tabs"                                 // .interfaceMode.side
    const val INBOX = "Inbox"                                         // .interfaceMode.inbox

    const val LANGUAGE = "Language"                                   // settings.general.row.language.title
    const val LANGUAGE_DESC = "Change the display language for OpenCode"

    const val DEFAULT_ENV = "Default environment"                     // settings.workspaces.default.title
    const val DEFAULT_ENV_DESC = "Choose where new sessions start"
    const val LAST_USED = "Last used per project"                     // .default.lastUsed
    const val LOCAL_DIR = "Local directory"                           // .default.local
    const val NEW_WORKTREE = "New worktree"                           // .default.new

    const val SHOW_AGENT = "Show agent"                               // settings.general.row.showCustomAgents.title
    const val SHOW_AGENT_DESC =
        "Switch between agents in the composer. When hidden, defaults to Build agent."

    const val FOLLOW_UP = "Follow-up behavior"                        // .followUpBehavior.title
    const val FOLLOW_UP_DESC =
        "Choose whether to queue follow-ups or steer the current turn. Use Ctrl+Enter to switch."
    const val QUEUE = "Queue"                                         // .followUpBehavior.queue
    const val STEER = "Steer"                                         // .followUpBehavior.steer

    const val PINCH_ZOOM = "Pinch to zoom"                            // .pinchZoom.title
    const val PINCH_ZOOM_DESC = "Allow trackpad pinch and Ctrl-scroll gestures to zoom"
    const val SPELLCHECK = "Spellcheck"                               // .spellcheck.title
    const val SPELLCHECK_DESC = "Underline misspelled words while typing"
    const val BOTTOM_NAV = "Bottom navigation"                        // .mobileTitlebarBottom.title
    const val BOTTOM_NAV_DESC = "Place the title bar at the bottom of the screen on mobile"
    const val WRAP_LINES = "Wrap lines"                               // read off the window: `general.mobileDiffWrap`
    const val WRAP_LINES_DESC = "Wrap long lines in mobile diffs instead of scrolling horizontally"

    const val BACKGROUND_ACTIVITY = "Background activity"             // settings.timeline.row.backgroundActivity.*
    const val BACKGROUND_ACTIVITY_DESC = "Show running subagents and shells above the composer"
    const val HIDDEN = "Hidden"
    const val LEFT = "Left"
    const val CENTER = "Center"
    const val RIGHT = "Right"

    const val TIMELINE_WIDTH = "Timeline width"                       // settings.timeline.row.width.*
    const val TIMELINE_WIDTH_DESC = "Narrower lines are easier to scan. Wider lines show more at once"
    const val FOCUSED = "Focused"
    const val COMFORTABLE = "Comfortable"
    const val WIDE = "Wide"

    // ---- Appearance ---------------------------------------------------------------------

    const val COLOR_SCHEME = "Color scheme"                           // settings.general.row.colorScheme.title
    const val COLOR_SCHEME_DESC = "Choose whether OpenCode follows the system, light, or dark theme"
    const val SYSTEM = "System"
    const val LIGHT = "Light"
    const val DARK = "Dark"

    const val THEME = "Theme"                                         // settings.general.row.theme.title
    const val THEME_DESC = "Customise how OpenCode is themed."
    const val THEME_NAME = "OpenCode"

    const val UI_FONT = "UI Font"                                     // .uiFont.title
    const val UI_FONT_DESC = "Customise the font used throughout the interface"
    const val CODE_FONT = "Code Font"                                 // .font.title
    const val CODE_FONT_DESC = "Customise the font used in code blocks"
    const val TERMINAL_FONT = "Terminal Font"                         // .terminalFont.title
    const val TERMINAL_FONT_DESC = "Customise the font used in the terminal"

    const val ZOOM = "Zoom"                                           // .zoom.title
    const val ZOOM_DESC = "Make text and controls larger across the app"
    const val REDUCE_MOTION = "Reduce motion"                         // .reduceMotion.title
    const val REDUCE_MOTION_DESC = "Reduce animations or match your system"
    const val ON = "On"                                               // .reduceMotion.on
    const val OFF = "Off"                                             // .reduceMotion.off
    const val TABS = "Tabs"                                           // settings.appearance.row.tabs.title
    const val TABS_DESC = "Choose how session tabs are arranged"
    const val HORIZONTAL = "Horizontal"                               // .tabs.horizontal
    const val VERTICAL = "Vertical"                                   // .tabs.vertical

    // ---- Notifications ------------------------------------------------------------------

    const val SOUND_AGENT = "Agent"                                   // settings.general.sounds.agent.title
    const val SOUND_PERMISSIONS = "Permissions"
    const val SOUND_QUESTIONS = "Questions"
    const val SOUND_ERRORS = "Errors"
    const val SOUND_INTERFACE = "Interface"                           // settings.general.sounds.interface.title
    const val SOUND_INTERFACE_DESC =
        "Play subtle sounds when the wordmark appears and when you play with it"
    const val NONE = "None"                                           // sound.option.none

    // ---- Extensions / Projects / Worktrees -----------------------------------------------

    const val MCPS = "MCPs"                                           // settings.extensions.tab.mcps
    const val PLUGINS = "Plugins"                                     // settings.extensions.tab.plugins
    const val SKILLS = "Skills"                                       // settings.extensions.tab.skills
    const val AVAILABLE_ALL = "Available to all projects"             // settings.extensions.availableAll
    const val MANAGE_CONFIG = "Manage in opencode.json"               // settings.extensions.manageConfig
    const val PROJECTS_SEARCH = "Search projects"                     // settings.projects.search.placeholder
    const val OTHER_PROJECTS = "Other projects"                       // settings.projects.other
    const val NO_PROJECTS_YET = "No projects yet"                     // settings.projects.empty.title
    const val NO_PROJECTS_DESC = "Add a project to get started"       // settings.projects.empty.description
    const val ADD_PROJECT = "Add project"                             // read off the window
    const val NO_WORKTREES = "No worktrees yet"                       // settings.workspaces.empty
    const val NO_WORKTREES_DESC = "Worktrees created in OpenCode will appear here"

    // ---- Server / Pairing ---------------------------------------------------------------

    const val ADD_SERVER = "Add server"                               // read off the window
    const val TERMINAL_SHELL = "Terminal shell"                       // settings.general.row.shell.title
    const val TERMINAL_SHELL_DESC = "Shell used by the terminal and agent tools"
    const val AUTO_DEFAULT = "Auto (Default)"                         // .shell.autoDefault

    // The Pairing page post-dates the extracted string table; these are read off the window.
    const val PAIRING_TITLE = "Pairing"
    const val PAIRING_DESC = "Connect another device to this machine's OpenCode server."
    const val PAIR_DEVICE = "Pair a device"
    const val PAIR_DEVICE_DESC =
        "Connect a phone, tablet, or another computer from anywhere through an end-to-end encrypted tunnel."
    const val PAIR_DEVICE_BUTTON = "Pair device"
    const val REMOTE_ACCESS = "Remote access"
    const val REMOTE_ACCESS_DESC =
        "Paired devices reach this computer's OpenCode server over the internet. " +
            "Turning it off disconnects them until you pair them again."
    const val KEEP_SCREEN = "Keep screen active"
    const val KEEP_SCREEN_DESC =
        "Prevent this computer's display from sleeping while OpenCode is running."

    // ---- Experimental -------------------------------------------------------------------

    const val NEW_TIMELINE = "New timeline"                           // settings.experimental.row.newTimeline.title
    const val NEW_TIMELINE_DESC =
        "A redesigned session timeline that groups what the agent did, with one status line below the turn"
    const val NEW_QUESTION_UI = "New question UI"                     // .newQuestionUI.title
    const val NEW_QUESTION_UI_DESC = "Preview the keyboard-first question dock"
    const val SIDEBAR_ORGANIZE = "Organize vertical sidebar"          // .sidebarOrganize.title
    const val SIDEBAR_ORGANIZE_DESC =
        "Choose how to organize sessions in side tabs. On desktop, Projects and Customize replace " +
            "Home with Settings in the sidebar."
    const val ONE_LIST = "One list"                                   // .sidebarOrganize.list
    const val RECENT = "Recent"                                       // .sidebarOrganize.recent
    const val CUSTOMIZE = "Customize"                                 // .sidebarOrganize.customize

    // ---- About --------------------------------------------------------------------------

    const val ABOUT_DESCRIPTION = "OpenCode, the open source coding agent"  // settings.about.description
    const val ABOUT_TAGLINE = "AI can't build great software, without you"  // settings.about.tagline
    const val ABOUT_TYPESET = "Typeset in Inter and IBM Plex Mono"          // settings.about.typeset
    const val ABOUT_PUBLISHED = "First published in Missouri, USA"          // settings.about.firstPublished
    const val ABOUT_WEBSITE = "www.opencode.ai"                             // settings.about.website
    const val ABOUT_TRADEMARK =
        "OpenCode is a registered trademark of Anomaly Innovations, Inc."   // settings.about.trademark
    const val ABOUT_COPYRIGHT = "© Anomaly Innovations, Inc."               // settings.about.copyright

    /** `settings.models.enableAll` / `settings.models.disableAll`. */
    const val ENABLE_ALL = "Enable all models"
    const val DISABLE_ALL = "Disable all models"

    /** `settings.plugins.check` / `.update` / `.reload`. */
    const val CHECK_UPDATES = "Check for updates"
    const val UPDATE = "Update"
    const val RELOAD = "Reload"
    const val BUILT_IN = "Built-in"                                   // settings.plugins.builtin

    /** `settings.about.version` already exists as [version]. */
    const val VERSION_PREFIX = "Version "

    /**
     * The sound catalogue, `sound.option.*`, in their own order.
     *
     * The id is what `sounds.agent` stores (`"staplebops-01"`); the label is what the row
     * prints (`"Staplebops 01"`). Both spellings come from the table, so the stored value and
     * the visible text can never drift apart.
     */
    val soundOptions: List<Pair<String, String>> = buildList {
        add("none" to NONE)
        for (i in 1..10) add("alert-${i.toString().padStart(2, '0')}" to "Alert ${i.toString().padStart(2, '0')}")
        // Their **id** keeps the hyphen (`bip-bop-01`) while the label key drops it
        // (`sound.option.bipbop01`). We stored `bipbop-01`, which is not an id their table has, so
        // a value saved under it could never resolve to a file.
        for (i in 1..10) add("bip-bop-${i.toString().padStart(2, '0')}" to "Bip-bop ${i.toString().padStart(2, '0')}")
        for (i in 1..7) add("staplebops-${i.toString().padStart(2, '0')}" to "Staplebops ${i.toString().padStart(2, '0')}")
        for (i in 1..12) add("nope-${i.toString().padStart(2, '0')}" to "Nope ${i.toString().padStart(2, '0')}")
        for (i in 1..6) add("yup-${i.toString().padStart(2, '0')}" to "Yup ${i.toString().padStart(2, '0')}")
    }

    fun soundLabel(id: String): String =
        soundOptions.firstOrNull { it.first == id }?.second ?: id

    // =====================================================================================
    // Phone-only vocabulary.
    //
    // Every constant above is a key in their table. The ones below are not: they exist
    // because a phone surface needs a word their desktop never prints, and inventing one
    // silently would be worse than naming it here. Each is a disclosed extension.
    // =====================================================================================

    /** `settings.tab.servers` - "Servers" on the rail. */
    const val TAB_SERVERS = "Servers"

    /** `settings.tab.notifications` - the rail label; [NOTIFICATIONS_DESCRIPTION] is the page line. */
    const val NOTIFICATIONS_TITLE = "Notifications"

    /** Accessibility name for the search field's clear button. */
    const val CLEAR = "Clear"

    /**
     * Their appearance page offers System / Light / Dark. ZAPP paints one dark canvas on
     * purpose (`Theme.kt`), so the row is shown with the choice stored and this line under it.
     */
    const val DARK_ONLY = "ZAPP renders dark only"

    /**
     * Under every control the phone stores but does not act on. Their desktop has no such
     * note because every control there works; a phone row that silently did nothing would be
     * a lie, so the row says so.
     */
    const val NOT_USED = "Not used on this phone"

    const val LOADING = "Loading"

    const val APP_NAME = "ZAPP-Net"

    // ---- data-page empty states ---------------------------------------------------------

    const val PROJECTS_NOTE =
        "A project appears once the server has seen a directory. Open a session to register one."
    const val WORKTREES_NOTE =
        "Worktrees are created on the server. Deleting one is not wired on this phone yet."
    const val PROVIDERS_CONNECTED = "Connected providers"    // settings.providers.section.connected
    const val PROVIDERS_EMPTY = "No connected providers"     // settings.providers.connected.empty
    const val PROVIDERS_EMPTY_DESC = "The server reports no provider yet"
    const val PROVIDERS_NOTE =
        "Accounts and keys are managed by the server's own configuration. This page reads them."
    const val MODELS_EMPTY = "No models reported by the server"
    const val NO_MCPS = "No MCP servers configured"
    const val NO_PLUGINS = "No plugins loaded"
    const val NO_SKILLS = "No skills available"
    const val SERVER_RETRY_DESC = "Test the connection again with the stored device token"
    const val SERVERS_NOTE =
        "ZAPP talks to one server: this phone's own opencode serve. Adding a second is not built."
    const val PAIRED_DEVICE = "This device"
    const val PAIRED_DEVICE_DESC =
        "The device token from setup is the server password. Nothing is typed and nothing is shown."
    const val PAIRING_SERVER = "Server"
    const val PAIRING_STATE = "State"
    const val PAIRING_NOTE =
        "Pairing another device needs opencode pair on the server; the phone has no QR route yet."
}
