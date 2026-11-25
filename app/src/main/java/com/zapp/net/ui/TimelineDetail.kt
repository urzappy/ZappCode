package com.zapp.net.ui

/**
 * PORT of `session-ui/src/timeline/detail.ts` — the contract that decides which rows a
 * transcript turn shows, and whether they stand alone, fold into the run, or disappear.
 *
 * Everything here is pure so it can be checked off-device. The renderer only asks it
 * questions; it never second-guesses the answer.
 */
object TimelineDetail {

    /** `timelineCategories`, in their order. The order matters for preset matching. */
    val categories = listOf("shell", "edit", "thinking", "subagents", "notices", "tools", "commentary")

    enum class Placement { Separate, Grouped, Hidden }

    enum class Expansion { Collapsed, Expanded }

    /**
     * One category's rule. `details` is null exactly when the category has no `details`
     * key on the wire (`subagents`, `notices`, `tools`, `commentary`), which
     * `timelinePreset` checks with `"details" in current`.
     */
    data class Rule(val placement: Placement, val details: Expansion? = null)

    /** `TimelineDetail`. */
    data class Detail(
        val shell: Rule,
        val edit: Rule,
        val thinking: Rule,
        val subagents: Rule,
        val notices: Rule,
        val tools: Rule,
        val commentary: Rule,
    ) {
        fun of(category: String): Rule = when (category) {
            "shell" -> shell
            "edit" -> edit
            "thinking" -> thinking
            "subagents" -> subagents
            "notices" -> notices
            "tools" -> tools
            "commentary" -> commentary
            else -> error("unknown timeline category: $category")
        }
    }

    private fun sep(details: Expansion) = Rule(Placement.Separate, details)
    private fun grp(details: Expansion) = Rule(Placement.Grouped, details)
    private fun hid(details: Expansion) = Rule(Placement.Hidden, details)
    private fun only(p: Placement) = Rule(p)

    /** `timelinePresets`, verbatim and in order. */
    val presets: List<Pair<String, Detail>> = listOf(
        "everything" to Detail(
            shell = sep(Expansion.Expanded),
            edit = sep(Expansion.Expanded),
            thinking = sep(Expansion.Expanded),
            subagents = only(Placement.Separate),
            notices = only(Placement.Separate),
            tools = only(Placement.Separate),
            commentary = only(Placement.Separate),
        ),
        "detailed" to Detail(
            shell = sep(Expansion.Expanded),
            edit = sep(Expansion.Expanded),
            thinking = grp(Expansion.Collapsed),
            subagents = only(Placement.Separate),
            notices = only(Placement.Grouped),
            tools = only(Placement.Grouped),
            commentary = only(Placement.Separate),
        ),
        "compact" to Detail(
            shell = grp(Expansion.Collapsed),
            edit = grp(Expansion.Collapsed),
            thinking = grp(Expansion.Collapsed),
            subagents = only(Placement.Separate),
            notices = only(Placement.Grouped),
            tools = only(Placement.Grouped),
            commentary = only(Placement.Separate),
        ),
        "quiet" to Detail(
            shell = hid(Expansion.Collapsed),
            edit = grp(Expansion.Collapsed),
            thinking = hid(Expansion.Collapsed),
            subagents = only(Placement.Separate),
            notices = only(Placement.Hidden),
            tools = only(Placement.Hidden),
            commentary = only(Placement.Separate),
        ),
        "text-only" to Detail(
            shell = hid(Expansion.Collapsed),
            edit = hid(Expansion.Collapsed),
            thinking = hid(Expansion.Collapsed),
            subagents = only(Placement.Hidden),
            notices = only(Placement.Hidden),
            tools = only(Placement.Hidden),
            commentary = only(Placement.Separate),
        ),
    )

    /** `defaultSettings.general.timelineDetail` = `{ ...timelinePresets[2].value }` = compact. */
    val default: Detail = presets[2].second

    /**
     * `timelinePreset(value)` — the preset id a hand-edited value still equals, or null.
     *
     * Their comparison is deliberately loose in one direction: when the **current** rule is
     * `hidden`, or carries no `details`, its expansion is ignored. So a quiet preset with
     * `shell.details: expanded` still reads as `quiet`, because hidden rows have no
     * expansion to disagree about.
     */
    fun presetId(value: Detail): String? = presets.firstOrNull { (_, expected) ->
        categories.all { c ->
            val cur = value.of(c)
            val exp = expected.of(c)
            cur.placement == exp.placement &&
                (
                    cur.placement == Placement.Hidden ||
                        cur.details == null ||
                        (exp.details != null && cur.details == exp.details)
                    )
        }
    }?.first

    /** `timelineCategory(content)` — text is prose, so it has no category at all. */
    fun category(type: String, name: String?): String? = when {
        type == "text" -> null
        type == "reasoning" -> "thinking"
        name in setOf("shell", "execute", "bash") -> "shell"
        name in setOf("edit", "write", "patch", "apply_patch") -> "edit"
        name in setOf("subagent", "task") -> "subagents"
        else -> "tools"
    }

    /**
     * `shellResultFailed(metadata)` — a shell completion reports the process outcome in
     * metadata, not in the tool status.
     */
    fun shellResultFailed(timeout: Boolean, exit: Int?): Boolean = timeout || (exit != null && exit != 0)

    /**
     * `timelineNoticeRequired(message)` — the notices a `hidden` placement must still show.
     *
     * A compaction only speaks while it is not completed; a synthetic notice only when it
     * carries an error, or a shell that finished with a failure.
     */
    fun noticeRequired(
        type: String,
        status: String?,
        state: String?,
        source: String?,
        timeout: Boolean = false,
        exit: Int? = null,
    ): Boolean {
        if (type == "compaction") return status != "completed"
        if (type != "synthetic") return false
        return state == "error" ||
            (source == "shell" && state == "completed" && shellResultFailed(timeout, exit))
    }

    /**
     * A category's rule replaced, the rest untouched - the whole of the advanced editor.
     *
     * `Detail` is a fixed record on their side too (`timelineDetailSchema` has one key per
     * category), so an editor cannot invent a category; it can only move one of the seven.
     */
    fun with(value: Detail, category: String, rule: Rule): Detail = when (category) {
        "shell" -> value.copy(shell = rule)
        "edit" -> value.copy(edit = rule)
        "thinking" -> value.copy(thinking = rule)
        "subagents" -> value.copy(subagents = rule)
        "notices" -> value.copy(notices = rule)
        "tools" -> value.copy(tools = rule)
        "commentary" -> value.copy(commentary = rule)
        else -> error("unknown timeline category: $category")
    }

    /**
     * Whether a category carries a `details` key at all.
     *
     * Read off the presets rather than hard-coded: their schema is
     * `Persistence.optional(...)` per category, and the presets are the only place the
     * answer is visible in the extract. `shell`, `edit` and `thinking` have one; the other
     * four do not.
     */
    fun hasDetails(category: String): Boolean =
        presets.any { it.second.of(category).details != null }

    /**
     * Wire form for a hand-edited `Detail`, so the advanced editor can persist one.
     *
     * This is **ours**, not theirs: desktop keeps the object in its settings JSON, which a
     * phone has no reason to reproduce. `shell=S1,edit=G0,...` - one letter for placement
     * (`S`eparate / `G`rouped / `H`idden), one for details (`1` expanded, `0` collapsed,
     * `-` no details key). Every preset round-trips through it, which is what the test
     * checks, so a saved custom value can never be a shape the renderer cannot read.
     */
    fun encode(value: Detail): String =
        categories.joinToString(",") { c -> "$c=${code(value.of(c))}" }

    private fun code(rule: Rule): String {
        val p = when (rule.placement) {
            Placement.Separate -> "S"
            Placement.Grouped -> "G"
            Placement.Hidden -> "H"
        }
        val d = when (rule.details) {
            Expansion.Expanded -> "1"
            Expansion.Collapsed -> "0"
            null -> "-"
        }
        return p + d
    }

    /** Inverse of [encode]. Null when the string is not a complete, well-formed detail. */
    fun decode(text: String): Detail? {
        val map = HashMap<String, Rule>()
        for (part in text.split(",")) {
            val eq = part.indexOf('=')
            if (eq <= 0 || eq == part.length - 1) continue
            val category = part.substring(0, eq)
            val body = part.substring(eq + 1)
            if (category !in categories || body.length != 2) continue
            val placement = when (body[0]) {
                'S' -> Placement.Separate
                'G' -> Placement.Grouped
                'H' -> Placement.Hidden
                else -> null
            } ?: continue
            val details = when (body[1]) {
                '1' -> Expansion.Expanded
                '0' -> Expansion.Collapsed
                '-' -> null
                else -> return null
            }
            map[category] = Rule(placement, details)
        }
        if (map.size != categories.size) return null
        return Detail(
            shell = map["shell"] ?: return null,
            edit = map["edit"] ?: return null,
            thinking = map["thinking"] ?: return null,
            subagents = map["subagents"] ?: return null,
            notices = map["notices"] ?: return null,
            tools = map["tools"] ?: return null,
            commentary = map["commentary"] ?: return null,
        )
    }

    /**
     * `timelineNoticePlacement(message, detail)`.
     *
     * Background work reports its outcome as its own row where it finished, never folded
     * into a group; a subagent's outcome follows the subagent setting.
     */
    fun noticePlacement(
        type: String,
        status: String?,
        state: String?,
        source: String?,
        detail: Detail,
        timeout: Boolean = false,
        exit: Int? = null,
    ): Placement {
        val placement = if (source == "subagent") detail.subagents.placement else detail.notices.placement
        if (placement == Placement.Hidden) {
            return if (noticeRequired(type, status, state, source, timeout, exit)) Placement.Separate
            else Placement.Hidden
        }
        return if (source == "subagent" || source == "shell") Placement.Separate else placement
    }

    /**
     * `place.ts hidesActivity(detail)` — whether the setting hides all of the agent's
     * activity, as the `text-only` preset does. Only that preset does in the shipped
     * table, which is why the `Worked` row exists at all.
     */
    fun hidesActivity(detail: Detail): Boolean =
        listOf("shell", "edit", "thinking", "subagents", "tools")
            .all { detail.of(it).placement == Placement.Hidden }
}
