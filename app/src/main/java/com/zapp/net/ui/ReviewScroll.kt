package com.zapp.net.ui

/**
 * PORT of `gui-plugins/src/review/scroll-machine.ts` - the whole file.
 *
 * The review list is a list that **reloads**. Diffs arrive late, wrapping changes re-measure
 * every row, and a file can be focused before its row exists. Their machine exists to answer one
 * question on every event: may this position be written down, or is the list still moving?
 *
 * Three rules carry the whole design:
 *
 * 1. A scroll the **restore** or a layout change fires is not the user's. Only `settled` saves,
 *    so restoring never overwrites the position it is restoring.
 * 2. A restore and a focused file belong to their **visit**. A new visit restores its own scroll
 *    and never inherits a frame scheduled for the last one.
 * 3. A focused file scrolls **a frame at a time** until its diff stays at the top, at most
 *    [MAX_FRAMES] more frames. The first frame cannot know the row height, so one frame is not
 *    enough and a fixed jump is wrong.
 */
object ReviewScroll {

    /** `REVIEW_SCROLL_MAX_FRAMES` - how many frames a focused file may take before it gives up. */
    const val MAX_FRAMES = 60

    /** `ReviewScrollContext` - what decides whether the list can restore or scroll now. */
    data class Context(
        /** The routing visit of the session the review shows. */
        val visitID: Int,
        /** Only the narrow review scrolls its list; the wide panel previews one file. */
        val narrow: Boolean,
        /** The narrow review's list is mounted. */
        val scroller: Boolean,
        /** The diffs have loaded. */
        val ready: Boolean,
        /** The layout loaded, so the stored scroll can be read and written. */
        val layoutReady: Boolean,
        /** A review comment is focused; the review centers it itself. */
        val commentFocused: Boolean,
    )

    /** `ReviewScrollState` */
    sealed class State {
        abstract val attempt: Int

        /** `Active = Exclude<ReviewScrollState, {status:"disposed"}>` - every state with a context. */
        sealed class Active : State() {
            abstract val context: Context
        }

        /** Returns to the stored scroll as its diffs render. */
        data class Restoring(
            override val context: Context,
            /** A restore frame is scheduled. */
            val restoreQueued: Boolean,
            override val attempt: Int,
        ) : Active()

        /** A file was focused on a narrow screen; waits for the list and the diffs. */
        data class Waiting(
            override val context: Context,
            val path: String,
            override val attempt: Int,
        ) : Active()

        /** Scrolls a frame at a time until the file's diff stays at the top. */
        data class Scrolling(
            override val context: Context,
            val path: String,
            val retries: Int,
            override val attempt: Int,
        ) : Active()

        /** The user, a focused file or a focused comment placed the list; its scroll is stored. */
        data class Settled(
            override val context: Context,
            override val attempt: Int,
        ) : Active()

        data class Disposed(override val attempt: Int) : State()
    }

    /** `ReviewScrollEvent` */
    sealed class Event {
        data class ContextSynced(val context: Context) : Event()
        /** The list mounted, or its diffs or wrapping changed: restore the stored scroll again. */
        object RestoreRequested : Event()
        data class RestoreStepped(val attempt: Int) : Event()
        data class FileFocused(val path: String) : Event()
        /** A scheduled frame scrolled toward the file: settled once its diff stays at the top. */
        data class FrameStepped(val attempt: Int, val settled: Boolean) : Event()
        /** The user scrolled, pressed a key, touched the list or opened or closed a diff. */
        object Interacted : Event()
        object Disposed : Event()
    }

    /** `ReviewScrollEffect` */
    sealed class Effect {
        data class FrameRestore(val attempt: Int) : Effect()
        data class FrameSchedule(val attempt: Int, val path: String) : Effect()
        object FrameCancel : Effect()
    }

    data class Transition(val state: State, val effects: List<Effect>)

    /** `INITIAL_REVIEW_SCROLL_STATE` */
    val initial: State = State.Restoring(
        context = Context(
            visitID = 0,
            narrow = false,
            scroller = false,
            ready = false,
            layoutReady = false,
            commentFocused = false,
        ),
        restoreQueued = false,
        attempt = 0,
    )

    private val NONE = emptyList<Effect>()
    private val CANCEL = listOf<Effect>(Effect.FrameCancel)

    /** `transitionReviewScroll(state, event)` - branch for branch, in their order. */
    fun transition(state: State, event: Event): Transition {
        if (state is State.Disposed) return unchanged(state)

        // Past the disposed check every state is active, exactly like their `Active` alias.
        val active = state as State.Active

        if (event is Event.Disposed) return stop(active, State.Disposed(active.attempt + 1))

        if (event is Event.ContextSynced) return synced(active, event.context)

        if (event is Event.RestoreRequested) {
            return if (active is State.Restoring && !active.restoreQueued && restorable(active.context)) {
                restore(active.context, active.attempt + 1, NONE)
            } else {
                unchanged(active)
            }
        }

        if (event is Event.RestoreStepped) {
            return if (active is State.Restoring && active.restoreQueued && event.attempt == active.attempt) {
                changed(active.copy(restoreQueued = false))
            } else {
                unchanged(active)
            }
        }

        if (event is Event.FileFocused) {
            // The wide panel previews the file itself; nothing waits to scroll a list that may
            // mount much later.
            if (!active.context.narrow) return unchanged(active)
            val attempt = active.attempt + 1
            val cancel = if (framed(active)) CANCEL else NONE

            if (!scrollable(active.context)) {
                return Transition(State.Waiting(active.context, event.path, attempt), cancel)
            }
            return scroll(active.context, event.path, attempt, cancel)
        }

        if (event is Event.Interacted) {
            return if (active is State.Settled || !active.context.narrow) {
                unchanged(active)
            } else {
                settle(active, active.context)
            }
        }

        event as Event.FrameStepped
        if (active !is State.Scrolling || event.attempt != active.attempt) return unchanged(active)

        if (event.settled || active.retries >= MAX_FRAMES) {
            return changed(State.Settled(active.context, active.attempt))
        }

        return Transition(
            active.copy(retries = active.retries + 1),
            listOf(Effect.FrameSchedule(active.attempt, active.path)),
        )
    }

    /** `canRestoreReviewScroll(state, attempt)` - whether the restore frame may still move the list. */
    fun canRestore(state: State, attempt: Int): Boolean =
        state is State.Restoring && state.restoreQueued && state.attempt == attempt &&
            restorable(state.context)

    /** `canSaveReviewScroll(state)` - whether a scroll event's position is the user's to store. */
    fun canSave(state: State): Boolean = state is State.Settled && restorable(state.context)

    /** `sameReviewScrollContext(a, b)` */
    fun sameContext(a: Context, b: Context): Boolean =
        a.visitID == b.visitID &&
            a.narrow == b.narrow &&
            a.scroller == b.scroller &&
            a.ready == b.ready &&
            a.layoutReady == b.layoutReady &&
            a.commentFocused == b.commentFocused

    private fun synced(state: State.Active, context: Context): Transition {
        if (sameContext(state.context, context)) return unchanged(state)
        val cancel = if (framed(state)) CANCEL else NONE

        // A restore and a focused file belong to their visit; the next visit restores its own.
        if (context.visitID != state.context.visitID) {
            if (context.commentFocused) return settle(state, context)

            return if (restorable(context)) {
                restore(context, state.attempt + 1, cancel)
            } else {
                Transition(
                    State.Restoring(context, restoreQueued = false, attempt = state.attempt + 1),
                    cancel,
                )
            }
        }

        // The wide panel scrolls no list; the narrow list restores again once it mounts.
        if (!context.narrow) {
            if (state is State.Restoring && !state.restoreQueued) {
                return changed(state.copy(context = context))
            }
            return Transition(
                State.Restoring(context, restoreQueued = false, attempt = state.attempt + 1),
                cancel,
            )
        }

        if (state is State.Waiting) {
            return if (scrollable(context)) {
                scroll(context, state.path, state.attempt + 1, NONE)
            } else {
                changed(state.copy(context = context))
            }
        }

        if (state is State.Scrolling) {
            if (scrollable(context)) return changed(state.copy(context = context))

            // The list unmounted and restores once it mounts again; or the diffs reload and the
            // file's position is unknown.
            return Transition(
                if (context.scroller) {
                    State.Settled(context, state.attempt + 1)
                } else {
                    State.Restoring(context, restoreQueued = false, attempt = state.attempt + 1)
                },
                CANCEL,
            )
        }

        // The list remounted on the same visit, as when the narrow review's tab opens again.
        if (state is State.Settled) {
            return changed(
                if (context.scroller) {
                    state.copy(context = context)
                } else {
                    State.Restoring(context, restoreQueued = false, attempt = state.attempt)
                },
            )
        }

        // The focused comment's position wins over the stored scroll.
        if (context.commentFocused) return settle(state, context)

        val restoring = state as State.Restoring

        if (!restorable(context)) {
            return Transition(
                restoring.copy(context = context, restoreQueued = false),
                if (restoring.restoreQueued) CANCEL else NONE,
            )
        }

        if (!restoring.restoreQueued && !restorable(restoring.context)) {
            return restore(context, restoring.attempt + 1, NONE)
        }

        return changed(restoring.copy(context = context))
    }

    private fun restore(context: Context, attempt: Int, cancel: List<Effect>): Transition =
        Transition(
            State.Restoring(context, restoreQueued = true, attempt = attempt),
            cancel + listOf<Effect>(Effect.FrameRestore(attempt)),
        )

    private fun scroll(context: Context, path: String, attempt: Int, cancel: List<Effect>): Transition =
        Transition(
            State.Scrolling(context, path, retries = 0, attempt = attempt),
            cancel + listOf<Effect>(Effect.FrameSchedule(attempt, path)),
        )

    private fun settle(state: State.Active, context: Context): Transition =
        stop(state, State.Settled(context, state.attempt + 1))

    /** Moves to a state that schedules no frame, cancelling a scheduled one. */
    private fun stop(state: State.Active, next: State): Transition =
        Transition(next, if (framed(state)) CANCEL else NONE)

    /** Whether a restore or scroll frame is scheduled. */
    private fun framed(state: State): Boolean =
        (state is State.Restoring && state.restoreQueued) || state is State.Scrolling

    private fun scrollable(context: Context): Boolean =
        context.narrow && context.scroller && context.ready

    private fun restorable(context: Context): Boolean =
        context.narrow && context.scroller && context.layoutReady

    private fun changed(state: State): Transition = Transition(state, NONE)

    private fun unchanged(state: State): Transition = Transition(state, NONE)
}
