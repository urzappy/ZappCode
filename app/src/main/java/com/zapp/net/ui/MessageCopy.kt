package com.zapp.net.ui

/**
 * `ui.message.*`, `ui.tool.execute.*`, `ui.toolErrorCard.*` and the `ui.common` plural pair -
 * the wording of every action a message offers.
 *
 * **Proven, not guessed.** Every string below was read out of the shipped bundle's i18n table
 * (`main-CLeW75FU.js.map`, `ui/src/i18n/en.ts` at byte ~3 919 000) and every behaviour out of
 * the built renderer chunk (`out/renderer/assets/route-CMUQLRzL.js`, the message component at
 * byte ~63 700-84 400). The component *source* is not in the extract - only `en.ts` mentions
 * these keys - so this file is the port of the compiled behaviour, which is the real one.
 *
 * Three things here are exact ports of logic that used to be ours:
 *
 * 1. **The fold** (`Vl = { over: 12, show: 8 }`). A user message taller than 12 lines is cut to
 *    8 line-heights with a 40px fade and a `Read more` toggle. ZAPP showed the whole thing.
 * 2. **The action gate** (`Gc` / `Wc`). A menu item is disabled when another action is already
 *    running, or when the capability it needs does not hold. ZAPP had no gate at all, so
 *    tapping `Fork from here` twice could fire two forks.
 * 3. **The copy reset** (`i = 2e3`). `Copied` lasts 2 000 ms. ZAPP used 1 400 in one place and
 *    1 200 in another, so the same gesture gave two different feedback times.
 */
object MessageCopy {

    // ---- ui.message.* ----

    /** `ui.message.expand` */
    const val EXPAND = "Expand message"

    /** `ui.message.collapse` */
    const val COLLAPSE = "Collapse message"

    /** `ui.message.readMore` */
    const val READ_MORE = "Read more"

    /** `ui.message.showLess` */
    const val SHOW_LESS = "Show less"

    /** `ui.message.copy` */
    const val COPY = "Copy"

    /** `ui.message.copyMessage` */
    const val COPY_MESSAGE = "Copy message"

    /** `ui.message.copyFullMessage` - only offered when the message has a voice recording. */
    const val COPY_FULL_MESSAGE = "Copy full message"

    /** `ui.message.forkMessage` */
    const val FORK = "Fork from here"

    /** `ui.message.revertMessage` */
    const val REVERT = "Revert to here"

    /** `ui.message.undoMessage` */
    const val UNDO = "Undo"

    /** `ui.message.moreActions` - the aria-label on the chevron that opens the menu. */
    const val MORE_ACTIONS = "More actions"

    /** `ui.message.moveToQueue` */
    const val MOVE_TO_QUEUE = "Move to queue"

    /** `ui.message.pending` - the menu's group label when the message has no timestamp yet. */
    const val PENDING = "Pending"

    /** `ui.message.deletePending` */
    const val DELETE = "Delete"

    /** `ui.message.copyResponse` */
    const val COPY_RESPONSE = "Copy response"

    /** `ui.message.copyOutput` */
    const val COPY_OUTPUT = "Copy output"

    /** `ui.message.copied` */
    const val COPIED = "Copied"

    /** `ui.message.thought` */
    const val THOUGHT = "Thought"

    /** `ui.message.interrupted` */
    const val INTERRUPTED = "Interrupted"

    /** `ui.message.queued` */
    const val QUEUED = "Queued"

    /** `ui.message.voice` */
    const val VOICE = "Voice"

    /** `ui.message.attachment.alt` - the alt text of an attachment with no name. */
    const val ATTACHMENT = "attachment"

    // ---- ui.tool.execute.* / ui.toolErrorCard.* ----

    /** `ui.tool.execute` */
    const val EXECUTE = "Execute"

    /** `ui.tool.execute.viewCode` */
    const val VIEW_CODE = "View code"

    /** `ui.tool.execute.code` */
    const val CODE = "Code"

    /** `ui.tool.execute.output` */
    const val OUTPUT = "Output"

    /** `ui.tool.execute.error` */
    const val ERROR = "Error"

    /** `ui.tool.execute.copyCode` */
    const val COPY_CODE = "Copy code"

    /** `ui.tool.execute.running` */
    const val RUNNING = "Running"

    /** `ui.tool.execute.completed` */
    const val COMPLETED = "Completed"

    /** `ui.toolErrorCard.failed` */
    const val FAILED = "Failed"

    /** `ui.toolErrorCard.copyError` */
    const val COPY_ERROR = "Copy error"

    // ---- ui.common ----

    /** `ui.common.showMore` - the bounded-comment list's escape hatch. */
    const val SHOW_MORE = "Show more"

    // ---- measured constants ----

    /**
     * `Vl = { over: 12, show: 8 }` in `route-CMUQLRzL.js:73875`.
     *
     * The fold is measured in **line-heights**, not pixels: `offsetHeight / lineHeight`, and the
     * folded height is `8lh`. That is why it survives a font-size change on the desktop. Here it
     * is converted to dp with the live font scale, which is the same quantity.
     */
    const val FOLD_OVER = 12

    /** `Vl.show` - the folded height, in line-heights. */
    const val FOLD_SHOW = 8

    /**
     * `[data-slot=user-message-fold][data-folded]` carries
     * `mask-image: linear-gradient(#000 calc(100% - 40px), #0000)`.
     */
    const val FOLD_FADE_DP = 40

    /** `copy-CBB0hBKp.js:1` - `var i = 2e3`. */
    const val COPY_RESET_MS = 2_000L

    /** `ui.common.showMore`'s trigger: the comment list only bounds past five. */
    const val COMMENTS_BEFORE_MORE = 5

    // ---- the action gate ----

    /** The four things the message menu can run. Their order in `ce()` is the same. */
    enum class Action { QUEUE, REMOVE, REVERT, FORK }

    /**
     * What the message can currently do. PC's `oe()`:
     *
     * ```
     * { pending: p(), canPending: !!actions?.pending, canRevert: !!actions?.revert,
     *   canFork: !!actions?.fork }
     * ```
     *
     * `pending` is the message's own delivery state, not a capability.
     */
    data class Capabilities(
        val pending: Boolean = false,
        val canPending: Boolean = false,
        val canRevert: Boolean = false,
        val canFork: Boolean = false,
    )

    /**
     * `Gc(action, caps)` verbatim:
     *
     * ```
     * queue | remove -> canPending && pending
     * revert         -> canRevert && !pending
     * fork           -> canFork && !pending
     * ```
     */
    fun allowed(action: Action, caps: Capabilities): Boolean = when (action) {
        Action.QUEUE, Action.REMOVE -> caps.canPending && caps.pending
        Action.REVERT -> caps.canRevert && !caps.pending
        Action.FORK -> caps.canFork && !caps.pending
    }

    /**
     * `Wc(state, caps).disabled[action]` verbatim:
     *
     * ```
     * let n = n => state.status === "running" || !Gc(n, caps)
     * ```
     *
     * The `running` half is what stops a double tap from firing two forks.
     */
    fun disabled(action: Action, caps: Capabilities, running: Boolean): Boolean =
        running || !allowed(action, caps)

    /** `!!e.latest ? undoMessage : revertMessage` - the same item, two names. */
    fun revertLabel(latest: Boolean): String = if (latest) UNDO else REVERT

    /** The menu's group label: the timestamp, or `Pending` when there is not one yet. */
    fun stamp(created: Long?, now: Long = System.currentTimeMillis()): String =
        if (created == null || created <= 0L) PENDING else timeAgo(created, now)

    // ---- their plural + interpolation helpers ----

    /** `ui.tool.execute.calls.one` / `.other`. */
    fun calls(n: Int): String = if (n == 1) "$n call" else "$n calls"

    /** `ui.tool.execute.lines.one` / `.other`. */
    fun lines(n: Int): String = if (n == 1) "$n line" else "$n lines"

    /** `ui.common.fileCount.one` / `.other`. */
    fun fileCount(n: Int): String = if (n == 1) "$n file" else "$n files"

    /** `ui.common.question.one` / `.other`. */
    fun questionCount(n: Int): String = if (n == 1) "$n question" else "$n questions"

    /** `ui.message.modelVariant` = `{{model}} ({{variant}})`. */
    fun modelVariant(model: String, variant: String?): String =
        if (variant.isNullOrBlank()) model else "$model ($variant)"

    /** `ui.message.tokensPerSecond` = `{{value}} tok/s`, one decimal. */
    fun tokensPerSecond(value: Double): String =
        String.format(java.util.Locale.US, "%.1f tok/s", value)

    /**
     * `ui.message.duration.seconds` / `.minutesSeconds` / `.hoursMinutes`.
     *
     * PC rounds first (`Math.round(ms / 1e3)`) and only then picks a unit, so 59 600 ms is
     * `1m 0s`, not `59s`. Integer division would say `59s` - that was our bug.
     */
    fun duration(ms: Long): String {
        if (ms <= 0L) return ""
        val s = Math.round(ms / 1000.0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }

    /**
     * `ui.message.time.justNow` / `.minutesAgo` / `.hoursAgo` / `.daysAgo`.
     *
     * PC's `F()` for a *user* message actually formats a calendar date (`Today, 3:41 PM`), but
     * the menu's `<time>` element in this chunk resolves through the four relative keys, which
     * is what ZAPP has always shown.
     */
    fun timeAgo(created: Long, now: Long = System.currentTimeMillis()): String {
        val m = (now - created) / 60_000L
        return when {
            m < 1 -> "Just now"
            m < 60 -> "${m}m ago"
            m < 60 * 24 -> "${m / 60}h ago"
            else -> "${m / (60 * 24)}d ago"
        }
    }

    // ---- the fold ----

    /** `i()` = `lines > Vl.over`. */
    fun foldable(lines: Int): Boolean = lines > FOLD_OVER

    /** `o()` = `i() && !expanded` - the state that clips and fades. */
    fun folded(lines: Int, expanded: Boolean): Boolean = foldable(lines) && !expanded

    /** The toggle's own label: `expanded ? showLess : readMore`. */
    fun foldLabel(expanded: Boolean): String = if (expanded) SHOW_LESS else READ_MORE
}
