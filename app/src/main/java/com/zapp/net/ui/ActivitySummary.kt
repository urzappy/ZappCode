package com.zapp.net.ui

/**
 * The capsule above the composer that says how much background work is running.
 *
 * Ported from their i18n context, `app/src/runtime/i18n/language.tsx:701-767`. Their two
 * functions, verbatim:
 *
 * ```ts
 * const activitySummary = (counts) => {
 *   const subagents = counts.subagents ? plural("session.activity.subagents", counts.subagents) : undefined
 *   const shells    = counts.shells    ? plural("session.activity.shells",    counts.shells)    : undefined
 *   if (subagents && shells) return t("session.activity.summary", { subagents, shells })
 *   return subagents ?? shells ?? ""
 * }
 *
 * const activitySummaryRich = (counts, render) => {
 *   if (!counts.subagents || !counts.shells) return render(activitySummary(counts))
 *   return rich("session.activity.summary", {
 *     subagents: render(activitySummary({ subagents: counts.subagents, shells: 0 })),
 *     shells:    render(activitySummary({ subagents: 0, shells: counts.shells })),
 *   })
 * }
 * ```
 *
 * Their strings, from `en.ts:1383-1403`:
 *
 * ```
 *   session.activity.subagents.one   = "{{count}} subagent"
 *   session.activity.subagents.other = "{{count}} subagents"
 *   session.activity.shells.one      = "{{count}} shell"
 *   session.activity.shells.other    = "{{count}} shells"
 *   session.activity.summary         = "{{subagents}} · {{shells}}"
 * ```
 *
 * Three rules fall out of that and all three were missing here:
 *
 *  1. **Zero is silent.** A count of 0 yields `undefined`, not `"0 shells"`. Their
 *     `counts.shells ? … : undefined` is a falsy test, so an empty group disappears from the
 *     sentence entirely rather than being named.
 *  2. **Both groups share one capsule**, joined by `" · "`. ZAPP drew shells only, so a turn
 *     running two subagents and nothing else showed no capsule at all.
 *  3. **Only running work counts.** The setting's own description says it
 *     (`settings.timeline.row.backgroundActivity.description`: *"Show running subagents and
 *     shells above the composer"*). ZAPP counted `shells.size`, which included every shell the
 *     session had ever finished - so the capsule stayed up, and kept climbing, after the work
 *     was done.
 *
 * Kept pure so it can be checked without a device, the same way [ShellChrome],
 * [NotifyPolicy] and [TimelineDetail] are.
 */
object ActivitySummary {

    const val SUBAGENT_ONE = "subagent"
    const val SUBAGENT_MANY = "subagents"
    const val SHELL_ONE = "shell"
    const val SHELL_MANY = "shells"

    /** `session.activity.summary` - the separator between the two groups. */
    const val SEPARATOR = " \u00B7 "

    /** `plural("session.activity.subagents", n)` - null when the group is empty. */
    fun subagents(n: Int): String? = when {
        n <= 0 -> null
        n == 1 -> "1 $SUBAGENT_ONE"
        else -> "$n $SUBAGENT_MANY"
    }

    /** `plural("session.activity.shells", n)` - null when the group is empty. */
    fun shells(n: Int): String? = when {
        n <= 0 -> null
        n == 1 -> "1 $SHELL_ONE"
        else -> "$n $SHELL_MANY"
    }

    /**
     * `activitySummary(counts)`. Empty string when there is nothing running - their
     * `subagents ?? shells ?? ""`, so the caller can test `isEmpty()` and draw nothing.
     */
    fun label(subagents: Int, shells: Int): String {
        val s = subagents(subagents)
        val h = shells(shells)
        if (s != null && h != null) return s + SEPARATOR + h
        return s ?: h ?: ""
    }

    /** `activitySummaryRich` flattens to the same string on a phone: both groups, one line. */
    fun parts(subagents: Int, shells: Int): List<String> =
        listOfNotNull(subagents(subagents), shells(shells))

    /** Their plural rule is a plain `n == 1` test for these two keys in every shipped locale. */
    fun isPlural(n: Int): Boolean = n > 1

    // ---- `session.activity.shell.*` and `session.activity.section.*` (en.ts:1387-1405) ----
    //
    // The background card and its panel. These are theirs word for word; the panel used to
    // say "Kill", "done" and "exit 1", none of which exists in their dictionary.
    //
    //   session.activity.shell.back      = "Back to background work"
    //   session.activity.shell.stop      = "Stop"
    //   session.activity.shell.stopping  = "Stopping."
    //   session.activity.shell.exited    = "Exited"
    //   session.activity.shell.exitCode  = "Exited with code {{code}}"
    //   session.activity.shell.timeout   = "Timed out"
    //   session.activity.shell.killed    = "Stopped"
    //   session.activity.shell.empty     = "No output yet"
    //   session.activity.section.subagents = "Subagents"
    //   session.activity.section.shells    = "Shells"

    const val BACK = "Back to background work"
    const val STOP = "Stop"
    const val STOPPING = "Stopping."
    const val EXITED = "Exited"
    const val TIMED_OUT = "Timed out"
    const val KILLED = "Stopped"
    const val NO_OUTPUT = "No output yet"
    const val SECTION_SUBAGENTS = "Subagents"
    const val SECTION_SHELLS = "Shells"

    /** `session.activity.shell.exitCode` with `{{code}}` filled in. */
    fun exitedWith(code: Int): String = "Exited with code $code"

    /**
     * The status word on a background shell card.
     *
     * Lifted from the shipped bundle, `out/renderer/assets/route-9_N8wbSw.js` at 648 700
     * (sha256 `2d10df76…49af5f`), which REA graphed as `ev_8f5dd3c0…c5cbf6`. Their function,
     * verbatim:
     *
     * ```js
     * function RL(e, t) {
     *   return e.status === `timeout` ? t(`session.activity.shell.timeout`)
     *        : e.status === `killed`  ? t(`session.activity.shell.killed`)
     *        : e.exit === void 0      ? t(`session.activity.shell.exited`)
     *        :                          t(`session.activity.shell.exitCode`, { code: String(e.exit) })
     * }
     * ```
     *
     * Three things this corrected in our port:
     *
     *   * **exit 0 is named.** The first version returned a bare "Exited" for a zero exit.
     *     Theirs has no such case - `e.exit === void 0` is the *only* path to "Exited", so a
     *     clean run reads **"Exited with code 0"**.
     *   * **only `timeout` and `killed` outrank an exit.** Not `cancelled`, not `interrupted`.
     *     Their `ended` adapter (`route.js` 687 500) is what normalises the wire: a shell the
     *     server still calls `running` when asked for its end state is reported as `killed`,
     *     so the stored status is one of `running | exited | killed | timeout` and no other
     *     spelling can reach this function.
     *   * **no word for a running shell.** The pulsing dot carries that state, as above.
     */
    fun shellStatus(running: Boolean, exit: Int?, status: String?): String {
        if (running) return ""
        val s = status.orEmpty().lowercase()
        // Their `ended` adapter's normalisation, applied on our side because `/api/shell`
        // reports the raw wire status and has no adapter of its own.
        val norm = when (s) {
            "timeout", "timedout", "timed_out" -> "timeout"
            "killed", "cancelled", "canceled", "interrupted", "stopped", "aborted" -> "killed"
            else -> "exited"
        }
        if (norm == "timeout") return TIMED_OUT
        if (norm == "killed") return KILLED
        if (exit == null) return EXITED
        return exitedWith(exit)
    }

    /**
     * Their `data-failed` attribute on the same row (`route.js` 648 900):
     *
     * ```js
     * z(n, `data-failed`, e().status === `timeout` || e().status === `exited` && !!e().exit || void 0)
     * ```
     *
     * `!!e().exit` is the part worth copying: **exit 0 is not a failure.** A clean run is
     * `exited` with a zero exit, which is falsy, so it stays in the normal colour.
     */
    fun failed(status: String?, exit: Int?): Boolean {
        val s = status.orEmpty().lowercase()
        if (s in setOf("timeout", "timedout", "timed_out")) return true
        if (s in setOf("exited", "completed", "done")) return exit != null && exit != 0
        return false
    }

    /** How many trailing output lines their panel keeps: `slice(-14)` (`route.js` 649 100). */
    const val TAIL_LINES = 14

    /** Their tail: CRLF folded to LF, trimmed at the end, last [TAIL_LINES] lines. */
    fun tail(output: String): String =
        output.replace("\r\n", "\n").replace("\r", "\n").trimEnd()
            .split("\n").takeLast(TAIL_LINES).joinToString("\n")
}
