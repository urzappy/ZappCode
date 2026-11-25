package com.zapp.net.ui

/**
 * PORT of `settings.permissions.tool.*` - the fourteen tool descriptions from `en.ts:1284-1311`.
 *
 * These are the only human words PC has for *what a tool does*, and they are exactly what a
 * permission card needs: the wire sends `action = "bash"`, a person needs "Run shell commands".
 * Their settings page lists the same fourteen keys with the same titles, so the copy a reader sees
 * here is the copy they would see there.
 *
 * The **arrangement** is ours - PC renders the list as a settings tree; ZAPP puts the entry on the
 * card at the moment the request arrives. The words, the titles and the order are theirs.
 *
 * `action` is matched exactly. Anything unknown is shown raw, never guessed at: inventing a
 * friendly name for a tool we have not seen would be a lie about what is being asked.
 */
object PermissionCopy {

    /** One row of their table. `key` is the `action` the server sends. */
    data class Tool(val key: String, val title: String, val description: String)

    /** `settings.permissions.tool.*`, in the order they appear in `en.ts`. */
    val tools: List<Tool> = listOf(
        Tool("read", "Read", "Reading a file (matches the file path)"),
        Tool("edit", "Edit", "Modify files, including edits, writes, and patches"),
        Tool("glob", "Glob", "Match files using glob patterns"),
        Tool("grep", "Grep", "Search file contents using regular expressions"),
        Tool("list", "List", "List files within a directory"),
        Tool("bash", "Bash", "Run shell commands"),
        Tool("task", "Task", "Launch sub-agents"),
        Tool("skill", "Skill", "Load a skill by name"),
        Tool("lsp", "LSP", "Run language server queries"),
        Tool("todowrite", "Todo Write", "Update the todo list"),
        Tool("webfetch", "Web Fetch", "Fetch content from a URL"),
        Tool("websearch", "Web Search", "Search the web"),
        Tool("external_directory", "External Directory", "Access files outside the project directory"),
        Tool("doom_loop", "Doom Loop", "Detect repeated tool calls with identical input"),
    )

    fun find(action: String): Tool? = tools.firstOrNull { it.key == action }

    /**
     * The heading a card shows. Their title when the action is known, the raw action otherwise -
     * an unknown tool is still named, just not translated.
     */
    fun title(action: String): String = find(action)?.title ?: action

    /**
     * The line under the heading, or null when there is nothing true to say.
     *
     * An unknown action gets no description rather than the action repeated: the heading already
     * carries it, and "bash" under "bash" would be noise.
     */
    fun description(action: String): String? = find(action)?.description

    /** `notification.permission.title` - the card's own heading, above the tool's. */
    const val HEADING = "Permission required"

    // ---- the card's three decisions (`ui.permission.*`) ----

    /** `ui.permission.deny` */
    const val DENY = "Deny"

    /** `ui.permission.allowAlways` */
    const val ALLOW_ALWAYS = "Allow always"

    /** `ui.permission.allowOnce` */
    const val ALLOW_ONCE = "Allow once"

    /**
     * Their `permission-input` rows: `request.metadata.input` rendered as `key: value`.
     *
     * `JSON.stringify` is their formatter, and it quotes strings - so a string primitive is used
     * raw and everything else keeps its JSON shape. A missing `input`, or one that is not an
     * object, yields no rows rather than a guess.
     */
    fun inputLines(metadata: kotlinx.serialization.json.JsonObject?): List<String> {
        val input = metadata?.get("input") as? kotlinx.serialization.json.JsonObject ?: return emptyList()
        return input.entries.map { (key, value) -> "$key: ${valueText(value)}" }
    }

    /** `fj(t) ? t : JSON.stringify(t)` - a string primitive raw, anything else as JSON. */
    private fun valueText(value: kotlinx.serialization.json.JsonElement): String =
        if (value is kotlinx.serialization.json.JsonPrimitive && value.isString) value.content
        else value.toString()


    // ---- auto-accept toasts (`toast.permissions.autoaccept.*`) ----

    /** `toast.permissions.autoaccept.on.title` */
    const val TOAST_ON_TITLE = "Auto-accepting permissions"

    /** `toast.permissions.autoaccept.on.description` */
    const val TOAST_ON_DESCRIPTION = "Permission requests will be automatically approved"

    /** `toast.permissions.autoaccept.off.title` */
    const val TOAST_OFF_TITLE = "Stopped auto-accepting permissions"

    /** `toast.permissions.autoaccept.off.description` */
    const val TOAST_OFF_DESCRIPTION = "Permission requests will require approval"

    /** `command.permissions.autoaccept.enable` / `.disable` - the command palette's two halves. */
    fun toastTitle(on: Boolean): String = if (on) TOAST_ON_TITLE else TOAST_OFF_TITLE

    fun toastDescription(on: Boolean): String =
        if (on) TOAST_ON_DESCRIPTION else TOAST_OFF_DESCRIPTION

    /** `command.permissions.autoaccept.enable` - the palette row while auto-accept is off. */
    const val COMMAND_ENABLE = "Auto-accept permissions"

    /** `command.permissions.autoaccept.disable` - the palette row while it is on. */
    const val COMMAND_DISABLE = "Stop auto-accepting permissions"
}
