package com.zapp.net.api

/**
 * Their plugin check/update machine, verbatim.
 *
 * Source: `shell-D_fdbe5y.js` @ 122 861-124 500 - `Ws` (initial), `Gs` (reduce), `Ks` (per-plugin
 * view), `qs` (checking). Their Settings -> Extensions tab runs it; ours runs it in the Extensions
 * overlay's Plugins panel.
 *
 * Four rules, each of which is a line in their reducer:
 *
 * 1. **`list.loaded` deletes findings, it does not merge them.** The filter is
 *    `!(n in t.outdated) || t.outdated[n] === e.findings[n]?.outdated` - so a finding is dropped
 *    when its target vanished **or** when the server already agrees with it, and kept only while
 *    the two disagree. That is how a local `outdated:false` written by a successful update
 *    survives a list load that has not caught up yet.
 * 2. **A check result cannot overwrite a newer one.** `(r[n]?.at ?? -1) > t.attempt` skips any
 *    target whose finding is newer than the check that just landed.
 * 3. **A target being updated is not overwritten by a check.** `e.updating.some(e=>e.target===n)`
 *    skips it, so an update in flight cannot be flipped back to `outdated` mid-flight.
 * 4. **One update per target at a time.** `if (e.updating.some(e=>e.target===t.target)) return Q(e)`.
 */
object PluginLifecycle {

    /** One check result for one package target. */
    data class Finding(val outdated: Boolean, val at: Int)

    /** One update in flight. `id` is the identity its result is matched against. */
    data class Updating(val id: Int, val target: String)

    /** `Ws` - `{status:'idle', nextId:0, findings:{}, updating:[]}`. */
    data class State(
        val status: String = "idle",
        val nextId: Int = 0,
        val findings: Map<String, Finding> = emptyMap(),
        val updating: List<Updating> = emptyList(),
        val attempt: Int = 0,
    ) {
        val disposed: Boolean get() = status == "disposed"
        val checking: Boolean get() = status == "checking"
    }

    sealed interface Event {
        data object Disposed : Event

        /** The server changed - a reconnect. Clears findings, keeps `nextId`. */
        data object ServerChanged : Event

        /** The plugin list answered; `outdated` is [outdatedMap] of its entries. */
        data class ListLoaded(val outdated: Map<String, Boolean>) : Event

        data object CheckRequested : Event
        data class CheckSucceeded(val attempt: Int, val outdated: Map<String, Boolean>) : Event
        data class CheckFailed(val attempt: Int, val message: String) : Event

        data class UpdateRequested(val target: String) : Event
        data class UpdateSucceeded(val id: Int) : Event
        data class UpdateFailed(val id: Int, val message: String) : Event
    }

    sealed interface Effect {
        data class Check(val attempt: Int) : Effect
        data class Update(val id: Int, val target: String) : Effect
        data class Failed(val message: String) : Effect
        data object Refresh : Effect
    }

    data class Result(val state: State, val effects: List<Effect> = emptyList())

    /** `A(e)` - `source.target -> source.outdated === true`, packages only. */
    fun outdatedMap(plugins: List<PluginEntry>): Map<String, Boolean> = buildMap {
        for (p in plugins) {
            val src = p.source ?: continue
            if (src.type != "package") continue
            val target = src.target ?: continue
            put(target, src.outdated == true)
        }
    }

    /** `Gs` - the reducer. */
    fun reduce(state: State, event: Event): Result {
        if (state.disposed) return Result(state)

        when (event) {
            is Event.Disposed -> return Result(State(status = "disposed"))

            is Event.ServerChanged -> return Result(
                State(status = "idle", nextId = state.nextId),
            )

            is Event.ListLoaded -> {
                val drop = state.findings.keys.filter { target ->
                    !event.outdated.containsKey(target) ||
                        event.outdated[target] == state.findings[target]?.outdated
                }
                if (drop.isEmpty()) return Result(state)
                val next = state.findings.toMutableMap()
                drop.forEach { next.remove(it) }
                return Result(state.copy(findings = next))
            }

            is Event.CheckRequested -> {
                if (state.checking) return Result(state)
                val attempt = state.nextId
                return Result(
                    state.copy(status = "checking", attempt = attempt, nextId = attempt + 1),
                    listOf(Effect.Check(attempt)),
                )
            }

            is Event.CheckSucceeded, is Event.CheckFailed -> {
                val attempt = when (event) {
                    is Event.CheckSucceeded -> event.attempt
                    is Event.CheckFailed -> event.attempt
                    else -> return Result(state)
                }
                if (!state.checking || state.attempt != attempt) return Result(state)

                val base = state.copy(status = "idle")
                if (event is Event.CheckFailed) {
                    return Result(base, listOf(Effect.Failed(event.message)))
                }
                val succeeded = event as Event.CheckSucceeded
                val next = state.findings.toMutableMap()
                for ((target, outdated) in succeeded.outdated) {
                    if ((next[target]?.at ?: -1) > succeeded.attempt) continue
                    if (state.updating.any { it.target == target }) continue
                    next[target] = Finding(outdated, succeeded.attempt)
                }
                return Result(base.copy(findings = next))
            }

            is Event.UpdateRequested -> {
                if (state.updating.any { it.target == event.target }) return Result(state)
                val id = state.nextId
                return Result(
                    state.copy(
                        nextId = id + 1,
                        updating = state.updating + Updating(id, event.target),
                    ),
                    listOf(Effect.Update(id, event.target)),
                )
            }

            is Event.UpdateSucceeded, is Event.UpdateFailed -> {
                val id = when (event) {
                    is Event.UpdateSucceeded -> event.id
                    is Event.UpdateFailed -> event.id
                    else -> return Result(state)
                }
                val row = state.updating.firstOrNull { it.id == id } ?: return Result(state)
                val rest = state.updating.filter { it !== row }
                if (event is Event.UpdateFailed) {
                    return Result(
                        state.copy(updating = rest),
                        listOf(Effect.Failed(event.message)),
                    )
                }
                // A successful update writes `outdated:false` at the update's own id, which is
                // newer than any check, so a later check cannot resurrect the flag.
                return Result(
                    state.copy(
                        updating = rest,
                        findings = state.findings +
                            (row.target to Finding(outdated = false, at = row.id)),
                    ),
                    listOf(Effect.Refresh),
                )
            }
        }
    }

    /** What one plugin row shows. `Ks(e,t)` plus the `updating === true` override. */
    data class View(val outdated: Boolean, val updating: Boolean)

    fun view(state: State, target: String?, outdated: Boolean?, forceUpdating: Boolean = false): View {
        if (state.disposed) return View(outdated = false, updating = false)
        val busy = forceUpdating || (target != null && state.updating.any { it.target == target })
        val flag = target?.let { state.findings[it]?.outdated } ?: outdated ?: false
        return View(outdated = flag, updating = busy)
    }
}
