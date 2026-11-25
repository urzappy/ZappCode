package com.zapp.net

/**
 * PORT of `@opencode/util/session-title-fallback` (`util/src/session-title-fallback.ts`).
 *
 * Desktop never shows a raw stored title:
 *  - `sessionTabTitle` (`shell/titlebar/tab-title.ts:9`) shows the tab fallback when the title
 *    is missing **or** is one of the historical timestamped fallbacks;
 *  - `displayLabel` (`:37-43`) collapses `New session - <ISO>` / `Child session - <ISO>`
 *    back to `New session` / `Child session`, and uses the parent flag when there is no title.
 *
 * That is why our `"(untitled)"` label was wrong: the PC's string for the same state is
 * `New session` (or `Child session`) — the same label a draft tab carries.
 */
object SessionTitle {

    /** `const pattern = /^(New session|Child session) - \d{4}-...Z$/` (`:5`). */
    private val timestamped = Regex(
        """^(New session|Child session) - \d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$""",
    )

    /** `isFallbackTitle` (`:49-53`). */
    fun isFallback(title: String?): Boolean = title == null || timestamped.matches(title)

    /** `displayLabel` (`:37-43`). */
    fun display(title: String?, hasParent: Boolean): String {
        if (title == null) return if (hasParent) "Child session" else "New session"
        val hit = timestamped.find(title) ?: return title
        return hit.groupValues[1]
    }

    /** `sessionTabTitle` (`tab-title.ts:9`): what a tab strip shows. */
    fun tab(title: String?, hasParent: Boolean): String =
        if (isFallback(title)) (if (hasParent) "Child session" else "New session")
        else display(title, hasParent)
}
