package com.zapp.net

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zapp.net.api.ModelInfo
import com.zapp.net.api.OpenCodeClient
import com.zapp.net.api.MessageWithParts
import com.zapp.net.api.Provider
import com.zapp.net.api.Session
import com.zapp.net.api.SessionPage
import com.zapp.net.api.SessionImport
import com.zapp.net.api.models
import com.zapp.net.guard.GuardState
import com.zapp.net.api.providers
import kotlinx.serialization.encodeToString
import com.zapp.net.ui.ConnectScreen
import com.zapp.net.ui.BootFrame
import com.zapp.net.ui.DeniedFrame
import com.zapp.net.ui.UnreachableFrame
import com.zapp.net.ui.GuardScreen
import com.zapp.net.ui.ChatScreen
import com.zapp.net.ui.Artifact
import com.zapp.net.ui.ShellScreen
import com.zapp.net.ui.theme.Accent
import com.zapp.net.ui.theme.AccentSoft
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.Surface
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.ZappTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import okhttp3.sse.EventSource

/**
 * What the server said when asked whether this device may still use it.
 *
 * the owner's requirement: "verifying if they are connected to my server, if they leave they
 * can't access, and add the option to join again". So the verdict has to be specific
 * enough to act on:
 *
 *  - [Ok]        the server answered and accepted the credential -> chat opens
 *  - [Denied]    the server answered and refused (401/403) -> access was taken away
 *  - [Unreachable] nothing came back -> the phone has no route, not a revocation
 *  - [NoServer]  no credential is stored at all -> first run, nothing to verify
 */
sealed interface GateState {
    data object Idle : GateState
    data object Checking : GateState
    data object Ok : GateState
    data object Denied : GateState
    data class Unreachable(val detail: String) : GateState
    data object NoServer : GateState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = getApplication<Application>()

    /**
     * Upload progress for the file body currently on the wire, or null.
     *
     * `ui.promptInput.uploading` = `"{{percent}}%"`. The server writes attachment bytes before
     * it answers, so a 13 MB photo can occupy the POST for a long time; without this the
     * composer looked dead for up to 180 s and a working send read as a broken one.
     */
    val upload = MutableStateFlow<Upload?>(null)

    data class Upload(val sent: Long, val total: Long) {
        val percent: Int get() = if (total <= 0) 0 else ((sent * 100) / total).toInt().coerceIn(0, 100)
    }

    private var _client: OpenCodeClient? = null
    var client: OpenCodeClient?
        get() = _client
        private set(value) {
            _client = value
            upload.value = null
            value?.onUploadProgress = { sent, total ->
                upload.value = Upload(sent, total)
                // A body that has fully written is done; the server may still be thinking, and
                // that state is `sending`, not `uploading`.
                if (total > 0 && sent >= total) upload.value = null
            }
        }

    val connected = MutableStateFlow(false)
    val status = MutableStateFlow("")

    /**
     * True when [status] is reporting a failure rather than progress.
     *
     * The strip used to paint every status line in `TextDim`, so `Messages failed: Failed to
     * connect to /127.0.0.1:4096` arrived in the same grey as "Moved to background" - an error
     * with no colour at all. the owner, 21 Nov: "why i dont see colors numbers etc".
     *
     * The flag is set by the writer, never inferred from the text at draw time: a message that
     * happens to contain the word "failed" (a model name, a shell's own output) is not an error.
     */
    val statusFailed = MutableStateFlow(false)

    /** A status line that reports progress or success. Clears [statusFailed]. */
    fun statusOk(text: String) {
        status.value = text
        statusFailed.value = false
    }

    /** A status line that reports a failure. Painted in `Danger` (`v2-red-500`). */
    fun statusFail(text: String) {
        status.value = text
        statusFailed.value = true
    }

    /**
     * `toast.permissions.autoaccept.*` - a two-line message that clears itself.
     *
     * PC raises one when auto-accept is turned on or off (`notification.tsx`'s toast surface).
     * A phone has no toast queue worth porting, so this is the smallest thing that carries the
     * same two strings: title, description, gone after [TOAST_MS].
     */
    data class Toast(val title: String, val description: String)

    val toast = MutableStateFlow<Toast?>(null)

    /** Identity-guarded: a second toast must not be cleared early by the first one's timer. */
    fun showToast(title: String, description: String) {
        val t = Toast(title, description)
        toast.value = t
        viewModelScope.launch {
            kotlinx.coroutines.delay(TOAST_MS)
            if (toast.value === t) toast.value = null
        }
    }

    /** Turns `permissions.autoApprove` on or off and says so, the way their command does. */
    fun setAutoAccept(on: Boolean) {
        com.zapp.net.settings.DisplayPrefs.setAutoApprove(ctx, on)
        showToast(
            com.zapp.net.ui.PermissionCopy.toastTitle(on),
            com.zapp.net.ui.PermissionCopy.toastDescription(on),
        )
        // Their three effects on the switch: the connect sweep starts or stops, and any cycle
        // the new setting no longer covers is cancelled so a scheduled retry never answers for
        // him. Anything already waiting is picked up by the sweep, not by a one-off pass.
        autoSyncSweep()
        autoCancelUncovered()
    }

    /**
     * True while the app is still deciding whether it can go straight to the chat.
     *
     * Without this, `connected` starts `false` and `Root` renders [com.zapp.net.ui.ConnectScreen]
     * on every cold start, even when a saved token is about to reconnect a few hundred
     * milliseconds later. That flash is what the owner reads as "a loading screen": it is the
     * login screen, shown for no reason. A Saved login must never show a login form.
     *
     * Cleared by [tryReconnect] on every exit path, plus a watchdog in `init` so a settings
     * store that never emits cannot hold the app on the holding frame.
     */
    val booting = MutableStateFlow(true)

    /**
     * Result of the server check that runs before the chat is shown.
     *
     * [GateState.Idle] only exists before the first probe. The four outcomes are deliberately
     * distinct because the screen says something different for each, and "wrong password"
     * must never be shown for "no network".
     */
    val gate = MutableStateFlow<GateState>(GateState.Idle)

    /**
     * True for the length of a connect attempt.
     *
     * Drives the button label, so the user sees `Connect` -> `Verifying...` -> `Connected`
     * instead of a button that looks idle while a 2 s round trip is in flight. the owner asked
     * for exactly this: "continue text continued or continue".
     */
    val connecting = MutableStateFlow(false)

    val sessions = MutableStateFlow<List<Session>>(emptyList())

    /**
     * `GET /api/session/active` -> `{sessionID: status}`.
     *
     * Their client reads it on every `server.connected` (`solid-B85sHMHe.js` @ 8 880) and folds
     * it into the sidebar's status map. ZAPP's drawer shows the same thing as a dot, because the
     * only other place a running session was visible was the chat you were already looking at.
     */
    val activeRuns = MutableStateFlow<Map<String, String>>(emptyMap())
    val activeSession = MutableStateFlow<Session?>(null)
    val messages = MutableStateFlow<List<MessageWithParts>>(emptyList())
    val sessionTotals = MutableStateFlow<Session?>(null)
    val sending = MutableStateFlow(false)
    /**
     * The assistant message that most recently received a streamed delta.
     *
     * The reveal in `ChatScreen` needs to know a reply is *new*, not just that the turn is
     * still busy. The server ends a turn with one whole-text delta and then `session.idle`
     * back to back, so `isLast && sending` can already be false by the time that text is
     * composed - and the block drew at full length, which is the jump the owner sees. This id
     * says "these characters came off the wire on this turn", so the reveal can type them
     * without depending on the turn status at all. History never matches it.
     */
    val liveMsgId = MutableStateFlow<String?>(null)

    /**
     * True while the server is rewriting the history (`POST /api/session/:id/compact`).
     *
     * `ui.messagePart.compaction.running` = "Compacting". Both compaction paths - the automatic
     * one before a heavy turn and the `/compact` command - were completely silent on screen, so
     * the app looked idle while the whole conversation was being summarised.
     */
    val compacting = MutableStateFlow(false)
    val shells = MutableStateFlow<List<com.zapp.net.api.ShellEntry>>(emptyList())    // their Pending queue, straight from GET /api/session/:id/inbox
    val inbox = MutableStateFlow<List<com.zapp.net.api.InboxItem>>(emptyList())

    init {
        // Mirror the live-turn flag into the guard so the tunnel never rebuilds mid-stream.
        // Measured 21:42:33 VERIFY ok -> 21:42:35 a rebuild replaced the device -> the turn
        // died with ECONNRESET, then EAI_AGAIN for DNS while the tun was mid-swap.
        viewModelScope.launch {
            sending.collect { com.zapp.net.guard.GuardState.setStreaming(it) }
        }

        // The settings store is a DataStore read; if it never emits (corrupt file, a cold
        // read that hangs) nothing would ever clear [booting] and the holding frame would sit
        // there for good - a worse failure than the flash it replaces. Six seconds is far
        // longer than the measured reconnect (the phone answers /api/agent in under 2 s).
        viewModelScope.launch {
            kotlinx.coroutines.delay(6_000)
            if (booting.value) {
                booting.value = false
                android.util.Log.e("ZAPP", "boot watchdog: no settings emission in 6s, released")
            }
        }

        // A turn the server still calls `busy` but that stopped moving is a zombie.
        //
        // Measured 22 Nov 14:54 on `ses_e17ab07f8ffeDWoK1kI1OF6OUI`: `time.busy` was
        // 100 minutes newer than `time.idle`, `time.updated` had not moved in 34 minutes,
        // the transcript had not grown, and no `session.status` frame was coming - because
        // the provider socket died mid-answer and the server never wrote `idle`. The app was
        // right to show "busy": the server said so. What he saw was a caret blinking once a
        // second with no text behind it, a composer that refused a new prompt, and 115% CPU.
        // Nothing would ever end it, because the frame that ends a turn is the frame that
        // never came.
        //
        // Tapping Stop fixes it - this is that tap, on a timer. The test is the session's
        // own `updated` stamp, not silence alone: a real long tool call keeps writing parts
        // and keeps `updated` moving, so it is never cut. Only a session that has stopped
        // moving *while the server still claims to be working it* is interrupted.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                runCatching {
                    if (!sending.value) return@runCatching
                    val s = activeSession.value ?: return@runCatching
                    val detail = client?.sessionDetail(s.id) ?: return@runCatching
                    if (detail.time?.isBusy != true) return@runCatching
                    val now = System.currentTimeMillis()
                    val updated = detail.time?.updated ?: 0L
                    // Two different silences, and they are measured from different clocks.
                    //
                    // A tool run is legitimately quiet: `updated` moves when the tool's state
                    // changes, which for a five-minute build means once at the start and once at
                    // the end. Cutting that at 45 s would kill real work, so it keeps the full
                    // ZOMBIE_MS and is judged by the server's own stamp.
                    //
                    // An *open text or reasoning part* is judged by [lastDeltaAt] instead - the
                    // last time a delta actually landed on this phone. The server's `updated` is
                    // NOT a streaming heartbeat; it does not advance while a text part is being
                    // appended, so judging a stream by it is how a healthy long reply gets cut.
                    // This is the correction the owner asked for on 22 Nov: "or u are the one who tap
                    // it" - the watchdog must never be able to interrupt a stream that is still
                    // delivering words.
                    //
                    // `hadContent` is the safety catch: a part that has never held a single
                    // character is still waiting for its first token, so it gets the slow path.
                    val row = messages.value.lastOrNull()
                    val tailPart = row?.parts?.lastOrNull()
                    val tailIsOpenText = tailPart != null &&
                        tailPart.time?.completed == null &&
                        (tailPart.type == "text" || tailPart.type == "reasoning")
                    val hadContent = row?.parts?.any { !it.text.isNullOrEmpty() } == true
                    val silentFor: Long
                    val limit: Long
                    if (tailIsOpenText && hadContent && lastDeltaAt > 0L) {
                        silentFor = now - lastDeltaAt
                        limit = DEAD_STREAM_MS
                    } else {
                        if (updated == 0L) return@runCatching
                        silentFor = now - updated
                        limit = ZOMBIE_MS
                    }
                    if (silentFor < limit) return@runCatching
                    android.util.Log.e(
                        "ZAPP",
                        "zombie turn: busy, silent ${silentFor / 1000}s " +
                            "(limit ${limit / 1000}s, openText=$tailIsOpenText, " +
                            "sinceDelta=${if (lastDeltaAt > 0L) (now - lastDeltaAt) / 1000 else -1}s) " +
                            "-> interrupt ${s.id}",
                    )
                    client?.interrupt(s.id)
                    sending.value = false
                    loadMessages(s)
                }.onFailure { android.util.Log.e("ZAPP", "zombie watchdog: $it") }
            }
        }
    }

    /** How long a session may stand still while the server still calls it busy. */
    private val ZOMBIE_MS = 180_000L

    /**
     * How long an *already-flowing* text or reasoning stream may go silent before the turn is
     * declared dead. Only used when the open part has content, so a model still waiting on its
     * first token is never cut. See the watchdog for the measurement behind it.
     *
     * 45 s was too short for the same reason the socket deadline was: measured 23 Nov, the
     * engine answers /api/info in 4-5 ms when healthy but intermittently closes a connection
     * with no reply for ~2.5 s, and a step that runs a shell tool emits NO deltas while the
     * tool runs - `Used 1 Shell` then `Step interrupted 2m 17s` in the transcript is exactly
     * this watchdog cutting a tool step that was still working. A tool may legitimately run
     * for a minute or more, so this only has to be longer than the longest honest gap.
     */
    private val DEAD_STREAM_MS = 120_000L

    /**
     * The real quota wall, as the server classified it (`session.step.failed` ->
     * `data.error.type`). Held so the transcript can show their free-limit card and the
     * footer can say what is happening; cleared as soon as a turn runs again.
     */
    val quotaWall = MutableStateFlow<com.zapp.net.guard.QuotaWall?>(null)

    // ---- new-session base branch (`route-BvCVPSgV.js` `wr` + `cr`) ---------------------------

    /**
     * The draft's base branch, keyed by draft id. `null` is the **main checkout**, which is
     * their `selection.set("main")`; anything else is a branch name.
     *
     * Their `selection.branch(...)` is a draft-level selection and nothing else: no checkout
     * route is called, nothing on disk moves. The value is carried into the session when the
     * draft is promoted by the first prompt.
     */
    private val branchByDraft = mutableMapOf<String, String?>()

    /** The active draft's selection, or `null` for the main checkout. */
    val draftBranch = MutableStateFlow<String?>(null)

    /** `cr`'s state for the picker that is on screen right now. */
    val branchState = MutableStateFlow<com.zapp.net.ui.BranchMachine.State>(
        com.zapp.net.ui.BranchMachine.State.Inactive(),
    )

    private val branchJobs = mutableMapOf<Int, Job>()

    /**
     * `g` - reduce one event, then run the effects it asked for.
     *
     * Everything the machine needs is in `BranchMachine`; this only supplies the two things
     * the reducer deliberately leaves out: the HTTP call and the draft selection.
     */
    fun branchDispatch(e: com.zapp.net.ui.BranchMachine.Event) {
        val r = com.zapp.net.ui.BranchMachine.reduce(branchState.value, e)
        branchState.value = r.state
        r.effects.forEach { runBranchEffect(it) }
    }

    /**
     * Their component is rebuilt per mount (`l(Te)` in their harness): a `disposed` machine
     * ignores every event, so re-opening the surface must start from `ir`, not from the corpse.
     */
    fun branchReset() {
        branchJobs.values.forEach { it.cancel() }
        branchJobs.clear()
        branchState.value = com.zapp.net.ui.BranchMachine.State.Inactive()
    }

    /**
     * The palette's own `source(query, signal)`:
     *
     *     source: async (t, n) => {
     *       let r = e.workspace.project.root(), i = await e.workspace.branches(t, n);
     *       return t || g({ type: 'branches.synced', directory: r, items: i }), i
     *     }
     *
     * `null` is a failed call, which is what draws `Try again`. Their 100 ms debounce lives
     * in the composable, next to the query it debounces.
     */
    suspend fun branchSearch(query: String): List<String>? = runCatching {
        client?.vcsBranches(
            search = query,
            limit = com.zapp.net.ui.BranchMachine.FETCH_LIMIT,
            directory = null,
        )?.map { it.name }
    }.getOrNull()

    /** `bar.checkout()?.branch` - the muted subtitle beside `Use main checkout`. */
    suspend fun branchCheckout(): String? = runCatching {
        client?.vcsInfo(null)?.branch?.current
    }.getOrNull()

    private fun runBranchEffect(e: com.zapp.net.ui.BranchMachine.Effect) {
        when (e) {
            is com.zapp.net.ui.BranchMachine.Effect.BranchesFetch -> {
                branchJobs.remove(e.attempt)?.cancel()
                branchJobs[e.attempt] = viewModelScope.launch {
                    // `rr` - the 10 s deadline. It settles the state as **failed** and then
                    // aborts the request, which is what turns a wedged server into their
                    // `Try again` row instead of a spinner that never stops.
                    val timer = launch {
                        delay(com.zapp.net.ui.BranchMachine.TIMEOUT_MS.toLong())
                        branchDispatch(com.zapp.net.ui.BranchMachine.Event.BranchesTimedOut(e.attempt))
                    }
                    try {
                        val c = client
                        if (c == null) {
                            timer.cancel()
                            branchDispatch(
                                com.zapp.net.ui.BranchMachine.Event.BranchesFailed(e.attempt),
                            )
                            return@launch
                        }
                        // `workspace.branches("", signal)` - the machine's own fetch is always
                        // the empty query; the palette filters client-side.
                        val items = c.vcsBranches(
                            limit = com.zapp.net.ui.BranchMachine.FETCH_LIMIT,
                            // `""` is the server's default directory. Their `project.root()`
                            // has no phone equivalent: there is exactly one project here and
                            // the server's cwd IS it.
                            directory = e.directory.ifBlank { null },
                        ).map { it.name }
                        timer.cancel()
                        branchDispatch(
                            com.zapp.net.ui.BranchMachine.Event.BranchesLoaded(e.attempt, items),
                        )
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        timer.cancel()
                        branchDispatch(
                            com.zapp.net.ui.BranchMachine.Event.BranchesFailed(e.attempt),
                        )
                    }
                }
            }
            is com.zapp.net.ui.BranchMachine.Effect.BranchesAbort -> {
                branchJobs.remove(e.attempt)?.cancel()
            }
            com.zapp.net.ui.BranchMachine.Effect.FocusComposer -> {
                composerFocusTick.value = composerFocusTick.value + 1
            }
            is com.zapp.net.ui.BranchMachine.Effect.BranchChoose -> setDraftBranch(e.branch)
            com.zapp.net.ui.BranchMachine.Effect.CheckoutUse -> setDraftBranch(null)
        }
    }

    /** `selection.branch(x)` / `selection.set("main")` - a draft selection, never a checkout. */
    private fun setDraftBranch(branch: String?) {
        val id = activeDraft.value ?: return
        branchByDraft[id] = branch
        draftBranch.value = branch
    }

    /** Bumped when something asks the composer for focus (`focus.composer`). */
    val composerFocusTick = MutableStateFlow(0L)

    /** scope -> text to put back in the composer after a wall (their `cr.set(er)`). */
    val restorePrompt = MutableStateFlow<Pair<String, String>?>(null)

    /**
     * Tokens of context measured on the turn that walled, when that context is the reason.
     *
     * Measured 2026-11-19 23:16 on ONE address (`2a09:bac5:4ff1:2646::/64`):
     *
     *   tiny session, cacheRead 5 888  -> 35 prompts, 35 successes, zero walls
     *   this session, cacheRead 108 416 -> 429 `provider.free-limit` on the first prompt
     *
     * So the free-tier refusal is a **per-request context gate**, not an allowance the
     * address spends down: a fresh /64 cannot clear it, and rotating only interrupts the
     * chat. When this is set the UI says so and offers a new chat instead of pretending a
     * rotation will help.
     */
    val contextTooBig = MutableStateFlow<Long?>(null)

    /** Context tokens of the chat on screen, keyed by session so a stale value cannot leak. */
    val contextOfSession = MutableStateFlow<Pair<String, Long>?>(null)

    /**
     * The resend loop that owns a wall once it fires.
     *
     * Measured 2026-11-19 23:35-23:44: the same prompt that came back `provider.free-limit`
     * was answered four minutes later on the same tunnel, and a brand-new session answered
     * in the same minute. A wall is therefore a wait, not a verdict. This job holds the
     * refused prompt and re-sends it on a widening schedule so one reply does not mean the
     * chat is dead until 09:04.
     */
    private var quotaRetryJob: kotlinx.coroutines.Job? = null

    /** Every wall increments this, so a retry can tell "taken" from "walled again". */
    @Volatile private var wallSeq = 0

    /** Context tokens measured on the last send, for the retry's size verdict. */
    @Volatile private var lastContextSeen = 0L

    /**
     * Their permission gate. A tool that asks cannot run until one of these is answered, so
     * an unanswered request looks exactly like a frozen turn. Server routes:
     *   GET  /api/session/:id/permission          (per session)
     *   GET  /api/permission/request              (everything outstanding)
     *   POST /api/session/:id/permission/:rid/reply {decision: once|always|reject}
     */
    val permissions = MutableStateFlow<List<com.zapp.net.api.PermissionRequest>>(emptyList())

    /** GET /api/command ï¿½ the server's own slash commands (init, review, goal on 2.3.2). */
    val commands = MutableStateFlow<List<com.zapp.net.api.CommandEntry>>(emptyList())

    /** Their form cards: a tool (or MCP elicitation) asking structured questions. */
    val forms = MutableStateFlow<List<com.zapp.net.api.FormInfo>>(emptyList())

    /**
     * `question.v2.*` - the `question` tool's blocking requests.
     *
     * Separate from [forms] on purpose: a form is a schema of fields, a question is a
     * fixed list of choices the model is waiting on. The server keeps them in two
     * different stores (`Question.state` vs the form registry) and exposes two
     * different route families, so folding them together here would mean inventing a
     * mapping the server does not have.
     */
    val questions = MutableStateFlow<List<com.zapp.net.api.QuestionRequest>>(emptyList())

    /** Rules that `Always` wrote - the same list their permission settings shows. */
    val savedPerms = MutableStateFlow<List<com.zapp.net.api.SavedPermission>>(emptyList())

    /**
     * Review panel state, ported from `gui-plugins/src/review/model.ts`.
     *
     * `modes`: the modes the session offers (`options()`, line 365) ï¿½ `git` when the location
     * has a provider, `branch` only when there is a default branch and the current one differs,
     * `turn` only for Git sessions with an id.
     * `mode`: the shown mode (`mode()`, line 411) ï¿½ the stored choice while it is offered,
     * otherwise the first offered one.
     * `reviewFiles`: `FileDiff.Info[]` from `/api/vcs/diff` (or `/session/:id/diff` for turn).
     */
    val reviewMode = MutableStateFlow("git")
    val reviewModes = MutableStateFlow(listOf("git"))
    val reviewFiles = MutableStateFlow<List<com.zapp.net.api.PartFile>>(emptyList())
    val reviewNoVcs = MutableStateFlow(false)
    val reviewLoading = MutableStateFlow(false)
    val reviewError = MutableStateFlow("")

    // `review/model.ts:1033-1041` - the session store keeps the review's `open` list and its
    // active `file`; the narrow review reads both back when the session reopens.
    val reviewOpenFiles = MutableStateFlow<Set<String>>(emptySet())
    val reviewActiveFile = MutableStateFlow<String?>(null)

    /** Server-side list state: search text, archive filter, page cursor. */
    val sessionSearch = MutableStateFlow("")
    val showArchived = MutableStateFlow(false)
    val sessionCursor = MutableStateFlow<String?>(null)

    // â”€â”€ PORT of `home/sessions/load-machine.ts` â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Desktop keeps the home list's phase in an explicit machine instead of a
    // boolean. Copying the four timings is what makes its loading feel different
    // from ours: nothing is shown for the first 250 ms, placeholder rows appear
    // only if the fetch is still out, and the list then fades in over them for
    // 200 ms rather than snapping. The fetch itself has a 30 s deadline and a
    // failed refresh keeps the list it was showing.
    //   :19 HOME_SESSIONS_PLACEHOLDER_DELAY_MS = 250
    //   :25 HOME_SESSIONS_REVEAL_MS = 200
    //   :11 HOME_SESSIONS_FETCH_TIMEOUT_MS = 30_000
    //   queries.ts:43 staleTime = 30_000
    private val homeSessionsPlaceholderDelayMs = 250L
    private val homeSessionsRevealMs = 200L
    private val homeSessionsFetchTimeoutMs = 30_000L
    private val homeSessionsStaleMs = 30_000L

    /** `listView.status === "loading" && listView.skeleton` - placeholder rows are up. */
    val listSkeleton = MutableStateFlow(false)

    /** `listView.status === "ready" && listView.revealing` - list fading in over the placeholders. */
    val listRevealing = MutableStateFlow(false)

    /** `listView.status === "error"` description; empty while healthy. */
    val listError = MutableStateFlow<String?>(null)

    /** True once a list has ever answered, so an empty board can say "Nothing here yet". */
    val listLoaded = MutableStateFlow(false)

    private var listLoading = false
    private var listQueued = false
    private var listIssued = 0
    private var listFetchedAt = 0L


    /** Their "Open file" (`ui.sessionReview.openFile`): GET /api/fs/read/<path>, shown over the chat.
     *  A file whose kind is an image is read as **bytes** instead of text - `review/model.ts:784-798`
     *  reads a preview's bytes and hands them on with their `artifactMime`, because decoding a PNG
     *  as text destroys it. */
    data class FileView(
        val path: String,
        val text: String? = null,
        val busy: Boolean = true,
        val error: String? = null,
        val bytes: ByteArray? = null,
    )

    val fileView = MutableStateFlow<FileView?>(null)

    /**
     * Every file tab that is open, keyed by the path it was opened with.
     *
     * Their registry holds one tab per `file:open:<path>`; `fileView` alone could only ever hold
     * the newest one, so a second file replaced the first instead of opening beside it.
     */
    val fileViews = MutableStateFlow<Map<String, FileView>>(emptyMap())

    /** Every file tab currently being watched, so a close can unwatch the same path. */
    private val watchedFiles = mutableMapOf<String, String>()

    fun openFile(path: String) {
        val c = client ?: return
        val root = activeSession.value?.dir
        val relative = !Regex("^[A-Za-z]:|^/").containsMatchIn(path)
        val candidates = mutableListOf(path)
        // timeline cards carry project-relative paths; the server reads from its own root
        if (root != null && relative) {
            candidates += root.trimEnd('\\', '/') + "\\" + path.replace('/', '\\')
        }
        val binary = Artifact.isImage(Artifact.kindOf(path))
        // A binary that is not an image has no preview here, and reading it as text is what
        // crashed the app on the 113 MB apk (`OutOfMemoryError` inside `ResponseBody.string`).
        // Refuse before a byte is requested, and say why.
        val noPreview = !binary && Artifact.isBinaryFile(path)
        fun put(v: FileView) {
            fileView.value = v
            fileViews.value = fileViews.value + (v.path to v)
        }
        put(FileView(path))
        if (noPreview) {
            val ext = path.substringAfterLast('.', "").lowercase().ifBlank { "file" }
            put(
                FileView(
                    path, null, false,
                    "No preview for .$ext - the agent can still read it by path.",
                ),
            )
            return
        }
        viewModelScope.launch {
            var last: String? = null
            var askedLocation = false
            var i = 0
            while (i < candidates.size) {
                val cand = candidates[i++]
                try {
                    put(
                        if (binary) {
                            FileView(path, null, false, null, c.readBytes(cand))
                        } else {
                            FileView(path, c.readFile(cand), false, null)
                        }
                    )
                    // Their `focus(e)` -> `screen.file.sync(path, {silent:true})`; the watch is
                    // opened on the path that actually resolved, so a project-relative card and
                    // an absolute tab never disagree about what is being watched.
                    if (watchedFiles[path] != cand) {
                        c.fsWatch(cand, root)
                        watchedFiles[path] = cand
                    }
                    return@launch
                } catch (e: Exception) {
                    last = e.message
                }
                // `vu()`'s second link: when the session directory is not the repository root,
                // the same relative path may only exist under `project.canonical`. Asked once,
                // after the cheap candidates have both failed.
                if (!askedLocation && relative && i == candidates.size) {
                    askedLocation = true
                    val dir = root ?: continue
                    val canonical = c.locationInfo(dir)?.project?.canonical
                    if (!canonical.isNullOrBlank()) {
                        val next = canonical.trimEnd('\\', '/') + "\\" + path.replace('/', '\\')
                        if (next !in candidates) candidates += next
                    }
                }
            }
            put(FileView(path, null, false, last ?: "failed to read"))
        }
    }

    /** Closing one file tab. With no argument it is the one the old single-slot viewer held. */
    fun closeFile(path: String? = null) {
        val key = path ?: fileView.value?.path ?: return
        fileViews.value = fileViews.value - key
        if (fileView.value?.path == key) fileView.value = null
        // Their tab `close` runs `screen.file.unwatch(...)`; a watch left open would keep the
        // server reading a file nobody is looking at.
        watchedFiles.remove(key)?.let { resolved ->
            client?.let { c -> viewModelScope.launch { c.fsUnwatch(resolved, activeSession.value?.dir) } }
        }
    }

    /**
     * Re-read a file tab without clearing it first.
     *
     * This is their `focus(e)` behaviour: `screen.file.sync(path, {silent:true})` refreshes the
     * bytes and keeps the scroll. A failed re-read leaves the previous content in place rather
     * than blanking the pane, which is why the error slot is only written on the first load.
     */
    fun reloadFile(path: String) {
        val c = client ?: return
        val resolved = watchedFiles[path] ?: path
        val binary = Artifact.isImage(Artifact.kindOf(path))
        viewModelScope.launch {
            runCatching {
                val v = if (binary) FileView(path, null, false, null, c.readBytes(resolved))
                else FileView(path, c.readFile(resolved), false, null)
                fileViews.value = fileViews.value + (path to v)
                if (fileView.value?.path == path) fileView.value = v
            }
        }
    }

    // ---- persistent pty: the terminal their panel opens ----
    //
    // Their terminal tab is not a shell list: `session.panel` order 20 registers
    // `{id: "new", icon: "terminal"}` and a tap **creates a persistent pty**
    // (`index-BWsziAR3.js` @ 603 601), which then lives as its own tab. Closing the tab removes
    // the pty. The session's live terminals are `GET .../terminal`.

    data class PtyInfo(val id: String, val title: String)

    val ptys = MutableStateFlow<List<PtyInfo>>(emptyList())

    /** One driver per open pty. Survives tab switching, dies with the tab. */
    private val ptySessions = mutableMapOf<String, com.zapp.net.api.PtySession>()

    fun ptySession(id: String): com.zapp.net.api.PtySession? = ptySessions[id]

    /**
     * Their title numbering (`index-BWsziAR3.js` @ 588 294): `Terminal {{number}}`, and the next
     * number is the first one not already taken by a live pty. Their localisation table has the
     * same shape in eight languages, each with the number at the end, so a title that ends in
     * `[1-9][0-9]*` after one of those prefixes is a numbered terminal.
     */
    private val titlePrefixes = listOf(
        "Terminal ", "\u0645\u062d\u0637\u0629 \u0637\u0631\u0641\u064a\u0629 ",
        "\u0422\u0435\u0440\u043c\u0438\u043d\u0430\u043b ",
        "\u30bf\u30fc\u30df\u30ca\u30eb ", "\ud130\ubbf8\ub110 ",
        "\u0e40\u0e17\u0e2d\u0e23\u0e4c\u0e21\u0e34\u0e19\u0e31\u0e25 ",
        "\u7ec8\u7aef ", "\u7d42\u7aef\u6a5f ",
    )

    /** `Qz(e)` - the number a title carries, or null. */
    fun ptyNumber(title: String): Int? = titlePrefixes.firstNotNullOfOrNull { p ->
        if (!title.startsWith(p)) null else title.removePrefix(p).toIntOrNull()?.takeIf { it > 0 }
    }

    /** `Zz(n)` */
    fun ptyTitle(n: Int): String = "Terminal $n"

    fun loadPtys() {
        val c = client ?: return
        val session = activeSession.value ?: return
        viewModelScope.launch {
            runCatching { c.ptyListForSession(session.id) }.onSuccess { body ->
                val list = parsePtyList(body)
                ptys.value = list
            }
        }
    }

    private fun parsePtyList(body: String): List<PtyInfo> = runCatching {
        val root = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .parseToJsonElement(body)
        val arr = when (root) {
            is kotlinx.serialization.json.JsonArray -> root
            is kotlinx.serialization.json.JsonObject ->
                (root["data"] as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
            else -> return emptyList()
        }
        arr.mapNotNull { el ->
            val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            val title = (o["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            PtyInfo(id, title)
        }
    }.getOrDefault(emptyList())

    /**
     * Create a pty in the session's directory, start its driver, and return it.
     *
     * `cwd` is `session.location.directory` - the same value their create call passes - so a
     * terminal opens where the conversation is.
     */
    suspend fun createPty(): PtyInfo? {
        val c = client ?: return null
        val session = activeSession.value ?: return null
        val taken = ptys.value.mapNotNull { ptyNumber(it.title) }.toSet()
        var n = 1
        while (taken.contains(n)) n++
        val created = runCatching {
            c.ptyCreateForSession(session.id, session.dir, ptyTitle(n))
        }.getOrNull() ?: return null
        val info = parsePtyList(created).firstOrNull()
            ?: parsePtyObject(created)
            ?: return null
        ptys.value = ptys.value + info
        startPty(info.id)
        return info
    }

    private fun parsePtyObject(body: String): PtyInfo? = runCatching {
        val o = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject ?: return null
        val inner = (o["data"] as? kotlinx.serialization.json.JsonObject) ?: o
        val id = (inner["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
        PtyInfo(id, (inner["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty())
    }.getOrNull()

    /** Attach a driver to a pty if one is not already running. Idempotent. */
    fun startPty(id: String) {
        if (ptySessions.containsKey(id)) return
        val c = client ?: return
        val s = com.zapp.net.api.PtySession(
            client = c,
            ptyId = id,
            attachmentID = "zapp-" + java.util.UUID.randomUUID().toString().take(8),
            scope = viewModelScope,
        )
        ptySessions[id] = s
        s.start()
    }

    /** Closing the tab: their `remove` route, then drop the driver. */
    fun killPty(id: String) {
        ptySessions.remove(id)?.close(kill = true)
        ptys.value = ptys.value.filterNot { it.id == id }
    }

    // ---- the Context tab's data ----

    /** `session.get` + every `message.list` page, which is what their export composes. */
    suspend fun contextPayload(sessionId: String): com.zapp.net.api.OpenCodeClient.ContextPayload? =
        client?.let { runCatching { it.contextPayload(sessionId) }.getOrNull() }

    /** `JSON.stringify({info, messages}, null, 2)` and their filename rule. */
    suspend fun exportSession(sessionId: String): Pair<String, String> {
        val c = client ?: throw IllegalStateException("no client")
        return c.exportSessionJson(sessionId)
    }

    /**
     * Their `saveFile({defaultPath}, json)` is a desktop save dialog. A phone has one obvious
     * destination, so the file goes to `Download/` through MediaStore - visible in Files, no
     * permission needed on a modern target.
     */
    fun saveExport(name: String, json: String): String? = runCatching {
        val resolver = ctx.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                )
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values,
        ) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        showToast(com.zapp.net.ui.ContextCopy.EXPORT_OK_TITLE, com.zapp.net.ui.ContextCopy.exportOkBody(name))
        uri.toString()
    }.getOrNull()

    /**
     * `markdown-image.ts`'s reader. A local source goes through `fs/read` with the same
     * project-relative fallback as [openFile]; an `http(s)` source is fetched directly,
     * because that is what the browser does for an unmarked image in their DOM.
     *
     * Returns null for anything unreadable, which their machine treats as `read.failed`.
     */
    suspend fun readImageBytes(src: String): ByteArray? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val c = client ?: return@withContext null
        if (src.startsWith("http://", true) || src.startsWith("https://", true)) {
            return@withContext runCatching {
                val conn = java.net.URL(src).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = true
                conn.inputStream.use { it.readBytes() }
            }.getOrNull()
        }
        val root = activeSession.value?.dir
        val candidates = buildList {
            add(src)
            if (root != null && !Regex("^[A-Za-z]:|^/").containsMatchIn(src)) {
                add(root.trimEnd('\\', '/') + "\\" + src.replace('/', '\\'))
            }
        }
        for (cand in candidates) {
            runCatching { return@withContext c.readBytes(cand) }
        }
        null
    }

    // ---- their permission gate / slash commands / agents ----

    /**
     * `settings/model.tsx:316` - `permissions.autoApprove`, default `false`.
     *
     * Replying `once` rather than `always` is deliberate. `always` writes a rule to the
     * server's saved-permission list (`savedPerms`), which would outlive the switch being
     * turned back off; `once` answers this request and nothing else, so switching
     * auto-accept off is immediately true again.
     *
     * The reply lifecycle is theirs (`app/src/session/requests/auto-approve-machine.ts`): a
     * `runId` names one approval cycle and an `attempt` one reply within it, so a reply or a
     * timer from a cancelled or resolved cycle can never move a later one. Three replies at
     * most, 1 s then 2 s apart, 15 s each; after that the request is surfaced instead of being
     * hidden behind an answer that never landed.
     */
    private val autoRepliedLimit = 1000

    /**
     * `permissions.autoApprove` is the only producer of a scoped choice on the phone. Desktop
     * also stores `autoApproveProjects`; `DisplayPrefs` does not yet, so the override map is
     * empty and every project falls back to the default - their `override ?? default` with no
     * override present.
     */
    private fun permissionScope() = com.zapp.net.ui.PermissionScope(
        scope = "local",
        default = com.zapp.net.settings.DisplayPrefs.autoApprove.value,
    )

    /** Their `requests` store: an absent entry means `pending`. */
    private val autoStates =
        java.util.concurrent.ConcurrentHashMap<String, com.zapp.net.ui.AutoApproveState>()

    private val autoReplyTimers = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val autoRetries = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    @Volatile
    private var autoSweepState: com.zapp.net.ui.AutoApproveSweepState =
        com.zapp.net.ui.INITIAL_AUTO_APPROVE_SWEEP_STATE
    private var autoSweepJob: kotlinx.coroutines.Job? = null
    private var autoSweepTimer: kotlinx.coroutines.Job? = null
    private var autoSweepRetry: kotlinx.coroutines.Job? = null

    /** Their `uuid()` - one id per approval cycle. */
    private fun autoRunId() = java.util.UUID.randomUUID().toString()

    private fun autoDispatch(requestID: String, event: com.zapp.net.ui.AutoApproveEvent) {
        val state = autoStates[requestID] ?: com.zapp.net.ui.INITIAL_AUTO_APPROVE_STATE
        val result = com.zapp.net.ui.AutoApproveMachine.transition(state, event)
        if (result.changed) {
            if (result.state is com.zapp.net.ui.AutoApproveState.Pending) {
                autoStates.remove(requestID)
            } else {
                autoStates[requestID] = result.state
            }
            if (result.state is com.zapp.net.ui.AutoApproveState.Replied) autoPruneReplied()
        }
        result.effects.forEach { autoExecute(requestID, it) }
    }

    private fun autoExecute(requestID: String, effect: com.zapp.net.ui.AutoApproveEffect) {
        when (effect) {
            is com.zapp.net.ui.AutoApproveEffect.ClearReplyTimer -> {
                autoReplyTimers.remove(requestID)?.cancel()
            }

            is com.zapp.net.ui.AutoApproveEffect.Reply -> {
                autoReplyTimers.remove(requestID)?.cancel()
                autoReplyTimers[requestID] = viewModelScope.launch {
                    kotlinx.coroutines.delay(effect.timeoutMs)
                    autoDispatch(
                        requestID,
                        com.zapp.net.ui.AutoApproveEvent.ReplyTimedOut(effect.runId, effect.attempt),
                    )
                }
                val c = client
                if (c == null) {
                    autoDispatch(
                        requestID,
                        com.zapp.net.ui.AutoApproveEvent.ReplyFailed(effect.runId, effect.attempt),
                    )
                    return
                }
                viewModelScope.launch {
                    val ok = runCatching { c.replyPermission(effect.sessionID, requestID, "once") }.isSuccess
                    if (ok) permissions.value = permissions.value.filterNot { it.id == requestID }
                    autoDispatch(
                        requestID,
                        if (ok) {
                            com.zapp.net.ui.AutoApproveEvent.ReplySucceeded(effect.runId, effect.attempt)
                        } else {
                            com.zapp.net.ui.AutoApproveEvent.ReplyFailed(effect.runId, effect.attempt)
                        },
                    )
                }
            }

            is com.zapp.net.ui.AutoApproveEffect.ScheduleRetry -> {
                autoRetries.remove(requestID)?.cancel()
                autoRetries[requestID] = viewModelScope.launch {
                    kotlinx.coroutines.delay(effect.delayMs)
                    autoRetries.remove(requestID)
                    autoDispatch(
                        requestID,
                        com.zapp.net.ui.AutoApproveEvent.RetryDue(effect.runId, effect.attempt),
                    )
                }
            }

            is com.zapp.net.ui.AutoApproveEffect.CancelRetry -> {
                autoRetries.remove(requestID)?.cancel()
            }
        }
    }

    /** Their `pruneReplied`: settled requests are only forgotten once the store passes 1 000. */
    private fun autoPruneReplied() {
        val replied = autoStates.entries
            .filter { it.value is com.zapp.net.ui.AutoApproveState.Replied }
            .map { it.key }
        if (replied.size <= autoRepliedLimit) return
        replied.take(replied.size - autoRepliedLimit).forEach { autoStates.remove(it) }
    }

    /**
     * Their `consider`: a request is approved only for a project the settings cover, and a
     * request from a session this client has not loaded needs that session's project first.
     */
    private fun autoConsider(req: com.zapp.net.api.PermissionRequest) {
        val scope = permissionScope()
        if (!scope.autoApprovesOnServer()) return
        if (scope.autoApproves(null)) return autoApprove(req, null)

        val known = sessions.value.firstOrNull { it.id == req.sessionID }
            ?: activeSession.value?.takeIf { it.id == req.sessionID }
        if (known != null) return autoApprove(req, known.projectID)

        viewModelScope.launch {
            val project = runCatching { client?.sessionDetail(req.sessionID)?.projectID }.getOrNull()
            autoApprove(req, project)
        }
    }

    private fun autoApprove(req: com.zapp.net.api.PermissionRequest, projectID: String?) {
        if (!permissionScope().autoApproves(projectID)) return
        autoDispatch(
            req.id,
            com.zapp.net.ui.AutoApproveEvent.Approve(req.sessionID, projectID, autoRunId()),
        )
    }

    /** Their `createEffect` on `enabled()`: a connect or a settings change replaces the sweep. */
    private fun autoSyncSweep() {
        val on = permissionScope().autoApprovesOnServer() && connected.value
        autoDispatchSweep(
            if (on) com.zapp.net.ui.AutoApproveSweepEvent.Start(autoRunId())
            else com.zapp.net.ui.AutoApproveSweepEvent.Cancel,
        )
    }

    private fun autoDispatchSweep(event: com.zapp.net.ui.AutoApproveSweepEvent) {
        val result = com.zapp.net.ui.AutoApproveSweepMachine.transition(autoSweepState, event)
        autoSweepState = result.state
        result.effects.forEach { autoExecuteSweep(it) }
    }

    private fun autoExecuteSweep(effect: com.zapp.net.ui.AutoApproveSweepEffect) {
        when (effect) {
            is com.zapp.net.ui.AutoApproveSweepEffect.Abort -> {
                autoSweepTimer?.cancel()
                autoSweepTimer = null
                autoSweepJob?.cancel()
                autoSweepJob = null
            }

            is com.zapp.net.ui.AutoApproveSweepEffect.Run -> {
                autoSweepTimer?.cancel()
                autoSweepJob?.cancel()
                autoSweepTimer = viewModelScope.launch {
                    kotlinx.coroutines.delay(effect.timeoutMs)
                    autoDispatchSweep(
                        com.zapp.net.ui.AutoApproveSweepEvent.TimedOut(effect.runId, effect.attempt),
                    )
                }
                autoSweepJob = viewModelScope.launch {
                    val complete = runCatching { autoSweep() }.getOrDefault(false)
                    autoDispatchSweep(
                        if (complete) {
                            com.zapp.net.ui.AutoApproveSweepEvent.Succeeded(effect.runId, effect.attempt)
                        } else {
                            com.zapp.net.ui.AutoApproveSweepEvent.Failed(effect.runId, effect.attempt)
                        },
                    )
                }
            }

            is com.zapp.net.ui.AutoApproveSweepEffect.ScheduleRetry -> {
                autoSweepRetry?.cancel()
                autoSweepRetry = viewModelScope.launch {
                    kotlinx.coroutines.delay(effect.delayMs)
                    autoSweepRetry = null
                    autoDispatchSweep(
                        com.zapp.net.ui.AutoApproveSweepEvent.RetryDue(effect.runId, effect.attempt),
                    )
                }
            }

            is com.zapp.net.ui.AutoApproveSweepEffect.CancelRetry -> {
                autoSweepRetry?.cancel()
                autoSweepRetry = null
            }
        }
    }

    /**
     * Their `sweep`: resync every active session so another client's move is seen, then list
     * the requests. They list per location; `/api/permission/request` is server-wide, which is
     * the same set on this phone's single server in one call instead of N.
     */
    private suspend fun autoSweep(): Boolean {
        val c = client ?: return false
        val active = runCatching { c.activeSessions() }.getOrNull() ?: return false
        val synced = active.keys.map { id -> runCatching { c.sessionDetail(id) }.isSuccess }
        val listed = runCatching { c.pendingPermissions() }.getOrNull() ?: return false
        listed.forEach { autoConsider(it) }
        return synced.all { it }
    }

    /**
     * Their third effect: turning auto-approve off for a request's project stops its cycle, so
     * a scheduled retry never answers on his behalf and turning it on again starts over.
     */
    private fun autoCancelUncovered() {
        val scope = permissionScope()
        autoStates.entries.toList().forEach { (id, state) ->
            val projectID = when (state) {
                is com.zapp.net.ui.AutoApproveState.Replying -> state.projectID
                is com.zapp.net.ui.AutoApproveState.WaitingRetry -> state.projectID
                is com.zapp.net.ui.AutoApproveState.GaveUp -> state.projectID
                else -> return@forEach
            }
            if (!scope.autoApproves(projectID)) {
                autoDispatch(id, com.zapp.net.ui.AutoApproveEvent.Cancel)
            }
        }
    }

    /** `permission.replied` settles a cycle another client, or a lost attempt, already answered. */
    private fun autoRequestId(raw: String): String? =
        Regex("\"requestID\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1)

    private fun autoAnswerPermissions(list: List<com.zapp.net.api.PermissionRequest>) {
        if (!permissionScope().autoApprovesOnServer()) return
        list.forEach { autoConsider(it) }
    }

    /** Pulls whatever is waiting for an answer. Called on connect, on `permission.asked`,
     *  after every send, and whenever a session opens. */
    fun loadPermissions() {
        val c = client ?: return
        val sid = activeSession.value?.id
        viewModelScope.launch {
            val list = runCatching {
                if (sid != null) c.permissions(sid) else c.pendingPermissions()
            }.getOrDefault(emptyList())
            permissions.value = list
            autoAnswerPermissions(list)
        }
    }

    /** `once` | `always` | `reject` ï¿½ their `Permission.Reply`, sent verbatim. */
    fun answerPermission(req: com.zapp.net.api.PermissionRequest, decision: String, note: String? = null) {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.replyPermission(req.sessionID, req.id, decision, note) }
                .onFailure { statusFail("permission: ${it.message?.take(120)}") }
            permissions.value = permissions.value.filterNot { it.id == req.id }
        }
    }

    /** GET /api/command ï¿½ cached once per connection. */
    fun loadCommands() {
        val c = client ?: return
        if (commands.value.isNotEmpty()) return
        viewModelScope.launch {
            commands.value = runCatching { c.commands() }.getOrDefault(emptyList())
        }
    }

    /** POST /api/session/:id/command ï¿½ `/init`, `/review`, `/goal`. The text after the command
     *  name is the command's own argument, exactly like their composer sends it. */
    fun runCommand(name: String, text: String = "") {
        val c = client ?: return
        val s = activeSession.value ?: run { statusFail("no session"); return }
        viewModelScope.launch {
            runCatching {
                c.runCommand(s.id, name, text, delivery = if (sending.value) "steer" else null)
            }.onFailure { statusFail("/$name: ${it.message?.take(120)}") }
            sending.value = true
            loadMessages(s)
        }
    }

    /** `POST /api/session/:id/compact` ï¿½ their `/compact`: the server summarises the history
     *  now instead of waiting for its own threshold. */
    /**
     * Compact before a turn that would otherwise be expensive.
     *
     * The whole history is resent every turn and billed as `input`. Their own session list,
     * measured 04:31, spans 4.4k to 543k input per turn on the same account - a 120x spread in
     * how fast a free bucket empties. So this runs before the send, once, and only when the
     * context has crossed [COMPACT_AT] of the model's own `limit.context`.
     *
     * The threshold is OURS. Their schema is `Config.Compaction.Percent` = `"<n>%"` and we do
     * not know their default, so this is a choice, not a port - logged with the numbers every
     * time it fires so it can be corrected against evidence instead of guessed again.
     */
    private suspend fun autoCompactIfHeavy(c: OpenCodeClient) {
        val s = activeSession.value ?: return
        // `SessionTokens` has no `context`; the per-message `TokenUse` does, and it is the same
        // figure their header ring shows. Take the newest assistant turn.
        val used = messages.value.lastOrNull { it.info.role == "assistant" }?.info?.tokens?.context
            ?: return
        if (used <= 0) return
        val limit = models.value.firstOrNull {
            it.modelID == (s.model?.id ?: "") && it.providerID == (s.model?.providerID ?: "")
        }?.limit?.context ?: models.value.firstOrNull { it.modelID == s.model?.id }?.limit?.context ?: 0L
        if (limit <= 0) return
        val ratio = used.toDouble() / limit
        if (ratio < COMPACT_AT) return
        android.util.Log.e(
            "ZAPP",
            "auto-compact: ${used}/${limit} = ${"%.1f".format(ratio * 100)}% >= " +
                "${"%.0f".format(COMPACT_AT * 100)}% - compacting before send",
        )
        compacting.value = true
        try {
            runCatching { c.compact(s.id) }
                .onFailure { android.util.Log.e("ZAPP", "auto-compact failed: ${it.message}") }
            runCatching { loadMessages(s) }
        } finally {
            compacting.value = false
        }
    }

    fun compactNow() {
        val c = client ?: return
        val s = activeSession.value ?: run { statusFail("no session"); return }
        viewModelScope.launch {
            compacting.value = true
            try {
                runCatching { c.compact(s.id) }
                    .onFailure { statusFail("compact: ${it.message?.take(120)}") }
                loadMessages(s)
            } finally {
                compacting.value = false
            }
        }
    }

    /** `POST /api/session/:id/fork` ï¿½ branch from the newest message. */
    fun forkNow() {
        val c = client ?: return
        val s = activeSession.value ?: return
        viewModelScope.launch {
            runCatching { c.forkSession(s.id, null) }
                .onSuccess { child ->
                    statusOk("Forked")
                    refreshSessions()
                    open(child)
                }
                .onFailure { statusFail("fork: ${it.message?.take(120)}") }
        }
    }

    /** `POST /api/session/:id/agent` ï¿½ the same route their agent menu posts. */
    fun setSessionAgent(agent: String) {
        val c = client ?: return
        val s = activeSession.value ?: return
        agentId.value = agent
        viewModelScope.launch {
            runCatching { c.setAgent(s.id, agent) }
                .onFailure { statusFail("agent: ${it.message?.take(120)}") }
        }
    }

    // ---- @file menu ----
    /** What the last `GET /api/fs/list` returned. Their `fs/find` is 500 on this build, so
     *  the @ menu lists a directory level and descends on tap, exactly like a file picker. */
    val fileRows = MutableStateFlow<List<com.zapp.net.api.FsEntry>>(emptyList())
    val fileDir = MutableStateFlow("")

    fun loadFiles(path: String? = null) {
        val c = client ?: return
        viewModelScope.launch {
            val list = runCatching { c.fsList(path) }.getOrDefault(emptyList())
            fileRows.value = list
            fileDir.value = path.orEmpty()
        }
    }

    // ---- forms (server-driven question cards) ----

    /** Pulls every pending form for the open session. `form.created` also calls this. */
    fun loadForms() {
        val c = client ?: return
        val sid = activeSession.value?.id
        viewModelScope.launch {
            val mine = if (sid != null) runCatching { c.forms(sid) }.getOrDefault(emptyList())
            else emptyList()
            val global = runCatching { c.globalForms() }.getOrDefault(emptyList())
            forms.value = (mine + global).distinctBy { it.id }
        }
    }

    /** POST ï¿½/form/:id/reply {answer} ï¿½ `answerJson` is the object, already encoded. */
    fun answerForm(form: com.zapp.net.api.FormInfo, answerJson: String) {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.replyForm(form.sessionID, form.id, answerJson) }
                .onFailure { statusFail("form: ${it.message?.take(120)}") }
            forms.value = forms.value.filterNot { it.id == form.id }
        }
    }

    fun dismissForm(form: com.zapp.net.api.FormInfo) {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.cancelForm(form.sessionID, form.id) }
            forms.value = forms.value.filterNot { it.id == form.id }
        }
    }

    // ---- questions (`question.v2.*`, the `question` tool) ----

    /**
     * Pulls every pending question request: the open session's own, then the global
     * list. `question.v2.asked` also calls this.
     *
     * The two lists are merged the same way [loadForms] merges them, and for the same
     * reason: a subagent's question is owned by the child session, so a session-scoped
     * fetch alone would leave a blocking request invisible.
     */
    fun loadQuestions() {
        val c = client ?: return
        val sid = activeSession.value?.id
        viewModelScope.launch {
            val mine = if (sid != null) {
                runCatching { c.questions(sid) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val global = runCatching { c.pendingQuestions() }.getOrDefault(emptyList())
            questions.value = (mine + global).distinctBy { it.id }
        }
    }

    /**
     * `POST /api/session/:id/question/:requestID/reply {answers}`.
     *
     * `answers` is `string[][]` and the index is the question index - their own
     * payload description: "User answers in order of questions (each answer is an
     * array of selected labels)". An unanswered question is an empty array, never a
     * missing entry, so the server can still tell "skipped" from "wrong length".
     */
    fun replyQuestion(req: com.zapp.net.api.QuestionRequest, answers: List<List<String>>) {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.replyQuestion(req.sessionID, req.id, answers) }
                .onFailure { statusFail("question: ${it.message?.take(120)}") }
            questions.value = questions.value.filterNot { it.id == req.id }
        }
    }

    /** `POST /api/session/:id/question/:requestID/reject` - the model receives
     *  `QuestionV2.RejectedError` ("The user dismissed this question"). */
    fun rejectQuestion(req: com.zapp.net.api.QuestionRequest) {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.rejectQuestion(req.sessionID, req.id) }
            questions.value = questions.value.filterNot { it.id == req.id }
        }
    }

    // ---- permission rules ----

    fun loadSavedPermissions() {
        val c = client ?: return
        viewModelScope.launch {
            savedPerms.value = runCatching { c.savedPermissions() }.getOrDefault(emptyList())
        }
    }

    fun forgetPermission(rule: com.zapp.net.api.SavedPermission) {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.deleteSavedPermission(rule.id) }
            savedPerms.value = savedPerms.value.filterNot { it.id == rule.id }
        }
    }

    // ---- revert / move / vcs ----

    /** `Clear revert` ï¿½ DELETE /api/session/:id/revert. */
    fun clearRevert() {
        val c = client ?: return
        val s = activeSession.value ?: return
        viewModelScope.launch {
            runCatching { c.unrevert(s.id) }
                .onFailure { statusFail("revert: ${it.message?.take(120)}") }
            loadMessages(s)
        }
    }

    /**
     * `Revert to here`. Their flow is interrupt -> wait -> stage (`route-CMUQLRzL.js` @ 512 669),
     * then commit. `files = true` puts the files on disk back as well; `false` only trims the
     * transcript.
     */
    fun revertTo(messageId: String, files: Boolean = true) {
        val c = client ?: return
        val s = activeSession.value ?: return
        viewModelScope.launch {
            runCatching {
                c.rewind(s.id, messageId, files)
                c.commitRevert(s.id)
            }.onFailure { statusFail("revert: ${it.message?.take(120)}") }
            loadMessages(s)
            refreshSessions()
        }
    }

    /** Their "move session" action: same session, different working directory. */
    fun moveSessionTo(directory: String) {
        val c = client ?: return
        val s = activeSession.value ?: return
        viewModelScope.launch {
            runCatching { c.moveSession(s.id, directory) }
                .onSuccess { statusOk("Moved to $directory"); refreshSessions() }
                .onFailure { statusFail("move: ${it.message?.take(120)}") }
        }
    }

    /**
     * The review mode list, ported line for line from
     * `gui-plugins/src/review/model.ts:365-403`:
     *
     *  - `git` when the location has a repository (`project?.vcs`)
     *  - `branch` only when there is a repository, a current branch, a default branch, and
     *    they differ ï¿½ a session sitting on the default branch never offers Branch
     *  - `turn` only for Git sessions that have an id (turn snapshots are captured per turn)
     */
    private fun reviewModeOptions(provider: String?, branch: com.zapp.net.api.VcsBranch?, hasSession: Boolean): List<String> {
        val list = mutableListOf<String>()
        if (provider != null) list += "git"
        val current = branch?.current
        val default = branch?.default
        if (provider != null && !current.isNullOrBlank() && !default.isNullOrBlank() && current != default) {
            list += "branch"
        }
        if (provider == "git" && hasSession) list += "turn"
        return list
    }

    /**
     * `review/model.ts:411` ï¿½ the shown mode: the stored choice while it is offered, else the
     * first offered one, and `git` when nothing is offered at all.
     */
    private fun shownReviewMode(scope: String, options: List<String>): String {
        val chosen = reviewPrefs.get(scope)
        return if (options.contains(chosen)) chosen else (options.firstOrNull() ?: "git")
    }

    /**
     * Loads the panel the way desktop does: resolve the location, ask `/api/vcs` for the
     * provider and branches, decide the offered modes, then request the diff for the shown
     * mode with `untracked=folders` (`model.ts:667-685`). A session with no repository is the
     * `no-vcs` branch (`model.ts:457`), never an empty "clean" list.
     */
    fun loadReview() {
        val c = client ?: return
        val s = activeSession.value
        val scope = s?.id ?: "new"
        val dir = s?.directory
        // One review request at a time per session change: `/api/vcs` is a slow path on this
        // phone (42 s measured), and `open()` fires on every session emission - stacking the
        // requests is what would wedge the panel.
        reviewJob?.cancel()
        reviewJob = viewModelScope.launch {
            reviewLoading.value = true
            reviewError.value = ""
            try {
                // `REVIEW_DIFF_TIMEOUT_MS = 30_000` (`review/machine.ts:7`): a wedged server
                // shows a failure with Retry instead of loading forever.
                val info = try {
                    kotlinx.coroutines.withTimeout(30_000) { c.vcsInfo(dir) }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    reviewError.value = "Changes took too long to load"
                    reviewLoading.value = false
                    return@launch
                }
                val provider = info?.provider
                val options = reviewModeOptions(provider, info?.branch, s != null)
                reviewModes.value = options
                val mode = shownReviewMode(scope, options)
                reviewMode.value = mode
                reviewNoVcs.value = provider == null
                if (provider == null) {
                    reviewFiles.value = emptyList()
                    return@launch
                }
                reviewFiles.value = try {
                    kotlinx.coroutines.withTimeout(30_000) {
                        if (mode == "turn" && s != null) {
                            c.sessionDiffFiles(s.id)
                        } else {
                            // Desktop's `git` mode is the wire mode `working` (`model.ts:675`);
                            // Branch passes `branch` straight through.
                            c.vcsDiffFiles(dir, if (mode == "git") "working" else mode, "folders")
                        }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    reviewError.value = "Changes took too long to load"
                    emptyList()
                }
            } catch (e: Exception) {
                android.util.Log.e("ZAPP", "review FAIL: ${e.message}", e)
                reviewError.value = e.message?.take(160) ?: "Could not load changes"
                reviewFiles.value = emptyList()
            } finally {
                reviewLoading.value = false
                // `session-review-machine.ts:89-94` `props.synced`: the open list is filtered to
                // the files the loaded list still has, so a deleted file drops out silently.
                val listed = reviewFiles.value.mapNotNull { it.file }.toSet()
                val pruned = reviewOpenFiles.value.filter { it in listed }.toSet()
                if (pruned != reviewOpenFiles.value) {
                    reviewOpenFiles.value = pruned
                    activeSession.value?.id?.let { reviewOpenPrefs.saveFiles(it, pruned) }
                }
                val active = reviewActiveFile.value
                if (active != null && active !in listed) {
                    reviewActiveFile.value = null
                    activeSession.value?.id?.let { reviewOpenPrefs.saveActive(it, null) }
                }
            }
        }
    }

    /** The rail tap: store the choice for this session, then reload with it. */
    fun selectReviewMode(mode: String) {
        val scope = activeSession.value?.id ?: "new"
        reviewPrefs.put(scope, mode)
        reviewMode.value = mode
        loadReview()
    }

    /** `ui.sessionReviewV2.empty.noGit.action` ï¿½ `POST /api/vcs/init`, then load again. */
    /**
     * `session-review-machine.ts:97-113` `open.changed` -> `notify-open`: the parent owns the
     * list, so every expansion is written back in full and stored with the session.
     */
    fun setReviewOpenFiles(next: Set<String>) {
        val id = activeSession.value?.id ?: return
        reviewOpenFiles.value = next
        reviewOpenPrefs.saveFiles(id, next)
    }

    /** One row's chevron. `review/mobile.tsx:62-66` reports the whole list, not the diff. */
    fun toggleReviewFile(path: String) {
        val id = activeSession.value?.id ?: return
        val next = if (reviewOpenFiles.value.contains(path)) reviewOpenFiles.value - path
        else reviewOpenFiles.value + path
        reviewOpenFiles.value = next
        reviewOpenPrefs.saveFiles(id, next)
    }

    /**
     * `session-review.tsx:320-329` `handleExpandOrCollapseAll`: opening expands every file that
     * has changes to render (`additions !== 0 || deletions !== 0`); closing clears the list.
     */
    fun toggleReviewExpandAll() {
        val id = activeSession.value?.id ?: return
        val next = if (reviewOpenFiles.value.isNotEmpty()) emptySet()
        else reviewFiles.value.filter { it.additions != 0 || it.deletions != 0 }
            .mapNotNull { it.file }.toSet()
        reviewOpenFiles.value = next
        reviewOpenPrefs.saveFiles(id, next)
    }

    /** `review/model.ts:892-899` `focusFile`: open it, make it the session's file, scroll to it. */
    fun focusReviewFile(path: String) {
        val id = activeSession.value?.id ?: return
        val next = reviewOpenFiles.value + path
        reviewOpenFiles.value = next
        reviewActiveFile.value = path
        reviewOpenPrefs.saveFiles(id, next)
        reviewOpenPrefs.saveActive(id, path)
    }

    fun createRepository() {
        val c = client ?: return
        val dir = activeSession.value?.directory
        viewModelScope.launch {
            reviewLoading.value = true
            runCatching { c.vcsInit(directory = dir) }
                .onFailure {
                    reviewError.value = it.message?.take(160) ?: "Could not create repository"
                    reviewLoading.value = false
                }
            reviewLoading.value = false
            loadReview()
        }
    }

    /** Their "Open file" on a diff row reads through `/api/fs/read/<path>` ï¿½ `openFile` does that. */

    fun activateSkill(id: String) {
        val c = client ?: return
        val s = activeSession.value ?: return
        viewModelScope.launch {
            runCatching { c.activateSkill(s.id, id) }
                .onFailure { statusFail("skill: ${it.message?.take(120)}") }
        }
    }

    val models = MutableStateFlow<List<ModelInfo>>(emptyList())
    /**
     * The full catalog, unfiltered - what `settings/models` must list.
     *
     * `models` is the PICKER's list (filtered by `models.disabled` and the one-model lock).
     * Feeding that list to the settings page hid every model he turned off, so a model could
     * be disabled but never re-enabled; and a lock would have left that page with a single row.
     */
    val catalogModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val providers = MutableStateFlow<List<Provider>>(emptyList())

    data class SelectedModel(
        val providerID: String,
        val modelID: String,
        val variant: String,
        val label: String,
    )

    val selectedModel = MutableStateFlow<SelectedModel?>(null)

    private companion object {
        /**
         * Silence that makes the app *ask*, not a silence that kills the stream.
         *
         * Deliberately below the socket's own 70 s read deadline, so this check always runs
         * first and a probe decides - see [watchStream]. The old comment claimed a ~15 s beat;
         * the phone measured **28 957 ms** on a clean install (2026-11-23 20:51:39), and with a
         * 45 s verdict nothing but a probe can tell a late beat from a dead server.
         */
        const val SILENT_STREAM_MS = 45_000L

        /**
         * Consecutive failed loopback reads before Termux is asked to replace the engine.
         *
         * Each failed read costs the client's own 30 s read timeout, so this is ~90 s of a
         * server that cannot answer a one-session list. Measured 23 Nov 20:11:14 -> 20:12:54:
         * four reads 32 s apart on an engine whose process was alive at 2h uptime but whose
         * event loop had stopped serving. Nothing else in the app would ever have restarted it.
         */
        const val ENGINE_RESTART_AFTER = 3

        /**
         * How long a cold engine may take before the connect reports a failure.
         *
         * Measured 23 Nov on this phone: relay 17:55:03 -> `opencode serve` 17:55:45, so 42 s
         * with everything already installed. A fresh install installs python, glibc-runner and
         * a 121 MB engine first, which is minutes, and onboarding already waits that out. This
         * ceiling is the post-setup number, four times the measured one.
         */
        const val ENGINE_WAIT_MS = 180_000L
        /**
         * How long a toast stays. PC's toast component has no number in the extract, so this is
         * ours: long enough to read two short lines, short enough not to sit on the composer.
         */
        const val TOAST_MS = 2_600L

        /**
         * Compact when a session's context reaches this share of the model's `limit.context`.
         *
         * OURS, not theirs. Their config is `Config.Compaction.Percent` (`"<n>%"`) and their
         * default is not in the client we have, so 0.75 is a policy choice. Every fire logs the
         * real numbers, so it can be tuned from evidence rather than guessed again.
         */
        const val COMPACT_AT = 0.75
        /** Ids upstream has retired: 1.19.6 keeps listing them, but every call answers
            `410 ModelDeprecated`, so they must never be offered as a choice. */
        /** Models the provider answers with HTTP 410 ModelDeprecated. Verified live:
         *  - glm-5.3-flash-free        (410, replacement deepseek-v4.1-flash-free)
         *  - crouton-alpha-free        (410; the phone server's own default model, which is
         *                               why a fresh session errored with "Provider request
         *                               failed with HTTP 410" instead of ever running)
         *  - minimax-m3.1-flash-free   (410)
         *  Filtered out of the picker so a session can never be pointed at one. */
        val RETIRED = setOf(
            "glm-5.3-flash-free",
            "crouton-alpha-free",
            "minimax-m3.1-flash-free",
        )

        /** 410 replacement map, used by the auto-switch + resend path. */
        val RETIRED_REPLACEMENT = mapOf(
            "glm-5.3-flash-free" to "deepseek-v4.1-flash-free",
            "crouton-alpha-free" to "deepseek-v4.1-flash-free",
            "minimax-m3.1-flash-free" to "deepseek-v4.1-flash-free",
        )
    }

    private var sse: com.zapp.net.api.SseStream.Handle? = null
    private var lastSessionId = ""

    /**
     * Bumped on every subscription. A failure carries the generation it belonged to, so a
     * late `onFailure` from a stream that was already replaced cannot start a retry loop on
     * top of the new one.
     */
    private var streamGen = 0

    /** Wall-clock of the last frame; the mid-turn watchdog compares against it. */
    @Volatile private var lastFrameMs = 0L

    /** True between `subscribe` and the stream's own death verdict. */
    @Volatile private var streamDown = false

    /** The single pending resubscribe; cancelled by any successful [listen]. */
    private var streamRetry: Job? = null

    /** Last generation that delivered a frame; drives the one-shot "stream open" line. */
    private var streamHeardGen = -1

    /** Last generation that delivered a heartbeat; proves the comment path is wired. */
    private var streamBeatGen = -1

    // ---- desktop's connection machine -------------------------------------------------
    //
    // Ported from `app/src/runtime/server/server-sync/machine.ts` (official desktop app,
    // `app.asar` sha256 2615cfd5f147db0e244e54bd51492cd6b93486edf20a10fc1d58a85af69b246f).
    // Its own doc comment:
    //
    //   "A stream is live once the client reports it connected AND its `server.connected`
    //    handshake has arrived, in either order. `stale` means an earlier stream already
    //    reported `connected` ... the bootstrap query may hold a fetch it started, so going
    //    live restarts it."
    //
    // The "restart it" half is what this app was missing. Every drop set `streamDown` and
    // resubscribed, but nothing re-read the transcript, so the deltas the server wrote while
    // the socket was dead were gone for good and the answer only reappeared when the end of
    // the turn forced a read - which is the "teleports in one lump" the owner reports.
    /** An earlier stream already went live; a fetch it started may have read nothing. */
    private var streamEverLive = false

    /** `server.connected` seen on the CURRENT generation. Not live without it. */
    private var streamHandshake = false

    /** A drop happened after going live: the next live must replace the transcript. */
    private var streamStale = false

    /** Desktop's `settleMs = 150`: a burst of same-key events costs one refetch. */
    private var syncSettle: Job? = null

    /** Per-scope composer drafts, ported from `composer/state.ts` (see `Drafts`). */
    private val drafts = com.zapp.net.settings.Drafts(ctx)
    private var draftJob: kotlinx.coroutines.Job? = null

    /**
     * `ui.promptInput.history` - "Prompt history" (`dropzone-DPkZ7Cwn.js`). Per scope, newest
     * first; `add` is called only after the engine accepted a prompt, so a refused send never
     * pollutes it.
     */
    private val promptHist = com.zapp.net.settings.PromptHistory(ctx)

    fun promptHistory(scope: String): List<com.zapp.net.settings.PromptHistory.Entry> =
        promptHist.entries(scope)

    fun rememberPrompt(scope: String, text: String, files: String = "") =
        promptHist.add(scope, text, files)

    /** The review mode each session last chose (`stored()?.mode`, `review/model.ts:411`). */
    private val reviewPrefs = com.zapp.net.settings.ReviewMode(ctx)
    private val reviewOpenPrefs = com.zapp.net.settings.ReviewOpen(ctx)
    private var reviewJob: kotlinx.coroutines.Job? = null
    private var reviewRefreshJob: kotlinx.coroutines.Job? = null

    /**
     * `review/model.ts:217-221` `wantsReview = tree + files + panel > 0`: the review only
     * requests diffs while something shows it. ZAPP has one surface, so the panel being open
     * is the whole demand.
     */
    @Volatile private var reviewOpen = false

    fun setReviewOpen(open: Boolean) {
        reviewOpen = open
    }

    /**
     * `review/model.ts:411-420`: the server reports file changes per directory on its event
     * stream, and the review refreshes when the change belongs to the session's own
     * directory. `:403-407` debounces the refresh by 100 ms.
     */
    private fun onFilesystemChanged(raw: String) {
        if (!reviewOpen) return
        val dir = activeSession.value?.directory ?: return
        val changed = runCatching {
            org.json.JSONObject(raw).optJSONObject("data")
                ?.optJSONObject("location")?.optString("directory")
        }.getOrNull()
        if (changed.isNullOrBlank() || changed != dir) return
        reviewRefreshJob?.cancel()
        reviewRefreshJob = viewModelScope.launch {
            kotlinx.coroutines.delay(100)
            loadReview()
        }
    }

    /**
     * `review/model.ts:969-982`: a session that goes idle has finished its turn, which may
     * have changed files, so the review reloads then too.
     */
    private fun onSessionIdled(raw: String) {
        val id = runCatching {
            org.json.JSONObject(raw).optJSONObject("data")?.optString("sessionID")
        }.getOrNull()
        val active = activeSession.value?.id
        if (!id.isNullOrBlank() && active != null && id != active) return
        // The same reasoning applies to every watched file tab: the turn that just ended is the
        // one that could have written them. Their client gets a per-file push from the watch;
        // ZAPP has no push consumer for `fs` events yet, so the turn end is the trigger.
        val openFiles = fileViews.value.keys.toList()
        if (openFiles.isNotEmpty()) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(150)
                openFiles.forEach { reloadFile(it) }
            }
        }
        if (!reviewOpen) return
        reviewRefreshJob?.cancel()
        reviewRefreshJob = viewModelScope.launch {
            kotlinx.coroutines.delay(100)
            loadReview()
        }
    }

    // ---- notifications: PORT of `shell/notifications/notification.tsx` -------------------

    /** Last time each notification key fired, so two event names for one turn cannot double-post. */
    private val lastNotifyMs = HashMap<String, Long>()

    private fun notifyGate(key: String, windowMs: Long = 3_000L): Boolean {
        val now = System.currentTimeMillis()
        val last = lastNotifyMs[key] ?: 0L
        if (now - last < windowMs) return false
        lastNotifyMs[key] = now
        if (lastNotifyMs.size > 64) lastNotifyMs.clear()
        return true
    }

    private fun sessionTitleOf(id: String): String? =
        sessions.value.firstOrNull { it.id == id }?.title?.takeIf { it.isNotBlank() }

    /** `notification.tsx:809-867` - dispatch on the event's own `type`. */
    private fun onNotifyEvent(raw: String) {
        val o = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return
        val type = o.optString("type")
        val data = o.optJSONObject("data") ?: return
        when (type) {
            "session.execution.succeeded", "session.idle" ->
                notifySession(data, com.zapp.net.notify.NotifyKind.AGENT)
            "session.execution.failed", "session.error" ->
                notifySession(data, com.zapp.net.notify.NotifyKind.ERROR)
            "permission.asked" -> notifyRequest(data)
            "form.created" -> notifyForm(data)
            // `question.v2.asked` - the `question` tool blocking. Their event carries the
            // whole request (`{id, sessionID, questions, tool?}`), so the title is the
            // first question's header and the body is the question itself.
            "question.v2.asked" -> notifyQuestion(data)
        }
    }

    /** `notification.tsx:619-667` and `:673-717`. */
    private fun notifySession(data: org.json.JSONObject, kind: com.zapp.net.notify.NotifyKind) {
        val id = data.optString("sessionID").ifBlank { return }
        val s = sessions.value.firstOrNull { it.id == id } ?: return
        if (!notifyGate("${kind.name}:$id")) return
        deliver(id, kind, s.title, !s.parentId.isNullOrBlank())
    }

    /** `notification.tsx:721-805` - `permission.asked`. */
    private fun notifyRequest(data: org.json.JSONObject) {
        val id = data.optString("sessionID").ifBlank { return }
        val requestId = data.optString("id").ifBlank { return }
        if (!notifyGate("req:$requestId")) return
        deliver(id, com.zapp.net.notify.NotifyKind.PERMISSION, sessionTitleOf(id), false)
    }

    /** `notification.tsx:835-851` - only the three form kinds that block the agent. */
    private fun notifyForm(data: org.json.JSONObject) {
        val form = data.optJSONObject("form") ?: return
        val kind = form.optJSONObject("metadata")?.optString("kind").orEmpty()        // `:837`
        val nk = when (kind) {
            "question" -> com.zapp.net.notify.NotifyKind.QUESTION
            "mcp-elicitation" -> com.zapp.net.notify.NotifyKind.ELICITATION
            "websearch.provider" -> com.zapp.net.notify.NotifyKind.WEBSEARCH
            else -> return
        }
        val id = form.optString("sessionID").ifBlank { return }
        val formId = form.optString("id").ifBlank { return }
        if (!notifyGate("form:$formId")) return
        deliver(id, nk, sessionTitleOf(id), false)
    }

    /** `question.v2.asked` - the `question` tool's blocking request. */
    private fun notifyQuestion(data: org.json.JSONObject) {
        val id = data.optString("sessionID").ifBlank { return }
        val requestId = data.optString("id").ifBlank { return }
        if (!notifyGate("question:$requestId")) return
        deliver(id, com.zapp.net.notify.NotifyKind.QUESTION, sessionTitleOf(id), false)
    }

    /**
     * Every rule lives in `NotifyPolicy.decide`, which has no Android in it and is covered by
     * `NotifyPolicyTest`; this only supplies the live state and posts the result.
     */
    private fun deliver(
        sessionId: String,
        kind: com.zapp.net.notify.NotifyKind,
        title: String?,
        hasParent: Boolean,
    ) {
        val d = com.zapp.net.notify.NotifyPolicy.decide(
            kind = kind,
            sessionId = sessionId,
            sessionTitle = title,
            hasParent = hasParent,
            muted = com.zapp.net.settings.SessionPrefs.mutedFor(ctx, sessionId, null),
            hasOpenTab = sessionId in tabs.list(),                                   // `:641`
            foreground = Foreground.isForeground,
            visibleSessionId = activeSession.value?.id,
            settings = com.zapp.net.settings.SessionPrefs.notify.value,
        ) ?: return
        if (d.banner) {
            com.zapp.net.notify.Notifier.post(
                ctx, sessionId, d.title, d.body,
                if (d.channel == com.zapp.net.notify.NotifyChannel.REQUEST)
                    com.zapp.net.notify.Notifier.CH_REQUEST
                else com.zapp.net.notify.Notifier.CH_AGENT,
                d.sound,
                d.soundId,
            )
        } else {
            com.zapp.net.notify.Notifier.chime(ctx, d.soundId)                        // `:645`
        }
    }

    /** Open tab list, ported from `Persist.window("tabs")` (see `Tabs`). */
    private val tabs = com.zapp.net.settings.Tabs(ctx)

    /** Closed-tab stack, `Persist.window("tabs.closed")` (`tabs.tsx:237`, see `ClosedTabs`). */
    private val closedTabs = com.zapp.net.settings.ClosedTabs(ctx)

    /** Sessions known to carry a user message - `tab-strip.tsx:575-581` decides `prompted` this way. */
    private val promptedSessions = HashSet<String>()

    /** What `RecentlyClosedTabsMenu` lists: `listClosedTabs` filtered by `prompted` (`:79`). */
    val recentlyClosed = MutableStateFlow<List<com.zapp.net.settings.ClosedTabs.Entry>>(emptyList())

    /** Their `store.session.message[sessionID]`: the transcript cache a tab switch renders
     *  from instantly (`client/src/solid/data.ts`). Clearing it on every switch is what made
     *  our app flash empty and re-download the same conversation. */
    private val messageCache = HashMap<String, List<com.zapp.net.api.MessageWithParts>>()

    /** One open tab: a session id, or `new` for the draft tab. */
    data class OpenTab(val id: String, val title: String, val draft: Boolean)

    val openTabs = MutableStateFlow<List<OpenTab>>(emptyList())

    /**
     * Their tab list, in open order (`shell/tabs/tabs.tsx`): the draft tab is a real tab with
     * its own id, not "the current chat but emptied". Sessions contribute their title; a tab
     * whose session is gone is pruned (`tabs.tsx:651`).
     */
    fun refreshTabs() {
        val byId = sessions.value.associateBy { it.id }
        openTabs.value = tabs.list().mapNotNull { id ->
            if (isDraft(id)) OpenTab(id, "New session", true)
            // A tab survives a list page that does not mention it: desktop keeps the tab and
            // titles it from its own info (`tab-title.ts`). Pruning it here is what made open
            // tabs disappear after a refresh.
            else {
                val s = byId[id]
                OpenTab(id, SessionTitle.tab(s?.title, !s?.parentId.isNullOrBlank()), false)
            }
        }
        refreshRecentlyClosed()
    }

    /** `listClosedTabs` (`closed.ts:39-59`) + the menu's `prompted` filter (`:79`), capped at 10. */
    fun refreshRecentlyClosed() {
        recentlyClosed.value = closedTabs.menu(tabs.list()).filter { it.prompted }
    }

    /** Their `persistSaveDelay = 100`: write 100 ms after the last keystroke. */
    fun saveDraft(scope: String, text: String) {
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            kotlinx.coroutines.delay(100)
            drafts.put(scope, text)
        }
    }

    fun draftText(scope: String): String = drafts.get(scope)

    /**
     * `layout.draft({ server, directory, prompt })` (`plugin/src/gui/builtin.ts`).
     *
     * Their Extensions panel starts a draft session already holding a prompt - `setup.mcp`,
     * `setup.plugins`, `setup.skills` (`details/server-panel.tsx:999-1026`). We have no separate
     * global-config directory to draft in, so the equivalent is a fresh draft tab whose composer
     * opens with the same words in it. Nothing is sent: he still presses send.
     */
    fun draftWithPrompt(prompt: String) {
        newSession()
        val id = activeDraft.value ?: return
        // Read once by the composer for this scope, then cleared, so reopening the tab keeps
        // whatever he typed instead of re-seeding the prompt.
        pendingDraftPrompt.value = id to prompt
    }

    /** One-shot `scope to text` seed for a draft tab opened by `draftWithPrompt`. */
    val pendingDraftPrompt = MutableStateFlow<Pair<String, String>?>(null)

    /** `removeDraftPersisted` on tab close (`shell/tabs/tabs.tsx:373`). */
    fun dropDraft(scope: String) {
        draftJob?.cancel()
        drafts.remove(scope)
    }

    /**
     * Declared HERE, above `init`, on purpose.
     *
     * Kotlin runs property initialisers in declaration order, and the `init` block below collects
     * the settings flow. `MutableStateFlow.value` on an uninitialised property is a null receiver,
     * so a settings emission that lands synchronously during construction killed the process:
     *
     * ```
     * java.lang.NullPointerException: Attempt to invoke interface method
     * 'void kotlinx.coroutines.flow.MutableStateFlow.setValue(java.lang.Object)' on a null object
     * reference
     *     at com.zapp.net.MainViewModel$8$1.emit(MainActivity.kt:1978)
     *     at com.zapp.net.MainViewModel.<init>(MainActivity.kt:1973)
     * ```
     *
     * Measured on the phone 2026-11-22 07:52:52, then four more `Process com.zapp.net has died`
     * lines at 07:53:03, 07:56:04, 07:56:36 and 07:59:04. It only fired when DataStore answered
     * before the constructor finished, which is why the app sometimes opened and sometimes died -
     * and why the tunnel never got a chance to come up.
     */
    val serverBase = MutableStateFlow(com.zapp.net.settings.PHONE_SERVER)
    private var reconnectTried = false

    init {
        // a staged Termux script runs once, at app start, while we are still allowed to
        // start the RunCommand foreground service
        runCatching { com.zapp.net.termux.BootRelay.runPending(ctx) }
            .onFailure { android.util.Log.e("ZAPP", "boot relay: $it") }

        // One-time proof that Termux really accepts our commands. Termux refuses external
        // commands until `allow-external-apps` sits in its own settings file, and that state
        // is invisible from the outside - the only way to know is to send a command and read
        // Termux's own result bundle. `TermuxSetup.probe` does that; onboarding runs the same
        // probe interactively. Recorded once per install so a bug report can be read instead
        // of guessed at, and so it costs one Termux command, not one per launch.
        if (com.zapp.net.onboarding.Onboarding.termuxReady(ctx)) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val prefs = ctx.getSharedPreferences("termux_setup", android.content.Context.MODE_PRIVATE)
                // Once it has answered `OK` there is nothing left to prove, so it stops for
                // good. Until then it runs once per app start, which is how a user who fixes
                // the line in Termux gets a clean bill on the next launch with no extra tap.
                if (prefs.getBoolean("probed_ok", false)) return@launch
                val r = runCatching { com.zapp.net.termux.TermuxSetup.probe(ctx) }.getOrNull()
                android.util.Log.e(
                    "ZAPP",
                    "termux access self-check: ${r?.state} ${r?.detail?.take(200)}",
                )
                if (r?.state == com.zapp.net.termux.TermuxSetup.State.OK) {
                    prefs.edit().putBoolean("probed_ok", true).apply()
                }
            }
        }

        // The Tor lane's proxy has to answer *before* serve.sh decides whether to attach
        // HTTPS_PROXY, so it comes up with the app instead of behind a menu tap.
        // Fail-open by construction: with Tor down this is a transparent passthrough.
        runCatching { com.zapp.net.net.EgressProxy.start() }
            .onFailure { android.util.Log.e("ZAPP", "proxy start: $it") }

        // Their `zen_guard.py` keeper: keep the chosen egress alive, re-check it on a
        // timer, never leave a dead route in front of the chat.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (true) {
                runCatching {
                    val wanted = com.zapp.net.settings.GuardPrefs.torOn(ctx)
                    // The switch has to gate the route too, not just the daemon: otherwise
                    // "off" would still send every provider call through an exit.
                    com.zapp.net.net.EgressProxy.torEnabled = wanted
                    // serve.sh decides once, at launch, whether to export HTTPS_PROXY, and it
                    // keys that decision on this marker. Keep the marker in step with the
                    // switch so the next server start does the right thing instead of
                    // silently running the previous lane.
                    com.zapp.net.termux.TorLaneMarker.sync(ctx, wanted)
                    if (wanted) {
                        // Read the settings flow, never the fields: this block runs before the
                        // property initialisers below, so a field read here is null and Kotlin
                        // throws on the non-null check (measured 00:47:08 `tor keeper: NPE ...
                        // isBlank`). The flow is published after load, so it is always real.
                        val s = runCatching {
                            com.zapp.net.settings.observe(ctx).first()
                        }.getOrNull()
                        val pair = "opencode:" + (s?.authToken.orEmpty())
                        val base = s?.serverBase?.takeIf { it.isNotBlank() }
                            ?: com.zapp.net.settings.PHONE_SERVER
                        val st = com.zapp.net.net.TorLane.state.value
                        if (!st.ready) {
                            com.zapp.net.net.TorLane.ensure(ctx, base, pair)
                        } else {
                            com.zapp.net.net.TorLane.refresh()
                        }
                        com.zapp.net.net.TorLane.exitIp()
                    } else {
                        // Not in the lane: still report whether a daemon is up, so the
                        // card can say "available" instead of guessing.
                        com.zapp.net.net.TorLane.refresh()
                    }
                }.onFailure { android.util.Log.e("ZAPP", "tor keeper: $it") }
                kotlinx.coroutines.delay(30_000)
            }
        }

        viewModelScope.launch {
            com.zapp.net.settings.observe(ctx).collect { s ->
                selectedModel.value = if (s.modelID.isNotBlank())
                    SelectedModel(s.providerID, s.modelID, s.variant, s.modelLabel)
                else null
                serverBase.value = s.serverBase
                if (s.sessionId.isNotBlank()) lastSessionId = s.sessionId
                com.zapp.net.guard.GuardState.restoreCounters(s.hops, s.walls)
                if (!reconnectTried) {
                    reconnectTried = true
                    android.util.Log.e("ZAPP", "settings emission: base=${s.serverBase} token=${s.authToken.take(12)} cookiesLen=${s.cookiesJson.length}")
                    tryReconnect(s.serverBase, s.cookiesJson, s.authToken)
                }
                // NOTE: no `pickActiveSession()` here. Desktop picks the active tab when the tab
                // list resolves (`tabs.tsx:919`), never on a preferences write. Re-picking here
                // is what snatched the screen back to the previous chat ~0.1 s after he tapped
                // `+` (measured 13:42:41.635 clear -> 13:42:41.750 open).
            }
        }
        // A wall must be answered once for the life of the install, not once per ViewModel.
        // See WallLedger: four identities were burned in ten seconds on one stored error.
        com.zapp.net.guard.WallLedger.attach(ctx)
        // counters are evidence: load them from disk and write them at event time, not from
        // a UI collector that can be clobbered by a stale settings emission
        com.zapp.net.guard.GuardState.attach(ctx)
        // persist lifetime counters whenever they change
        viewModelScope.launch {
            com.zapp.net.guard.GuardState.status.collect { st ->
                com.zapp.net.settings.saveStats(ctx, st.hops, st.walls)
            }
        }
        // Debug builds only: the outside handle can feed a frame shaped like the server's
        // `session.step.failed` through the same handler the SSE stream uses, so the card and
        // the composer restore can be observed before the free lane is actually exhausted.
        viewModelScope.launch {
            com.zapp.net.guard.WallDebug.frames.collect { frame -> onQuotaWall(frame) }
        }
    }

    // ---- extensions rail: all of this comes straight from the server ----
    val mcpEntries = MutableStateFlow<List<com.zapp.net.api.McpEntry>>(emptyList())
    val skillEntries = MutableStateFlow<List<com.zapp.net.api.SkillEntry>>(emptyList())
    val pluginEntries = MutableStateFlow<List<com.zapp.net.api.PluginEntry>>(emptyList())
    val agentEntries = MutableStateFlow<List<com.zapp.net.api.AgentEntry>>(emptyList())
    val configJson = MutableStateFlow("")
    val agentId = MutableStateFlow("build")

    // ---- Settings -> Projects / Worktrees -------------------------------------------------
    //
    // Their sidebar groups sessions under `GET /api/project`; the Worktrees page reads
    // `GET /api/worktree`. Both are already in the client, but nothing on the phone ever
    // called them, so the two pages had no data to draw. They load on demand from the
    // Settings surface, not on boot: a phone has one server and one project at most.

    val projectEntries = MutableStateFlow<List<com.zapp.net.api.ProjectInfo>>(emptyList())
    val worktreeRaw = MutableStateFlow("")
    val settingsLoading = MutableStateFlow(false)
    val settingsError = MutableStateFlow("")

    fun loadSettingsData() {
        viewModelScope.launch {
            val c = client ?: return@launch
            settingsLoading.value = true
            settingsError.value = ""
            runCatching { projectEntries.value = c.projects() }
                .onFailure { settingsError.value = it.message ?: "Could not load projects" }
            runCatching {
                val pid = projectEntries.value.firstOrNull()?.id
                worktreeRaw.value = c.worktrees(pid)
            }
            settingsLoading.value = false
        }
    }

    fun loadExtensions() {
        viewModelScope.launch {
            val c = client ?: return@launch
            val dir = activeSession.value?.dir
            runCatching { mcpEntries.value = c.mcpServers(dir) }
            runCatching { skillEntries.value = c.skills() }
            runCatching {
                pluginEntries.value = c.plugins()
                // `list.loaded` - their machine keeps a finding only while it disagrees with the
                // server, so a list that arrives after an update does not resurrect the flag.
                pluginState = com.zapp.net.api.PluginLifecycle.reduce(
                    pluginState,
                    com.zapp.net.api.PluginLifecycle.Event.ListLoaded(
                        com.zapp.net.api.PluginLifecycle.outdatedMap(pluginEntries.value),
                    ),
                ).state
            }
            runCatching {
                agentEntries.value = c.agentNames()
                if (agentEntries.value.none { it.id == agentId.value })
                    agentEntries.value.firstOrNull()?.let { agentId.value = it.id }
            }
            runCatching { configJson.value = c.configDocs() }
        }
    }

    // ---- MCP toggle: PORT of `panel-QKwfqHQW.js` `Ee`/`ke`/`Ae` and the `h()` run -----------

    /** The machine, as their `Te`. A fresh object per panel open - a disposed one ignores events. */
    var mcpToggle by mutableStateOf(com.zapp.net.api.McpToggle.State())
        private set

    /** `Me`/`De`: the row's disabled state, so a second tap cannot start a second call. */
    fun mcpBusy(name: String): Boolean = mcpToggle.busy(name)

    fun mcpMount() {
        // Their harness builds the machine with `l(Te)` on every mount, so a re-open is a **new**
        // object. A disposed machine ignores `mounted`, so the reset has to happen here.
        if (mcpToggle.disposed) mcpToggle = com.zapp.net.api.McpToggle.State()
        mcpToggle = com.zapp.net.api.McpToggle.dispatch(
            mcpToggle,
            com.zapp.net.api.McpToggle.Event.Mounted,
        ).state
    }

    fun mcpUnmount() {
        mcpToggle = com.zapp.net.api.McpToggle.dispatch(
            mcpToggle,
            com.zapp.net.api.McpToggle.Event.Unmounted,
        ).state
    }

    /**
     * Their toggle, one for one:
     *
     * ```js
     * let server = (await client.mcp.list({location})).data.find(e => e.name === name);
     * if (!server) return {type:'toggle.succeeded', ...};
     * if (!enabled) await client.experimental.mcp.disconnect({server: name, location});
     * if (enabled && server.status.status !== 'needs_auth')
     *     await client.experimental.mcp.connect({server: name, location});
     * // re-list, then: enabled && needs_auth && integrationID -> OAuth
     * ```
     *
     * The `needs_auth` skip is the important one: connecting a server that is waiting for OAuth
     * cannot work, so their client does not even ask.
     */
    fun toggleMcp(name: String, enabled: Boolean) {
        val entry = mcpEntries.value.firstOrNull { it.name == name }
        val result = com.zapp.net.api.McpToggle.dispatch(
            mcpToggle,
            com.zapp.net.api.McpToggle.Event.Requested(name, enabled, entry?.status?.status),
        )
        mcpToggle = result.state
        val run = result.effects.filterIsInstance<com.zapp.net.api.McpToggle.Effect.RunToggle>()
            .firstOrNull() ?: return
        viewModelScope.launch {
            val c = client ?: return@launch
            val dir = activeSession.value?.dir
            try {
                // Re-read first: the row could be gone, and a stale status would send the wrong
                // call (`needs_auth` must skip connect).
                val live = runCatching { c.mcpServers(dir) }.getOrNull()
                    ?.firstOrNull { it.name == name }
                if (live == null) {
                    mcpToggle = com.zapp.net.api.McpToggle.dispatch(
                        mcpToggle,
                        com.zapp.net.api.McpToggle.Event.Succeeded(name, run.attempt),
                    ).state
                    return@launch
                }
                if (!enabled) {
                    c.mcpDisconnect(name, dir)
                } else if (!live.needsAuth) {
                    c.mcpConnect(name, dir)
                }
                mcpEntries.value = runCatching { c.mcpServers(dir) }.getOrDefault(mcpEntries.value)
                val after = mcpEntries.value.firstOrNull { it.name == name }
                if (enabled && after?.needsAuth == true && after.integrationID != null) {
                    // Their flow finds an `oauth` method with no form and opens the browser. ZAPP
                    // has no generic integration browser, so this is reported instead of faked.
                    mcpToggle = com.zapp.net.api.McpToggle.dispatch(
                        mcpToggle,
                        com.zapp.net.api.McpToggle.Event.Failed(
                            name,
                            run.attempt,
                            "$name needs authorisation in an integration",
                            server = true,
                        ),
                    ).state
                } else {
                    mcpToggle = com.zapp.net.api.McpToggle.dispatch(
                        mcpToggle,
                        com.zapp.net.api.McpToggle.Event.Succeeded(name, run.attempt),
                    ).state
                }
            } catch (e: Exception) {
                mcpToggle = com.zapp.net.api.McpToggle.dispatch(
                    mcpToggle,
                    com.zapp.net.api.McpToggle.Event.Failed(
                        name,
                        run.attempt,
                        e.message ?: "failed",
                        server = false,
                    ),
                ).state
            }
        }
    }

    // ---- plugin check/update: PORT of `shell-D_fdbe5y.js` `Gs` ------------------------------

    var pluginState by mutableStateOf(com.zapp.net.api.PluginLifecycle.State())
        private set

    fun checkPlugins() {
        val result = com.zapp.net.api.PluginLifecycle.reduce(
            pluginState,
            com.zapp.net.api.PluginLifecycle.Event.CheckRequested,
        )
        pluginState = result.state
        val attempt = result.effects
            .filterIsInstance<com.zapp.net.api.PluginLifecycle.Effect.Check>()
            .firstOrNull()?.attempt ?: return
        viewModelScope.launch {
            val c = client ?: return@launch
            runCatching { c.pluginCheck(activeSession.value?.dir) }
                .onSuccess { list ->
                    pluginEntries.value = list
                    pluginState = com.zapp.net.api.PluginLifecycle.reduce(
                        pluginState,
                        com.zapp.net.api.PluginLifecycle.Event.CheckSucceeded(
                            attempt,
                            com.zapp.net.api.PluginLifecycle.outdatedMap(list),
                        ),
                    ).state
                }
                .onFailure { e ->
                    pluginState = com.zapp.net.api.PluginLifecycle.reduce(
                        pluginState,
                        com.zapp.net.api.PluginLifecycle.Event.CheckFailed(
                            attempt,
                            e.message ?: "check failed",
                        ),
                    ).state
                    showToast("Check failed", e.message ?: "")
                }
        }
    }

    fun updatePlugin(target: String) {
        val result = com.zapp.net.api.PluginLifecycle.reduce(
            pluginState,
            com.zapp.net.api.PluginLifecycle.Event.UpdateRequested(target),
        )
        pluginState = result.state
        val update = result.effects
            .filterIsInstance<com.zapp.net.api.PluginLifecycle.Effect.Update>()
            .firstOrNull() ?: return
        viewModelScope.launch {
            val c = client ?: return@launch
            runCatching { c.pluginUpdate(listOf(target), activeSession.value?.dir) }
                .onSuccess {
                    pluginState = com.zapp.net.api.PluginLifecycle.reduce(
                        pluginState,
                        com.zapp.net.api.PluginLifecycle.Event.UpdateSucceeded(update.id),
                    ).state
                    // `refresh` - their effect reloads the plugin list after a successful update.
                    loadExtensions()
                }
                .onFailure { e ->
                    pluginState = com.zapp.net.api.PluginLifecycle.reduce(
                        pluginState,
                        com.zapp.net.api.PluginLifecycle.Event.UpdateFailed(
                            update.id,
                            e.message ?: "update failed",
                        ),
                    ).state
                    showToast("Update failed", e.message ?: "")
                }
        }
    }

    private fun persistCookies(list: List<okhttp3.Cookie>) {
        viewModelScope.launch {
            com.zapp.net.api.saveCookies(
                ctx,
                list.filter { it.expiresAt > System.currentTimeMillis() }
                    .map { com.zapp.net.api.cookieToData(it) }
            )
        }
    }

    private fun persistToken(t: String) {
        viewModelScope.launch { com.zapp.net.api.saveToken(ctx, t) }
    }

    /**
     * Debug-only: read the real usage surface once per launch and say what came back.
     * `data: null` is the honest answer on this phone (no Console workspace), and it is the
     * case their `usage.signedOut.*` copy exists for. Logged so the device carries the proof.
     */
    private fun debugProbeUsage() {
        val debuggable =
            (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        viewModelScope.launch {
            val c = client ?: return@launch
            val dir = activeSession.value?.dir?.takeIf { it.isNotBlank() }
            val env = runCatching { c.usageLimits(dir) }.getOrNull()
            val verdict = when {
                env == null -> "FAIL (request or decode)"
                env.data == null -> "signed-out (data=null)"
                else -> buildString {
                    append("workspace=").append(env.data.workspace?.id ?: "-")
                    append(" budgets=").append(env.data.budgets.size)
                    val g = env.data.go
                    if (g != null) append(" go(rolling=").append(g.rolling?.percent)
                        .append(" weekly=").append(g.weekly?.percent)
                        .append(" monthly=").append(g.monthly?.percent).append(')')
                }
            }
            android.util.Log.i("ZAPP", "usage limits: $verdict")
        }
    }

    /**
     * Ask the server whether this device is still allowed, and record the verdict.
     *
     * Runs on every cold start (from [tryReconnect]) and again whenever the user taps
     * `Join again`. It never throws and never blocks the UI: the screen reads [gate].
     *
     * `Denied` is the only state that wipes anything, because it is the only state where the
     * server itself said no. An unreachable server must not log the user out - his phone
     * loses the route several times a day (measured repeatedly on 2026-11-20) and clearing a
     * working token on a carrier blip would lock him out of his own phone.
     */
    suspend fun verifyServer(): GateState {
        val c = client
        if (c == null) {
            gate.value = GateState.NoServer
            return GateState.NoServer
        }
        gate.value = GateState.Checking
        val p = runCatching { c.probe() }
            .getOrElse { OpenCodeClient.Probe(false, -1, it.message ?: it.toString()) }
        val verdict = when {
            p.ok -> GateState.Ok
            p.status == 401 || p.status == 403 -> GateState.Denied
            p.status == -1 -> GateState.Unreachable(p.detail)
            else -> GateState.Unreachable("server answered ${p.status}: ${p.detail}")
        }
        gate.value = verdict
        android.util.Log.e("ZAPP", "gate: ok=${p.ok} status=${p.status} -> $verdict")
        return verdict
    }

    /**
     * "Join again": drop the stored credential and put the user back on the connect screen.
     *
     * Their session id, cookies and token all go. The egress lane, the guard and the
     * fingerprint survive - this is a login reset, not a factory reset.
     */
    fun joinAgain() {
        viewModelScope.launch {
            // "Join again" is a decision about the CREDENTIAL, so it may only run when the
            // server has actually answered. Measured 23 Nov: the gate drew "Join again" while
            // `serve.sh` was still installing the engine, he tapped it, and the token was wiped
            // on a server that had never been asked - the login then had to be redone from
            // scratch. An unreachable engine is not an invalid credential.
            val engineUp = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    java.net.Socket().use {
                        it.connect(java.net.InetSocketAddress("127.0.0.1", 4096), 500)
                        true
                    }
                }
            }.getOrDefault(false)
            if (!engineUp) {
                statusOk("The engine is still starting - one moment")
                android.util.Log.e("ZAPP", "gate: joinAgain refused - engine not listening on 4096")
                return@launch
            }
            client = null
            connected.value = false
            gate.value = GateState.NoServer
            runCatching { com.zapp.net.api.saveToken(ctx, "") }
            runCatching { com.zapp.net.api.saveCookies(ctx, emptyList()) }
            statusOk("Signed out - connect again")
            android.util.Log.e("ZAPP", "gate: joinAgain -> credential cleared")
        }
    }

    /** Saved token/cookie from last run -> straight into chat, no pair screen. */
    private fun tryReconnect(base: String, cookiesJson: String, authToken: String) {        android.util.Log.e("ZAPP", "tryReconnect: tok=${authToken.take(12)} cookiesLen=${cookiesJson.length}")
        if (authToken.isBlank() && cookiesJson.isBlank()) {
            // Nothing saved: there is nothing to wait for, so the connect screen is correct
            // and must appear immediately.
            booting.value = false
            gate.value = GateState.NoServer
            return
        }
        viewModelScope.launch {
            try {
                val cookies = com.zapp.net.api.decodeCookies(cookiesJson)
                    .mapNotNull { com.zapp.net.api.dataToCookie(it) }
                val c = OpenCodeClient(
                    base, cookies, initialToken = authToken.ifBlank { null },
                    onCookies = { persistCookies(it) },
                    onToken = { persistToken(it) },
                )
                // Every cold start lands here before the engine exists: the relay fires from
                // `init` and needs ~42 s. Failing this test used to drop a returning user onto
                // the connect screen while their engine was starting perfectly well.
                if (isLoopback(base)) awaitEngine("reconnect")
                firstAnswer(c) // auth test: unauthorized -> throws -> stays on pair screen
                client = c
                connected.value = true
                // their `createEffect`: every connect re-runs the recovery sweep while the
                // setting is on, because the event stream never replays what was missed
                autoSyncSweep()
                statusOk("Reconnected automatically")
                android.util.Log.e("ZAPP", "reconnect OK")
                // `refetchOnReconnect: true` + the 30 s stale window (`queries.ts:43,51`).
                refreshSessions(force = false)
                loadPickers()
                loadCommands()
                loadPermissions()
                loadForms()
                loadQuestions()
                listen()
                debugProbeUsage()
                // The credential worked, so the gate is already answered; re-asking would
                // cost a second round trip for the same verdict. Still recorded, so the
                // status line is truthful rather than assumed.
                gate.value = GateState.Ok
            } catch (e: Exception) {
                android.util.Log.e("ZAPP", "reconnect FAIL: ${e.message}", e)
                statusFail("Reconnect failed: ${e.message}")
                // A failed reconnect is not automatically a revocation. Distinguish it:
                // 401/403 means the server refused the credential, anything else is local.
                val msg = e.message.orEmpty()
                gate.value = if (msg.contains("-> 401") || msg.contains("-> 403"))
                    GateState.Denied
                else GateState.Unreachable(msg.take(160))
            } finally {
                // Every path must release the holding frame, including the throw above.
                booting.value = false
            }
        }
    }

    fun loadPickers() {
        viewModelScope.launch {
            try {
                // Desktop scopes every provider/model call to the active directory
                // (`?location[directory]=...`). Asking globally returned the server's
                // default location catalog, which is a different list than the one PC shows
                // for the folder he is actually working in.
                val dir = activeSession.value?.dir?.takeIf { it.isNotBlank() }
                val pList = client?.providers(dir) ?: return@launch
                val mList = client?.models(dir) ?: return@launch
                // PORT of runtime/server/global-sync/utils.ts `buildProviderCatalogs`:
                // providers first, then each provider's models, dropping only the ones the
                // server marks deprecated and the ones whose provider is not registered.
                // No allowlist, no personal lane, no ranking ï¿½ desktop's exact rule.
                val groups = com.zapp.net.api.providerGroups(pList, mList)
                providers.value = groups.map { it.provider }
                val allowed = groups.flatMap { it.models }
                catalogModels.value = allowed
                // PORT of `settings/models` - "Choose which models appear in model picker".
                // The full catalog stays in `allowed` because the heal check below must still
                // see a model the user hid; only the picker's list is filtered.
                models.value = com.zapp.net.settings.SettingsPrefs.filterModels(ctx, allowed) {
                    it.modelID
                }
                if (allowed.isEmpty()) {
                    statusFail("No model - server reports ${mList.size}, providers ${pList.size}")
                    android.util.Log.e("ZAPP", "pickers EMPTY: models=${mList.size} providers=${pList.size}")
                    return@launch
                }
                // heal ONLY if the saved model vanished from the catalog entirely
                // (renamed/removed/deprecated). A real selection is respected.
                val sel = selectedModel.value
                android.util.Log.e("ZAPP", "pickers: models=${mList.size} shown=${allowed.size} sel=${sel?.providerID}/${sel?.modelID}")
                val exists = sel != null && allowed.any { it.providerID == sel.providerID && it.modelID == sel.modelID }
                if (!exists) {
                    // their `default`: first non-deprecated model of the provider
                    val pid = groups.firstOrNull()?.provider?.id ?: return@launch
                    val first = com.zapp.net.api.defaultModelFor(pList, mList, pid) ?: return@launch
                    val variant = first.variants.firstOrNull()?.id ?: ""
                    selectedModel.value = SelectedModel(first.providerID, first.modelID, variant, first.name)
                    com.zapp.net.settings.saveModel(ctx, first.providerID, first.modelID, variant, first.name)
                    android.util.Log.e("ZAPP", "heal -> ${first.providerID}/${first.modelID} v=$variant")
                    statusOk("Saved model gone - switched to ${first.name}")
                    activeSession.value?.let { applySessionModel(it) }
                }
                // A lock outranks the heal: while it is on, the app stays on ONE model.
                // the owner, 2026-11-24: *"WHAT IF I WANT 1 MODEL ONLY KASI YUNG IBANG MODEL
                // ANG HIHINA"*. If the locked id left the catalog the lock retires with a
                // notice rather than quietly leaving him on whatever sorted first.
                val lock = com.zapp.net.settings.SettingsPrefs.lockedModel(ctx)
                if (lock.isNotBlank()) {
                    val locked = allowed.firstOrNull { it.modelID == lock }
                    if (locked == null) {
                        com.zapp.net.settings.SettingsPrefs.unlockModel(ctx)
                        statusFail("Locked model $lock is gone - model list unlocked")
                        android.util.Log.e("ZAPP", "lock cleared: $lock not in catalog")
                    } else if (selectedModel.value?.modelID != lock) {
                        chooseModel(locked.providerID, locked.modelID, "", locked.name)
                        statusOk("Locked to ${locked.name}")
                    }
                }
            } catch (e: Exception) {
                statusFail("Picker load failed: ${e.message}")
            }
        }
    }

    fun chooseModel(providerID: String, modelID: String, variant: String, label: String) {
        selectedModel.value = SelectedModel(providerID, modelID, variant, label)
        viewModelScope.launch {
            com.zapp.net.settings.saveModel(ctx, providerID, modelID, variant, label)
            // make the chip REAL: push the model onto the open session
            activeSession.value?.let { applySessionModel(it) }
        }
    }

    /**
     * the owner, 2026-11-24: one model, no weaker ones in the picker.
     *
     * Locks the visible list to [modelID] with their own `models.disabled` store, and makes
     * that model the active one so the very next send uses it. `models.value` is refreshed
     * from the server catalog because the lock has to be applied by the same filter the
     * picker reads, not by a second rule that could drift.
     */
    fun lockToModel(modelID: String) {
        if (modelID.isBlank()) return
        val ids = catalogModels.value.map { it.modelID }.ifEmpty { models.value.map { it.modelID } }
        com.zapp.net.settings.SettingsPrefs.lockModel(ctx, modelID, ids)
        val m = models.value.firstOrNull { it.modelID == modelID }
        if (m != null) {
            chooseModel(m.providerID, m.modelID, "", m.name)
            statusOk("Only ${m.name} - model list locked")
        } else {
            statusOk("Model list locked to $modelID")
        }
        loadPickers()
    }

    fun unlockModels() {
        com.zapp.net.settings.SettingsPrefs.unlockModel(ctx)
        statusOk("Model list unlocked")
        loadPickers()
    }

    /** VERIFIED LIVE: body {"model":{id, providerID, modelID, variant?}} -> 204.
     *  Without `id` the server 400s ("Missing key at [model][id]"); id == modelID in the registry. */
    private suspend fun applySessionModel(session: Session) {
        val sel = selectedModel.value ?: return
        runCatching {
            val varJson = if (sel.variant.isNotBlank()) ""","variant":${json(sel.variant)}""" else ""
            client?.post(
                "/api/session/${session.id}/model",
                """{"model":{"id":${json(sel.modelID)},"providerID":${json(sel.providerID)},"modelID":${json(sel.modelID)}$varJson}}"""
            )
        }.onFailure { statusFail("Model switch failed: ${it.message}") }
    }

    private fun json(s: String) = kotlinx.serialization.json.Json.encodeToString(s)

    private var loadingMessages = false

    /** The session whose transcript request is currently in flight (dedupe key). */
    private var inFlightSession: String? = null

    /** Increments on every transcript request; only the newest one may touch `messages`. */
    private var messagesToken = 0

    /**
     * Rows he just sent that the server has not echoed back yet. Their client shows the
     * message the instant he hits send; waiting for `POST /prompt` + a full reload is what
     * made his own text appear seconds late, and made a slow turn look like a dead app.
     */
    private val optimistic = LinkedHashMap<String, MessageWithParts>()
    private var optimisticSeq = 0

    /**
     * Never let a server copy shorten text that is already on screen.
     *
     * the owner, 22 Nov: "THE BUG IS DELAY REPLY ITS STOP AND THEN SEND NOT THE SAME AS PC".
     * Two groups of frames forced a **full transcript read** in the middle of a live turn:
     * `session.status` (a busy/idle ping) sat in the `terminal` group, and
     * `session.message.content.updated` sat in `structural`. Both run `loadMessages()`, which
     * replaces the whole list with the server's copy.
     *
     * The server persists a part *after* it streams it, so that copy is routinely behind the
     * deltas already drawn. Replacing the list made the answer jump backwards - the "stop" -
     * and the deltas that had already been consumed were never re-sent, because
     * `liveOrdinals` correctly refuses a repeated ordinal. The missing text then appeared
     * only with the next full read: the "then send".
     *
     * Desktop never refetches a live transcript; its store only ever grows. This is that rule
     * applied at the one place a read still happens: text and reasoning parts are append-only
     * for the life of a turn, so the longer copy is by definition the newer one.
     *
     * Only while `sending` is true: once the turn ends the server copy is authoritative, and a
     * revert or an undo must be able to shorten text again.
     */
    private fun mergeLive(
        prev: List<MessageWithParts>,
        next: List<MessageWithParts>,
    ): List<MessageWithParts> {
        if (prev.isEmpty()) return next
        val byId = prev.associateBy { it.info.id }
        var kept = 0
        val out = next.map { m ->
            val old = byId[m.info.id] ?: return@map m
            // A message the server has marked complete is the truth, and it may legitimately be
            // shorter than what is on screen (a fork, an edit, a truncated retry).
            //
            // While it is still open, the opposite is true: the server's copy can lag the
            // stream, and letting it win made the transcript shrink and grow again. That is
            // the owner's "PAUSE AND THEN A SEC BLINK" - measured 22 Nov 16:28, the gate here was
            // `sending.value` alone, so the instant that flag flickered mid-turn the drawn text
            // was replaced by an older copy and then restored by the next delta.
            if (m.info.time?.completed != null && !sending.value) return@map m
            var hit = false
            val parts = m.parts.mapIndexed { i, p ->
                if (p.type != "text" && p.type != "reasoning") return@mapIndexed p
                val o = old.parts.getOrNull(i) ?: return@mapIndexed p
                if (o.type != p.type) return@mapIndexed p
                if (o.text.orEmpty().length > p.text.orEmpty().length) {
                    hit = true
                    p.copy(text = o.text)
                } else p
            }
            if (!hit) m else {
                kept++
                m.copy(parts = parts)
            }
        }
        if (kept > 0) {
            android.util.Log.e(
                "ZAPP",
                "merge: kept $kept live message(s) that the server copy was behind on",
            )
        }

        // Rows the server has not written yet.
        //
        // `next` is the server's list, and this used to be `next.map { ... }` and nothing else -
        // so any row that existed only on the phone was dropped by every reload. The assistant
        // row being streamed into is exactly that row for the first seconds of a turn, which is
        // why the text vanished and then arrived in one block: the next delta recreated an empty
        // row and the rest of the answer came back with the following fetch. Measured 22 Nov
        // 17:00:20, one reload added 49 979 B in a single step.
        //
        // Desktop never hits this because the server *pushes* the row before streaming into it
        // (`session.text.started` after the message is created), so its index always has one.
        // We fetch-and-replace, so the only honest equivalent is to keep the unfinished rows
        // the server has not caught up with yet.
        //
        // Three limits keep this from resurrecting anything:
        //   * only while a turn is open (`sending`), so a row deleted after a turn ends stays gone
        //   * only rows with no `time.completed` - a finished row is the server's to state
        //   * never an optimistic row (`local-*`), which `withOptimistic` owns and would duplicate
        val seen = next.mapTo(HashSet(next.size)) { it.info.id }
        val orphans = if (!sending.value) emptyList() else prev.filter {
            it.info.id !in seen &&
                !it.info.id.startsWith("local-") &&
                it.info.time?.completed == null
        }
        if (orphans.isNotEmpty()) {
            android.util.Log.e(
                "ZAPP",
                "merge: kept ${orphans.size} row(s) the server had not written yet",
            )
            return out + orphans
        }
        return out
    }

    private fun withOptimistic(list: List<MessageWithParts>): List<MessageWithParts> {
        if (optimistic.isEmpty()) return list
        // a confirmed row means the server caught up: drop our stand-in for it
        val sent = list.filter { it.info.role == "user" }
            .map { m -> m.parts.firstOrNull { it.type == "text" }?.text.orEmpty() }
            .toMutableSet()
        optimistic.entries.removeAll { (_, v) ->
            sent.remove(v.parts.firstOrNull { it.type == "text" }?.text.orEmpty())
        }
        return if (optimistic.isEmpty()) list else list + optimistic.values
    }

    private fun addOptimistic(text: String, files: List<com.zapp.net.api.OutAttachment>): String {
        val id = "local-${++optimisticSeq}"
        val parts = buildList {
            add(com.zapp.net.api.Part(type = "text", text = text))
            files.forEach { f ->
                add(
                    com.zapp.net.api.Part(
                        type = "image",
                        mime = f.mime,
                        url = f.dataUrl,
                        text = f.name,
                    ),
                )
            }
        }
        val row = MessageWithParts(
            info = com.zapp.net.api.MessageInfo(
                id = id,
                role = "user",
                type = "user",
                time = com.zapp.net.api.MsgTime(created = System.currentTimeMillis()),
            ),
            parts = parts,
        )
        optimistic[id] = row
        messages.value = messages.value + row
        return id
    }

    private fun dropOptimistic(id: String) {
        if (optimistic.remove(id) == null) return
        messages.value = messages.value.filterNot { it.info.id == id }
    }

    /** Cursor for `/message?cursor=`: `previous` walks back into older history. */
    private val olderCursor = MutableStateFlow<String?>(null)
    val hasOlder = MutableStateFlow(false)
    private var loadingOlder = false

    /** `Load older` at the top of the timeline ï¿½ one page, prepended, never a full reload. */
    fun loadOlderMessages() {
        val c = client ?: return
        val s = activeSession.value ?: return
        val cur = olderCursor.value ?: return
        if (loadingOlder) return
        loadingOlder = true
        viewModelScope.launch {
            try {
                val page = c.messagesPage(s.id, limit = 40, cursor = cur)
                val known = messages.value.mapTo(HashSet()) { it.info.id }
                val older = page.messages.filterNot { it.info.id in known }
                if (older.isNotEmpty()) messages.value = older + messages.value
                olderCursor.value = page.previous
                hasOlder.value = page.previous != null
            } catch (e: Exception) {
                statusFail("Older failed: ${e.message?.take(120)}")
            } finally {
                loadingOlder = false
            }
        }
    }

    fun loadMessages(session: Session, force: Boolean = false) {
        // Their client never blocks a fetch behind another one: opening a session must show
        // THAT session, always. The old `if (loadingMessages) return` gate meant a slow page
        // for session A silently cancelled the load for session B, so B kept rendering A's
        // transcript ï¿½ "every session has our whole conversation in it".
        //
        // Deduplicate only the *same* session: `open()` loads, then the screen's
        // `LaunchedEffect(active.id)` fires and used to issue an identical second request,
        // doubling the wait on a slow link. `force` is desktop's `cancelRefetch: true`
        // (`sync.tsx:231`): the resync after a reconnected stream must REPLACE a read that is
        // still running, because that read may have started before the drop and can therefore
        // be missing everything the server wrote in between.
        if (inFlightSession == session.id && !force) return
        inFlightSession = session.id
        val token = ++messagesToken
        loadingMessages = true
        viewModelScope.launch {
            try {
                val c = client ?: return@launch
                val page = c.messagesPage(session.id, limit = 80)
                // a newer load (another session, or a reload of this one) already won
                if (token != messagesToken) return@launch
                if (activeSession.value?.id != session.id) return@launch
                val list = page.messages
                // `tab-strip.tsx:575-581`: `prompted` is "the session has a user message",
                // and only prompted sessions appear in the recently-closed menu (`:79`).
                if (list.any { it.info.role == "user" }) promptedSessions.add(session.id)
                olderCursor.value = page.previous
                hasOlder.value = page.previous != null
                messages.value = withOptimistic(mergeLive(messages.value, list))
                // Seed the ledger's watermark so a reload cannot charge a turn that finished
                // before this app started. Measured 22 Nov 11:10: the first `session.idle`
                // replay after launch charged 12k against a brand-new address for a turn it
                // had never carried. While a turn IS live the tail is the in-progress message,
                // so it is left unseeded and charged when it really finishes.
                if (!sending.value) {
                    lastChargedMsgId = list.lastOrNull { it.info.role == "assistant" }?.info?.id
                }
                // Keep the chat's own size on hand: the free tier refuses a request whose
                // context is too large no matter which /64 it comes from (measured
                // 2026-11-19 23:16: 35 small prompts passed on one address while the
                // 108k-token chat was refused twice on it). Knowing the number before he
                // types is the difference between a warning and a dead turn.
                contextOfSession.value = session.id to contextOf(list)
                // `store.session.message[sessionID]` - kept so a tab switch paints the last
                // known transcript immediately instead of a blank pane (`data.ts`).
                messageCache[session.id] = messages.value
                // replay any text that arrived before this row existed, so a live stream
                // started mid-turn still shows from its first buffered token
                flushPendingDeltas()
                // The wall arrives as a finished assistant message, not as a thrown error:
                // `finish:"error"` + `error.message`. Watching for it here is what lets the
                // hop fire on its own instead of waiting for him to read a dead turn.
                wallFromMessages(session, list)
                // a reopened chat mid-run must still show the live status line: the last
                // assistant part has no completion stamp until the server finishes it
                //
                // 22 Nov 15:27: this was written straight into `sending`, and it is the reason
                // a one-tap Repair sat on stage 5 for 180 s saying "reply is still streaming -
                // rotation would cut it" on a phone where all four sessions were idle. The
                // interrupted zombie turn left an assistant part with no `completed` stamp, so
                // this line answered `true` on every load; when `sessionDetail` came back null
                // (it does, on a cold tunnel) the correct answer below never ran and nothing
                // ever cleared the flag. It is a *provisional* value now, only used when the
                // detail read fails, and it can never outlive a null.
                val tail = list.lastOrNull()
                val tailTime = tail?.parts?.lastOrNull()?.time
                val tailUnfinished = tail != null &&
                    tail.info.role == "assistant" &&
                    tailTime?.completed == null && tailTime?.ran == null
                // busy is sticky here: only `session.idle`/errors end a turn, because a
                // poll landing between busy and idle used to flip the whole UI
                val detail = client?.sessionDetail(session.id)
                if (detail != null) {
                    sessionTotals.value = detail
                    // `busy` advances while the server works, so it is normally authoritative -
                    // but it can outlive the turn. Measured 22 Nov 13:55 on
                    // `ses_dfead4cfaffenNJBHHjrSRzCdU`: `time.busy=1795325668552` was newer than
                    // `time.idle=1795324926526` and stayed that way for half an hour after the
                    // free-limit wall had already finished the message, so `isBusy` answered
                    // true forever. That single flag kept "Working" on screen, kept `sending`
                    // true so the composer refused a new prompt, and kept an animation running
                    // at 115% CPU - his "it's delayed, not live".
                    //
                    // A turn cannot outlive its own last assistant message: `time.completed` or
                    // `time.ran` on that part is the authoritative end. Trust the message over
                    // the session flag, and never resurrect a finished turn from `isBusy`.
                    //
                    // 22 Nov 17:50, on his screen: "Working · 163s" sat over a turn that had
                    // already finished, and the composer refused to send because it was stuck on
                    // "Queue". The server had written its terminal row - an `idle` message
                    // carrying `outcome:"interrupted"` - but `busy` was still newer than `idle`
                    // and the *previous* row was the assistant one, whose part never got a
                    // `completed` stamp because the free-limit wall interrupted it. Checking only
                    // for an assistant tail therefore answered `false` for `finished` forever.
                    //
                    // The `idle` row is the server's own end-of-turn record: it is written last,
                    // and it cannot exist while a turn is still running. So it outranks `busy`
                    // exactly the way a completed assistant part does.
                    val tailIsIdle = tail != null && (
                        tail.info.role == "idle" || tail.parts.any { it.type == "idle" }
                        )
                    val finished = tailIsIdle || (tail != null &&
                        tail.info.role == "assistant" &&
                        (tailTime?.completed != null || tailTime?.ran != null))
                    sending.value = detail.time?.isBusy == true && !finished
                } else {
                    // No detail read: keep whatever the stream says. Never invent `true` from a
                    // missing stamp - that is the exact shape that stranded Repair on stage 5.
                    // `tailUnfinished` is still useful for the status line, so it is only used
                    // when the flag was already set by a real delta.
                    if (sending.value && !tailUnfinished) sending.value = false
                }
            } catch (e: Exception) {
                val why = e.message.orEmpty()
                android.util.Log.e("ZAPP", "messages FAIL sid=${session.id}: $why")
                // The row he tapped no longer exists on the server. Their sidebar drops it and
                // keeps the list honest instead of leaving a blank screen pointed at nothing.
                if (why.contains("SessionNotFound")) {
                    sessions.value = sessions.value.filterNot { it.id == session.id }
                    if (activeSession.value?.id == session.id) {
                        clearActive("messages: session not found")
                        messages.value = emptyList()
                        pickActiveSession()
                    }
                } else {
                    statusFail("Messages failed: ${why.take(140)}")
                    ensureServer("messages failed: ${why.take(80)}")
                }
            } finally {
                if (token == messagesToken) loadingMessages = false
                if (inFlightSession == session.id) inFlightSession = null
                // deltas are still buffered: the row they belong to was not in this page,
                // so ask once more (bounded) instead of waiting for the terminal reload
                //
                // The test is on the *row*, not on the buffer. A live turn always has deltas
                // in flight, so `pendingDeltas.isNotEmpty()` was true after every load and this
                // refetched up to six full transcripts back to back - 4.5 MB each on the long
                // session - with nothing missing. Every one of those replaces the list, which
                // is the other half of the visible "stop, then jump". Desktop has no such
                // retry at all: its store is pushed to, never re-read.
                val stranded = pendingDeltas.keys.any { key ->
                    val mid = key.substringBefore('|')
                    messages.value.none { it.info.id == mid }
                }
                if (stranded && refetchAttempts < 6) {
                    refetchAttempts++
                    val s = session
                    viewModelScope.launch {
                        delay(150)
                        if (activeSession.value?.id == s.id) loadMessages(s)
                    }
                }
            }
        }
    }

    /** their retry shape: one wall -> one fresh /64 -> resend the same prompt once. */
    private fun wallFromMessages(session: Session, list: List<MessageWithParts>) {
        val last = list.lastOrNull { it.info.role == "assistant" } ?: return
        val err = last.parts.lastOrNull { it.type == "error" }?.text?.trim().orEmpty()
        if (err.isBlank()) return
        // CLASSIFY BEFORE CLAIMING.
        //
        // The claim is the "this wall has already been paid for with an identity" record, and
        // it used to be written first for EVERY error text. A transport failure or a
        // `Step interrupted` was therefore recorded as an answered wall, and the next genuine
        // wall on that same message - same `sessionID|messageID` key - was silently swallowed.
        //
        // Measured 2026-11-23 21:49: `already answered: ses_df76...|msg_208a...(text:Free usage
        // limit reached)` was printed while a second hop was already burning another /64. A
        // claim is only meaningful for an error that is actually a wall.
        val engine = com.zapp.net.guard.RotationEngine(ctx)
        val text = list.lastOrNull { it.info.role == "user" }
            ?.parts?.firstOrNull { it.type == "text" }?.text.orEmpty()
        val low = err.lowercase()

        // Their `_WarpState.note_stream_reset`: a single reset can be the provider closing a
        // finished stream, so the edge is retired only after a second reset inside five
        // minutes. Measured 21:42-21:43 (`provider.transport ECONNRESET` mid-answer) - that
        // one was our own rebuild cutting the socket, and the rebuild cause is fixed. This is
        // the accounting that says whether an edge, rather than the provider, keeps cutting.
        if (low.contains("econnreset") || low.contains("transport")) {
            val edge = com.zapp.net.vpn.ZappVpnService.activeEdgeName
            if (edge != null && com.zapp.net.guard.GuardState.status.value.verified &&
                com.zapp.net.guard.EdgePins.noteStreamReset(edge)
            ) {
                android.util.Log.e("ZAPP", "stream reset x2 on $edge -> retired for 120s")
                com.zapp.net.guard.GuardState.log("edge", "2 stream resets on $edge - retired 120s")
            }
        }

        // upstream retires model ids without notice: `410 ModelDeprecated` names the
        // replacement, so follow it instead of leaving him on a dead chip.
        //
        // With a one-model lock the rule stays theirs - follow the id the SERVER named - but
        // the old invented fallback (`?: deepseek-v4.1-flash-free`) is gone. the owner, 24 Nov:
        // *"YUNG IBANG MODEL ANG HIHINA"*. Guessing a weaker model and resending his prompt
        // under it is not a rescue, it is a silent downgrade. If the server names nothing we
        // stop and say so.
        if (low.contains("deprecated") && low.contains("model")) {
            val target = models.value.firstOrNull { low.contains(it.modelID.lowercase()) }
            if (target == null) {
                statusFail("Model retired - the server named no replacement. Pick another in the model list.")
                return
            }
            val lock = com.zapp.net.settings.SettingsPrefs.lockedModel(ctx)
            if (lock.isNotBlank()) {
                // the locked id is the one that just died, so the lock moves with the
                // replacement rather than leaving a lock pointing at a retired id
                com.zapp.net.settings.SettingsPrefs.lockModel(ctx, target.modelID, catalogModels.value.map { it.modelID })
            }
            viewModelScope.launch {
                resendAs(
                    session, text, target.providerID, target.modelID,
                    "Model retired - switched to ${target.name}",
                )
            }
            return
        }

        if (!engine.isQuotaWall(err)) {
            statusFail(err)
            return
        }
        // Only a real wall is worth an identity. The ledger lives on disk, so a reconnect can
        // never replay a wall that has already been paid for.
        if (!com.zapp.net.guard.WallLedger.claim(
                ctx,
                session.id,
                last.info.id,
                "text:${err.take(24)}",
            )
        ) return
        // A refused step does not always close the turn.
        //
        // Measured on this phone 2026-11-23 21:53: after the provider refused with `Free usage
        // limit reached`, the engine still reported the session `busy`. Two more sends sat in the
        // transcript as user bubbles with no assistant row at all - they were QUEUED behind a
        // turn that could never finish - while `/api/info` answered in 40 ms and the composer
        // showed `Gathering thoughts`. That is the owner's "nothing happens hahah".
        //
        // The refusal IS the end of the turn, so close it on the server before rotating; the
        // queue behind it can then run on the new address instead of waiting for `ZOMBIE_MS`
        // (three minutes) to interrupt it.
        viewModelScope.launch {
            runCatching { client?.interrupt(session.id) }
        }
        sending.value = false
        if (!com.zapp.net.vpn.ZappVpnService.running) {
            // a new /64 only changes our egress if Termux is inside the tunnel
            runCatching {
                ctx.startService(
                    android.content.Intent(ctx, com.zapp.net.vpn.ZappVpnService::class.java)
                        .setAction(com.zapp.net.vpn.ZappVpnService.ACTION_START),
                )
            }
        }
        statusFail("Wall hit - rotating /64-")
        // Tag the reason with the wall's own type. `zen_guard` sets `last_free_wall` at
        // detection, and `_note_wall` reads it; without the tag this path would rotate
        // without ever counting a free wall, so the 2-hop pause could never fire.
        val wallType = engine.wallOf(err)?.type ?: "message"
        // Charge the address for the turn it just refused. `spentTokens` is the ledger that
        // makes "this IP is finished" knowable *before* the next wall instead of after it -
        // the owner's 200k per address, counted rather than guessed.
        val walledCost = contextOf(list)
        viewModelScope.launch {
            runCatching {
                val pool = com.zapp.net.guard.WarpPool(ctx)
                pool.usable()?.let { pool.charge(it, walledCost) }
            }
            val reg = com.zapp.net.guard.RotationEngine.hopNow(ctx, "wall:$wallType")
            if (reg == null) {
                // Nothing was rotated, so nothing was sent: the prompt the server refused must
                // go back into the composer. Measured 05:06:18, this path set the status line
                // "Wall hit - hop failed, see Guard" and dropped the text, so his message was
                // gone with no way to resend it.
                if (text.isNotBlank()) restorePrompt.value = session.id to text
                statusFail(
                    if (engine.isPaused())
                        "Rotation paused (account cap) - prompt is back in the composer"
                    else if (text.isNotBlank())
                        "Wall hit - hop failed, prompt is back in the composer"
                    else "Wall hit - hop failed, see Guard",
                )
                return@launch
            }
            // The hop installs the new /64 with "no register, no verify wait". Measured on this
            // phone 2026-11-23 21:49:15:
            //
            //     [hop] warm swap: installed 2a09:bac5:4ff3:2e1e::/64 in 146.410692 ms
            //           (no register, no verify wait)
            //
            // and five seconds later every outbound probe timed out - `api64.ipify.org` and
            // `[2606:4700:4700::1111]/cdn-cgi/trace` both `SocketTimeoutException`. Resuming
            // his prompt on that address sent it into a tunnel nobody had proven: no provider
            // answer ever came back, and the chat looked frozen mid-response.
            //
            // A hop is not an exit. Wait for the service's own egress reading on THIS
            // registration before spending a send on it, and if it never lands, the prompt goes
            // back to the composer where he can see it.
            // 8 s, not 20.
            //
            // The wait exists because resuming on an unproven address sent his prompt into a
            // tunnel nobody had proven, and no answer ever came back (measured 23 Nov 21:49).
            // But twenty seconds is longer than the reply he is waiting for, and the owner's
            // complaint is the opposite of that: "unlimited pero super tagal".
            //
            // A warm swap usually has a proven `/64` already in the pool and this returns on
            // the first pass (`egress64` is copied with the identity). A fresh registration is
            // the only case that waits, and 8 s is enough for the keeper's own 8 s probe to
            // land. If it does not, the prompt goes back to the composer - visible, one tap -
            // instead of a silent send into a dead tunnel.
            if (awaitProvenEgress(reg, 8_000L) == null) {
                if (text.isNotBlank()) restorePrompt.value = session.id to text
                statusFail("New address not proven - prompt is back in the composer")
                return@launch
            }
            // AUTO-SEND REMOVED (the owner, 2026-11-20): "AND ALSO REMOVE AUTO SEND".
            // What that removed was the literal word "continue" fired into every walled chat.
            // The composer is still the fallback; the resume below is narrower and opt-out.
            if (text.isNotBlank()) restorePrompt.value = session.id to text
            // ...and the turn continues on it. The old socket died with the TUN, so the
            // server-side turn is gone - only a fresh request can carry on. This is his own
            // prompt, in this chat, once. It is NOT the old auto-send that fired the literal
            // word "continue" into every chat the wall had touched.
            com.zapp.net.settings.GuardPrefs.load(ctx)
            if (text.isNotBlank() && com.zapp.net.settings.GuardPrefs.state.value.resume) {
                val sel = selectedModel.value
                if (sel == null) {
                    statusOk("New address ready \u2014 prompt is back in the composer")
                } else {
                    runCatching {
                        client?.prompt(session.id, text, sel.providerID, sel.modelID, emptyList(), null)
                    }.onSuccess {
                        restorePrompt.value = null
                        com.zapp.net.guard.GuardState.log(
                            "resume",
                            "re-sent the walled prompt on the reserve address " +
                                "(${reg.egress64 ?: reg.v6})",
                        )
                        statusOk("Resumed on the reserve address")
                    }.onFailure {
                        statusFail("Resume failed \u2014 prompt is back in the composer")
                    }
                }
            } else {
                statusOk("New address ready \u2014 prompt is back in the composer")
            }
        }
    }

    /**
     * A real quota wall, straight from the server's own classification.
     *
     * Their desktop client does two things when `provider.free-limit` lands: shows the
     * "Free limit reached" card and puts the refused prompt back in the composer so one
     * keypress retries it (`cr.set(er)`). We do the same, plus the thing only we can do:
     * if the limit is attached to our egress address, rotate the /64 first so that retry
     * actually has a chance.
     */
    fun onQuotaWall(frame: com.zapp.net.guard.QuotaWall.Frame) {
        val wall = frame.wall
        // Durable frames are replayed after every reconnect, and the debug mirror can deliver
        // the same one twice. Surfacing a stale one re-arms the "Free limit reached" card on a
        // session that is answering him fine right now - the owner, 24 Nov: "ang ayaw ko is
        // nakikita yung free limit reached dialog while meron pa naman". Measured 14:18-14:28:
        // zero live wall frames, yet the card was on screen for the whole window.
        val age = frame.created?.let { System.currentTimeMillis() - it } ?: 0L
        if (age > WALL_FRESH_MS) {
            android.util.Log.i(
                "ZAPP",
                "wall frame ${age / 1000}s old (${wall.type}) - replay, not surfaced",
            )
            return
        }
        quotaWall.value = wall
        sending.value = false
        // restore the refused prompt into its own tab (their composer restore)
        val sid = frame.sessionID
        if (sid != null) {
            val lastUser = messages.value.lastOrNull { it.info.role == "user" }
            val text = lastUser?.parts?.firstOrNull { it.type == "text" }?.text
            if (!text.isNullOrBlank()) restorePrompt.value = sid to text
        }
        android.util.Log.i(
            "ZAPP",
            "wall handled: type=${wall.type} scoped=${wall.addressScoped} " +
                "sid=${sid ?: "-"} restore=${restorePrompt.value != null}",
        )
        // The event stream replays durable frames after every reconnect, and the debug mirror
        // delivers the same frame twice. Answering a replay costs a whole WARP identity and
        // buys nothing: measured 01:37, four identities in ten seconds on one stored error.
        if (!com.zapp.net.guard.WallLedger.claim(
                ctx,
                sid,
                frame.assistantMessageID,
                wall.type,
            )
        ) {
            // keep the card and the restored prompt, skip the rotation entirely
            statusFail(wall.message)
            return
        }
        wallSeq += 1
        if (!wall.addressScoped && wall !is com.zapp.net.guard.QuotaWall.FreeLimit) {
            // a plan or a workspace budget does not move with the tunnel
            statusFail(wall.message)
            return
        }
        // A retry is already running for this wall: it owns the resend, so do not start a
        // second one (that raced the first and burned two /64s per wall).
        if (quotaRetryJob?.isActive == true) return
        val ctxSid = sid ?: return
        // The retry job holds the prompt itself. Leaving it in `restorePrompt` would make the
        // composer consume it and clear it (ChatScreen.kt:4010), so the next attempt would
        // find nothing to send; it is handed back to the composer only if every retry fails.
        val refusedText = restorePrompt.value?.takeIf { it.first == ctxSid }?.second.orEmpty()
        restorePrompt.value = null
        // AUTO-SEND REMOVED (the owner, 2026-11-20): "AND ALSO REMOVE AUTO SEND".
        //
        // A wall is never resent on his behalf any more. The address is rotated once so the
        // next send has a fresh one, his text goes back into the composer, and HE decides when
        // to press send. The sixteen-attempt resend loop that used to live here, and the
        // guard's `[auto-resume] sent 'continue'` sender, were both producing prompts nobody
        // typed - the exact thing this removes.
        if (refusedText.isNotBlank()) restorePrompt.value = ctxSid to refusedText
        quotaWall.value = wall
        // The status line must match what the guard is actually about to do.
        //
        // Measured today 12:48:17, `provider.free-limit` stated a 1197 min window, and
        // `WallGate.rotateOnce` answered `free limit states a 1197 min window - that is the
        // shared budget, not the address -> not moving the identity`. The old line below said
        // "rotating one address" anyway, so the app promised a hop it had just refused to make.
        // A long stated window is the provider's shared bucket; only its clock moves it.
        val sharedWall = com.zapp.net.guard.WallGate.isSharedBudget(wall)
        val statedWindow = (wall as? com.zapp.net.guard.QuotaWall.FreeLimit)?.retryAfterMs ?: 0L
        statusFail(
            if (sharedWall) {
                "Free limit reached - provider budget resets at " +
                    com.zapp.net.guard.QuotaWall.localTime(System.currentTimeMillis() + statedWindow) +
                    "; your prompt is back in the composer"
            } else {
                "Free limit reached - rotating one address; your prompt is back in the composer"
            },
        )
        quotaRetryJob = viewModelScope.launch {
            val reg = com.zapp.net.guard.WallGate.rotateOnce(ctx, wall, "wall:${wall.type}")
            if (activeSession.value?.id != ctxSid) return@launch
            if (reg != null) {
                com.zapp.net.guard.ResumeQueue.markProven(ctx)
                statusOk("New address ready - send when you want")
            } else if (sharedWall) {
                statusFail(
                    "Free limit reached - nothing on this phone moves the provider's clock. " +
                        "Your prompt is back in the composer.",
                )
            } else {
                val until = com.zapp.net.guard.WallPause.pausedUntilMs(ctx)
                statusFail(
                    if (until > System.currentTimeMillis())
                        "Rotation paused until ${com.zapp.net.guard.WallPause.utcHhmm(until)} - sending is not blocked"
                    else "No fresh /64 - waiting for the address pool",
                )
            }
        }
    }

    /**
     * Context above which the free tier refuses a request outright.
     *
     * Measured, not guessed: 5 888 tokens passed 35 times in a row on one address while
     * 108 416 tokens was refused on the same address one minute later. 48 000 sits under
     * the smallest refused reading we have and far above the largest accepted one, so a
     * session that crosses it gets told the truth before it loses a turn.
     */
    private val CONTEXT_GATE_TOKENS = 48_000L

    /**
     * A wall older than this is a replay, not a refusal that just happened.
     *
     * Live refusals land in the same breath as the failed step; two minutes is far beyond the
     * wire's own latency and far below the shortest budget window ever measured (87 min).
     */
    private val WALL_FRESH_MS = 120_000L

    /**
     * Context size of the newest assistant turn of the session that just walled.
     *
     * The number that matters is not `input` (small, because almost everything is served
     * from cache) but `input + cache.read`: measured 2026-11-19 22:13, `in=334` but
     * `cacheRead=105 984` on one turn. That total is what the free tier measures, so it
     * decides whether the request is answered at all.
     */
    private suspend fun lastContextTokens(sid: String): Long {
        val list = runCatching { client?.messages(sid, 6) }.getOrNull().orEmpty()
        return contextOf(list)
    }

    /**
     * Tokens the server sends for the next turn of this transcript.
     *
     * `input` alone understates it: almost everything is a cache read (measured `in=334`
     * with `cacheRead=105 984`), and the free tier judges the whole request.
     */
    private fun contextOf(list: List<MessageWithParts>): Long {
        val last = list.lastOrNull { it.info.role == "assistant" && it.info.tokens != null }
            ?: return 0L
        val t = last.info.tokens ?: return 0L
        return t.total + (t.cache?.read ?: 0L) + (t.cache?.write ?: 0L)
    }

    /** Switch the chip, push it onto the session, then re-send the same prompt once. */
    private suspend fun resendAs(        session: Session,
        text: String,
        providerID: String,
        modelID: String,
        note: String,
    ) {
        val c = client ?: return
        runCatching {
            val label = models.value.firstOrNull {
                it.providerID == providerID && it.modelID == modelID
            }?.name ?: modelID
            val variant = models.value.firstOrNull { it.modelID == modelID }?.variants
                ?.firstOrNull { it.id == "max" }?.id.orEmpty()
            selectedModel.value = SelectedModel(providerID, modelID, variant, label)
            com.zapp.net.settings.saveModel(ctx, providerID, modelID, variant, label)
            applySessionModel(session)
            if (text.isNotBlank()) {
                c.prompt(session.id, text, providerID, modelID, emptyList(), null)
            }
            statusOk(note)
        }.onFailure { statusFail("Resend failed: ${it.message}") }
    }

    // ---- live deltas ----
    // Their client appends `session.text.delta` / `session.reasoning.delta` straight into the
    // part and never refetches while a reply streams. We do the same now: the old 180 ms full
    // reload rebuilt every row (and every image) several times a second, which is exactly the
    // flicker and the "goes back up" he kept seeing.
    /**
     * Event ids already applied, so a replayed frame after a reconnect cannot write twice.
     *
     * The server's `ordinal` is the **part index**, not a delta counter. Measured 23 Nov on
     * this phone, one reply carried 11 `session.reasoning.delta` frames and 2
     * `session.text.delta` frames, and every one of them said `"ordinal":0`. Deduping on
     * `ord <= last` therefore discarded every delta after the first - the transcript grew one
     * chunk per block, which is `turn cadence: 1 deltas -> 1 flushes in 81522ms`. The event
     * `id` (`evt_...`) is unique per frame and repeats only on a replay, so it is the only
     * correct key.
     */
    private val seenEventIds = object : LinkedHashMap<String, Boolean>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) =
            size > 1024
    }

    /** Text that arrived for a message row the transcript has not fetched yet. Dropping
     *  these was the "reply lands in one lump at the end" bug: the first deltas always
     *  arrive before the assistant row exists locally, so they were discarded and only
     *  the terminal `session.idle` reload ever painted the answer. Keyed "mid|kind". */
    private val pendingDeltas = LinkedHashMap<String, StringBuilder>()
    private var refetchAttempts = 0

    /** Their `findLast` (`data.ts:562-574`): a text delta targets the last `text` part, a
     *  reasoning delta the last `reasoning` part that is **not completed**. Returning the index
     *  instead of assuming the tail is the whole fix for the duplicate thought row - the tail
     *  is a tool row most of the time, because the server ships one message per step. */
    private fun openPartIndex(parts: List<com.zapp.net.api.Part>, kind: String): Int? {
        for (i in parts.indices.reversed()) {
            val p = parts[i]
            if (p.type != kind) continue
            if (kind == "reasoning" && p.time?.completed != null) continue
            return i
        }
        return null
    }

    /** `session.text.started` / `.ended` and their reasoning twins.
     *
     *  `started` pushes an empty part, `ended` writes the authoritative full text and stamps
     *  `time.completed`. Their `editAssistant` drops the event when the row is not loaded
     *  (`data.ts:546-552`), and so does this - the first delta still creates the placeholder,
     *  so nothing is lost, it is only attached a moment later. */
    private fun applyPartBoundary(mid: String, kind: String, phase: String, full: String?): Boolean {
        val cur = messages.value
        val i = cur.indexOfFirst { it.info.id == mid }
        if (i < 0) return true
        val m = cur[i]
        val parts = m.parts.toMutableList()
        val now = System.currentTimeMillis()
        if (phase == "started") {
            parts.add(
                com.zapp.net.api.Part(
                    id = "$mid:$kind:${parts.count { it.type == kind }}",
                    type = kind,
                    text = "",
                    time = com.zapp.net.api.PartTime(created = now),
                ),
            )
        } else {
            val at = openPartIndex(parts, kind) ?: return true
            val p = parts[at]
            parts[at] = p.copy(
                text = full ?: p.text,
                time = (p.time ?: com.zapp.net.api.PartTime()).copy(
                    created = p.time?.created ?: now,
                    completed = now,
                ),
            )
        }
        messages.value = cur.toMutableList().also { it[i] = m.copy(parts = parts) }
        return true
    }

    /**
     * `session.tool.*` - PORT of `client/src/solid/data.ts:2226-2325`.
     *
     * Desktop patches the one tool part the frame names (`editTool`, `data.ts:1107`), so a row
     * exists the moment the model starts writing its arguments and fills in place. ZAPP only
     * handled text/reasoning and answered every tool frame with a 120 ms debounced
     * `refreshOneMessage`, so the entire tool lifecycle arrived one HTTP round-trip late and a
     * write/read/shell row sat on `streaming` with an empty input until the next full read.
     * Measured 24 Nov: `shellrow ... state=PartState(status=streaming, title=null, input="",
     * content=null, metadata=null, error=null)` repeated for the life of the turn.
     *
     * The guards are theirs verbatim: `delta`/`ended` only touch a `streaming` tool, `progress`
     * only a `running` one, `success` only a `running` one, `failed` only `streaming`/`running`,
     * and `failed` rewrites a still-streamed *string* input to `{}` because the raw fragment is
     * not a usable object.
     */
    private fun applyToolEvent(raw: String): Boolean {
        val root = try {
            kotlinx.serialization.json.Json.parseToJsonElement(raw)
                .let { it as? kotlinx.serialization.json.JsonObject }
        } catch (e: Exception) {
            null
        } ?: return false
        val type = (root["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: return false
        when (type) {
            "session.tool.input.started", "session.tool.input.delta",
            "session.tool.input.ended", "session.tool.called", "session.tool.progress",
            "session.tool.success", "session.tool.failed",
            -> Unit
            else -> return false
        }
        val d = root["data"] as? kotlinx.serialization.json.JsonObject ?: return false
        fun str(k: String) = (d[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (str("sessionID") != activeSession.value?.id) return false
        val mid = str("assistantMessageID") ?: return false
        val tid = str("id") ?: return false
        val at = (root["created"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toLongOrNull() ?: System.currentTimeMillis()

        fun meta(): com.zapp.net.api.PartMeta? {
            val o = d["metadata"] as? kotlinx.serialization.json.JsonObject ?: return null
            fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
            fun n(k: String) = s(k)?.toIntOrNull()
            fun b(k: String) = s(k)?.toBooleanStrictOrNull()
            val files = (o["files"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { f ->
                val fo = f as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun fs(k: String) = (fo[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
                com.zapp.net.api.PartFile(
                    file = fs("file"), patch = fs("patch"), status = fs("status"),
                    additions = fs("additions")?.toIntOrNull() ?: 0,
                    deletions = fs("deletions")?.toIntOrNull() ?: 0,
                )
            }
            val todos = (o["todos"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { t ->
                val to = t as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun ts(k: String) = (to[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
                com.zapp.net.api.TodoItem(
                    content = ts("content") ?: ts("text") ?: return@mapNotNull null,
                    status = ts("status") ?: "pending",
                    priority = ts("priority"),
                )
            }
            return com.zapp.net.api.PartMeta(
                files = files, exit = n("exit"), command = s("command"),
                timeout = b("timeout"), interrupted = b("interrupted"),
                matches = n("matches"), count = n("count"), todos = todos,
                output = o["output"],
            )
        }

        fun content(): List<com.zapp.net.api.PartContent>? {
            val arr = d["content"] as? kotlinx.serialization.json.JsonArray ?: return null
            return arr.mapNotNull { o ->
                val ob = o as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun s(k: String) = (ob[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
                com.zapp.net.api.PartContent(
                    type = s("type"), text = s("text"), uri = s("uri"), mime = s("mime"),
                )
            }
        }

        // their `editAssistant` drops a frame whose row is not loaded. Ours is created by the
        // first text delta, so only a tool-first turn needs the row made here - and only
        // `started` may make it, since it is the frame that begins the tool.
        val cur0 = messages.value
        if (cur0.none { it.info.id == mid }) {
            if (type != "session.tool.input.started") return false
            messages.value = cur0 + MessageWithParts(
                info = com.zapp.net.api.MessageInfo(
                    id = mid,
                    role = "assistant",
                    type = "assistant",
                    sessionId = activeSession.value?.id,
                    time = com.zapp.net.api.MsgTime(created = at),
                ),
                parts = emptyList(),
            )
        }

        val cur = messages.value
        val mi = cur.indexOfFirst { it.info.id == mid }
        if (mi < 0) return false
        val m = cur[mi]
        val parts = m.parts.toMutableList()
        var pi = parts.indexOfLast { it.type == "tool" && it.id == tid }
        val p0 = parts.getOrNull(pi)
        val st = p0?.state
        // every branch except `started` edits a row that already exists (`editTool` finds it);
        // `started` is the one frame that adds it
        if (type != "session.tool.input.started" && p0 == null) return true
        val row = p0 ?: return true

        when (type) {
            "session.tool.input.started" -> {
                // durable, so a reconnect replays it and a fetch may already have the row
                if (pi >= 0) return true
                pi = parts.size
                parts.add(
                    com.zapp.net.api.Part(
                        id = tid,
                        type = "tool",
                        name = str("name"),
                        time = com.zapp.net.api.PartTime(created = at),
                        state = com.zapp.net.api.PartState(
                            status = "streaming",
                            input = kotlinx.serialization.json.JsonPrimitive(""),
                        ),
                    ),
                )
            }
            "session.tool.input.delta" -> {
                if (st?.status != "streaming") return true
                val have = (st.input as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                parts[pi] = row.copy(
                    state = st.copy(
                        input = kotlinx.serialization.json.JsonPrimitive(have + (str("delta") ?: "")),
                    ),
                )
            }
            "session.tool.input.ended" -> {
                if (st?.status != "streaming") return true
                parts[pi] = row.copy(
                    state = st.copy(input = kotlinx.serialization.json.JsonPrimitive(str("text") ?: "")),
                )
            }
            "session.tool.called" -> {
                parts[pi] = row.copy(
                    time = (row.time ?: com.zapp.net.api.PartTime()).copy(ran = at),
                    state = com.zapp.net.api.PartState(
                        status = "running",
                        input = d["input"],
                        metadata = meta() ?: com.zapp.net.api.PartMeta(),
                    ),
                )
            }
            "session.tool.progress" -> {
                if (st?.status != "running") return true
                val md = meta() ?: return true
                parts[pi] = row.copy(state = st.copy(metadata = md))
            }
            "session.tool.success" -> {
                if (st?.status != "running") return true
                parts[pi] = row.copy(
                    time = (row.time ?: com.zapp.net.api.PartTime()).copy(completed = at),
                    state = com.zapp.net.api.PartState(
                        status = "completed",
                        input = st.input,
                        metadata = meta(),
                        content = content(),
                    ),
                )
            }
            "session.tool.failed" -> {
                if (st?.status != "streaming" && st?.status != "running") return true
                val err = (d["error"] as? kotlinx.serialization.json.JsonObject)?.let { eo ->
                    fun es(k: String) = (eo[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    com.zapp.net.api.WireError(type = es("type"), message = es("message"))
                } ?: (d["error"] as? kotlinx.serialization.json.JsonPrimitive)?.let {
                    com.zapp.net.api.WireError(message = it.content)
                }
                parts[pi] = row.copy(
                    time = (row.time ?: com.zapp.net.api.PartTime()).copy(completed = at),
                    state = com.zapp.net.api.PartState(
                        status = "error",
                        error = err,
                        input = when (st.input) {
                            is kotlinx.serialization.json.JsonPrimitive -> kotlinx.serialization.json.JsonObject(emptyMap())
                            else -> st.input
                        },
                        metadata = meta(),
                        content = content(),
                    ),
                )
            }
        }
        messages.value = cur.toMutableList().also { it[mi] = m.copy(parts = parts) }
        liveMsgId.value = mid
        // Evidence: one line per state change, never per delta. This is what tells us on device
        // whether the row filled from the stream or still needed a read.
        if (type != "session.tool.input.delta") {
            android.util.Log.e(
                "ZAPP",
                "toolrow $type id=${tid.take(14)} " +
                    "name=${parts.getOrNull(pi)?.name ?: "-"} " +
                    "status=${parts.getOrNull(pi)?.state?.status ?: "-"}",
            )
        }
        return true
    }

    private fun flushPendingDeltas() {
        if (pendingDeltas.isEmpty()) return
        var cur = messages.value
        if (cur.isEmpty()) return
        val done = ArrayList<String>(pendingDeltas.size)
        for ((key, sb) in pendingDeltas) {
            val mid = key.substringBefore('|')
            val kind = key.substringAfter('|')
            val i = cur.indexOfFirst { it.info.id == mid }
            if (i < 0) continue
            val m = cur[i]
            val parts = m.parts.toMutableList()
            val at = openPartIndex(parts, kind)
            if (at != null) {
                val tail = parts[at]
                // The server's copy already ends with this text when a fetch landed after the
                // delta was persisted, so appending would double it. This guard is also what
                // lets `refreshOneMessage` leave queued deltas alone: the queue is applied on
                // top of whatever the fetch returned, and only the part the fetch already
                // contains is skipped.
                if (tail.text.orEmpty().endsWith(sb.toString())) {
                    done.add(key)
                    continue
                }
                parts[at] = tail.copy(text = tail.text.orEmpty() + sb)
            } else {
                parts.add(
                    com.zapp.net.api.Part(
                        id = "$mid:$kind",
                        type = kind,
                        text = sb.toString(),
                        time = com.zapp.net.api.PartTime(created = System.currentTimeMillis()),
                    ),
                )
            }
            cur = cur.toMutableList().also { it[i] = m.copy(parts = parts) }
            done.add(key)
        }
        if (done.isNotEmpty()) {
            messages.value = cur
            done.forEach { pendingDeltas.remove(it) }
            refetchAttempts = 0
        }
    }

    /** The assistant row is missing locally: fetch just that one message
     *  (`GET /api/session/:id/message/:mid`) and append it, instead of re-reading the whole
     *  transcript. Falls back to a full load once the single fetch gives nothing. */
    private fun requestEarlyFetch(mid: String) {
        if (refetchAttempts > 6) return
        val s = activeSession.value ?: return
        viewModelScope.launch {
            delay(60)
            refetchAttempts++
            val c = client
            val one = runCatching { c?.message(s.id, mid) }.getOrNull()
            if (one != null) {
                val cur = messages.value
                val i = cur.indexOfFirst { it.info.id == mid }
                // merge, never shrink: the placeholder is already drawing deltas and the
                // server copy can be a moment behind them
                val merged = mergeLive(listOfNotNull(cur.getOrNull(i)), listOf(one))
                    .firstOrNull() ?: one
                messages.value = if (i >= 0) cur.toMutableList().also { it[i] = merged }
                else cur + merged
                // Same rule as [refreshOneMessage]: the fetched copy can lag the stream, so the
                // queue is applied on top instead of thrown away.
                flushPendingDeltas()
                return@launch
            }
            if (!loadingMessages && messages.value.none { it.info.id == mid }) loadMessages(s)
        }
    }

    /** The assistant message an event is about, or null when the frame does not say. */
    private fun affectedMessageId(raw: String): String? = runCatching {
        val d = org.json.JSONObject(raw).optJSONObject("data") ?: return@runCatching null
        listOf("assistantMessageID", "messageID", "messageId")
            .firstNotNullOfOrNull { k -> d.optString(k).takeIf { it.startsWith("msg") } }
    }.getOrNull()

    /**
     * Splice one freshly fetched message into the transcript.
     *
     * A `session.tool.*` or `session.step.*` frame changes exactly one assistant message, but
     * the handler answered it by reloading the **whole** transcript - 75 rows and 853 KB on the
     * chat this phone is holding. During a tool-heavy turn those frames arrive several times a
     * second, so every reload competed with the text stream and the reply looked late. the owner,
     * 22 Nov: "OUR AI REPLY ITS DELAY NOT THE SAME AS PC". Desktop patches its store per
     * message; this is the same decision with one HTTP call instead of a transcript read.
     *
     * Pending deltas for that message are dropped rather than flushed: the server's copy
     * already contains them, and appending on top would double the text.
     */
    private suspend fun refreshOneMessage(mid: String) {
        val s = activeSession.value ?: return
        val c = client ?: return
        val one = runCatching { c.message(s.id, mid) }.getOrNull() ?: return
        val cur = messages.value
        val i = cur.indexOfFirst { it.info.id == mid }
        // Same monotonic rule as [mergeLive]: a single-message fetch can also land behind the
        // deltas already drawn, so it must not shorten what is on screen.
        val merged = mergeLive(listOfNotNull(cur.getOrNull(i)), listOf(one)).firstOrNull() ?: one
        messages.value = if (i >= 0) cur.toMutableList().also { it[i] = merged } else cur + merged
        // Queued deltas are NOT dropped here. Measured 22 Nov 12:03, the server's
        // single-message copy lagged the stream on every fetch, so dropping the queue threw
        // away text the user had already watched arrive. [flushPendingDeltas] applies the queue
        // on top and skips only what the fetched copy already ends with.
        flushPendingDeltas()
    }

    /** true when the transcript grew locally, i.e. no reload is needed for this event. */
    private fun applyTextDelta(raw: String): Boolean {
        val root = try {
            kotlinx.serialization.json.Json.parseToJsonElement(raw)
                .let { it as? kotlinx.serialization.json.JsonObject }
        } catch (e: Exception) {
            null
        } ?: return false
        val type = (root["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: return false
        // Their six streaming events, verbatim (`client/src/solid/data.ts:1087-1187`):
        //
        //   session.text.started       -> content.push({ type:"text", text:"" })
        //   session.text.delta         -> text.text += delta
        //   session.text.ended         -> text.text = event.data.text
        //   session.reasoning.started  -> content.push({ type:"reasoning", text:"", time:{created} })
        //   session.reasoning.delta    -> reasoning.text += delta
        //   session.reasoning.ended    -> reasoning.text = text; time.completed = created
        //
        // Only `delta` was handled here. That is why the answer arrived in lumps and why the
        // transcript grew a second "Thinking" row: without `started` a reasoning chunk that
        // landed after a tool call had nothing to attach to and opened a brand-new part, and
        // without `ended` no part was ever marked closed, so the next chunk could not tell the
        // open one from a finished one. the owner, 22 Nov: "still i dont see her text 1 by 1".
        val kind: String
        val phase: String
        when (type) {
            "session.text.started" -> { kind = "text"; phase = "started" }
            "session.text.delta" -> { kind = "text"; phase = "delta" }
            "session.text.ended" -> { kind = "text"; phase = "ended" }
            "session.reasoning.started" -> { kind = "reasoning"; phase = "started" }
            "session.reasoning.delta" -> { kind = "reasoning"; phase = "delta" }
            "session.reasoning.ended" -> { kind = "reasoning"; phase = "ended" }
            else -> return false
        }
        val d = root["data"] as? kotlinx.serialization.json.JsonObject ?: return false
        fun str(k: String) = (d[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (str("sessionID") != activeSession.value?.id) return false
        val mid = str("assistantMessageID") ?: return false

        // A boundary rewrites the part list, so it has to land after everything queued for
        // that message - otherwise the queued text would append to the part the boundary just
        // closed. Deliberately not batched: there are two of them per block, not per token.
        if (phase != "delta") {
            flushPendingDeltas()
            liveBoundaries++
            return applyPartBoundary(mid, kind, phase, str("text"))
        }

        val delta = str("delta") ?: return false

        // SSE can repeat after a reconnect: never apply the same frame twice. The event id is
        // the only safe key. `ordinal` cannot be one - it is the part index, and the server
        // stamps 0 on every delta of a part, which is what silently swallowed the whole stream.
        val eid = (root["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (eid != null && seenEventIds.put(eid, true) != null) return true

        // A token is proof the provider accepted this prompt, so any free window that a past
        // refusal persisted is history. Without this the card outlived the refusal by hours
        // while he was chatting normally - the owner, 24 Nov. A genuinely refused send re-arms it
        // through `onQuotaWall` within the same second.
        if (com.zapp.net.settings.GuardPrefs.freeLimitUntil(ctx) > 0L) {
            runCatching { com.zapp.net.settings.GuardPrefs.setFreeLimitUntil(ctx, 0L) }
            if (quotaWall.value is com.zapp.net.guard.QuotaWall.FreeLimit) quotaWall.value = null
            android.util.Log.i("ZAPP", "live delta - free window cleared")
        }

        // BATCH, do not rebuild per token.
        //
        // Every delta used to do `messages.value = cur.toMutableList()...` - a full copy of the
        // message list plus a recomposition of the entire transcript. On the 75-row, 853 KB
        // chat this phone is holding, that made the UI fall behind the stream, so the reply
        // *looked* delayed: the owner, "OUR AI REPLY ITS DELAY NOT THE SAME AS PC". Desktop keeps a
        // mutable store with per-part reactivity; one UI update per 50 ms is the cheap
        // equivalent here - at most 20 recompositions a second however fast tokens arrive, and
        // every update carries all the text that accumulated since the last one.
        val key = "$mid|$kind"
        pendingDeltas.getOrPut(key) { StringBuilder() }.append(delta)
        liveDeltas++
        // which message the wire actually fed - the reveal keys on this, not on `sending`
        liveMsgId.value = mid
        // Client-side clock for the dead-stream watchdog. The server's `time.updated` is NOT a
        // streaming heartbeat - it does not advance while a text part is being appended - so a
        // rule built on it cuts healthy long replies. This stamp moves every time a delta
        // actually reaches the phone, which is the only honest definition of "stream alive".
        lastDeltaAt = System.currentTimeMillis()
        if (liveFirstDeltaMs == 0L) liveFirstDeltaMs = System.currentTimeMillis()
        if (messages.value.none { it.info.id == mid }) {
            // Desktop's store creates the assistant row from the first part event, so the
            // answer starts drawing on the first token. This app used to wait for
            // `GET /message/:id` to confirm the row exists before any delta could attach, and
            // on a TUN that round trip is seconds: the first stretch of every reply sat
            // buffered and then landed in one lump. the owner, 22 Nov: "ITS STOP AND THEN SEND".
            // The placeholder carries the real id, so the server's copy merges straight into it.
            messages.value = messages.value + MessageWithParts(
                info = com.zapp.net.api.MessageInfo(
                    id = mid,
                    role = "assistant",
                    type = "assistant",
                    sessionId = activeSession.value?.id,
                    time = com.zapp.net.api.MsgTime(created = System.currentTimeMillis()),
                ),
                parts = emptyList(),
            )
            requestEarlyFetch(mid)
        }
        scheduleLiveFlush()
        return true
    }

    /** Coalesced flush of streamed text; see [applyTextDelta]. */
    private var liveFlush: kotlinx.coroutines.Job? = null

    /**
     * Streaming cadence, so the batching is measurable instead of assumed.
     *
     * the owner, 22 Nov: "OUR AI REPLY ITS DELAY NOT THE SAME AS PC". Without these counters there
     * is no way to tell a slow provider from a UI that rebuilds the transcript on every token:
     * both look like text arriving late. `deltas` is what the server sent, `flushes` is what
     * the screen actually drew, and their ratio is the batching factor.
     */
    private var liveDeltas = 0
    private var liveFlushes = 0
    private var liveFirstDeltaMs = 0L
    /** `started` / `ended` frames seen this window. Four per step (two kinds x two ends). */
    private var liveBoundaries = 0
    /** When a delta last reached this phone. Client-side, never a server stamp. */
    private var lastDeltaAt = 0L

    private fun scheduleLiveFlush() {
        if (liveFlush?.isActive == true) return
        liveFlush = viewModelScope.launch {
            kotlinx.coroutines.delay(50)
            flushPendingDeltas()
            liveFlushes++
            // A rolling window, not a running total.
            //
            // This used to log every 20 flushes against `liveFirstDeltaMs`, which was set once
            // and never reset, so the line read `40 deltas -> 40 flushes in 453045ms` - seven
            // minutes of app lifetime reported as if it were one reply. That number said
            // "0/s" and made a healthy stream look dead. The window closes every 30 s so the
            // rate describes the last half minute and nothing else.
            val now = System.currentTimeMillis()
            if (liveFirstDeltaMs == 0L) liveFirstDeltaMs = now
            val span = now - liveFirstDeltaMs
            if (span >= 30_000L) {
                android.util.Log.e(
                    "ZAPP",
                    "stream cadence: $liveDeltas deltas -> $liveFlushes flushes in ${span}ms" +
                        " (${liveDeltas * 1000L / span}/s in, " +
                        "${liveFlushes * 1000L / span}/s drawn, $liveBoundaries boundaries)",
                )
                liveDeltas = 0
                liveFlushes = 0
                liveBoundaries = 0
                liveFirstDeltaMs = now
            }
        }
    }

    /** Draft promotions in flight (`promotion-machine.ts` entries, keyed by draft tab id). */
    private val promoting = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * PC's shell mode: `prompt.mode.shell` = "Shell", toggled by `command.prompt.mode.toggle`.
     *
     * `POST /api/session/{id}/shell` answers **204 No Content**, so there is no reply to place
     * here - the command's output comes back through `/api/event` and the normal message
     * refresh, the same path a tool part takes. Same contract as [send]: return `false` when
     * nothing left the app, so the composer keeps what he typed instead of clearing it.
     */
    fun sendShell(command: String): Boolean {
        if (command.isBlank()) return false
        val sid = activeSession.value?.id ?: run {
            statusFail("Shell needs a session - open or create one first.")
            return false
        }
        val c = client ?: run {
            statusFail("Engine not connected - your command is still in the box.")
            return false
        }
        viewModelScope.launch {
            runCatching { c.shell(sid, command.trim()) }
                .onFailure { e ->
                    android.util.Log.e("ZAPP", "shell FAIL: ${e.message}")
                    statusFail("Shell failed: ${e.message?.take(160) ?: e.javaClass.simpleName}")
                }
        }
        return true
    }

    fun send(
        text: String,
        files: List<com.zapp.net.api.OutAttachment> = emptyList(),
        queue: Boolean = false,
        skills: List<com.zapp.net.api.SkillPick> = emptyList(),
    ): Boolean {
        val sel = selectedModel.value
        // a prompt with attachments but no words is valid: the desktop allows it too
        if (text.isBlank() && files.isEmpty()) return false
        // Shrink a session on demand. Same route the UI button uses, reachable by typing, so a
        // fat chat can be reduced without hunting for a control.
        if (files.isEmpty() && text.trim().lowercase() in setOf("compact", "/compact")) {
            compactNow()
            // handled in-app, so the composer may clear
            return true
        }
        // Commands the app performs itself. Checked before the client and before anything
        // network happens, so a walled model - or a server that is not running at all -
        // cannot stop him from rotating the tunnel or reading its status.
        // Measured 04:15: four "rotate the tunnel" sends, four quota walls, no rotation.
        if (files.isEmpty() && com.zapp.net.local.LocalCommands.preview(text) != null) {
            val sid = activeSession.value?.id
            viewModelScope.launch {
                val reply = com.zapp.net.local.LocalCommands.handle(ctx, text) ?: return@launch
                val now = System.currentTimeMillis()
                val mine = com.zapp.net.api.MessageWithParts(
                    info = com.zapp.net.api.MessageInfo(
                        id = "local_u_$now",
                        role = "user",
                        type = "user",
                        sessionId = sid,
                        time = com.zapp.net.api.MsgTime(created = now),
                    ),
                    parts = listOf(com.zapp.net.api.Part(type = "text", text = text)),
                )
                val answer = com.zapp.net.api.MessageWithParts(
                    info = com.zapp.net.api.MessageInfo(
                        id = "local_a_$now",
                        role = "assistant",
                        type = "assistant",
                        sessionId = sid,
                        time = com.zapp.net.api.MsgTime(created = now),
                    ),
                    parts = listOf(
                        com.zapp.net.api.Part(
                            type = "text",
                            text = if (reply.body.isBlank()) reply.title
                            else "${reply.title}\n\n${reply.body}",
                        ),
                    ),
                )
                messages.value = messages.value + mine + answer
                quotaWall.value = null
                statusOk("")
                android.util.Log.e("ZAPP", "local: ${reply.title}")
            }
            // a local command is handled in-app: the composer may clear
            return true
        }
        // No client means the engine link is down. This used to `return` silently while the
        // composer cleared his draft anyway, so the prompt vanished with no message and no
        // error - which is exactly what he described as "hindi ako maka send". Refuse, say
        // why, and keep the text where he can send it again.
        val c = client ?: run {
            statusFail("Engine not connected - your message is still in the box.")
            return false
        }
        // Send it. A big chat is tried, not refused in advance: measured 2026-11-20 00:05,
        // a 7.4k chat that came back `provider.free-limit` at 23:36 answered the same prompt
        // at 23:40, so a wall is a wait, not a verdict - and blocking the send here would
        // turn a recoverable turn into a card. The size verdict is raised by the retry loop
        // only after repeated refusals (see onQuotaWall).
        val ctxPair = contextOfSession.value
        val gateSid = activeSession.value?.id
        if (gateSid != null && ctxPair != null && ctxPair.first == gateSid) {
            lastContextSeen = ctxPair.second
        }
        // he is sending again: whatever the last wall said is history
        quotaWall.value = null
        // ...and so is the window it persisted. `FreeLimitCard` reads that pref, so leaving it
        // armed is exactly the card that sat on screen while he still had quota (the owner, 24 Nov:
        // "ang ayaw ko is nakikita yung free limit reached dialog while meron pa naman").
        // A refused send sets it again through `onQuotaWall`/`noteFreeBudget`.
        runCatching { com.zapp.net.settings.GuardPrefs.setFreeLimitUntil(ctx, 0L) }
        quotaRetryJob?.cancel()
        quotaRetryJob = null
        contextTooBig.value = null
        // their `reset()` after a send: the draft becomes DEFAULT_PROMPT, i.e. nothing stored.
        // The scope is the focused draft tab's id (`tabs.tsx:1095`), not a shared "new" key.
        val sendingDraftId = activeSession.value?.id ?: activeDraft.value
        // `promotion-machine.ts:135-161`: one promotion per draft key. A second `prepare` for the
        // same key disposes the previous pending attempt instead of minting another session - our
        // unguarded path created three empty sessions from one burst of taps (14:35:55).
        val promotingDraft = if (activeSession.value == null) sendingDraftId else null
        if (promotingDraft != null && !promoting.add(promotingDraft)) return false
        dropDraft(sendingDraftId ?: "new")
        viewModelScope.launch {
            // Their `Config.Compaction.Percent` policy, which this app has never applied: the
            // port (`compact()`) existed, the trigger did not. Measured 04:31 from their own
            // session list - one turn in a 543k-input chat costs 32x a turn in a 4.4k one, and
            // the free bucket empties at that same rate. Compacting before the turn is the
            // difference between 46 turns and 2.
            autoCompactIfHeavy(c)
            // Desktop does not ask him to tap twice: `openNewSession` makes the session and
            // the composer's send continues into it. Creating one at STARTUP is what left a
            // phantom empty session on screen while his real conversation sat untouched.
            var session = activeSession.value
            if (session == null) {
                val created = runCatching { c.newSession() }.getOrNull()
                if (created == null) {
                    promotingDraft?.let { promoting.remove(it) }
                    statusFail("New session failed")
                    return@launch
                }
                sessions.value = listOf(created) + sessions.value.filterNot { it.id == created.id }
                listLoaded.value = true
                // `promotion-machine.ts`: the draft tab is *replaced* by the session tab.
                sendingDraftId?.let { if (isDraft(it)) { tabs.remove(it); dropDraft(it) } }
                applySessionModel(created)
                open(created)
                session = created
            }
            val sid = (session ?: return@launch).id
            val ses = session!!
            // Fail-closed, as their firewall kill-switch does: never let a prompt leave
            // through the carrier. A refusal from the carrier address looks exactly like a
            // quota wall and used to burn a /64 the provider never saw.
            if (!com.zapp.net.guard.TunnelGate.ensureCarrying(ctx)) {
                promotingDraft?.let { promoting.remove(it) }
                sending.value = false
                statusFail("Tunnel is down - not sending (it would leave through the carrier)")
                return@launch
            }
            // Their `DF()`: `delivery = working ? behavior : "steer"`. While idle the queue is
            // meaningless - a prompt sent as `queue` just sits in the inbox and never runs - so
            // an idle send is ALWAYS a plain prompt (no delivery field at all).
            val wasRunning = sending.value
            if (!queue) sending.value = true
            // his text is on screen before the request leaves the phone, like theirs
            val localId = addOptimistic(text, files)
            try {
                val delivery = when {
                    !wasRunning -> null
                    queue -> "queue"
                    else -> "steer"
                }
                if (sel != null) {
                    // The prompt body cannot carry a model on 1.19.6 (PromptInput = text/
                    // files/agents only), so the session must already own it. A session with
                    // no model falls back to the server default, which on this build is a
                    // deprecated one that answers HTTP 410 and renders as silence.
                    applySessionModel(ses)
                    c.prompt(
                        sid, text, sel.providerID, sel.modelID, files, delivery, skills = skills,
                    )
                } else {
                    c.prompt(sid, text, files = files, delivery = delivery, skills = skills)
                }
                loadMessages(ses)
                loadInbox(ses)
            } catch (e: Exception) {
                android.util.Log.e("ZAPP", "send FAIL: ${e.message}", e)
                statusFail("Send failed: ${e.message}")
                // The POST never landed, so this turn is not running. Without this the
                // composer stayed on "Working" forever after a transport failure
                // (measured on his screen: `getaddrinfo EAI_AGAIN opencode.ai`, then the
                // app looked busy while nothing was in flight).
                sending.value = false
                // the server never took it: remove the stand-in so the list is honest again
                dropOptimistic(localId)
                // app-side wall reaction: same detector as the guard service
                val engine = com.zapp.net.guard.RotationEngine(ctx)
                if (engine.isQuotaWall(e.message.orEmpty())) {
                    statusFail("Wall hit - rotating /64...")
                    val wallType = engine.wallOf(e.message.orEmpty())?.type ?: "send-fail"
                    val reg = com.zapp.net.guard.RotationEngine.hopNow(ctx, "wall:$wallType")
                    if (reg != null) {
                        // AUTO-SEND REMOVED (the owner, 2026-11-20): "AND ALSO REMOVE AUTO SEND".
                        // A fresh address is prepared; his own text goes back into the
                        // composer and he decides when it leaves. This path used to
                        // re-POST the prompt on its own.
                        if (text.isNotBlank()) restorePrompt.value = ses.id to text
                        statusOk("New address ready - prompt is back in the composer")
                    } else if (engine.isPaused()) {
                        statusFail("Rotation paused - see Guard for the reason")
                    }
                }
            } finally {
                // the POST returning only means the prompt was accepted; the agent keeps
                // working. `sending` stays true until the server says session.idle, so the
                // status line (Thinking / Running commands ï¿½) survives the whole turn.
                promotingDraft?.let { promoting.remove(it) }
                // The body is off the wire one way or another. Leaving a stale percentage
                // behind would show an upload that no longer exists.
                upload.value = null
            }
        }
        // accepted: the composer may clear itself. A refusal above returns false and keeps
        // his text and attachments exactly where they were.
        return true
    }

    fun stop() {
        val session = activeSession.value ?: return
        sending.value = false
        viewModelScope.launch { runCatching { client?.abort(session.id) } }
    }

    /** Bring the tunnel up and swap in a freshly registered /64 (guard "Hop now"). */
    fun hopTunnel() {
        android.util.Log.e("ZAPP", "hopTunnel: requested")
        viewModelScope.launch {
            try {
                com.zapp.net.guard.GuardState.setVpnRunning(true)
                val reg = com.zapp.net.guard.RotationEngine.hopNow(ctx, "manual")
                android.util.Log.e("ZAPP", "hopTunnel: result=${reg?.v6 ?: "null"}")
                if (reg != null) {
                    statusOk("Hopped to ${reg.v6.take(14)}...")
                } else {
                    statusFail("Hop failed - see Guard")
                }
            } catch (e: Exception) {
                android.util.Log.e("ZAPP", "hopTunnel threw", e)
                statusFail("Hop failed: ${e.message}")
            }
        }
    }

    /** Their `Move to background`: the server moves the blocking tools (shells, subagents)
        off the turn, so the session stays running without pinning the UI to it. */
    fun moveToBackground() {
        val session = activeSession.value ?: return
        viewModelScope.launch {
            try {
                client?.backgroundSession(session.id)
                statusOk("Moved to background")
                loadShells()
                loadMessages(session)
            } catch (e: Exception) {
                statusFail("Background failed: ${e.message}")
            }
        }
    }

    // ---- background shells (real /api/shell data, cursor-tailing like their client) ----
    fun loadShells() {
        viewModelScope.launch {
            runCatching { shells.value = client?.shells() ?: emptyList() }
        }
    }

    /** Their tail is incremental, so we remember where we stopped per shell. */
    private val shellCursors = HashMap<String, Long>()
    private val shellBuffers = HashMap<String, StringBuilder>()

    /** Their "run in background": POST /api/shell {command, cwd?, timeout?}. */
    fun runShell(command: String, cwd: String? = null) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        viewModelScope.launch {
            try {
                val s = client?.runShell(cmd, cwd) ?: return@launch
                shells.value = listOf(s) + shells.value.filterNot { it.id == s.id }
                shellBuffers[s.id] = StringBuilder()
                shellCursors[s.id] = 0L
                loadShells()
            } catch (e: Exception) {
                statusFail("Shell failed: ${e.message}")
            }
        }
    }

    /** Their "Move to background": POST /api/session/:id/shell {id?, command}. */
    fun detachShell(messageId: String?, command: String) {
        val session = activeSession.value ?: return
        viewModelScope.launch {
            try {
                val s = client?.detachShell(session.id, messageId, command) ?: return@launch
                shells.value = listOf(s) + shells.value.filterNot { it.id == s.id }
                shellBuffers[s.id] = StringBuilder()
                shellCursors[s.id] = 0L
                loadShells()
                loadMessages(session)
            } catch (e: Exception) {
                statusFail("Background failed: ${e.message}")
            }
        }
    }

    /** Rebuilt from the cursor so the panel never re-reads what it already shows. */
    suspend fun shellOutputOf(id: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val c = client ?: return@withContext ""
        val chunk = c.shellOutput(id, shellCursors[id])
            ?: return@withContext shellBuffers[id]?.toString().orEmpty()
        val sb = shellBuffers.getOrPut(id) { StringBuilder() }
        chunk.output?.let { sb.append(it) }
        if (sb.length > 400_000) sb.delete(0, sb.length - 400_000)
        chunk.cursor?.let { shellCursors[id] = it }
        sb.toString()
    }

    /** The finished text of a shell, for copy/open ï¿½ same buffer, no extra request. */
    fun shellTextOf(id: String): String = shellBuffers[id]?.toString().orEmpty()

    /**
     * Their event shapes, read off the live stream:
     *   shell.created -> data.info = full Shell.Info
     *   shell.exited  -> data {id, exit, status}
     *   shell.deleted -> data {id}
     */
    private fun onShellEvent(raw: String) {
        runCatching {
            val o = org.json.JSONObject(raw)
            val type = o.optString("type")
            val data = o.optJSONObject("data") ?: return
            when (type) {
                "shell.created" -> {
                    val i = data.optJSONObject("info") ?: return
                    val id = i.optString("id")
                    if (id.isBlank()) return
                    val t = i.optJSONObject("time")
                    val e = com.zapp.net.api.ShellEntry(
                        id = id,
                        status = i.optString("status"),
                        command = i.optString("command"),
                        cwd = i.optString("cwd"),
                        shell = i.optString("shell"),
                        file = i.optString("file"),
                        pid = i.optLong("pid").takeIf { it != 0L },
                        time = com.zapp.net.api.ShellTime(
                            started = t?.optLong("started")?.takeIf { it != 0L },
                            completed = t?.optLong("completed")?.takeIf { it != 0L },
                        ),
                    )
                    shells.value = listOf(e) + shells.value.filterNot { it.id == id }
                }
                "shell.exited" -> {
                    val id = data.optString("id")
                    val code = if (data.has("exit") && !data.isNull("exit")) data.optDouble("exit") else null
                    val st = data.optString("status").ifBlank { "exited" }
                    val now = System.currentTimeMillis()
                    shells.value = shells.value.map {
                        if (it.id == id) it.copy(
                            status = st,
                            exit = code,
                            time = it.time?.copy(completed = it.time?.completed ?: now),
                        ) else it
                    }
                }
                "shell.deleted" -> {
                    val id = data.optString("id")
                    shells.value = shells.value.filterNot { it.id == id }
                    shellCursors.remove(id)
                    shellBuffers.remove(id)
                }
            }
        }
    }

    /** `Fork from here`: the child session opens immediately, like the PC. */
    fun fork(sessionId: String, before: String?) {
        viewModelScope.launch {
            try {
                val s = client?.forkSession(sessionId, before) ?: return@launch
                sessions.value = listOf(s) + sessions.value.filterNot { it.id == s.id }
                open(s)
                messages.value = emptyList()
                loadMessages(s)
            } catch (e: Exception) {
                statusFail("Fork failed: ${e.message}")
            }
        }
    }

    /**
     * `Undo`: stage + commit a revert at that message (drops it and everything after).
     *
     * the owner, 2026-11-24: *"PAG UNDO HINDI AKO NAPUPUNTA DON SA UNDO"*. The server path was
     * right - `interrupt` -> `experimental.session.wait` -> `revert.stage` -> `revert.commit`
     * is their order (`route-CMUQLRzL.js` @ 512 669) - but the screen showed nothing until
     * all four round trips finished. `wait` alone is a 30 s read timeout on the shared client,
     * and it is *supposed* to block until the session is idle, so a turn that had stopped
     * streaming without going idle (an orphaned provider socket) made a tap look like a no-op
     * for half a minute.
     *
     * The cut is known locally the moment he taps: that message and everything after it. So
     * the transcript is cut first, and the server call reconciles behind it. If the server
     * refuses, the previous list is put back and the failure is reported - the screen can be
     * briefly wrong, but it can never be silently wrong.
     */
    fun undo(sessionId: String, messageId: String) {
        val before = messages.value
        val cut = before.indexOfFirst { it.info.id == messageId }
        if (cut < 0) {
            statusFail("Undo: message ${messageId.take(12)} is not in the transcript")
            return
        }
        messages.value = before.subList(0, cut).toList()
        statusOk("Undoing…")
        viewModelScope.launch {
            try {
                client?.undoMessage(sessionId, messageId)
                activeSession.value?.let { loadMessages(it, force = true) }
                statusOk("Undone")
            } catch (e: Exception) {
                messages.value = before
                statusFail("Undo failed: ${e.message}")
            }
        }
    }

    /**
     * `Import session` - PORT of their `importSession(conn, worktree)`
     * (`index-BWsziAR3.js` @ 83 741).
     *
     * They open a picker for `application/json`, hand the file text to the SDK as
     * `{...parsed, location:{directory: worktree}}`, then `session.remember` + `openProjectSession`
     * on the returned session. On the phone the picker is SAF and the directory is the active
     * session's - the same "the project you are looking at" rule, without a project list to pick
     * from.
     */
    /**
     * `Import session` straight from the picker's URI, without holding the file in memory.
     *
     * the owner, 22 Nov: *"WHY NOW ITS 162MB ONLY I CAN TAKE 1GB"*. The String path below reads the
     * whole document and then parses it into a JSON tree, so a real export - 162 MB for this
     * conversation - could never be imported; only a hand-trimmed tail could. This hands the
     * picker's stream to [OpenCodeClient.sessionImportStream] and never materialises it.
     */
    fun importSessionStream(uri: android.net.Uri) {
        val c = client
        if (c == null) {
            android.util.Log.e("ZAPP", "import: no client yet")
            statusFail("Import failed: not connected to the server")
            return
        }
        val dir = activeSession.value?.dir
        viewModelScope.launch {
            importing.value = true
            val tmp = java.io.File(ctx.cacheDir, "zapp-import-${System.currentTimeMillis()}.json")
            try {
                android.util.Log.e("ZAPP", "import: uri=$uri dir=$dir")
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val input = ctx.contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("cannot open $uri")
                    input.use { SessionImport.stage(it, dir, tmp) }
                }
                android.util.Log.e("ZAPP", "import: staged $bytes B -> ${tmp.name}")
                if (bytes <= 2) throw java.io.IOException("the file is empty")
                val s = c.sessionImportFile(tmp)
                if (s == null) {
                    android.util.Log.e("ZAPP", "import: 200 but no session in the answer")
                    statusFail("Import: the server did not return a session")
                } else {
                    android.util.Log.e("ZAPP", "import: created ${s.id}")
                    refreshSessions()
                    open(s)
                    statusOk("Imported")
                }
            } catch (e: Exception) {
                android.util.Log.e("ZAPP", "import FAILED: ${e.javaClass.simpleName}: ${e.message}")
                statusFail("Import failed: " + (e.message ?: e.javaClass.simpleName).take(160))
            } finally {
                runCatching { tmp.delete() }
                importing.value = false
            }
        }
    }

    fun importSession(text: String) {
        val c = client ?: return
        val dir = activeSession.value?.dir
        viewModelScope.launch {
            importing.value = true
            try {
                val s = c.sessionImport(text, dir)
                if (s == null) {
                    statusFail("Import: the server did not return a session")
                } else {
                    refreshSessions()
                    open(s)
                    statusOk("Imported")
                }
            } catch (e: Exception) {
                statusFail("Import failed: " + (e.message ?: "").take(120))
            } finally {
                importing.value = false
            }
        }
    }

    /** Their `importingSession:() => Tu(h())` - true while a picker or an upload is running. */
    val importing = MutableStateFlow(false)

    fun loadInbox(session: Session? = null) {
        val s = session ?: activeSession.value ?: return
        viewModelScope.launch {
            runCatching { inbox.value = client?.inbox(s.id) ?: emptyList() }
        }
    }

    /** `ui.message.moveToQueue`: PATCH the inbox item to `delivery:"queue"`. */
    fun moveToQueue(id: String) {
        val session = activeSession.value ?: return
        viewModelScope.launch {
            runCatching { client?.setInboxDelivery(session.id, id, "queue") }
            loadInbox(session)
        }
    }

    /** The other half of their `alternate` toggle: put a queued prompt back on `steer`. */
    fun moveToSteer(id: String) {
        val session = activeSession.value ?: return
        viewModelScope.launch {
            runCatching { client?.setInboxDelivery(session.id, id, "steer") }
            loadInbox(session)
        }
    }

    fun cancelQueued(id: String) {
        val session = activeSession.value ?: return
        viewModelScope.launch {
            runCatching { client?.cancelInbox(session.id, id) }
            loadInbox(session)
        }
    }

    fun killShell(id: String) {
        viewModelScope.launch {
            runCatching { client?.killShell(id) }
            shellCursors.remove(id)
            shellBuffers.remove(id)
            loadShells()
        }
    }

    /**
     * Desktop's `+` opens a draft tab, not a server session (`shell/tabs/promotion-machine.ts`
     * promotes the draft into a session on the first prompt). `activeDraft` is that empty tab;
     * `drafting` is the derived boolean the UI reads.
     *
     * Desktop mints a **uuid per draft** (`tabs.tsx:1095` `const draftID = draft.draftID ?? uuid()`)
     * and pushes it (`:1105-1111`), so tapping `+` twice gives two tabs with two composers. Our
     * old single `"new"` id collapsed them into one - that is why he could not create more tabs.
     */
    val activeDraft = MutableStateFlow<String?>(null)
    val drafting = MutableStateFlow(false)

    private fun setDraft(id: String?) {
        activeDraft.value = id
        drafting.value = id != null
        // Their `bar.branch()` is per draft, so switching tabs must switch the selection too.
        draftBranch.value = if (id == null) null else branchByDraft[id]
    }

    /** A draft tab id. Never a server id, so `isDraft` can tell them apart. */
    private fun newDraftId(): String = "draft:" + java.util.UUID.randomUUID().toString().take(8)

    /** Draft tabs: the new `draft:<uuid>` form and the legacy single `"new"` still on disk. */
    private fun isDraft(tabId: String): Boolean = tabId == "new" || tabId.startsWith("draft:")

    /**
     * `+` in their UI opens `openProjectNewSession` -> a **draft** tab: no server session is
     * created until the first prompt promotes it (`shell/tabs/promotion-machine.ts`). Creating
     * the session on tap is what littered the server with empty "(untitled)" rows and made the
     * next launch restore one of them instead of a real conversation.
     *
     * So: push a fresh draft tab, deselect, clear the transcript, and let `send()` create the
     * session and continue in the same tap.
     */
    fun newSession() {
        val draftId = newDraftId()
        lastSessionId = ""
        setDraft(draftId)
        clearActive("newSession")
        messagesToken++
        // The cache keeps every other tab's transcript; only the draft tab is empty.
        messages.value = emptyList()
        olderCursor.value = null
        hasOlder.value = false
        pendingDeltas.clear()
        optimistic.clear()
        sending.value = false
        inbox.value = emptyList()
        // `tabs.push(tab)` (`tabs.tsx:1109`): every `+` is its own tab, never the same one reused.
        tabs.touch(draftId)
        refreshTabs()
        viewModelScope.launch { com.zapp.net.settings.saveSession(ctx, draftId) }
    }

    /** Focus an existing draft tab (`navigate(draftHref(draftID))`, `tabs.tsx:1115`). */
    fun openDraft(draftId: String) {
        if (activeDraft.value == draftId && activeSession.value == null) return
        lastSessionId = ""
        setDraft(draftId)
        clearActive("openDraft")
        messagesToken++
        messages.value = emptyList()
        olderCursor.value = null
        hasOlder.value = false
        pendingDeltas.clear()
        optimistic.clear()
        sending.value = false
        inbox.value = emptyList()
        refreshTabs()
        viewModelScope.launch { com.zapp.net.settings.saveSession(ctx, draftId) }
    }

    /**
     * Close one tab. Desktop's `nextTabAfterClose` (`shell/tabs/closed.ts`):
     * `tabs[index + 1] ?? tabs[index - 1] ?? null` - and only when the closed tab was active.
     */
    fun closeTab(id: String) {
        val list = tabs.list()
        val index = list.indexOf(id)
        val wasActive = (activeSession.value?.id ?: activeDraft.value) == id
        // `tabs.tsx:1297`: a session tab is pushed onto the closed stack *before* removal.
        if (!isDraft(id)) recordClosed(id, index)
        dropDraft(id)
        tabs.remove(id)
        refreshTabs()
        if (!wasActive) return
        // `nextTabAfterClose` (`closed.ts:141-147`): `tabs[index + 1] ?? tabs[index - 1] ?? null`.
        val next = list.getOrNull(index + 1) ?: list.getOrNull(index - 1)
        if (next == null) {
            clearActive("closeTab: no tab left")
            setDraft(null)
            messages.value = emptyList()
            return
        }
        if (isDraft(next)) {
            openDraft(next)
            return
        }
        openById(next)
    }

    /** `pushClosedTab` (`closed.ts:19-33`) with the info their menu renders. */
    private fun recordClosed(id: String, index: Int) {
        if (isDraft(id)) return
        val s = sessions.value.firstOrNull { it.id == id }
        closedTabs.push(
            com.zapp.net.settings.ClosedTabs.Entry(
                id = id,
                index = index.coerceAtLeast(0),
                closedAt = System.currentTimeMillis(),
                title = s?.title,
                directory = s?.directory ?: s?.location?.directory,
                prompted = promptedSessions.contains(id),
            ),
        )
    }

    /**
     * `tabs.tsx:329-369 reopenClosedTabNow`: pop the entry, reinsert it at
     * `Math.min(entry.index, store.length)` and navigate to it.
     */
    fun reopenClosedTab(id: String? = null) {
        val open = tabs.list()
        val entry = closedTabs.take(open, id) ?: return
        val session = sessions.value.firstOrNull { it.id == entry.id } ?: run {
            statusFail("That session is gone")
            refreshRecentlyClosed()
            return
        }
        tabs.insert(entry.id, kotlin.math.min(entry.index, open.size))
        refreshTabs()
        open(session)
    }

    /** `closeTabs(keys, keep)` (`tabs.tsx:1303`). */
    fun closeOtherTabs(keep: String) {
        closeMany(tabs.list().filter { it != keep })
    }

    /** `common.closeTabsToRight` (`tab-nav.tsx:93-117`). */
    fun closeTabsToRight(id: String) {
        val list = tabs.list()
        val i = list.indexOf(id)
        if (i < 0) return
        closeMany(list.drop(i + 1))
    }

    /**
     * `tabs.tsx:1309-1321`: entries are recorded right-to-left so successive reopens
     * restore the original positions.
     */
    private fun closeMany(keys: List<String>) {
        if (keys.isEmpty()) return
        val list = tabs.list()
        val activeId = activeSession.value?.id ?: activeDraft.value
        keys.sortedByDescending { list.indexOf(it) }.forEach { key ->
            if (isDraft(key)) {
                dropDraft(key)
            } else {
                recordClosed(key, list.indexOf(key))
                dropDraft(key)
            }
            tabs.remove(key)
        }
        refreshTabs()
        if (activeId == null || activeId !in keys) return
        val next = tabs.list().lastOrNull()
        if (next == null) {
            clearActive("closeMany: no tab left")
            setDraft(null)
            messages.value = emptyList()
        } else if (isDraft(next)) {
            openDraft(next)
        } else {
            openById(next)
        }
    }

    /** Session menu: rename (PATCH), delete (DELETE), export (local markdown). */    fun renameSession(id: String, title: String) {
        viewModelScope.launch {
            try {
                val s = client?.renameSession(id, title) ?: return@launch
                sessions.value = sessions.value.map { if (it.id == s.id) s else it }
                if (activeSession.value?.id == s.id) activeSession.value = s
            } catch (e: Exception) {
                statusFail("Rename failed: ${e.message}")
            }
        }
    }

    /**
     * `tab-strip.tsx:421-429`: deleting a session removes the tab for it **and for every
     * descendant** (`sessionTreeIDs`), then `SESSION_TABS_REMOVED_EVENT` closes them.
     */
    fun deleteSession(id: String) {
        viewModelScope.launch {
            try {
                val ids = sessionTreeIds(id)
                client?.deleteSession(id)
                ids.forEach { dropDraft(it) }
                tabs.list().filter { it in ids }.forEach { tabs.remove(it) }
                // `closed.ts:129 removeClosedTabs`: a deleted session cannot be reopened.
                closedTabs.remove(ids)
                sessions.value = sessions.value.filterNot { it.id in ids }
                if (activeSession.value?.id?.let { it in ids } == true) {
                    clearActive("deleteSession")
                    messages.value = emptyList()
                    pickActiveSession()
                }
                refreshTabs()
            } catch (e: Exception) {
                statusFail("Delete failed: ${e.message}")
            }
        }
    }

    /** `sessionTreeIDs` (`tab-strip.tsx:421`): the session itself plus every descendant. */
    private fun sessionTreeIds(rootID: String): List<String> {
        val byParent = sessions.value.groupBy { it.parentId }
        val out = ArrayList<String>()
        fun walk(id: String) {
            if (!out.add(id)) return
            byParent[id].orEmpty().forEach { walk(it.id) }
        }
        walk(rootID)
        return out
    }

    /** A tab whose session is not in the current page: fetch it, then open it. */
    fun openById(id: String) {
        sessions.value.firstOrNull { it.id == id }?.let { open(it); return }
        viewModelScope.launch {
            val s = runCatching { client?.sessionDetail(id) }.getOrNull()
            if (s == null) {
                statusFail("That session is gone")
                return@launch
            }
            sessions.value = listOf(s) + sessions.value.filterNot { it.id == s.id }
            refreshTabs()
            open(s)
        }
    }

    /** Their "Exportï¿½" ï¿½ plain markdown of the transcript, handed to the share sheet. */
    fun exportMarkdown(): String = markdownOf(activeSession.value?.title, messages.value)

    /** Row `Exportï¿½` (`home/sessions/view.tsx:1729`): any session, not only the open one. */
    fun exportSessionMarkdown(session: Session, ready: (String) -> Unit) {
        viewModelScope.launch {
            val list = runCatching {
                client?.messagesPage(session.id, limit = 200)?.messages
            }.getOrNull().orEmpty()
            ready(markdownOf(session.title, list))
        }
    }

    /**
     * `command.session.export` - *"Export session"* / *"Export the full session transcript as
     * JSON"* (`i18n/en.ts:237-239`).
     *
     * Their command is not a share-sheet hand-off: it writes the same `{info, messages}` document
     * that `Import session` reads, so a session can move between machines. `exportSessionJson`
     * already built exactly that document - nothing called it, so the only export the app could
     * produce was markdown, which cannot be imported back.
     */
    fun exportSessionJsonText(sessionId: String, ready: (name: String, json: String) -> Unit) {
        viewModelScope.launch {
            val pair = runCatching { client?.exportSessionJson(sessionId) }.getOrNull()
            if (pair == null) {
                statusFail("Failed to export session")
                return@launch
            }
            ready(pair.first, pair.second)
        }
    }

    /**
     * Write an export straight into `Download/ZAPP/`, with no save dialog.
     *
     * the owner, 22 Nov: *"WHEN I TAP THE EXPORT PLEASE NA MAPUPUNTA SYA DON SA MISMONG NA DOWNLOAD
     * NONG PC ... PWDE E NOH? DIRICT SA NAME PARA TAP NALANG NILA"*.
     *
     * Their desktop export opens a native save dialog because a desktop has one. The phone's
     * equivalent of "it is in Downloads now" is MediaStore: on API 29+ it needs no permission and
     * writes exactly where the `Import session` picker already looks, so the round trip is
     * tap Export, tap Import, tap the file - no ADB, no cable, no dialog.
     *
     * Returns the visible path, or null when the write did not happen (then the caller opens the
     * save dialog instead of failing).
     */
    private fun writeDownload(name: String, text: String): String? = runCatching {
        val dir = "Download/ZAPP"
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS + "/ZAPP",
                )
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            }
            val uri = ctx.contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values,
            ) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { o ->
                o.write(text.toByteArray(Charsets.UTF_8))
            } ?: return null
        } else {
            val d = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                ),
                "ZAPP",
            )
            if (!d.exists() && !d.mkdirs()) return null
            java.io.File(d, name).writeText(text, Charsets.UTF_8)
        }
        "$dir/$name"
    }.getOrNull()

    /**
     * `Export session` - writes to `Download/ZAPP/` directly, and only falls back to the save
     * dialog when the direct write is refused.
     *
     * The name carries a minute stamp (`<title-slug>-20261122-1312.json`) so a second export never
     * overwrites the first and the newest one is obvious in the picker by name alone.
     */
    fun exportSessionToDownloads(
        sessionId: String,
        fallback: (name: String, json: String) -> Unit,
    ) {
        viewModelScope.launch {
            val pair = runCatching { client?.exportSessionJson(sessionId) }.getOrNull()
            if (pair == null) {
                statusFail("Failed to export session")
                return@launch
            }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                .format(java.util.Date())
            val name = pair.first.removeSuffix(".json") + "-" + stamp + ".json"
            val where = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                writeDownload(name, pair.second)
            }
            if (where != null) {
                showToast("Session exported", where)
            } else {
                fallback(name, pair.second)
            }
        }
    }

    private fun markdownOf(title: String?, rows: List<MessageWithParts>): String {
        val sb = StringBuilder()
        sb.append("# ").append(title ?: "OpenCode session").append("\n\n")
        rows.forEach { m ->
            if (m.info.role == "user") {
                val t = m.parts.filter { it.type == "text" }
                    .joinToString("\n") { it.text.orEmpty() }.trim()
                if (t.isNotBlank()) sb.append("## User\n\n").append(t).append("\n\n")
            } else {
                m.parts.filter { it.type == "text" }.forEach { p ->
                    val t = p.text.orEmpty().trim()
                    if (t.isNotBlank()) sb.append(t).append("\n\n")
                }
            }
        }
        return sb.toString()
    }

    fun pairAndConnect(serverBase: String, pairUrl: String) {
        viewModelScope.launch {
            connecting.value = true
            statusOk("Pairing...")
            try {
                com.zapp.net.settings.saveServer(ctx, serverBase)
                val c = OpenCodeClient(
                    serverBase,
                    onCookies = { persistCookies(it) },
                    onToken = { persistToken(it) },
                )
                val tok = c.pair(pairUrl)
                statusOk("Paired (token ${tok.take(8)}...)")
                client = c
                connected.value = true
                // their `createEffect`: every connect re-runs the recovery sweep while the
                // setting is on, because the event stream never replays what was missed
                autoSyncSweep()
                statusOk("Connected")
                refreshSessions()
                loadPickers()
                listen()
            } catch (e: Exception) {
                statusFail("Pair failed: ${e.message}")
            } finally {
                connecting.value = false
            }
        }
    }

    /**
     * The Servers page's `Retry` row. Their `settings.server.unreachable.retry` re-runs the
     * same connect the app already performed; nothing is re-typed and the token is not shown.
     */
    fun retryConnection(): Job {
        val token = runCatching { com.zapp.net.onboarding.Onboarding.ensureToken(ctx) }
            .getOrDefault("")
        return connectWithToken(serverBase.value, token)
    }

    /**
     * The engine wait, as a value the screen can draw.
     *
     * the owner, 23 Nov: "build a indicator bout this login". A sentence in the status strip was
     * not enough - the first connect on a cold phone is 40+ seconds, and a 12 sp grey line is
     * indistinguishable from a hang. Every field here is measured: `id`/`bytes`/`total` come
     * from `$HOME/.zapp/status`, which `serve.sh` writes at each real boundary, and `seconds`
     * is elapsed wall clock. [fraction] is null unless the phone reported a real total, so the
     * bar is determinate only when there is a real denominator.
     */
    data class EngineWait(
        /** mirror, python, download, check, unpack, glibc, start, ready, or "" before the first read. */
        val id: String = "",
        val label: String = "",
        val bytes: Long = 0L,
        val total: Long = 0L,
        val seconds: Int = 0,
        /** True only while a wait is actually in flight. */
        val active: Boolean = false,
    ) {
        val fraction: Float?
            get() = if (total > 0L) (bytes.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
    }

    val engineWait = MutableStateFlow(EngineWait())

    /**
     * Start the engine if it is not answering, then wait for it while reporting the phone's
     * own stage. Returns true when port 4096 is open.
     *
     * Measured on this phone 23 Nov: relay fired 17:55:03.837 -> `zapp_lane` 17:55:13 ->
     * `opencode serve` 17:55:45. **42 seconds**, and every one of them is invisible to a
     * connect attempt that fires at second zero. One `c.sessions()` call fails with
     * `Failed to connect to /127.0.0.1:4096`, and nothing used to retry it, so the app sat on
     * the connect screen forever even though the engine arrived moments later. the owner:
     * "we have all user like this problem connect this phone" - that is this.
     *
     * A PC server is already running when OpenCode starts, which is why desktop has no
     * equivalent. On a phone the server is a second app that has to be woken and, on a fresh
     * install, has to install its own runtime first.
     */
    private suspend fun awaitEngine(reason: String): Boolean {
        // One wait, however many callers. Measured 18:10: a cold start had `tryReconnect` and
        // the connect screen both waiting, each fired its own relay 9 s apart. Two `serve.sh`
        // runs can race for the dpkg lock, and two listeners subscribe the same stream twice.
        // The second caller now joins the first instead.
        val gate = engineGate
        return gate.withLock {            if (com.zapp.net.termux.TermuxSetup.engineListeningNow()) {
                android.util.Log.e("ZAPP", "awaitEngine($reason): engine already answers on 4096")
                return@withLock true
            }
            android.util.Log.e("ZAPP", "awaitEngine($reason): engine down, firing the relay")
            engineWait.value = EngineWait(label = "Waking Termux", active = true)
            withContext(Dispatchers.IO) {
                runCatching { com.zapp.net.termux.BootRelay.runPending(ctx) }
                    .onFailure { android.util.Log.e("ZAPP", "awaitEngine relay threw: $it") }
            }
            val started = System.currentTimeMillis()
            var ticks = 0
            var stage = com.zapp.net.termux.TermuxSetup.Stage()
            while (System.currentTimeMillis() - started < ENGINE_WAIT_MS) {
                if (com.zapp.net.termux.TermuxSetup.engineListeningNow()) {
                    val took = (System.currentTimeMillis() - started) / 1000
                    android.util.Log.e("ZAPP", "awaitEngine($reason): engine answered after ${took}s")
                    engineWait.value = EngineWait(active = false)
                    statusOk("Engine ready")
                    return@withLock true
                }
                // One RUN_COMMAND every 5 s: the stage file, the real bytes and the heartbeat all
                // come back together, and Termux posts one notification per command it runs.
                if (ticks % 5 == 0) {
                    runCatching { com.zapp.net.termux.TermuxSetup.snapshot(ctx) }
                        .getOrNull()?.let { stage = it.stage }
                }
                val secs = ((System.currentTimeMillis() - started) / 1000).toInt()
                val wait = EngineWait(
                    id = stage.id,
                    label = stageLabel(stage),
                    bytes = stage.bytes,
                    total = stage.total,
                    seconds = secs,
                    active = true,
                )
                engineWait.value = wait
                statusOk("${wait.label} (${secs}s)")
                ticks++
                delay(1_000)
            }
            val last = engineWait.value
            engineWait.value = last.copy(active = false)
            statusFail(
                "The engine did not answer in ${ENGINE_WAIT_MS / 1000}s - " +
                    "${last.label.ifBlank { "no stage reported" }}",
            )
            false
        }
    }

    private val engineGate = kotlinx.coroutines.sync.Mutex()

    /**
     * The phone's own stage, in plain words.
     *
     * Nothing here is a timer dressed up as progress: `id` and `bytes`/`total` are read from
     * `$HOME/.zapp/status`, which `serve.sh` writes at each real boundary. An unknown stage says
     * so instead of inventing a percentage.
     */
    private fun stageLabel(s: com.zapp.net.termux.TermuxSetup.Stage): String = when (s.id) {
        "mirror" -> "Choosing a Termux mirror"
        "python" -> "Installing python in Termux"
        "download" -> "Downloading the engine"
        "check" -> "Checking the download"
        "unpack" -> "Unpacking the engine"
        "glibc" -> "Installing glibc-runner"
        "start" -> "Starting the engine"
        "ready" -> "Engine starting"
        else -> "Waiting for Termux"
    }

    /** This phone's own server, as opposed to a PC on the LAN or a remote host. */
    private fun isLoopback(base: String): Boolean =
        base.contains("127.0.0.1") || base.contains("localhost") || base.contains("[::1]") ||
            base.contains("0.0.0.0")

    /**
     * The first authenticated request, retried while the socket is refused.
     *
     * Port-open is not the same as serving: measured in the same run, `opencode serve` binds
     * 4096 at 17:55:45 and answers `/api/session` a moment later. One attempt right at the bind
     * can still be refused, and that refusal used to end the whole connect.
     */
    private suspend fun firstAnswer(c: OpenCodeClient, tries: Int = 10) {
        var last: Exception? = null
        for (i in 1..tries) {
            try {
                c.sessions(limit = 1)
                return
            } catch (e: Exception) {
                val m = e.message.orEmpty()
                val refused = m.contains("Failed to connect") || m.contains("ConnectException") ||
                    m.contains("refused", true) || m.contains("ECONNREFUSED") ||
                    m.contains("timed out", true) || m.contains("timeout", true)
                // A 401/403 is a real verdict from a live server; only a dead socket retries.
                if (!refused) throw e
                last = e
                android.util.Log.e("ZAPP", "firstAnswer try $i/$tries refused: ${m.take(90)}")
                if (i < tries) {
                    statusOk("Waiting for the engine to answer (${i * 2}s)")
                    delay(2_000L)
                }
            }
        }
        throw last ?: IllegalStateException("The engine never answered")
    }

    /**
     * Phone-local server: no pair link, no account. `opencode serve` started with
     * OPENCODE_SERVER_PASSWORD=<token> takes that token as the Basic password, so we
     * hand it to the client directly and test it with one cheap session list call.
     */
    fun connectWithToken(serverBase: String, token: String): Job =
        viewModelScope.launch {
            connecting.value = true
            statusOk("Connecting\u2026")
            try {
                com.zapp.net.settings.saveServer(ctx, serverBase)
                // The engine is a second app on this phone and takes ~42 s to appear on a cold
                // start. Waiting for it here is what makes "Connect to this phone" work on the
                // first tap for everyone, instead of only for whoever taps twice.
                if (isLoopback(serverBase)) awaitEngine("connect")
                val c = OpenCodeClient(
                    serverBase,
                    initialToken = token.ifBlank { null },
                    onCookies = { persistCookies(it) },
                    onToken = { persistToken(it) },
                )
                firstAnswer(c)
                // remember it: without this every launch fell back to the old stored token,
                // got a 401 from the phone server and dropped back to the connect screen
                if (token.isNotBlank()) persistToken(token)
                client = c
                connected.value = true
                // their `createEffect`: every connect re-runs the recovery sweep while the
                // setting is on, because the event stream never replays what was missed
                autoSyncSweep()
                // The gate is what the Servers page reads, so a manual connect records the same
                // verdict `tryReconnect` records rather than leaving an older one in place.
                gate.value = GateState.Ok
                statusOk("Connected to $serverBase")
                refreshSessions()
                listen()
                loadPickers() // cold catalogue on the phone takes ~90 s; never block the UI on it
            } catch (e: Exception) {
                statusFail("Connect failed: ${e.message}")
                // Same distinction `tryReconnect` makes: 401/403 is a refusal, anything else is
                // the phone not reaching the server.
                val msg = e.message.orEmpty()
                gate.value = if (msg.contains("-> 401") || msg.contains("-> 403")) {
                    GateState.Denied
                } else {
                    GateState.Unreachable(msg.take(160))
                }
            } finally {
                connecting.value = false
            }
        }

    /** The list request itself, built exactly as the two desktop call sites build it. */
    private suspend fun listPage(c: OpenCodeClient): SessionPage = if (showArchived.value) {
        // `shell/titlebar/inbox-model.ts:89,297`: archived view, newest first, 50 rows.
        c.sessionsPage(
            limit = 50,
            search = sessionSearch.value.takeIf { it.isNotBlank() },
            parentID = "",
            archived = true,
        )
    } else {
        // `home/sessions/index.ts:15,37`: one 500-row page of root sessions; archived rows
        // are hidden client-side by `time.archived === undefined` (`index.ts:75,83`).
        c.sessionsPage(
            limit = 500,
            search = sessionSearch.value.takeIf { it.isNotBlank() },
            parentID = "",
        )
    }

    /**
     * PORT of `home/sessions/load-machine.ts`.
     *
     * `force = false` is the `staleTime` path (`queries.ts:43`): a list younger than 30 s is
     * shown without a request. Every mutation path passes `force = true`, which is desktop's
     * `invalidate()`.
     */
    fun refreshSessions(force: Boolean = true) {
        val c = client ?: return
        // A cached list younger than the stale window is not refetched.
        if (!force && listLoaded.value && System.currentTimeMillis() - listFetchedAt < homeSessionsStaleMs) return
        // `load-machine.ts:539-543`: a refresh asked for during a fetch runs once more after it.
        if (listLoading) { listQueued = true; return }
        val attempt = ++listIssued
        listLoading = true
        listQueued = false
        listError.value = null
        val hadRows = sessions.value.isNotEmpty()
        val fetchStartedAt = System.currentTimeMillis()
        if (!hadRows) {
            // `load-machine.ts:289-299,801`: placeholders start only after the 250 ms delay.
            listSkeleton.value = false
            viewModelScope.launch {
                delay(homeSessionsPlaceholderDelayMs)
                if (listIssued == attempt && listLoading) {
                    listSkeleton.value = true
                    android.util.Log.e("ZAPP", "list: skeleton on attempt=$attempt")
                }
            }
        }
        viewModelScope.launch {
            try {
                val page = withTimeout(homeSessionsFetchTimeoutMs) { listPage(c) }
                sessionCursor.value = page.next
                sessions.value = if (showArchived.value) page.sessions
                else page.sessions.filter { it.time?.archived == null }
                listLoaded.value = true
                listFetchedAt = System.currentTimeMillis()
                // Their `server.connected` handler refreshes the active map in the same breath as
                // the session list, so the two can never disagree about what is running.
                runCatching { c.activeSessions() }.onSuccess { activeRuns.value = it }
                // `load-machine.ts:565-591`: the reveal only names a fetch that had
                // placeholder rows on screen; a fast load paints straight, no flash.
                if (listSkeleton.value) {
                    listSkeleton.value = false
                    listRevealing.value = true
                    viewModelScope.launch {
                        delay(homeSessionsRevealMs)
                        listRevealing.value = false
                    }
                }
                // NOTE: no prune here. Desktop removes a tab only when the session is deleted
                // (`tab-strip.tsx:421-429` -> `SESSION_TABS_REMOVED_EVENT`), never because a
                // list page did not mention it. Pruning against the fetched page is what made
                // tabs vanish on refresh.
                refreshTabs()
                // A 2 kB list dump on every refresh filled the whole logcat ring buffer:
                // measured 03:10, thirty `sessions: n=30 ...` lines were all that survived,
                // and every tunnel/wall/verify line from the minutes he was reading was gone.
                // The count stays always; the roster is capped per process so the buffer holds
                // the evidence that matters.
                android.util.Log.e("ZAPP", "sessions: n=${page.sessions.size}")
                if (sessionsDumpLeft > 0) {
                    sessionsDumpLeft -= 1
                    android.util.Log.e(
                        "ZAPP",
                        "sessions[${page.sessions.size}]: " +
                            page.sessions.joinToString(" | ") { "${it.id.take(14)}=${it.title?.take(24)}" },
                    )
                }
                android.util.Log.e(
                    "ZAPP",
                    "list: ready attempt=$attempt hadRows=$hadRows skeleton=${listSkeleton.value} " +
                        "t=${System.currentTimeMillis() - fetchStartedAt}ms",
                )
                pickActiveSession()
            } catch (e: TimeoutCancellationException) {
                // `failure = { type: "timeout" }` (`load-machine.ts:657-661`).
                listSkeleton.value = false
                if (!hadRows) listError.value = "The server took too long to answer"
                statusFail("List timed out")
                ensureServer("list timeout")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                listSkeleton.value = false
                // `load-machine.ts:643-653`: a failed refresh keeps the list it was showing.
                if (!hadRows) listError.value = e.message?.take(160) ?: "Couldn't load sessions"
                statusFail("List failed: ${e.message}")
                ensureServer("list failed: ${e.message}")
            } finally {
                if (listIssued == attempt) listLoading = false
                if (listQueued) { listQueued = false; refreshSessions(force = true) }
            }
        }
    }

    /** When the server stops answering, ask Termux to run `serve.sh` again. That script is the
     *  ONLY owner of the engine's lifecycle: it probes `/api/agent`, holds its own start lock,
     *  and decides whether the process is healthy, stale or absent. The app never signals a
     *  process it does not own - see [com.zapp.net.termux.BootRelay] for the 21:07 race that
     *  rule prevents. 45 s cooldown so a dead phone cannot spawn a restart loop. */
    private var lastRelayAt = 0L
    private fun ensureServer(why: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        // A proven-dead engine may not be starved by the unforced path: `refreshSessions` fires
        // `ensureServer("list failed")` on every failed poll, so a plain 45 s gate could swallow
        // the stream's repair request right after a list failure. Forced gets 10 s instead of
        // 45 s - still enough that a loop cannot form, short enough to act inside one retry.
        val cooldown = if (force) 10_000L else 45_000L
        if (now - lastRelayAt < cooldown) return
        lastRelayAt = now
        android.util.Log.e(
            "ZAPP",
            "ensureServer: $why -> BootRelay" + (if (force) " (forced)" else ""),
        )
        runCatching { com.zapp.net.termux.BootRelay.runPending(ctx, force = force) }
            .onFailure { android.util.Log.e("ZAPP", "ensureServer relay failed: $it") }
    }

    /** Their session search runs on the server (`?search=&archived=&cursor=`); an empty
     *  query falls back to the plain list so the sidebar never blanks. */
    fun searchSessionsRemote(query: String) {
        sessionSearch.value = query
        sessionCursor.value = null
        refreshSessions(force = true)
        if (!looksLikeSessionId(query)) return
        val c = client ?: return
        // `home/sessions/search.ts:163-171`: an exact id is looked up and shown first, because
        // a pasted `ses_...` is not a title and the server's text search cannot return it.
        viewModelScope.launch {
            val exact = runCatching { c.sessionDetail(query) }.getOrNull() ?: return@launch
            if (sessionSearch.value != query) return@launch
            sessions.value = listOf(exact) + sessions.value.filterNot { it.id == exact.id }
        }
    }

    /** `session/search.ts:1-4` - `value.length > 20 && value.startsWith("ses_")`. */
    private fun looksLikeSessionId(v: String) = v.length > 20 && v.startsWith("ses_")

    /** `notification.tsx:919` - `markRead` is the server call. */
    fun markRead(id: String) {
        com.zapp.net.settings.SessionPrefs.setManualUnread(ctx, id, false)
        val s = sessions.value.firstOrNull { it.id == id }
        viewModelScope.launch {
            runCatching { client?.markViewed(id, s?.time?.idle ?: System.currentTimeMillis()) }
            com.zapp.net.notify.Notifier.cancelFor(ctx, id)
            refreshSessions(force = false)
        }
    }

    /** `notification.tsx:921-925` - `markUnread` only records the id locally. */
    fun markUnread(id: String) {
        com.zapp.net.settings.SessionPrefs.setManualUnread(ctx, id, true)
        refreshSessions(force = false)
    }

    /** `session-preferences.ts:81-98` - returns the new state. */
    fun toggleMute(id: String): Boolean {
        val next = id !in com.zapp.net.settings.SessionPrefs.muted(ctx)
        com.zapp.net.settings.SessionPrefs.setMuted(ctx, id, next)
        return next
    }

    /** `session-preferences.ts:81-98` - `hidden` keeps a session out of the list, not the server. */
    fun setHidden(id: String, hidden: Boolean) {
        com.zapp.net.settings.SessionPrefs.setHidden(ctx, id, hidden)
        refreshSessions(force = false)
    }

    /** `Show archived` ï¿½ same route, one flag. */
    fun toggleArchived() {
        showArchived.value = !showArchived.value
        sessionCursor.value = null
        refreshSessions(force = true)
    }

    /** `Load more` ï¿½ the cursor the previous page handed back. */
    fun nextSessionPage() {
        val c = client ?: return
        val cur = sessionCursor.value ?: return
        viewModelScope.launch {
            val page = runCatching {
                c.sessionsPage(
                    limit = if (showArchived.value) 50 else 500,
                    search = sessionSearch.value.takeIf { it.isNotBlank() },
                    parentID = "",
                    archived = if (showArchived.value) true else null,
                    cursor = cur,
                )
            }.getOrNull() ?: return@launch
            sessionCursor.value = page.next
            sessions.value = (sessions.value + page.sessions).distinctBy { it.id }
        }
    }

    /** `Archive` / `Unarchive` from the session menu. */
    fun archiveSession(session: Session, archived: Boolean) {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.patchSession(session.id, archived = archived) }
                .onFailure { statusFail("archive: ${it.message?.take(120)}") }
            // archiving closes the tab on desktop, so its draft and tab key go with it
            if (archived) {
                dropDraft(session.id)
                tabs.remove(session.id)
            }
            refreshSessions()
        }
    }

    /** Which session is on screen after the list answers.
     *
     *  Ported from `shell/tabs/tabs.tsx`:
     *   - `919`: the active tab is `open.find(tab => tabKey(tab) === recentKey()) ?? open.at(-1)`
     *   - `651`: `if (recent.key && !next.some(tab => tabKey(tab) === recent.key)) setRecentKey(undefined)`
     *
     *  So: the remembered session if the server still has it, otherwise the persisted key is
     *  cleared and nothing is selected - which is Home, with the session list one tap away.
     *  The old `maxBy { updatedAt }` fallback was mine, not theirs: it is what kept opening a
     *  fresh "(untitled)" session and made his real conversation look erased.
     */
    /** Every clear names itself: a stray re-open is traced to the path that dropped the tab. */
    private fun clearActive(why: String) {
        if (activeSession.value != null) {
            android.util.Log.e(
                "ZAPP",
                "clearActive: $why from=" + Throwable().stackTrace.drop(1).take(6)
                    .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" },
            )
        }
        activeSession.value = null
    }

    private fun pickActiveSession() {
        // An open draft tab is an explicit choice. Desktop's route is the draft
        // (`/new-session?draftId=...`, `tabs.tsx:143`), and no list refresh re-selects a session
        // behind it. Without this guard the next `GET /api/session` answered and `+` was
        // instantly undone - the "new tab is buggy" he reported.
        if (drafting.value) return
        // A draft tab is a valid remembered active tab (`tabs.tsx:919` returns any tab key,
        // draft or session). Restore it before the session lookup, or a launch would silently
        // drop the draft he left open and fall back to a session.
        if (isDraft(lastSessionId) && lastSessionId in tabs.list()) {
            val draftId = lastSessionId
            lastSessionId = ""
            openDraft(draftId)
            return
        }
        val list = sessions.value
        if (list.isEmpty()) return
        val current = activeSession.value
        if (current != null && list.any { it.id == current.id }) return
        // Their `open` list is every tab still open, restored from `Persist.window("tabs")`.
        // Active = `find(recentKey) ?? open.at(-1)` (tabs.tsx:919). Without the tab list a
        // blank `recent` fell through to the home board, which is what made a launch look
        // like the conversation had been lost even though the server still had it.
        // NOTE: no prune against the fetched page. Desktop removes tabs only on delete.
        val openIds = tabs.list()
        val byId = list.associateBy { it.id }
        val wanted = lastSessionId.ifBlank { openIds.lastOrNull().orEmpty() }
        val pick = byId[wanted]
            ?: openIds.mapNotNull { byId[it] }.lastOrNull()
        if (pick != null) {
            open(pick)
            return
        }
        if (lastSessionId.isNotBlank()) {
            lastSessionId = ""
            viewModelScope.launch { com.zapp.net.settings.saveSession(ctx, "") }
        }
        if (current != null) {
            // its tab is gone (archived, deleted, another client): drop it like they do
            clearActive("pickActiveSession: tab gone")
            messages.value = emptyList()
            olderCursor.value = null
            hasOlder.value = false
            optimistic.clear()
        }
    }

    /** True once `GET /api/session` has answered - the only moment an empty list means empty. */
    // (now `listLoaded`, a StateFlow the board reads)

    fun open(session: Session) {
        val same = activeSession.value?.id == session.id
        activeSession.value = session
        // A reply types out once, on the turn that produced it. Opening another chat must not
        // replay the last one's message as if it were arriving now.
        if (!same) liveMsgId.value = null
        // The banner for this session has served its purpose: reading it is the click.
        com.zapp.net.notify.Notifier.cancelFor(ctx, session.id)
        // The size verdict belongs to the chat it was measured on: opening another chat
        // must not inherit "too big" from the one that walled.
        if (contextTooBig.value != null) contextTooBig.value = null
        setDraft(null)
        lastSessionId = session.id
        // `tabs.tsx:993-1039`: opening an already-open tab does not reorder the strip.
        tabs.ensure(session.id)
        refreshTabs()
        android.util.Log.e(
            "ZAPP",
            "open: id=${session.id} title=${session.title} same=$same sessions=${sessions.value.size} " +
                "from=" + Throwable().stackTrace.drop(1).take(4)
                    .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" },
        )
        if (!same) {
            // `review/model.ts:1033-1041`: switch sessions, switch the review's stored list.
            reviewOpenFiles.value = reviewOpenPrefs.files(session.id)
            reviewActiveFile.value = reviewOpenPrefs.active(session.id)
            // Their store renders the session's cached messages the instant the tab switches
            // (`store.session.message[sessionID]`); the fetch below only refreshes them. This
            // is what removes the blank screen + spinner between tabs.
            messagesToken++
            messages.value = messageCache[session.id] ?: emptyList()
            olderCursor.value = null
            hasOlder.value = false
            pendingDeltas.clear()
            optimistic.clear()
            loadingMessages = false
            refetchAttempts = 0
            sending.value = false
            // Their sidebar selection runs the message query itself; relying on the screen's
            // effect alone left every path that opens a session (drawer, board, quick switch)
            // with an empty timeline until something else happened to reload it.
            loadMessages(session)
            loadInbox(session)
        }
        viewModelScope.launch {
            com.zapp.net.settings.saveSession(ctx, session.id)
            applySessionModel(session)
            // reading a session clears its unread badge on their side, and any permission
            // request that arrived while another session was on screen belongs to this one
            runCatching { client?.markViewed(session.id, session.time?.idle ?: System.currentTimeMillis()) }
            loadPermissions()
            loadForms()
            loadQuestions()
            // NOTE: desktop syncs the location's VCS on session entry
            // (`home/sessions/controller.tsx:559`), and that was tried here. On this phone the
            // VCS path wedges: `/api/vcs` measured 42 s once, then hung past 70 s. Warming it
            // on session open only stacks hanging requests in front of the chat queue (the
            // session list came back empty while it was stuck), so the Review panel loads on
            // demand instead, still with desktop's 30 s timeout + Retry.
        }
    }

    private var refreshJob: Job? = null

    /**
     * Desktop's `settle()` (`client/src/solid/data.ts:318-330`): "Runs `load` once a burst of
     * same-key events goes quiet, so N events cost one refetch." Their key is the session's
     * message list and their quiet window is `settleMs = 150` (`:95`).
     *
     * Used for the two cases where the live deltas are provably not the whole answer: a stream
     * that went live again after a drop, and a turn that ended leaving a tool marked running.
     * Both mean "the server knows more than this transcript does", and both arrive as bursts -
     * one refetch per burst, never one per event.
     */
    private fun settleSync(reason: String) {
        syncSettle?.cancel()
        syncSettle = viewModelScope.launch {
            delay(150)
            val s = activeSession.value ?: return@launch
            android.util.Log.e("ZAPP", "settle sync: $reason")
            loadMessages(s, force = true)
        }
    }


    /** How many full session rosters this process may print; see the log line in loadSessions. */
    private var sessionsDumpLeft = 4

    private fun listen() {
        // ONE LIVE SUBSCRIPTION, EVER - and never a new one while the current one is healthy.
        //
        // The four callers (connect, pair, connectWithToken, the retry ladder) all used to tear
        // down and reopen unconditionally, so a cold start cancelled a stream that had JUST
        // gone live: measured 2026-11-23 21:34:25, `stream subscribing (gen 1)` then
        // `(gen 2)` 83 ms later, with gen 1 already reporting `stream heartbeat`.
        //
        // That is the one shape the official desktop client does not have: there, the event
        // stream is a single refcounted connection per module, so a second `subscribe()` while
        // connected returns the existing subscription (`shared-events`). Ours instead cancelled
        // a live SSE mid-write, over and over, and the engine answered by parking: 2026-11-23
        // 21:42 the Bun process sat at ZERO cpu (utime 1269 -> 1269 over 3 s), 15 threads
        // asleep, a 40-deep accept backlog, 42 CLOSE_WAIT sockets whose request bytes were
        // never read (`rx_queue` 0x134/0x118/0xF6), and `/api/info` returning nothing for 6 s
        // while a plain TCP connect still succeeded off the backlog. Force-stopping the app -
        // which closes every socket it owns - was the only thing that brought it back.
        //
        // So: `streamDown` is the ONLY permission to subscribe again. It is set by
        // `onStreamDown` (a real EOF/error) and by the watchdog after its probe also fails.
        // The guard must run BEFORE `++streamGen`, because bumping the generation without
        // opening a stream would orphan the live one: its late `onDown` would be ignored as
        // stale and nothing would ever notice it had died.
        if (!streamDown && sse != null) {
            android.util.Log.e(
                "ZAPP",
                "stream already live (gen $streamGen) - not resubscribing",
            )
            return
        }
        // Retire the old stream's verdict *before* cancelling it: its `onDown` arrives on an
        // IO thread and would otherwise be read as a failure of the stream that replaces it,
        // starting a retry loop that immediately replaces a perfectly good subscription.
        val gen = ++streamGen
        // one live stream per client: a second subscribe after a reconnect duplicated every
        // event (two deltas per token, two reloads per idle) - the "app gets weird after
        // closing and reopening" symptom
        streamRetry?.cancel()
        streamRetry = null
        sse?.cancel()
        val c = client ?: return
        lastFrameMs = System.currentTimeMillis()
        streamDown = false
        streamHandshake = false
        android.util.Log.e("ZAPP", "stream subscribing (gen $gen)")
        sse = c.subscribeWithBeats(
            { raw ->
                // ANY frame is proof the socket is real, not just a `: heartbeat`. Measured
                // 23 Nov: during a live turn the server wrote deltas but no comment beats for
                // ~50 s, so `lastFrameMs` (only written by `onBeat`) aged out and the watchdog
                // tore down a perfectly healthy stream mid-answer - four times in three
                // minutes, each one throwing away the deltas that had not been drawn yet.
                // MEASURED GAP, because the 35 s read timeout keeps firing on an idle stream and
                // "the server stopped writing" must not be assumed. A healthy server beats every
                // ~15 s (measured 03:13:53, 03:14:07, 03:14:22), so any gap >= 4 s is the real
                // silence this stream experienced, printed with its own length and payload size.
                run {
                    val gap = System.currentTimeMillis() - lastFrameMs
                    if (gap >= 4_000) {
                        android.util.Log.e(
                            "ZAPP",
                            "stream frame gap ${gap}ms (gen $gen, ${raw.length} B dropped)",
                        )
                    }
                }
                lastFrameMs = System.currentTimeMillis()
                if (streamHeardGen != gen) {
                    streamHeardGen = gen
                    android.util.Log.e("ZAPP", "stream open (gen $gen): first frame received")
                }

                // Desktop's `handshake.received` transition (`machine.ts:77-91`): the socket
                // carrying bytes is NOT the same thing as the subscription being live. That
                // distinction is the 49 s silence measured today - a socket that opened and
                // then carried nothing looked healthy here forever.
                if (!streamHandshake && raw.contains("server.connected")) {
                    streamHandshake = true
                    // `sync { restart: stale }` (`:85`, `:109`) -> `bootstrap.refetch({
                    // cancelRefetch: true })` (`sync.tsx:229-231`). First live has no stale, so
                    // it does not double-read the load that is already running.
                    if (streamStale) {
                        streamStale = false
                        streamEverLive = true
                        android.util.Log.e("ZAPP", "stream live again (gen $gen) - replacing the transcript")
                        settleSync("reconnected")
                    } else {
                        streamEverLive = true
                        android.util.Log.e("ZAPP", "stream live (gen $gen): handshake")
                    }
                }
            // The real quota: the server classifies its own provider refusal and ships
            // `session.step.failed { data.error.type }`. This is the only honest wall
            // signal - an HTTP failure of the prompt call never contains it.
            runCatching { com.zapp.net.guard.QuotaWall.parseFrame(raw) }.getOrNull()
                ?.takeIf { it.wall.isWall }
                ?.let { onQuotaWall(it) }

            // Their four notification triggers (`shell/notifications/notification.tsx:809-867`).
            // Parsed on `type`, not on `raw.contains`, because `session.error` also occurs
            // inside message text and a substring test would fire on a transcript.
            onNotifyEvent(raw)

            // Event names come straight from their `shared-events` union (61 types).
            // Groups matter: each one decides whether the transcript changes, whether the
            // session list changes, or whether something needs an answer from him.
            val streaming = raw.contains("\"session.reasoning.") ||
                raw.contains("\"session.text.") ||
                raw.contains("\"session.tool.") ||
                raw.contains("\"session.step.")
            val terminal = raw.contains("session.idle") || raw.contains("session.error") ||
                raw.contains("message.error") || raw.contains("session.execution.")
            // A busy/idle ping, not content. Measured 22 Nov: `session.status` sat in the
            // `terminal` group, so every ping paid for a full 526 KB transcript read that the
            // server then answered with a copy behind the live stream - the "stop then send".
            val statusOnly = raw.contains("\"session.status")
            val interactive = raw.contains("permission.") || raw.contains("session.permissions") ||
                raw.contains("\"form.") || raw.contains("question.v2.") || raw.contains("\"question.")
            val structural = raw.contains("session.compaction.") ||
                raw.contains("session.retry.scheduled") ||
                raw.contains("session.usage.") ||
                raw.contains("session.agent.selected") ||
                raw.contains("session.model.selected") ||
                raw.contains("session.revert.") ||
                raw.contains("session.skill.activated") ||
                raw.contains("session.message.content.updated") ||
                raw.contains("session.synthetic") ||
                raw.contains("session.instructions.updated")

            if (raw.contains("session.error") || raw.contains("message.error") ||
                raw.contains("session.execution.interrupted")
            ) sending.value = false

            // `review/model.ts:411-420` and `:969-982`: the review follows the filesystem and
            // the end of a turn, not just the moment the panel opens.
            if (raw.contains("filesystem.changed")) onFilesystemChanged(raw)
            if (raw.contains("session.idle")) onSessionIdled(raw)

            // their three shell events carry the whole shell inline ï¿½ no reload needed
            if (raw.contains("\"type\":\"shell.")) onShellEvent(raw)
            else if (raw.contains("session.shell.")) loadShells()

            // permissions: the server says a tool is waiting, so pull the queue at once
            if (interactive) {
                // `permission.replied` first: it settles the cycle, and only then is the queue
                // worth re-reading, or the pull would approve a request already answered.
                if (raw.contains("permission.replied")) {
                    autoRequestId(raw)?.let {
                        autoDispatch(it, com.zapp.net.ui.AutoApproveEvent.Resolved)
                    }
                }
                loadPermissions()
                loadForms()
                loadQuestions()
                loadSavedPermissions()
            }

            // session-list shaped events
            if (raw.contains("session.deleted")) {
                // `runtime.tsx:523` - on `session.deleted`, desktop calls
                // `sessionPreferences.forget(connKey, sessionID)`, so a mute or a hide never
                // outlives the session it was set on.
                runCatching {
                    org.json.JSONObject(raw).optJSONObject("data")?.optString("sessionID")
                }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
                    com.zapp.net.settings.SessionPrefs.forget(ctx, it)
                    com.zapp.net.notify.Notifier.cancelFor(ctx, it)
                }
                refreshSessions()
            } else if (raw.contains("session.archived") || raw.contains("session.unarchived") ||
                raw.contains("session.renamed") || raw.contains("session.moved") ||
                raw.contains("session.forked") || raw.contains("session.created")
            ) refreshSessions()

            // the pending queue changed
            if (raw.contains("session.inbox.")) activeSession.value?.let { loadInbox(it) }

            // text and reasoning stream locally: appending the delta keeps the transcript
            // still, while a reload rebuilt every row several times a second.
            // `session.tool.*` is the same deal: desktop patches the one tool part in its store
            // (`data.ts:2226-2325`), so the row appears and fills without any read at all.
            val streamedText = applyTextDelta(raw)
            val streamedTool = if (streamedText) false else applyToolEvent(raw)
            val streamedLocally = streamedText || streamedTool
            if (streamedLocally) sending.value = true
            if (streamedTool) {
                // the Shells tab is separate server state, not part of the transcript
                refreshJob?.cancel()
                refreshJob = viewModelScope.launch {
                    delay(400)
                    loadShells()
                }
            }
            // Evidence for `GuardState.turnIsLive()`: a real turn emits these, a zombie turn
            // (provider socket died, server still says `isBusy`) emits nothing. Without this
            // the guard held a dead tunnel forever and refused every hop.
            //
            // A frame with a payload is ALSO carriage: it crossed the tunnel from the engine.
            // `session.status` pings are not, and that distinction is what makes the carry clock
            // able to go stale at all - the engine keeps pinging every few seconds while the
            // provider socket behind it is dead. Measured 2026-11-24:
            //
            //   02:38:55.804  reveal: seg=0 msg=msg_209b38c1 start=35 len=35 ... sending=true
            //   02:39:11-41  stream beat gap 15005ms / 9695ms / 15003ms   engine alive
            //   02:41:12.276  stream cadence: 1 deltas -> 2 flushes in 154703ms (0/s in, 0/s drawn)
            //
            // The beats were real and the answer was not. Only a delta or an ending is a
            // measurement of what the provider sent.
            if (streamedLocally || terminal) {
                com.zapp.net.guard.GuardState.noteTurnEvent()
                com.zapp.net.guard.GuardState.noteCarry()
            } else if (!statusOnly && streaming) {
                com.zapp.net.guard.GuardState.noteTurnEvent()
            }

            if (statusOnly) {
                // `session.status` is the server saying busy or idle. It carries no content, so
                // it must never cost a transcript read - that read is what made a live answer
                // jump backwards. Only the composer's own state moves.
                val busy = raw.contains("\"busy\"") || raw.contains("\"running\"")
                sending.value = busy
                if (!busy) refreshJob?.cancel()
            }

            // `data.ts:1212-1246`: on every way a turn can end, desktop sets the session idle
            // and then asks one question - did any tool part stay `streaming`/`running`? If it
            // did, the event stream lost that tool's ending (exactly what a cut tunnel does),
            // so the transcript is invalidated and refetched. Without this the spinner and the
            // half-written tool row stay on screen forever, because no later event will ever
            // mention that part again.
            if (raw.contains("session.execution.succeeded") ||
                raw.contains("session.execution.failed") ||
                raw.contains("session.execution.interrupted")
            ) {
                val stuck = messages.value.any { m ->
                    m.info.role == "assistant" && m.parts.any { p ->
                        p.type == "tool" && (p.state?.status == "streaming" || p.state?.status == "running")
                    }
                }
                if (stuck) {
                    android.util.Log.e("ZAPP", "turn ended with a tool still running - refetching")
                    settleSync("stuck tool")
                }
            }

            if (streaming || terminal || structural) {
                if (terminal) {
                    // The turn really ended: now the server copy IS authoritative, so the
                    // merge guard is released and the final read fills anything that was cut.
                    refreshJob?.cancel()
                    // Not gated on `loadingMessages`: desktop's `refresh()` refuses only when
                    // disposed or disconnected (`data.ts:285-288`), and the end of a turn is
                    // precisely when a read started mid-turn is stale. The newer read bumps
                    // `messagesToken`, so the older one is dropped instead of racing it.
                    activeSession.value?.let { loadMessages(it, force = true) }
                    refreshSessions()
                    settleTurn()
                    // End-of-turn cadence: the proof that the screen kept up with the
                    // stream. Ratio near 1 means the batching is doing nothing; a ratio of
                    // 5-20 means tokens were coalesced and the transcript still landed.
                    if (liveDeltas > 0) {
                        val span = if (liveFirstDeltaMs == 0L) 0L
                        else System.currentTimeMillis() - liveFirstDeltaMs
                        android.util.Log.e(
                            "ZAPP",
                            "turn cadence: $liveDeltas deltas -> $liveFlushes flushes in " +
                                "${span}ms (${if (span > 0) liveDeltas * 1000L / span else 0L}/s in, " +
                                "$liveBoundaries boundaries)",
                        )
                        liveDeltas = 0
                        liveFlushes = 0
                        liveBoundaries = 0
                        liveFirstDeltaMs = 0L
                    }
                } else if (structural) {
                    // Content changed while the turn is live. Desktop patches its store for
                    // the one message the event names; only the frames that genuinely rewrite
                    // the transcript shape are allowed to read the whole thing.
                    refreshJob?.cancel()
                    val mid = affectedMessageId(raw)
                    if (mid != null) {
                        refreshJob = viewModelScope.launch {
                            delay(120)
                            if (!loadingMessages) refreshOneMessage(mid)
                        }
                    } else if (raw.contains("session.compaction.") ||
                        raw.contains("session.revert.") ||
                        raw.contains("session.synthetic")
                    ) {
                        if (!loadingMessages) activeSession.value?.let { loadMessages(it, force = true) }
                    }
                    // everything else (`session.usage.`, `agent.selected`, `model.selected`,
                    // `skill.activated`, `retry.scheduled`) changes no transcript row
                } else if (!streamedLocally) {
                    // tool/step events still need the server's metadata (diffs, exits, args),
                    // but only for the one message they are about. See [refreshOneMessage].
                    refreshJob?.cancel()
                    val mid = affectedMessageId(raw)
                    refreshJob = viewModelScope.launch {
                        delay(180)
                        if (!loadingMessages) {
                            if (mid != null) refreshOneMessage(mid)
                            else activeSession.value?.let { loadMessages(it, force = true); loadInbox(it) }
                        }
                        loadShells() // shell state is server state: keep it in step
                    }
                }
            }
        },
            // The server's `: heartbeat` every ~15 s. Any byte is proof the socket is real.
            {
                // Same gap measurement as the event path. The server's `: heartbeat` every ~15 s
                // is this stream's only idle liveness proof, so the exact gap is what decides
                // whether the 35 s read timeout is right or too tight.
                run {
                    val gap = System.currentTimeMillis() - lastFrameMs
                    if (gap >= 4_000) {
                        android.util.Log.e("ZAPP", "stream beat gap ${gap}ms (gen $gen)")
                    }
                }
                lastFrameMs = System.currentTimeMillis()
                if (streamBeatGen != gen) {
                    streamBeatGen = gen
                    android.util.Log.e("ZAPP", "stream heartbeat (gen $gen)")
                }
            },
            { why -> onStreamDown(gen, why) },
        )
        watchStream(gen)
        // Anything that happened while the socket was dead is only in the server's state.
        // If a turn was live, refetch it so the composer cannot sit on "Working" because the
        // `session.idle` that would have cleared it went to a socket nobody was reading.
        //
        // The handshake path above owns this for a normal reconnect; this stays as the fallback
        // for a server that never sends `server.connected`, which is the only way a live stream
        // could arrive here without `streamHandshake`.
        if (sending.value) activeSession.value?.let { loadMessages(it, force = true) }
    }

    /**
     * Charge the turn that just finished, and move to a proven spare *before* the wall.
     *
     * The ledger only knew about walls: `spentTokens` moved when the provider refused, so the
     * runway was always read one turn too late. the owner, 22 Nov: "I SEE THAT I HAVE 5 SOLID
     * ADDRESS AND STILL NOT CONNECTED TO THE SOLID AND STILL NOT VERIFIED EVEN THO I HAVE A LOT
     * OF VERIFIED ADDRESS".
     *
     * The number to charge is [contextOf]: the free tier measures the whole request, and almost
     * all of it is a cache read (measured `in=334` with `cacheRead=105 984`). So a 100k chat
     * costs 100k of the address's 200k budget **per turn** - which is why a single long chat
     * spends an address in two prompts and why the reserve has to be spent deliberately.
     *
     * When what is left is no longer enough for one more turn of this size, the swap happens
     * here, between turns, where nothing is streaming. That is the only place it can happen
     * without cutting a reply.
     */
    private fun settleTurn() {
        val list = messages.value
        val last = list.lastOrNull { it.info.role == "assistant" && it.info.tokens != null } ?: return
        if (lastChargedMsgId == last.info.id) return
        // A turn older than this is history being replayed, not work this address carried.
        val created = last.info.time?.created ?: 0L
        if (created > 0L && System.currentTimeMillis() - created > 15 * 60_000L) {
            lastChargedMsgId = last.info.id
            return
        }
        val cost = contextOf(list)
        if (cost <= 0) return
        lastChargedMsgId = last.info.id
        viewModelScope.launch {
            runCatching {
                val pool = com.zapp.net.guard.WarpPool(ctx)
                val reg = pool.usable() ?: return@launch
                pool.charge(reg, cost)
                val spent = pool.all().firstOrNull { it.id == reg.id }?.spentTokens ?: cost
                val left = (com.zapp.net.guard.WarpPool.BUDGET - spent).coerceAtLeast(0L)
                android.util.Log.e(
                    "ZAPP",
                    "ledger: ${reg.id.take(8)} charged ${cost / 1000}k -> spent ${spent / 1000}k, " +
                        "${left / 1000}k left of ${com.zapp.net.guard.WarpPool.BUDGET / 1000}k",
                )
                // One more turn of this size would cross the budget: move while idle.
                if (left < cost) {
                    android.util.Log.e("ZAPP", "ledger: next turn would wall - swapping while idle")
                    com.zapp.net.guard.RotationEngine(ctx).swapToFreshSpare("budget")
                }
            }.onFailure { android.util.Log.e("ZAPP", "ledger: settle failed: $it") }
        }
    }

    /** Last assistant message charged, so a replayed `session.idle` cannot double-charge. */
    private var lastChargedMsgId: String? = null

    /**
     * The stream died (EOF, transport error, HTTP failure).
     *
     * OkHttp's `EventSource` never retried on its own, and neither did this: measured
     * 2026-11-20 03:10, the keeper rebuilt the TUN at 03:10:29 while a stream was open across
     * that socket, and nothing ever came back - the transcript froze mid-answer. Desktop's
     * event stream reconnects; this is that behaviour, with the probe kept in front of it so
     * a dead tunnel produces one quiet retry loop instead of a resubscribe storm.
     */
    /**
     * Wait for the VPN service's own egress reading to land on [reg].
     *
     * `RotationEngine.hopNow` returns as soon as the identity is installed - deliberately, with
     * "no register, no verify wait" - so the returned registration proves only that a /64 was
     * written into the tunnel, not that anything can leave through it. `WarpPool.setEgress` is
     * written by the service after it has actually reached Cloudflare through that address
     * (`ZappVpnService` line ~843), so a non-blank `egress64` here is a measured exit.
     *
     * Returns null when the identity is retired or the budget expires - in both cases there is
     * no proven exit and nothing should be sent on it.
     */
    private suspend fun awaitProvenEgress(
        reg: com.zapp.net.guard.WarpReg,
        budgetMs: Long,
    ): com.zapp.net.guard.WarpReg? {
        val pool = com.zapp.net.guard.WarpPool(ctx)
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            val row = runCatching { pool.all().firstOrNull { it.id == reg.id } }.getOrNull()
                ?: return null
            if (!row.egress64.isNullOrBlank()) return row
            kotlinx.coroutines.delay(700)
        }
        return null
    }

    /**
     * Does the engine actually ANSWER, on a 3 s budget?
     *
     * A TCP connect is never the verdict: a parked Bun process keeps its listen socket, so the
     * kernel completes every handshake from the accept backlog and a client sees "connected"
     * with no reply. Measured on this phone 2026-11-23 21:42 while the engine was parked:
     * `connect ok=10 fail=0 avg=29ms` and `GET /api/info` returned nothing for 6 040 ms.
     *
     * `Unauthorized` counts as answering: the 401 body is proof the HTTP stack ran.
     */
    private suspend fun probeAnswers(): Boolean {
        val base = serverBase.value
        val token = runCatching { com.zapp.net.onboarding.Onboarding.ensureToken(ctx) }
            .getOrDefault("")
        val health = com.zapp.net.api.ServerHealth.verify(
            base,
            token,
            timeoutMs = 3_000L,
            retryCount = 0,
        )
        return health is com.zapp.net.api.ServerHealth.Result.Healthy ||
            health is com.zapp.net.api.ServerHealth.Result.Unauthorized
    }

    private fun onStreamDown(gen: Int, why: String) {
        if (gen != streamGen) return // an old stream reporting late; a newer one owns the truth
        streamDown = true
        // `machine.ts:119-137`: any non-connected status moves to `disconnected{ stale: true }`
        // and fires `invalidate`. The next live read replaces the transcript.
        if (streamEverLive) streamStale = true
        android.util.Log.e("ZAPP", "stream down (gen $gen): $why")
        streamRetry?.cancel()
        streamRetry = viewModelScope.launch {
            var wait = 700L
            // Consecutive failed reads on loopback. Reset by any answer, and only used to ask
            // Termux for a fresh engine - never to give up on the subscription.
            var unreachable = 0
            // Never gated on `connected`.
            //
            // the owner, 22 Nov: "WHY I KEEP GETTING A CONNECTION LOST AFTER A LONG RUN ... THE AI
            // CANT REPLY AGAIN AND I NEED TO DISCONNECT AND RECONNECT". The loop condition was
            // `connected.value && ...`, so if the stream died while a tunnel rebuild had the
            // probe down, the body never ran once, the coroutine ended, and **nothing ever
            // resubscribed**: the answer existed on the server and the app sat on a dead socket
            // until he cycled the VPN by hand. `connected` is a result of this loop now, never a
            // precondition for it.
            while (isActive && gen == streamGen) {
                delay(wait)
                if (gen != streamGen) return@launch
                // The same failure that cut the stream usually kills plain HTTP too, and the
                // tunnel swap takes a couple of seconds to be carrying again. Only resubscribe
                // once a cheap request proves the server answers.
                //
                // The probe is `/api/info` under a HARD 3 s ceiling, not a session-list read.
                // A parked engine still completes TCP handshakes from its accept backlog - the
                // 21:42 measurement - so the read is the only honest signal, and the session
                // list's own 30 s deadline made three attempts cost ~90 s before anything was
                // asked of serve.sh. 3 s proves the same thing and asks in 9 s.
                val answering = runCatching {
                    probeAnswers()
                }.getOrDefault(false)
                if (!answering) {
                    android.util.Log.e("ZAPP", "stream retry: server unreachable, next in ${wait}ms")
                    wait = (wait * 2).coerceAtMost(8_000L)
                    // AN ENGINE THAT LIVES BUT ANSWERS NOTHING NEVER COMES BACK BY ITSELF.
                    //
                    // Measured 23 Nov 20:10:44-20:13:06 on v45. The engine process was alive
                    // (`opencode serve --port 4096` at 2h02m uptime, `zapp_lane` beside it) and
                    // `/sdcard/zapp_boot.log` ended at `serve: guard lane attached` with no
                    // `serve ready` - the Bun event loop was blocked, so loopback HTTP stopped
                    // answering too. This loop then retried 700/1400/2800/5600 ms forever with
                    // no restart request, because `ensureServer` was wired only to the session
                    // list. The app sat dead until Termux was cycled by hand.
                    //
                    // Each failed read here costs the client's own 30 s read timeout (measured
                    // 20:11:14 -> 20:11:46 -> 20:12:19 -> 20:12:54), so this threshold is ~90 s
                    // of a server that cannot answer a one-session list. A tunnel rebuild does
                    // not produce that: the engine is on 127.0.0.1 and never crosses the TUN.
                    // `ensureServer` keeps its own 45 s cooldown, so this cannot become a loop.
                    unreachable++
                    if (unreachable == ENGINE_RESTART_AFTER) {
                        android.util.Log.e(
                            "ZAPP",
                            "stream retry: engine unresponsive x$unreachable - asking serve.sh to replace it",
                        )
                        ensureServer("stream retry: $unreachable silent reads", force = true)
                    }
                    continue
                }
                unreachable = 0
                connected.value = true
                android.util.Log.e("ZAPP", "stream retry: server answered, resubscribing")
                listen()
                return@launch
            }
        }
    }

    /**
     * Heartbeat watchdog, the half-open detector.
     *
     * A socket whose packets stopped being routed - the exact state a TUN rebuild leaves -
     * never throws, so `onDown` never fires. But a quiet socket is NOT proof the server is
     * gone, and 23 Nov is the measurement that proves it: with a 45 s watchdog and a 35 s
     * socket deadline, the app spent an hour killing healthy streams, resubscribing, and
     * killing them again. The engine answered `/api/info` in **4-5 ms** between the cuts.
     *
     * So there are two signals before a verdict, the same rule the guard lane lives by:
     *   1. the stream has been silent past [SILENT_STREAM_MS], and
     *   2. a separate `/api/info` probe is also unreachable.
     * If the probe answers, the stream is kept and only its clock is re-dated. The official
     * desktop client does the same thing by construction - it has no read deadline at all and
     * decides liveness from frame activity (`shared-events`, `onActivity()` per frame).
     *
     * The silence ceiling still exists so a genuinely half-open socket cannot be kept forever:
     * past 3x the threshold the subscription is replaced even if the server answers.
     */
    private fun watchStream(gen: Int) {
        viewModelScope.launch {
            while (isActive && gen == streamGen) {
                delay(10_000)
                if (gen != streamGen) return@launch
                if (streamDown) return@launch // onStreamDown already owns the recovery
                val silent = System.currentTimeMillis() - lastFrameMs
                if (silent <= SILENT_STREAM_MS) continue

                val base = serverBase.value
                val token = runCatching { com.zapp.net.onboarding.Onboarding.ensureToken(ctx) }
                    .getOrDefault("")
                val health = runCatching {
                    com.zapp.net.api.ServerHealth.verify(
                        base,
                        token,
                        timeoutMs = 6_000L,
                        retryCount = 0,
                    )
                }.getOrNull()
                val answering = health is com.zapp.net.api.ServerHealth.Result.Healthy ||
                    health is com.zapp.net.api.ServerHealth.Result.Unauthorized

                if (answering && silent < SILENT_STREAM_MS * 3) {
                    android.util.Log.e(
                        "ZAPP",
                        "stream quiet ${silent}ms (gen $gen) but the server answers " +
                            "(${health?.javaClass?.simpleName}) -> keeping the stream",
                    )
                    lastFrameMs = System.currentTimeMillis()
                    continue
                }

                android.util.Log.e(
                    "ZAPP",
                    "stream silent ${silent}ms (gen $gen, probe=${health?.javaClass?.simpleName}) " +
                        "-> resubscribing",
                )
                // Silence AND a probe that cannot get an answer is a parked engine, and a parked
                // engine never comes back by itself: measured 2026-11-23 21:42, Bun sat at zero
                // cpu with a 40-deep accept backlog. Only `serve.sh` can replace that process, so
                // ask it now instead of waiting for the read timeout to expire first.
                if (!answering) ensureServer("stream silent + probe unreachable", force = true)
                streamDown = true
                // Half-open is a drop too: the socket never said anything, so whatever the
                // server wrote in that window is only in its own state (`machine.ts:119`).
                if (streamEverLive) streamStale = true
                listen()
                return@launch
            }
        }
    }

    override fun onCleared() {
        sse?.cancel()
        streamRetry?.cancel()
        syncSettle?.cancel()
        client?.events?.cancel()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The agent's hands. Bound to 127.0.0.1 only, keyed, and started with the app so a
        // chat that asks for a phone action never finds a dead listener.
        runCatching { com.zapp.net.mcp.McpServer.start(this) }
            .onFailure { android.util.Log.e("ZAPP", "mcp start failed: $it") }
        // Agent replies are notifications; without this the app can declare all it likes and
        // every post is dropped (`granted=false`, appop `ignore` - measured 18:31). Asked once:
        // the flag stops a second prompt after a denial, because Android only shows the dialog
        // twice anyway and a re-ask on every launch is nagging, not information.
        ensureNotificationPermission()
        // Debug-only: prove the quota classifier reads the server's own wire shapes.
        // Logged so the device itself carries the evidence.
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            val r = runCatching { com.zapp.net.guard.QuotaWall.selfTest() }
                .getOrElse { "threw: ${it.message}" }
            android.util.Log.i(
                "ZAPP",
                if (r.isBlank()) "quota self-test: PASS (7 cases)" else "quota self-test: FAIL $r",
            )
        }
        setContent { Root() }
        // device-test hook: `am start -n com.zapp.net/.MainActivity --es server_base <url> --es pair_url <link>`
        intent.getStringExtra("pair_url")?.let { link ->
            val base = intent.getStringExtra("server_base") ?: com.zapp.net.settings.PHONE_SERVER
            val vm = androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java]
            vm.pairAndConnect(base, link.trim())
        }
        handleVpnIntent(intent)

        // Nothing else revives the tunnel after the process is replaced. Measured
        // 2026-11-20 02:42:38: right after an install, the activity came back, `reconnect OK`
        // was logged, and the egress was still `2001:4455:59c:4700:... warp=off` - the guard
        // service was never started, so the only component that can ask for a tunnel was
        // never alive. The app then looks connected while every prompt leaves through the
        // carrier, which the provider refuses exactly like a quota wall.
        if (com.zapp.net.settings.GuardPrefs.guardOn(this)) {
            runCatching {
                startForegroundService(
                    android.content.Intent(
                        this,
                        com.zapp.net.guard.GuardForegroundService::class.java,
                    ),
                )
            }.onFailure { android.util.Log.e("ZAPP", "guard start from activity: $it") }
        }
    }

    /**
     * `am start` against a running task delivers to the top-most instance instead of
     * creating one, so onCreate alone never sees the second hop command. Both entry
     * points must handle it or the hook silently does nothing.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVpnIntent(intent)
    }

    /**
     * Android 13+ drops every notification until `POST_NOTIFICATIONS` is granted, and the
     * dialog only appears if the app asks. Measured before this existed: `granted=false`,
     * appop `ignore`, and a test post never reached the shade.
     */
    private fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val sp = getSharedPreferences("zapp_session_prefs", MODE_PRIVATE)
        if (sp.getBoolean("notif_asked", false)) return
        sp.edit().putBoolean("notif_asked", true).apply()
        androidx.core.app.ActivityCompat.requestPermissions(
            this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7001,
        )
    }

    /**
     * Device hook for the tunnel: `--es vpn start` brings WARP up (Termux-only),
     * `--es vpn hop` registers a fresh /64 and swaps to it. The service is not
     * exported, so this activity is the only outside handle on it.
     */
    private fun handleVpnIntent(intent: android.content.Intent?) {
        // Unconditional: this line is what tells us an intent arrived at all. Without it a
        // missing extra and a missing callback look identical in the log (measured 04:23).
        android.util.Log.e("ZAPP", "intent: vpn=${intent?.getStringExtra("vpn")} say=${intent?.getStringExtra("say")} extras=${intent?.extras?.keySet()}")
        when (intent?.getStringExtra("vpn")) {
            "start" -> startTunnel()
            "hop" -> androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java]
                .hopTunnel()
            // `--es vpn hot` - prove the UAPI rekey path and time it, without waiting for a
            // real wall. The service is the only component that may touch the socket, so this
            // only forwards the action.
            "hot" -> {
                val id = intent.getStringExtra("reg_id")
                startService(
                    android.content.Intent(this, com.zapp.net.vpn.ZappVpnService::class.java)
                        .setAction(com.zapp.net.vpn.ZappVpnService.ACTION_HOT_SWAP)
                        .putExtra(com.zapp.net.vpn.ZappVpnService.EXTRA_REG_ID, id ?: "self"),
                )
            }
            // `--es vpn stop` - drop the tunnel from outside, so the "a down tunnel is never
            // deferred" rule can be proven with a live turn running. Without it the only way
            // to reach that state is to be mid-reply when something kills the tunnel.
            "stop" -> startService(
                android.content.Intent(this, com.zapp.net.vpn.ZappVpnService::class.java)
                    .setAction(com.zapp.net.vpn.ZappVpnService.ACTION_STOP),
            )
            // `--es vpn prove` - wear one unproven spare so the verify loop measures it.
            // Without this the reserve only proves itself on a 45 s keeper tick, which makes
            // the fill take minutes to observe.
            "prove" -> com.zapp.net.guard.GuardState.appScope.launch {
                val r = runCatching {
                    com.zapp.net.guard.RotationEngine(this@MainActivity).proveSpare()
                }.getOrNull()
                android.util.Log.e(
                    "ZAPP",
                    "PROVE: " + (r?.let { "wearing ${it.id.take(8)}" } ?: "nothing to prove"),
                )
            }
            // `--es vpn pool` - audit every registration in the reserve.
            //
            // The panel shows a count; a count cannot tell a real /64 from a duplicate, a fresh
            // proof from a four-hour-old one, or a registration that was never verified at all.
            // Measured 22 Nov 10:07: the warm swap installed a registration that carried nothing
            // and the service had to discard it a second later - which means the pool was
            // handing out dead addresses while reporting them as proven. This dump is the
            // evidence that says which ones and why.
            "pool" -> com.zapp.net.guard.GuardState.appScope.launch {
                val pool = com.zapp.net.guard.WarpPool(this@MainActivity)
                val now = System.currentTimeMillis()
                val all = pool.all()
                val solidIds = pool.solid().map { it.id }.toSet()
                val readyIds = pool.ready().map { it.id }.toSet()
                android.util.Log.e(
                    "ZAPP",
                    "POOL: ${all.size} registration(s), ${solidIds.size} solid, " +
                        "${readyIds.size} ready, runway ${pool.runway() / 1000}k",
                )
                all.sortedByDescending { it.verifiedAtMs }.forEach { r ->
                    val age = if (r.verifiedAtMs == 0L) "never" else "${(now - r.verifiedAtMs) / 60_000}m"
                    // `solid` = this row's own reading is good. `counted` = it is the one that
                    // owns its /64. They differ when two registrations report the same prefix:
                    // measured 22 Nov 11:11, the address actually being worn printed
                    // `solid=false` while a parked twin printed `solid=true`, which reads as
                    // "not connected to a solid one" when the opposite is true - the /64 is
                    // counted once, under whichever registration the pool saw first.
                    val own = r.verifiedAtMs > 0L &&
                        r.spentTokens < com.zapp.net.guard.WarpPool.BUDGET &&
                        r.peerPubHex.length == 64 &&
                        (now - r.verifiedAtMs) < com.zapp.net.guard.WarpPool.STALE_MS
                    android.util.Log.e(
                        "ZAPP",
                        "POOL  ${r.id.take(8)}  egress=${r.egress64 ?: "-"}  " +
                            "v6=${com.zapp.net.guard.WarpPool.prefix64Of(r.v6) ?: r.v6}  " +
                            "proof=$age  spent=${r.spentTokens / 1000}k  " +
                            "solid=$own counted=${r.id in solidIds} ready=${r.id in readyIds}",
                    )
                }
            }
            // `--es vpn egress` - one real egress reading through the lane, logged. The only
            // way to answer "did the identity actually move" without tapping the panel.
            "egress" -> com.zapp.net.guard.GuardState.appScope.launch {
                val r = runCatching {
                    com.zapp.net.termux.EgressProbe.measure(this@MainActivity, 20_000)
                }.getOrNull()
                android.util.Log.e(
                    "ZAPP",
                    "EGRESS probe: " + (
                        r?.let { "ip=${it.ip} warp=${it.warp} /64=${it.v6Prefix}" }
                            ?: "no reading"
                        ),
                )
            }
            null -> Unit
        }
        val session = intent?.getStringExtra("session")
        if (!session.isNullOrBlank()) {
            android.util.Log.e("ZAPP", "intent: session=$session")
            androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java].openById(session)
        }
        // Debug builds only: prove the notification path end to end without waiting for a real
        // agent event, which would spend a request.
        //   am start -n com.zapp.net/.MainActivity --es testnotify ses_test
        val testNotify = intent?.getStringExtra("testnotify")
        if (!testNotify.isNullOrBlank()) {
            val debuggable =
                (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (debuggable) {
                android.util.Log.e("ZAPP", "testnotify: $testNotify")
                com.zapp.net.notify.Notifier.post(
                    this, testNotify, "Response ready", "test notification",
                    com.zapp.net.notify.Notifier.CH_AGENT, false,
                )
            } else {
                android.util.Log.e("ZAPP", "testnotify: refused (release build)")
            }
        }
        // Debug builds only: push text through the composer's own send(), which is the path a
        // keystroke takes. Exists so the local-command router can be proven end to end without
        // tapping the screen the owner is using.
        //   am start -n com.zapp.net/.MainActivity --es say 'status'
        val say = intent?.getStringExtra("say")
        if (!say.isNullOrBlank()) {
            val debuggable =
                (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (debuggable) {
                android.util.Log.e("ZAPP", "say: $say")
                androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java].send(say)
            } else {
                android.util.Log.e("ZAPP", "say: refused (release build)")
            }
        }
    }

    /** VpnService.prepare() returns null once the user has ever approved a tunnel. */
    private fun startTunnel() {
        if (android.net.VpnService.prepare(this) == null) {
            startService(
                android.content.Intent(this, com.zapp.net.vpn.ZappVpnService::class.java)
                    .setAction(com.zapp.net.vpn.ZappVpnService.ACTION_START),
            )
        } else {
            startActivity(android.net.VpnService.prepare(this))
        }
    }
}

@Composable
private fun Root(vm: MainViewModel = viewModel()) {
    ZappTheme {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        // Consent gates everything: nothing reads the address until step 1 has been
        // accepted, so this check must sit above every other branch, including the
        // connect screen. It is read once per composition because the flag only ever
        // moves forward (`acceptConsent` -> `markTermuxReady`).
        var onboarded by remember {
            mutableStateOf(
                com.zapp.net.onboarding.Onboarding.consented(ctx) &&
                    com.zapp.net.onboarding.Onboarding.termuxReady(ctx),
            )
        }
        if (!onboarded) {
            com.zapp.net.onboarding.OnboardingScreen(onDone = { onboarded = true })
            return@ZappTheme
        }
        val connected by vm.connected.collectAsState()
        val booting by vm.booting.collectAsState()
        val gate by vm.gate.collectAsState()
        val status by vm.status.collectAsState()
        val scope = androidx.compose.runtime.rememberCoroutineScope()

        // The standard-user card, once per process start - which is exactly "every time
        // they open the app". `remember` survives recomposition and dies with the process,
        // so a rotation does not re-show it and a cold start always does.
        var notice by remember { mutableStateOf(true) }

        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().then(
                    if (notice && android.os.Build.VERSION.SDK_INT >= 31) {
                        // Real blur of the live UI. Below API 31 this modifier is a no-op,
                        // which is why [StandardUserOverlay] also carries a scrim.
                        Modifier.blur(
                            radius = 18.dp,
                            edgeTreatment = BlurredEdgeTreatment.Unbounded,
                        )
                    } else {
                        Modifier
                    },
                ),
            ) {
                when {
                    connected -> {
                        var tab by remember { mutableStateOf(0) }
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) {
                                when (tab) {
                                    0 -> ChatScreen(vm)
                                    1 -> GuardScreen()
                                    else -> ShellScreen()
                                }
                            }
                            ZappTabBar(tab) { tab = it }
                        }
                    }
                    // Only while a saved login is actually being redeemed. With nothing saved,
                    // `booting` is cleared synchronously inside `tryReconnect`, so the connect
                    // screen is never delayed.
                    booting -> BootFrame(
                        // The engine wait is real work and it is long; showing "checking your
                        // saved login" over it is a lie that reads as a hang.
                        status.ifBlank { "checking your saved login" },
                    )
                    gate is GateState.Denied -> DeniedFrame(onJoinAgain = { vm.joinAgain() })
                    gate is GateState.Unreachable -> UnreachableFrame(
                        detail = (gate as GateState.Unreachable).detail,
                        onRetry = { scope.launch { vm.verifyServer() } },
                        onJoinAgain = { vm.joinAgain() },
                    )
                    else -> ConnectScreen(vm)
                }
            }

            com.zapp.net.ui.StandardUserOverlay(
                visible = notice,
                onDismiss = { notice = false },
                onDiscord = {
                    notice = false
                    com.zapp.net.ui.openDiscordInvite(
                        ctx,
                        com.zapp.net.onboarding.Onboarding.discordInvite(ctx),
                    )
                },
            )
        }
    }
}

/** Slim custom tab bar ï¿½ two targets, violet only where it matters. */
@Composable
private fun ZappTabBar(tab: Int, onTab: (Int) -> Unit) {
    // The bar owns the navigation-bar inset: it is the bottom-most surface, so the gesture pill
    // sits inside its background instead of on top of the row. Chat no longer pads for it.
    Column(Modifier.fillMaxWidth().background(Surface).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        Row(
            Modifier.fillMaxWidth().height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabItem(
                "Chat", com.zapp.net.ui.OcIcons.Chat, tab == 0,
                Modifier.weight(1f),
            ) { onTab(0) }
            // `Bolt` read as a settings toggle, not a shield. Their guard is a gate; the icon is
            // now a shield - one shape, no lightning.
            //
            // 22 Nov, "THE ICONS": these three were `Icons.AutoMirrored.Filled.Chat`,
            // `Icons.Filled.Shield` and `Icons.Filled.Terminal` - stock Material at Material's
            // stroke weight, next to a screen full of hand-built 16-unit glyphs. They are now the
            // catalogue's own paths (`comment`, `shield`, `terminal`) at the same 1-unit stroke.
            TabItem(
                "Guard", com.zapp.net.ui.OcIcons.Shield, tab == 1,
                Modifier.weight(1f),
            ) { onTab(1) }
            TabItem(
                "Shell", com.zapp.net.ui.OcIcons.Terminal, tab == 2,
                Modifier.weight(1f),
            ) { onTab(2) }
        }
    }
}

@Composable
private fun TabItem(
    label: String,
    icon: ImageVector,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    // 56 dp row, 38 dp of content: 18 icon + 1 + 12 text + 3 + 2 indicator. It fits with room to
    // breathe, which the old 48 dp row did not - its children measured 50 dp and overflowed.
    Column(
        modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon, label,
            tint = if (active) Color.White else Color.White.copy(alpha = 0.42f),
            modifier = Modifier.size(18.dp),
        )
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            color = if (active) Color.White else Color.White.copy(alpha = 0.42f),
            modifier = Modifier.padding(top = 1.dp),
        )
        Box(
            Modifier
                .padding(top = 3.dp)
                .size(width = 16.dp, height = 2.dp)
                .background(
                    if (active) Color.White else Color.Transparent,
                    RoundedCornerShape(1.dp),
                ),
        )
    }
}
