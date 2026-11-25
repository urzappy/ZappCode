package com.zapp.net.ui

/**
 * `wr` - the new-session **base branch** picker, from
 * `route-BvCVPSgV.js` (the reducer `cr` @ 37 723, its helpers @ 39 206-41 070,
 * the component `wr` @ 41 070).
 *
 * What it is: on the new-session surface, above the composer, there is one small
 * button. It says `Choose base branch`, or `from <branch>` once something is
 * picked. Tapping it opens a 243 px popover with a search field, the row
 * `Use main checkout` (with the current checkout's branch beside it), then the
 * branch list from `GET /api/vcs/branch`.
 *
 * What it is **not**: a checkout. Selecting a branch only sets the draft's
 * selection - their effect is `selection.branch(t.branch)`; nothing calls a
 * checkout route. `Use main checkout` is `selection.set("main")`. The branch is
 * carried into the session when the draft is promoted.
 *
 * The states, verbatim:
 *
 *     inactive   {status:'inactive', attempt:0}          <- no directory yet
 *     loading    {status:'loading', directory, attempt, open}
 *     ready      {status:'ready', directory, attempt, initial, refreshing, popover}
 *     disposed   {status:'disposed'}
 *
 * `attempt` is the only guard against a stale response: every fetch carries the
 * attempt it was started with, and `_r` drops any result whose attempt is not the
 * current one. Without it, typing fast and then clearing the field would let an
 * older, shorter list land on top of a newer, longer one.
 *
 * The 10 s `rr` timer is load-bearing: `branches.timed-out` settles the state as
 * **failed** (`initial = {items:[], failed:true}`) and then aborts the request,
 * which is what puts the palette into its `Try again` shape instead of leaving it
 * spinning forever on a branch list that will never arrive.
 *
 * Two easy things to get wrong, both pinned by tests:
 *
 *  1. `_r` on a **refresh** failure keeps the old `initial`. A failed background
 *     refresh must not blank a list the user is looking at.
 *  2. `hr` (close) emits `focus.composer` only when the popover was **not**
 *     `outside`. Closing by tapping away must not steal the cursor back into the
 *     composer; closing by Escape/selection must.
 */
object BranchMachine {

    /** `rr` - the fetch deadline. */
    const val TIMEOUT_MS = 10_000

    /** `vT`-style palette cap is the palette's business; this is only the fetch limit. */
    const val FETCH_LIMIT = 40

    // ---- state ------------------------------------------------------------------------------

    sealed interface State {
        /** `ir` - nothing to ask about yet. Keeps `attempt` so a late result is still dropped. */
        data class Inactive(val attempt: Int = 0) : State

        /** `ar` - terminal. Every event after this is ignored, `mounted` included. */
        data object Disposed : State

        /** Directory known, first fetch in flight. `open` survives the transition to `Ready`. */
        data class Loading(
            val directory: String,
            val attempt: Int,
            val open: Boolean = false,
        ) : State

        data class Ready(
            val directory: String,
            val attempt: Int,
            val initial: BranchItems,
            val refreshing: Boolean = false,
            val popover: Popover = Popover.Closed,
        ) : State
    }

    enum class Popover { Closed, Open, Outside }

    /**
     * `initial` is `{items, failed}`. `failed` is not the same as empty: an empty
     * list means Git answered "no other branches", failed means it did not answer.
     * Their `_r` writes `{items:[], failed:true}` on timeout/failure and the
     * palette renders `Try again` instead of `No results found`.
     */
    data class BranchItems(val items: List<String>, val failed: Boolean) {
        companion object {
            val Failed = BranchItems(emptyList(), true)
            val Empty = BranchItems(emptyList(), false)
        }
    }

    // ---- events -----------------------------------------------------------------------------

    sealed interface Event {
        /** `disposed` - the surface went away. */
        data object Disposed : Event

        /** `target.changed` - `directory` is `null` when there is no project root. */
        data class TargetChanged(val directory: String?) : Event

        /** `open.change` - the trigger or the popover asked to open/close. */
        data class OpenChange(val open: Boolean) : Event

        /**
         * `command.select` - the `Choose base branch` command ran. `source == "palette"`
         * means it came from the command palette, which is already on screen: their
         * reducer does nothing in that case rather than stacking a second popover.
         */
        data class CommandSelect(val source: String) : Event

        /** `pending.dismiss` - pointerdown/focusin/blur/Escape while the first fetch is open. */
        data object PendingDismiss : Event

        data class BranchesLoaded(val attempt: Int, val items: List<String>) : Event
        data class BranchesFailed(val attempt: Int) : Event
        data class BranchesTimedOut(val attempt: Int) : Event

        /** `interact.outside` - a tap landed outside the popover. */
        data object InteractOutside : Event

        data class BranchSelected(val branch: String) : Event
        data object CheckoutSelected : Event

        /**
         * `branches.synced` - the palette's own `source` finished an **empty-query**
         * fetch. Only the empty query syncs; a typed query is a transient filter and
         * must not overwrite the list the machine owns.
         */
        data class BranchesSynced(val directory: String, val items: List<String>) : Event
    }

    // ---- effects ----------------------------------------------------------------------------

    sealed interface Effect {
        /** `focus.composer` - `e.onDone()`. */
        data object FocusComposer : Effect

        /** `branch.choose` - `e.workspace.selection.branch(t.branch)`. No checkout. */
        data class BranchChoose(val branch: String) : Effect

        /** `checkout.use` - `e.workspace.selection.set("main")`. */
        data object CheckoutUse : Effect

        /** `branches.abort` - abort the controller for that attempt. */
        data class BranchesAbort(val attempt: Int) : Effect

        /** `branches.fetch` - the runner calls `workspace.branches("", signal)`. */
        data class BranchesFetch(val attempt: Int, val directory: String) : Effect
    }

    data class Result(val state: State, val effects: List<Effect>)

    // ---- predicates -------------------------------------------------------------------------

    /** `lr` - is the popover open (or about to be, while the first fetch is pending). */
    fun isOpen(s: State): Boolean = when (s) {
        is State.Loading -> s.open
        is State.Ready -> s.popover != Popover.Closed
        else -> false
    }

    /** `ur` - the first fetch is in flight *and* the popover is up. Drives the dismiss listeners. */
    fun isPending(s: State): Boolean = s is State.Loading && s.open

    /** `dr` - a fetch is running, first or refresh. Drives `aria-busy`. */
    fun isBusy(s: State): Boolean =
        s is State.Loading || (s is State.Ready && s.refreshing)

    /** `fr` - the list to render, or `null` before the first answer. */
    fun initialItems(s: State): BranchItems? = (s as? State.Ready)?.initial

    // ---- reducer ----------------------------------------------------------------------------

    private fun noOp(s: State) = Result(s, emptyList())

    /** `G` */
    private fun keep(s: State) = noOp(s)

    /** `vr` - abort the in-flight fetch, if there is one. */
    private fun aborts(s: State): List<Effect> = when {
        s is State.Loading -> listOf(Effect.BranchesAbort(s.attempt))
        s is State.Ready && s.refreshing -> listOf(Effect.BranchesAbort(s.attempt))
        else -> emptyList()
    }

    /** `pr` - `target.changed`. A null directory parks the machine; a new one starts a fetch. */
    private fun targetChanged(s: State, directory: String?): Result {
        if (directory == null) {
            return if (s is State.Inactive) {
                keep(s)
            } else {
                Result(State.Inactive(attempt = s.attemptOf()), aborts(s))
            }
        }
        if (s !is State.Inactive && s.directoryOf() == directory) return keep(s)
        val n = s.attemptOf() + 1
        return Result(
            State.Loading(directory = directory, attempt = n, open = false),
            aborts(s) + Effect.BranchesFetch(attempt = n, directory = directory),
        )
    }

    /** `mr` - open. A `Loading` machine only flips `open`; a `Ready` one refetches. */
    private fun open(s: State): Result = when (s) {
        is State.Loading ->
            if (s.open) keep(s) else Result(s.copy(open = true), emptyList())
        is State.Ready -> {
            if (s.popover != Popover.Closed) {
                keep(s)
            } else {
                val n = s.attempt + 1
                Result(
                    s.copy(attempt = n, refreshing = true, popover = Popover.Open),
                    aborts(s) + Effect.BranchesFetch(attempt = n, directory = s.directory),
                )
            }
        }
        else -> keep(s)
    }

    /** `hr` - close. */
    private fun close(s: State): Result = when (s) {
        is State.Loading ->
            if (s.open) Result(s.copy(open = false), emptyList()) else keep(s)
        is State.Ready ->
            if (s.popover == Popover.Closed) {
                keep(s)
            } else {
                // A close caused by tapping away must not pull focus into the composer;
                // a close caused by Escape or by picking a row must.
                commitClose(s, if (s.popover == Popover.Outside) emptyList() else listOf(Effect.FocusComposer))
            }
        else -> keep(s)
    }

    /** `gr` - the shared tail of `close`, also used by `command.select`. */
    private fun commitClose(s: State, effects: List<Effect>): Result = when (s) {
        is State.Loading -> Result(s.copy(open = false), effects)
        is State.Ready -> Result(s.copy(popover = Popover.Closed), effects)
        else -> keep(s)
    }

    /**
     * `_r` - settle attempt `t` with `items` (`null` == failed).
     *
     * The refresh branch is the subtle one: on failure it keeps `s.initial`, so a
     * background refresh that dies does not erase the list already on screen.
     */
    private fun settle(s: State, t: Int, items: List<String>?): Result {
        if (s.attemptOf() != t) return keep(s)
        return when {
            s is State.Loading -> Result(
                State.Ready(
                    directory = s.directory,
                    attempt = t,
                    initial = items?.let { BranchItems(it, false) } ?: BranchItems.Failed,
                    refreshing = false,
                    popover = if (s.open) Popover.Open else Popover.Closed,
                ),
                emptyList(),
            )
            s is State.Ready && s.refreshing -> Result(
                s.copy(
                    initial = items?.let { BranchItems(it, false) } ?: s.initial,
                    refreshing = false,
                ),
                emptyList(),
            )
            else -> keep(s)
        }
    }

    private fun State.attemptOf(): Int = when (this) {
        is State.Inactive -> attempt
        is State.Loading -> attempt
        is State.Ready -> attempt
        State.Disposed -> 0
    }

    private fun State.directoryOf(): String? = when (this) {
        is State.Loading -> directory
        is State.Ready -> directory
        else -> null
    }

    /** `cr` - the reducer. */
    fun reduce(s: State, e: Event): Result {
        if (s is State.Disposed) return keep(s)
        if (e is Event.Disposed) return Result(State.Disposed, aborts(s))
        if (e is Event.TargetChanged) return targetChanged(s, e.directory)
        if (s is State.Inactive) return keep(s)

        when (e) {
            is Event.OpenChange -> return if (e.open) open(s) else close(s)
            is Event.CommandSelect ->
                return if (isOpen(s)) {
                    if (e.source == "palette") keep(s) else commitClose(s, listOf(Effect.FocusComposer))
                } else {
                    open(s)
                }
            Event.PendingDismiss ->
                return if (s is State.Loading) close(s) else keep(s)
            is Event.BranchesLoaded -> return settle(s, e.attempt, e.items)
            is Event.BranchesFailed -> return settle(s, e.attempt, null)
            is Event.BranchesTimedOut -> {
                val n = settle(s, e.attempt, null)
                return if (n.state === s) n
                else Result(n.state, listOf(Effect.BranchesAbort(e.attempt)))
            }
            else -> Unit
        }

        if (s !is State.Ready) return keep(s)

        return when (e) {
            Event.InteractOutside ->
                if (s.popover == Popover.Open) Result(s.copy(popover = Popover.Outside), emptyList())
                else keep(s)
            is Event.BranchSelected, Event.CheckoutSelected ->
                if (s.popover == Popover.Closed) {
                    keep(s)
                } else {
                    val pick: Effect = if (e is Event.CheckoutSelected) {
                        Effect.CheckoutUse
                    } else {
                        Effect.BranchChoose((e as Event.BranchSelected).branch)
                    }
                    commitClose(s, listOf(pick, Effect.FocusComposer))
                }
            is Event.BranchesSynced ->
                if (e.directory == s.directory) {
                    Result(
                        s.copy(initial = BranchItems(e.items, false), refreshing = false),
                        aborts(s),
                    )
                } else {
                    keep(s)
                }
            else -> keep(s)
        }
    }

    // ---- the palette's own rules ------------------------------------------------------------

    /** `key` - the row identity. `checkout` is not a branch name and never collides. */
    fun rowKey(item: String?): String = if (item == null) "checkout" else "branch:$item"

    /** `selected` - `"branch:" + (bar.branch() ?? "")`, so an empty selection matches nothing. */
    fun selectedKey(branch: String?): String = "branch:" + (branch ?: "")

    /**
     * `match` - case-insensitive substring, and **the main-checkout row is never
     * matched by text**: `e !== Cr && e.toLowerCase().includes(t.toLowerCase())`.
     * That row is always offered, because it is the default, not a search result.
     */
    fun matches(item: String?, query: String): Boolean =
        item != null && item.lowercase().contains(query.lowercase())

    /**
     * The list the palette actually draws for a query: the checkout row first, then
     * every branch that matches. Their `Gt` renders `emptyQueryItem` before the
     * filtered source, so this mirrors it rather than filtering the sentinel.
     */
    fun visible(items: List<String>, query: String): List<String?> =
        buildList {
            add(null)
            items.filter { matches(it, query) }.forEach { add(it) }
        }

    /** `v` - the trigger's label. */
    fun label(branch: String?): String =
        if (branch != null) "from $branch" else "Choose base branch"

    /** The strings the popover uses, all from their bundle. */
    object Copy {
        const val CHOOSE = "Choose base branch"
        const val FROM = "from "          // `session.new.workspace.fromBranch` = "from {{branch}}"
        const val USE_MAIN = "Use main checkout"
        const val SEARCH = "Search branches"
        const val EMPTY = "No results found"   // `palette.empty`
        const val RETRY = "Try again"          // `common.retry`
    }
}
