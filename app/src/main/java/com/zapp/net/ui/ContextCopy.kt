package com.zapp.net.ui

/**
 * The Context tab's copy, verbatim.
 *
 * Source: `index-BWsziAR3.js` @ 552 743 - their i18n block for `tab.title`, `usage.*`,
 * `systemPrompt.title`, `rawMessages.title`, `export.session`, `stats.*` and `export.*`.
 *
 * One thing decides the label on this screen: their own phone build calls the tab
 * **`mobile.title: "Usage"`**, not `Context`. ZAPP is a phone build, so it says `Usage`.
 *
 * The numbers and dates that go beside this copy are formatted by
 * [com.zapp.net.api.ContextStats], which is where `se(locale)` was transcribed - keeping one
 * implementation means a row can be tested without a Compose tree.
 */
object ContextCopy {

    /** `tab.title` - the desktop label. */
    const val TAB_TITLE = "Context"

    /** `mobile.title` - what their phone build shows, and what this app shows. */
    const val MOBILE_TITLE = "Usage"

    // `usage.*`
    const val TOKENS = "Tokens"
    const val USAGE = "Context"
    const val COST = "Cost"
    const val VIEW = "View context usage"
    const val TOGGLE = "Toggle session context"

    const val SYSTEM_PROMPT = "System Prompt"
    const val RAW_MESSAGES = "Raw messages"
    const val EXPORT_SESSION = "Export session"

    // `export.*`
    const val EXPORT_OK_TITLE = "Session exported"
    fun exportOkBody(filename: String) = "Saved session to $filename"
    const val EXPORT_FAIL_TITLE = "Failed to export session"
    const val EXPORT_FAIL_BODY = "An error occurred while exporting the session"

    // `stats.*` - the sixteen rows, in their order (`tab-CbfQLfWC.js` `H`).
    const val STAT_SESSION = "Session"
    const val STAT_MESSAGES = "Messages"
    const val STAT_PROVIDER = "Provider"
    const val STAT_MODEL = "Model"
    const val STAT_LIMIT = "Context Limit"
    const val STAT_TOTAL_TOKENS = "Total Tokens"
    const val STAT_USAGE = "Usage"
    const val STAT_INPUT_TOKENS = "Input Tokens"
    const val STAT_OUTPUT_TOKENS = "Output Tokens"
    const val STAT_REASONING_TOKENS = "Reasoning Tokens"
    const val STAT_CACHE_TOKENS = "Cache Tokens (read/write)"
    const val STAT_USER_MESSAGES = "User Messages"
    const val STAT_ASSISTANT_MESSAGES = "Assistant Messages"
    const val STAT_TOTAL_COST = "Total Cost"
    const val STAT_SESSION_CREATED = "Session Created"
    const val STAT_LAST_ACTIVITY = "Last Activity"
}
