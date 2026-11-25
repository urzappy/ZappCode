package com.zapp.net.ui

/**
 * The side region, ported whole from `route-CMUQLRzL.js` @ 365 700 - 366 300.
 *
 * Their desktop has three panes (`side`, `split`, `bottom`); a phone has one screen, so only
 * `side` is ever driven. `split` and `bottom` stay in the model because their reducer iterates
 * `K = ["side","split","bottom"]` and a partial port would silently drop the `!= "side"` branch
 * that clears a pane when the plugin stops contributing tabs.
 *
 * The reducer is `QS`. Everything here is a straight transcription - same guards, same order,
 * same fallbacks. `select.new` does **not** append; it only moves `chosen`. Appending is
 * `iC`, reached through `Event.Open`.
 */
object SidePanel {

    /** `K` - their pane list, in their order. */
    val PANES: List<Pane> = listOf(Pane.SIDE, Pane.SPLIT, Pane.BOTTOM)

    enum class Pane { SIDE, SPLIT, BOTTOM }

    /** `{ids, chosen}` - the tabs a pane holds and the one that is showing. */
    data class PaneState(
        val ids: List<String> = emptyList(),
        val chosen: String? = null,
    )

    /** What a pane's tabs are, from the plugin that contributed them. */
    data class PaneSnapshot(
        val keys: List<String> = emptyList(),
        val active: String? = null,
    )

    /** The region's own view of the world, handed in on every sync. */
    data class Snapshot(
        val session: String = "",
        val empty: Boolean = true,
        val regionActive: String? = null,
        val sideOpen: Boolean = false,
        val panes: Map<Pane, PaneSnapshot> = PANES.associateWith { PaneSnapshot() },
    )

    data class State(
        val serial: Int = 0,
        val session: String = "",
        val openedEmpty: Boolean = false,
        val panes: Map<Pane, PaneState> = PANES.associateWith { PaneState() },
        val snapshot: Snapshot? = null,
    )

    /**
     * **What `ids` are, and what they are not.** From the region component
     * (`route-CMUQLRzL.js` @ 370 300):
     *
     * ```
     * snapshot: { session, empty, regionActive, sideOpen, panes: {
     *   side: { active: v.side.active(), keys: v.side.keys() }, ... } }
     * ```
     *
     * `v` is the tab registry, so a [PaneSnapshot]'s `keys` and `active` are **plugin tab keys**
     * (`review`, `browser`, `terminal`, `file`). [PaneState.ids] are **`new:<serial>`** shell
     * tabs - a placeholder a `+` creates and the four-item list then fills. A real plugin tab is
     * never in `ids`. That is why [chosenOf] returns null once the registry has an active key:
     * `newTab()` is then false, because the empty tab has been filled.
     */
    sealed interface Event {
        /** `{type:"sync"}` - the region re-reads what the plugins currently contribute. */
        data class Sync(val snapshot: Snapshot) : Event

        /** `{type:"open"}` - a brand new tab in [pane], and it becomes the chosen one. */
        data class Open(val pane: Pane = Pane.SIDE) : Event

        /** `{type:"select.new", pane, id}` - show [id] if the pane holds it. */
        data class SelectNew(val pane: Pane, val id: String) : Event

        /** `{type:"select.tab", pane}` - back to the tab list (`chosen = undefined`). */
        data class SelectTab(val pane: Pane) : Event

        /** Closing a tab; [empty] asks for `{type:"emptied"}` when the last one goes. */
        data class Close(val id: String, val empty: Boolean = false) : Event

        /**
         * `{type:"dismiss", id, empty}` - what `closeNewTab` dispatches. `QS` has no case for it,
         * so it falls through to the same `K.find(...)` path as [Close]; the only difference is
         * the name at the call site.
         */
        data class Dismiss(val id: String, val empty: Boolean = false) : Event
    }

    sealed interface Effect {
        data object Emptied : Effect
    }

    data class Result(val state: State, val effects: List<Effect> = emptyList())

    // ---- the reducer -------------------------------------------------------------------

    fun reduce(state: State, event: Event): Result = when (event) {
        is Event.Sync -> Result(sync(state, event.snapshot))

        is Event.Open -> Result(open(state, event.pane))

        is Event.SelectNew -> {
            val pane = state.panes.getValue(event.pane)
            if (!pane.ids.contains(event.id) || pane.chosen == event.id) {
                Result(state)
            } else {
                Result(put(state, event.pane, pane.copy(chosen = event.id)))
            }
        }

        is Event.SelectTab -> {
            val pane = state.panes.getValue(event.pane)
            if (pane.chosen == null) Result(state)
            else Result(put(state, event.pane, pane.copy(chosen = null)))
        }

        is Event.Close -> close(state, event.id, event.empty)

        // `QS` has no `dismiss` case; it reaches the same `K.find(...)` fallthrough.
        is Event.Dismiss -> close(state, event.id, event.empty)
    }

    /** The shared tail of `QS`: find the pane, pick the neighbour, filter, maybe `emptied`. */
    private fun close(state: State, id: String, empty: Boolean): Result {
        val pane = PANES.firstOrNull { state.panes.getValue(it).ids.contains(id) }
        if (pane == null) {
            return Result(state)
        }
        val p = state.panes.getValue(pane)
        val index = p.ids.indexOf(id)
        // `r.chosen === t.id ? r.ids[i+1] ?? r.ids[i-1] : r.chosen`
        val next = if (p.chosen == id) {
            p.ids.getOrNull(index + 1) ?: p.ids.getOrNull(index - 1)
        } else {
            p.chosen
        }
        val out = put(state, pane, PaneState(p.ids.filter { it != id }, next))
        return Result(
            out,
            if (empty && !anyTabs(out)) listOf(Effect.Emptied) else emptyList(),
        )
    }

    /** `iC` - a fresh tab id (`new:<serial+1>`), appended and chosen. */
    fun open(state: State, pane: Pane): State {
        val serial = state.serial + 1
        val id = "new:$serial"
        val p = state.panes.getValue(pane)
        return put(state, pane, PaneState(p.ids + id, id)).copy(serial = serial)
    }

    /** `nC`. */
    fun sync(state: State, next: Snapshot): State {
        if (next.session != state.session) {
            val fresh = state.copy(session = next.session, panes = emptyPanes(), snapshot = next)
            val reopened = next.sideOpen && next.empty
            return if (reopened) open(fresh.copy(openedEmpty = true), Pane.SIDE)
            else fresh.copy(openedEmpty = reopened)
        }

        val previous = state.snapshot
        val contributed = PANES.flatMap { next.panes.getValue(it).keys }.toSet()

        val pruned = PANES.associateWith { pane ->
            val mine = state.panes.getValue(pane)
            val theirs = next.panes.getValue(pane)

            // `i !== "side" && o.keys.length === 0`
            if (pane != Pane.SIDE && theirs.keys.isEmpty()) {
                return@associateWith if (mine.ids.isEmpty()) mine else PaneState()
            }
            if (previous == null) return@associateWith mine

            val before = previous.panes.getValue(pane)
            val wasChosen = chosenOf(mine, before.active)
            if (wasChosen == null ||
                !vanished(before, theirs, previous.regionActive, contributed)
            ) {
                return@associateWith mine
            }
            PaneState(mine.ids.filter { it != wasChosen }, null)
        }

        val changed = PANES.any { pruned.getValue(it) != state.panes.getValue(it) }
        val mid = state.copy(
            panes = if (changed) pruned else state.panes,
            snapshot = if (previous != null && previous == next) previous else next,
        )

        val reopened = next.sideOpen && next.empty
        val reopenedNow = reopened && !state.openedEmpty &&
            chosenOf(mid.panes.getValue(Pane.SIDE), next.panes.getValue(Pane.SIDE).active) == null

        val out = if (reopenedNow) open(mid.copy(openedEmpty = true), Pane.SIDE)
        else mid.copy(openedEmpty = reopened)

        return if (out.panes == state.panes && out.openedEmpty == state.openedEmpty &&
            out.snapshot === previous
        ) {
            state
        } else {
            out
        }
    }

    /** `rC` - did the tab the pane was showing disappear from the plugin's list. */
    fun vanished(
        before: PaneSnapshot,
        after: PaneSnapshot,
        regionActive: String?,
        contributed: Set<String>,
    ): Boolean {
        val active = after.active
        if (active == null || active == before.active) return false
        if (!before.keys.contains(active)) return true
        val parentOk = before.active == null || after.keys.contains(before.active)
        val regionOk = regionActive == null || contributed.contains(regionActive)
        return parentOk && regionOk
    }

    /** `aC` - `chosen ?? (active === undefined ? ids.last() : undefined)`. */
    fun chosenOf(pane: PaneState, active: String?): String? =
        pane.chosen ?: if (active == null) pane.ids.lastOrNull() else null

    /** `tC` - does any pane still hold a tab. */
    fun anyTabs(state: State): Boolean = PANES.any { state.panes.getValue(it).ids.isNotEmpty() }

    /** `sC`. */
    fun emptyPanes(): Map<Pane, PaneState> = PANES.associateWith { PaneState() }

    private fun put(state: State, pane: Pane, value: PaneState): State =
        state.copy(panes = state.panes + (pane to value))

    // ---- the `+` ----------------------------------------------------------------------

    /**
     * `ET`'s `arm`: `intent: Qt(e, modifier) ? "menu" : "new"`. A tap makes a tab; holding the
     * modifier opens the menu instead. The tooltip that explains it is
     * `session.tab.recentHint` = "Hold {{key}} for recently closed".
     */
    enum class Intent { NEW, MENU }

    fun intent(modifierHeld: Boolean): Intent = if (modifierHeld) Intent.MENU else Intent.NEW

    /** `session.tab.recentHint` exists only when there is something to reopen. */
    fun iconFor(intent: Intent, hasRecent: Boolean): String =
        if (intent == Intent.MENU && hasRecent) "chevron-down" else "plus"

    // ---- `session.panel` --------------------------------------------------------------

    /**
     * The four `session.panel` registrations, each `{menu: "session.panel", id, title, icon,
     * keybind, order}`, sorted the way the panel sorts them. Orders are theirs: 10, 15, 20, 30.
     *
     * [CONTEXT] is **not** in that menu. Their Context panel is a `BuiltinPanel`
     * (`context/renderer.tsx`: `region:"side"`, `id:"main"`, `mobile:{order:40, kind:"menu",
     * icon:"status"}`), opened by the header's usage pill via `layout.toggle("context:main")`,
     * never from the `+`. It is here because the side region holds it as a tab like the rest.
     */
    enum class Kind { REVIEW, BROWSER, TERMINAL, FILE, CONTEXT }

    data class MenuEntry(
        val order: Int,
        val kind: Kind,
        /** `"open"` reuses an existing tab, `"new"` always makes one. */
        val id: String,
        /** Their icon name from `icon-C5tP_h6Y.js`. */
        val icon: String,
        /** Their keybind id; the phone has no keyboard, kept for the record. */
        val keybind: String,
    )

    val MENU: List<MenuEntry> = listOf(
        MenuEntry(10, Kind.REVIEW, "open", "review", "review.open"),
        MenuEntry(15, Kind.BROWSER, "open", "globe", "browser.open"),
        MenuEntry(20, Kind.TERMINAL, "new", "terminal", "terminal.new"),
        MenuEntry(30, Kind.FILE, "open", "folder", "file.browse"),
    )

    /** `yT` - how many closed tabs the panel lists before "Show more". */
    const val RECENT_SHOWN = 5

    /** `vT` - the command palette's own cap. */
    const val RECENT_IN_PALETTE = 10

    // ---- the `+` state machine, `sT` ------------------------------------------------------

    /**
     * `route-CMUQLRzL.js` @ 408 372. The `+` is not a button with a menu; it is a popover whose
     * *open* event is turned into either "make a tab" or "show the menu" by what was armed on
     * pointer-down. That is why a tap makes a tab and a modifier-held tap shows `Recently closed`
     * on the same control.
     */
    enum class PlusStatus { CLOSED, OPEN, CLOSING, DISPOSED }

    data class PlusState(
        val status: PlusStatus = PlusStatus.CLOSED,
        val attempt: Int = 0,
        val hover: Boolean = false,
        val armed: Intent? = null,
        val shown: Boolean = false,
        val choice: String? = null,
    )

    sealed interface PlusEvent {
        /** `{type:"arm", intent}` - pointer-down recorded which gesture this is. */
        data class Arm(val intent: Intent) : PlusEvent

        /** `{type:"disarm"}` - blur. */
        data object Disarm : PlusEvent

        /** `{type:"open.change", open, available, defaultIntent}` - the popover opened or closed. */
        data class OpenChange(
            val open: Boolean,
            val available: Boolean,
            val defaultIntent: Intent = Intent.NEW,
        ) : PlusEvent

        /** `{type:"context.menu", available}` - long-press / secondary click. */
        data class ContextMenu(val available: Boolean) : PlusEvent

        /** `{type:"hover", hover}` - pointer over the control. A phone has no hover; kept whole. */
        data class Hover(val hover: Boolean) : PlusEvent

        /** `{type:"select", choice}` - a row of the menu was chosen. */
        data class Select(val choice: String?) : PlusEvent

        /** `{type:"close.autofocus"}` - the content wants focus back, so the timer restarts at 0. */
        data object CloseAutofocus : PlusEvent

        /** `{type:"timer.fired", attempt}` - the closing timer fired. */
        data class TimerFired(val attempt: Int) : PlusEvent

        /** `{type:"disposed"}` - the control left the tree. */
        data object Disposed : PlusEvent
    }

    sealed interface PlusEffect {
        /** `{type:"open.new-tab"}` - the popover closed itself so a tab could be made. */
        data object OpenNewTab : PlusEffect

        /** `{type:"timer.start", attempt, delay}` - then [PlusEvent.TimerFired]. */
        data class TimerStart(val attempt: Int, val delayMs: Long = 50) : PlusEffect

        /** `{type:"timer.stop"}` - a newer interaction superseded the close. */
        data object TimerStop : PlusEffect

        /** `{type:"run.choice", choice}` - the row's action, after the 50 ms fade. */
        data class RunChoice(val choice: String?) : PlusEffect
    }

    /** `aT` - is the menu showing (or still fading out with content). */
    fun plusOpen(s: PlusState): Boolean =
        s.status == PlusStatus.OPEN || (s.status == PlusStatus.CLOSING && s.shown)

    /** `oT` - does the tooltip show. */
    fun plusHover(s: PlusState): Boolean = s.status != PlusStatus.DISPOSED && s.hover

    /** `lT` - the closed resting state, with `armed` cleared. */
    fun plusClosed(attempt: Int, hover: Boolean): PlusState =
        PlusState(PlusStatus.CLOSED, attempt, hover, null)

    /** `cT` - begin the 50 ms close, remembering what was chosen. */
    fun plusClosing(
        attempt: Int,
        hover: Boolean,
        choice: String?,
    ): Pair<PlusState, List<PlusEffect>> {
        val next = attempt + 1
        return PlusState(PlusStatus.CLOSING, next, hover, null, shown = true, choice = choice) to
            listOf(PlusEffect.TimerStart(next))
    }

    /** `sT`. Only these four events reach it; `iT` owns the rest. */
    fun plusReduce(s: PlusState, e: PlusEvent): Pair<PlusState, List<PlusEffect>> = when (e) {
        is PlusEvent.Arm ->
            if (s.armed == e.intent) s to emptyList() else s.copy(armed = e.intent) to emptyList()

        PlusEvent.Disarm ->
            if (s.armed == null) s to emptyList() else s.copy(armed = null) to emptyList()

        is PlusEvent.OpenChange ->
            when {
                !e.open -> if (s.armed == null) s to emptyList()
                else s.copy(armed = null) to emptyList()

                (s.armed ?: e.defaultIntent) == Intent.MENU && e.available ->
                    // `{status:"open", attempt:e.attempt, hover:e.hover}` - a fresh object, so the
                    // gesture that opened it is forgotten along with any previous choice.
                    PlusState(PlusStatus.OPEN, s.attempt, s.hover) to emptyList()

                else -> plusClosed(s.attempt, s.hover) to listOf(PlusEffect.OpenNewTab)
            }

        is PlusEvent.ContextMenu ->
            when {
                !e.available -> if (s.armed == null) s to emptyList()
                else s.copy(armed = null) to emptyList()

                // Also a fresh object: same fields, nothing carried over.
                else -> PlusState(PlusStatus.OPEN, s.attempt, s.hover) to emptyList()
            }

        else -> s to emptyList()
    }

    /**
     * `iT` - the popover the `+` lives in. `route-CMUQLRzL.js` @ 407 192.
     *
     * Their `+` is not a button with a menu attached; it is a **menu popover with a close
     * animation**. A chosen row does not close the menu immediately: the state goes to
     * `closing` for 50 ms (`cT`), the row's action runs when `timer.fired` matches the attempt
     * (`run.choice`), and a newer interaction stops the timer instead. That is why
     * [PlusEffect.TimerStop] exists and why [PlusEffect.RunChoice] can arrive with a null choice.
     *
     * The branches, in their order:
     *
     * 1. `disposed` is inert, and disposing a closing menu stops its timer.
     * 2. `hover` only moves the pointer flag; while **closed** it also drops the arm when the
     *    pointer leaves.
     * 3. `closed` delegates to [plusReduce].
     * 4. `open` handles only `open.change(open=false)` and `select`.
     * 5. anything else is `closing`: `select` restarts the 50 ms close, a close only clears
     *    `shown`, `close.autofocus` restarts the timer at 0, and a matching `timer.fired` runs
     *    the choice. A menu that reopens mid-close stops the timer and runs the choice now.
     */
    fun plusPopover(s: PlusState, e: PlusEvent): Pair<PlusState, List<PlusEffect>> {
        if (s.status == PlusStatus.DISPOSED) return s to emptyList()

        if (e is PlusEvent.Disposed) {
            return PlusState(status = PlusStatus.DISPOSED) to
                if (s.status == PlusStatus.CLOSING) listOf(PlusEffect.TimerStop) else emptyList()
        }

        if (e is PlusEvent.Hover) {
            if (s.status != PlusStatus.CLOSED) {
                return (if (s.hover == e.hover) s else s.copy(hover = e.hover)) to emptyList()
            }
            // `let n = t.hover ? e.armed : void 0` - leaving the control forgets the gesture.
            val armed = if (e.hover) s.armed else null
            return if (s.hover == e.hover && s.armed == armed) {
                s to emptyList()
            } else {
                s.copy(hover = e.hover, armed = armed) to emptyList()
            }
        }

        // `(t.type === "context.menu" || t.type === "open.change" && t.open) && t.available`
        val wantsReopen = e is PlusEvent.ContextMenu || (e is PlusEvent.OpenChange && e.open)
        val reopenAvailable = when (e) {
            is PlusEvent.ContextMenu -> e.available
            is PlusEvent.OpenChange -> e.available
            else -> false
        }

        return when (s.status) {
            PlusStatus.CLOSED -> plusReduce(s, e)

            PlusStatus.OPEN -> when {
                e is PlusEvent.OpenChange && !e.open -> plusClosed(s.attempt, s.hover) to emptyList()
                e is PlusEvent.Select -> plusClosing(s.attempt, s.hover, e.choice)
                else -> s to emptyList()
            }

            // `closing`
            else -> when {
                e is PlusEvent.Select -> plusClosing(s.attempt, s.hover, e.choice)

                e is PlusEvent.OpenChange && !e.open ->
                    (if (s.shown) s.copy(shown = false) else s) to emptyList()

                e is PlusEvent.CloseAutofocus ->
                    s to listOf(PlusEffect.TimerStart(s.attempt, delayMs = 0))

                e is PlusEvent.TimerFired && e.attempt == s.attempt ->
                    plusClosed(s.attempt, s.hover) to listOf(PlusEffect.RunChoice(s.choice))

                wantsReopen && reopenAvailable ->
                    // `{status:"open", attempt: e.attempt+1, hover}` - a **fresh** object, so the
                    // previous `choice`, `shown` and `armed` do not survive.
                    PlusState(
                        status = PlusStatus.OPEN,
                        attempt = s.attempt + 1,
                        hover = s.hover,
                    ) to listOf(PlusEffect.TimerStop, PlusEffect.RunChoice(s.choice))

                else -> s to emptyList()
            }
        }
    }

    /** One row of `Recently closed`. */
    data class RecentEntry(
        /** The tab key, so reopening can restore the same tab rather than a new one. */
        val key: String,
        /** `entry.file ? basename : entry.detail` - the truncating part. */
        val title: String,
        /** `entry.file`'s directory, or their `detail` when there is no file. */
        val detail: String? = null,
        val file: String? = null,
        val icon: String = "file-document",
    )
}
