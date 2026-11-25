package com.zapp.net.ui

/**
 * PORT of `session-ui/src/components/markdown-image-machine.ts` — the local-image loader's
 * state machine, table for table.
 *
 * Their model in one paragraph (from `:17-31`): `loading` is a read in flight; `waiting` is a
 * backoff after a failure; `loaded` carries the resolved `src` and whether it must be released;
 * `failed` keeps the alt text. `attempts` is the running id, so a late result from an abandoned
 * read is recognised and thrown away instead of overwriting a newer one.
 *
 * The phone's difference is only in what `url` means: their `src` is a `blob:` or `data:` URL
 * the DOM resolves, ours is a key into a bitmap map. The retry, timeout and attempt semantics
 * are unchanged.
 */
object MarkdownImages {

    /** `:3` — a read that has not settled after this long is aborted and retried. */
    const val READ_TIMEOUT_MS = 15_000

    /** `:9` — the first retry waits this long; each later retry doubles it. */
    const val RETRY_BASE_MS = 1_000

    /** `:13` */
    const val MAX_RETRIES = 3

    sealed interface Entry {
        /** `:35` */
        data class Loading(val attempt: Int, val failures: Int) : Entry

        /** `:37` */
        data class Waiting(val attempt: Int, val failures: Int) : Entry

        /** `:39` — `revoke` marks a URL the machine must release (an SVG `data:` URL needs none). */
        data class Loaded(val url: String, val revoke: Boolean) : Entry

        /** `:41` */
        data object Failed : Entry
    }

    sealed interface State {
        /** `:45` */
        data class Active(val attempts: Int, val entries: Map<String, Entry>) : State

        /** `:59` */
        data object Disposed : State
    }

    sealed interface Event {
        /** `:65` */
        data class PathsSynced(val paths: Set<String>) : Event

        /** `:67` */
        data class ReadSucceeded(val path: String, val attempt: Int, val url: String, val revoke: Boolean) : Event

        /** `:81` */
        data class ReadEmpty(val path: String, val attempt: Int) : Event

        /** `:83` */
        data class ReadFailed(val path: String, val attempt: Int) : Event

        /** `:85` */
        data class ReadTimedOut(val path: String, val attempt: Int) : Event

        /** `:87` */
        data class RetryDue(val path: String, val attempt: Int) : Event

        /** `:89` */
        data object Disposed : Event
    }

    sealed interface Effect {
        /** `:95` */
        data class Read(val path: String, val attempt: Int, val timeoutMs: Int) : Effect

        /** `:97` */
        data class Wait(val path: String, val attempt: Int, val delayMs: Int) : Effect

        /** `:99` */
        data class Cancel(val attempt: Int) : Effect

        /** `:101` */
        data class RevokeUrl(val url: String) : Effect
    }

    data class Transition(val state: State, val effects: List<Effect>)

    /** `:115` */
    val INITIAL: State = State.Active(0, emptyMap())

    /** `:127` */
    val DISPOSED: State = State.Disposed

    /** `:135` */
    fun transition(state: State, event: Event): Transition {
        // `:143`
        if (state is State.Disposed) return ignore(state, event)

        // `:147`
        if (event is Event.Disposed) {
            val active = state as State.Active
            return Transition(DISPOSED, active.entries.values.flatMap(::release))
        }

        val active = state as State.Active

        // `:153`
        if (event is Event.PathsSynced) return sync(active, event.paths)

        // `:157`
        if (event is Event.RetryDue) {
            val entry = active.entries[event.path]
            if (entry !is Entry.Waiting || entry.attempt != event.attempt) return ignore(state, event)
            val attempt = active.attempts + 1
            return Transition(
                withEntry(active, event.path, Entry.Loading(attempt, entry.failures), attempt),
                listOf(
                    Effect.Cancel(entry.attempt),
                    Effect.Read(event.path, attempt, READ_TIMEOUT_MS),
                ),
            )
        }

        // `:187`
        val path = when (event) {
            is Event.ReadSucceeded -> event.path
            is Event.ReadEmpty -> event.path
            is Event.ReadFailed -> event.path
            is Event.ReadTimedOut -> event.path
            else -> return ignore(state, event)
        }
        val attemptOf = when (event) {
            is Event.ReadSucceeded -> event.attempt
            is Event.ReadEmpty -> event.attempt
            is Event.ReadFailed -> event.attempt
            is Event.ReadTimedOut -> event.attempt
            else -> -1
        }
        val entry = active.entries[path]
        // `:191`
        if (entry !is Entry.Loading || entry.attempt != attemptOf) return ignore(state, event)
        val cancel = Effect.Cancel(entry.attempt)

        // `:197`
        if (event is Event.ReadSucceeded) {
            return Transition(
                withEntry(active, path, Entry.Loaded(event.url, event.revoke), active.attempts),
                listOf(cancel),
            )
        }

        // `:209` — empty means the reader has nothing to show; too many failures means stop.
        if (event is Event.ReadEmpty || entry.failures >= MAX_RETRIES) {
            return Transition(withEntry(active, path, Entry.Failed, active.attempts), listOf(cancel))
        }

        val attempt = active.attempts + 1
        val failures = entry.failures + 1
        // `:223` — `IMAGE_RETRY_BASE_MS * 2 ** entry.failures`, so 1 s, 2 s, 4 s.
        return Transition(
            withEntry(active, path, Entry.Waiting(attempt, failures), attempt),
            listOf(
                cancel,
                Effect.Wait(path, attempt, RETRY_BASE_MS * (1 shl entry.failures)),
            ),
        )
    }

    /** `:231` */
    fun selectUrl(state: State, path: String): String? {
        if (state is State.Disposed) return null
        val entry = (state as State.Active).entries[path]
        return (entry as? Entry.Loaded)?.url
    }

    /** `:245` */
    private fun sync(state: State.Active, paths: Set<String>): Transition {
        val removed = state.entries.filterKeys { it !in paths }
        val added = paths.filter { it.isNotEmpty() && it !in state.entries }

        // `:259`
        if (removed.isEmpty() && added.isEmpty()) return Transition(state, emptyList())

        val entries = state.entries.toMutableMap()
        removed.keys.forEach { entries.remove(it) }

        // `:267`
        val reads = added.mapIndexed { index, path ->
            val attempt = state.attempts + index + 1
            entries[path] = Entry.Loading(attempt, 0)
            Effect.Read(path, attempt, READ_TIMEOUT_MS)
        }

        // `:281`
        return Transition(
            State.Active(state.attempts + added.size, entries),
            removed.values.flatMap(::release) + reads,
        )
    }

    /** `:293` */
    private fun release(entry: Entry): List<Effect> = when (entry) {
        is Entry.Loading -> listOf(Effect.Cancel(entry.attempt))
        is Entry.Waiting -> listOf(Effect.Cancel(entry.attempt))
        is Entry.Loaded -> if (entry.revoke) listOf(Effect.RevokeUrl(entry.url)) else emptyList()
        Entry.Failed -> emptyList()
    }

    /** `:309` */
    private fun withEntry(state: State.Active, path: String, entry: Entry, attempts: Int): State =
        State.Active(attempts, state.entries.toMutableMap().also { it[path] = entry })

    /**
     * `:327` — "A reader that ignores its abort signal can still hand back a blob URL after the
     * entry moved on; release it."
     */
    private fun ignore(state: State, event: Event): Transition {
        if (event is Event.ReadSucceeded && event.revoke) {
            return Transition(state, listOf(Effect.RevokeUrl(event.url)))
        }
        return Transition(state, emptyList())
    }
}
