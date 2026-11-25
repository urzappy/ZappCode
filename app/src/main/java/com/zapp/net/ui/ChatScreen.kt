package com.zapp.net.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.DismissibleNavigationDrawer
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.MainViewModel
import com.zapp.net.api.InboxItem
import com.zapp.net.api.MessageInfo
import com.zapp.net.api.MessageWithParts
import com.zapp.net.api.OutAttachment
import com.zapp.net.api.Part
import com.zapp.net.api.PartFile
import com.zapp.net.api.TodoItem
import com.zapp.net.ui.theme.Accent
import com.zapp.net.ui.theme.AccentContainer
import com.zapp.net.ui.theme.AccentSoft
import com.zapp.net.ui.theme.Bg
import com.zapp.net.ui.theme.Border
import com.zapp.net.ui.theme.Danger
import com.zapp.net.ui.theme.DiffAdd
import com.zapp.net.ui.theme.DiffAddBg
import com.zapp.net.ui.theme.DiffBarAdd
import com.zapp.net.ui.theme.DiffBarDel
import com.zapp.net.ui.theme.DiffBg
import com.zapp.net.ui.theme.DiffDel
import com.zapp.net.ui.theme.DiffDelBg
import com.zapp.net.ui.theme.DiffHunkBg
import com.zapp.net.ui.theme.DiffHunkText
import com.zapp.net.ui.theme.DiffNum
import com.zapp.net.ui.theme.Layer1
import com.zapp.net.ui.theme.Layer2
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.Link
import com.zapp.net.ui.theme.RingArc
import com.zapp.net.ui.theme.RingTrack
import com.zapp.net.ui.theme.Surface
import com.zapp.net.ui.theme.Surface2
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextFaint
import com.zapp.net.ui.theme.IconBase
import com.zapp.net.ui.theme.PressedOverlay
import com.zapp.net.ui.theme.TextMid
import com.zapp.net.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ZappCode's community invite.
 *
 * The same code the onboarding card writes into prefs (`Onboarding.kt:127 DEFAULT_INVITE`) and
 * the same one the Discord invite card renders. One code, one place to change it.
 */
internal const val DISCORD_INVITE = "RQ6NVspT2u"

/* The face � OpenCode's shape, redrawn compact for a phone:
   tab strip -> breadcrumb strip -> conversation -> composer.
   No bubbles for the agent, no dead air, brightness is the only accent. */

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(vm: MainViewModel) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    // Their menubar is a popover over the app, not a route. Ours opens inside the drawer, so
    // the same tap that opens the sidebar reaches it - no second surface to find.
    var appMenuOpen by remember { mutableStateOf(false) }
    // ZAPP's own About sheet. The Help menu used to open opencode.ai/docs, OpenCode's Discord
    // and OpenCode's GitHub issue tracker - none of which are ours, and all of which would send
    // his users to somebody else's support desk. This replaces them with a real About screen.
    var aboutOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sessions by vm.sessions.collectAsState()
    val importing by vm.importing.collectAsState()
    val active by vm.activeSession.collectAsState()
    val drafting by vm.drafting.collectAsState()
    val activeDraft by vm.activeDraft.collectAsState()
    val openTabs by vm.openTabs.collectAsState()
    val messages by vm.messages.collectAsState()
    val sending by vm.sending.collectAsState()
    val liveId by vm.liveMsgId.collectAsState()
    val totals by vm.sessionTotals.collectAsState()
    val chatStatus by vm.status.collectAsState()
    val chatStatusFailed by vm.statusFailed.collectAsState()
    var search by remember { mutableStateOf("") }
    var ext by remember { mutableStateOf(ExtPanel.NONE) }
    var showSessions by remember { mutableStateOf(false) }
    // Set the moment he closes the board. Without it the "no tab is open, so show the board"
    // rule below re-opened it behind his back every time the session list refreshed - he would
    // close it, go to Guard, come back, and it was open again. the owner, 2026-11-24: *"PAG CLOSE KO
    // SYA ... PAG BALIK NAKA OPEN PA RIN"*.
    var boardDismissed by remember { mutableStateOf(false) }
    var showFinder by remember { mutableStateOf(false) }
    // `#review-panel` is one side region with a tab strip, not four separate overlays. `paneTabs`
    // is their **registry** (Review, Browser, Terminal, one tab per opened file). The `new:<serial>`
    // placeholder a `+` makes lives in `region`, never in this list - the same split their
    // `panes[].ids` and the tab registry have.
    var paneTabs by remember { mutableStateOf<List<PaneTab>>(emptyList()) }
    var paneActive by remember { mutableStateOf<String?>(null) }
    var paneRecent by remember { mutableStateOf<List<SidePanel.RecentEntry>>(emptyList()) }
    // `browser:<n>` -> the page it is on. Their key prefix is `browser:`.
    var browserUrls by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // Their file plugin's `fileTree.tab`: `changes` (the session's changed files) or `all`
    // (the tree). `current-BpUjPsAD.js` @ 81 395 keeps exactly those two values.
    var fileTab by remember { mutableStateOf("all") }
    // The Open file pane's own back/forward, over the directories visited in this run.
    var dirHistory by remember { mutableStateOf(listOf("")) }
    var dirAt by remember { mutableIntStateOf(0) }

    /** Descend (or go up) and push the directory onto the pane's history. */
    fun visitDir(target: String?) {
        vm.loadFiles(target)
        val d = target.orEmpty()
        val kept = dirHistory.take(dirAt + 1)
        if (kept.lastOrNull() == d) return
        val next = kept + d
        dirHistory = next
        dirAt = next.lastIndex
    }
    // Their region is open/closed independently of whether it holds a tab: `sideOpen && empty`
    // opens one *empty* tab, which is what the four-item launcher is drawn in.
    var paneOpen by remember { mutableStateOf(false) }
    // `#review-panel[data-focused]`: true while the panel is the surface holding focus. Their CSS
    // dims a selected tab's title to `--v2-text-text-muted` when it is not.
    var paneFocused by remember { mutableStateOf(false) }
    // The ported region reducer, driving the placeholder tabs exactly as `QS` does.
    var region by remember { mutableStateOf(SidePanel.State()) }
    val sidePane = region.panes.getValue(SidePanel.Pane.SIDE)
    // `Wa.Tabs value={shownNewTab ?? pane.active() ?? "empty"}`
    val newTabChosen = sidePane.chosen != null
    val reviewTabOpen = paneTabs.any { it.kind == SidePanel.Kind.REVIEW }
    // Their Settings surface (`settings/surface.tsx`) is a full-window route; on the phone it
    // is a full-screen overlay reached from the drawer, which is our sidebar.
    var showSettings by remember { mutableStateOf(false) }
    // `null` opens Settings on its own root; a page opens it straight into that page, which is how
    // `Manage MCP servers` reaches the Extensions page.
    var settingsStart by remember { mutableStateOf<Page?>(null) }
    var showContext by remember { mutableStateOf(false) }
    // `settings/model.tsx:303-308` - the three display settings ZAPP can honour, read once
    // here so a change in the Settings screen is visible in the transcript immediately.
    val timelineDetail by com.zapp.net.settings.DisplayPrefs.detail.collectAsState()
    val showTurnDiffs by com.zapp.net.settings.DisplayPrefs.turnDiffs.collectAsState()
    val foldUserMessages by com.zapp.net.settings.DisplayPrefs.foldUser.collectAsState()
    // their Changes panel has a raw-diff view; fetch it when the panel opens, not before
    // Desktop syncs the location's VCS on session entry and after a send
    // (`home/sessions/controller.tsx:559`), so the panel reloads with the tab.
    val reviewFiles by vm.reviewFiles.collectAsState()
    val reviewModes by vm.reviewModes.collectAsState()
    val reviewMode by vm.reviewMode.collectAsState()
    val reviewNoVcs by vm.reviewNoVcs.collectAsState()
    val reviewLoading by vm.reviewLoading.collectAsState()
    val reviewOpenFiles by vm.reviewOpenFiles.collectAsState()
    val reviewActiveFile by vm.reviewActiveFile.collectAsState()
    val reviewError by vm.reviewError.collectAsState()
    // `review/model.ts:425-441`: opening the side region loads the diffs once, and the model
    // keeps following the filesystem and the end of a turn while it shows.
    androidx.compose.runtime.LaunchedEffect(reviewTabOpen) { vm.setReviewOpen(reviewTabOpen) }
    androidx.compose.runtime.LaunchedEffect(reviewTabOpen, active?.id) { if (reviewTabOpen) vm.loadReview() }
    var showShells by remember { mutableStateOf(false) }
    // `Reply` from a message menu drops the quote into the composer
    var replySeed by remember { mutableStateOf<String?>(null) }
    // skills attached to the next prompt: their `skills[]` field, our chips
    var attachedSkills by remember { mutableStateOf<List<com.zapp.net.api.SkillPick>>(emptyList()) }
    // PC: background is not a per-turn button. There it is a keybind (`session.background`,
    // "Move to background") and one setting: `backgroundActivity` = "Show running subagents
    // and shells above the composer" (default `left`). The timeline detaches by itself as
    // soon as you scroll away from the running turn; scrolling back re-attaches it.
    val shells by vm.shells.collectAsState()
    LaunchedEffect(sending) { if (sending) vm.loadShells() }
    // the panel is a live view of /api/shell, not a snapshot from when it was opened
    LaunchedEffect(showShells) {
        while (showShells) {
            vm.loadShells()
            kotlinx.coroutines.delay(900)
        }
    }
    // only a brand-new prompt re-attaches the timeline; a refresh never does
    val lastUserId = messages.lastOrNull { it.info.role == "user" }?.info?.id
    val turns = remember(messages) { buildTurns(messages) }
    val hasOlder by vm.hasOlder.collectAsState()
    val clipboard = LocalClipboardManager.current
    val appCtx = LocalContext.current
    // a deep file card reaches the viewer through this hook instead of a long param chain
    SideEffect { FileOpener.open = { p -> vm.openFile(p) } }
    SideEffect { ShellDetacher.detach = { c, id -> vm.detachShell(id, c) } }
    val fileView by vm.fileView.collectAsState()
    val fileViews by vm.fileViews.collectAsState()
    val fileRows by vm.fileRows.collectAsState()
    val fileDir by vm.fileDir.collectAsState()

    // Their `file:open:<path>` tabs. A file opened from a review row or a timeline card becomes
    // its own tab; before this it replaced whatever the single viewer was showing.
    //
    // the owner, 2026-11-24: *"YUNG NA CLICK KO NA LINK LALABAS ULIT YUNG PANEL NA REOPEN SYA KET
    // NA CLOSE KUNA"*. This effect raised the pane for **every** file already in `fileViews`,
    // and `fileViews` lives in the view model while `paneOpen`/`paneTabs` only live in this
    // screen. So: open a file link, close the panel (visibility only - the tab survives, as it
    // does on their side), leave for Guard, come back, and the remount saw "a file with no tab"
    // and reopened the panel by itself. On their side the tab registry and the panel's
    // visibility are separate pieces of state, and a remount never re-raises a closed panel.
    //
    // `panesPrimed` is that separation: the first pass after a remount may rebuild the tab list,
    // but only a file that arrives *later* is allowed to raise the pane.
    var panesPrimed by remember { mutableStateOf(false) }
    LaunchedEffect(fileViews.keys) {
        val fresh = fileViews.keys.filter { p -> paneTabs.none { it.key == "file:$p" } }
        if (fresh.isEmpty()) { panesPrimed = true; return@LaunchedEffect }
        paneTabs = paneTabs + fresh.map { p ->
            val norm = p.replace('\\', '/')
            PaneTab(
                key = "file:$p",
                kind = SidePanel.Kind.FILE,
                title = norm.substringAfterLast('/'),
                detail = norm.substringBeforeLast('/', ""),
                icon = OcIcons.FileDocument,
            )
        }
        paneActive = "file:" + fresh.last()
        // ...and show it. A file opened from a timeline card, a review row or a `@path` mention
        // added its tab and selected it but never raised the pane, so the tap looked dead -
        // the owner, 22 Nov: "I CANT TAP THE FILES LINK". Their PC opens the tab and the side region
        // with it. A file that was already open before this screen existed does not re-raise it.
        if (panesPrimed) paneOpen = true
        panesPrimed = true
    }

    /**
     * The four `session.panel` registrations. Their `run` is what a tap on an entry does; here it
     * adds the tab the entry names and chooses it, then `select.tab` clears the placeholder so the
     * launcher gives way to the real tab - exactly the dispatch their `Wa.Tabs.onChange` makes.
     */
    fun openPaneKind(kind: SidePanel.Kind) {
        // Their `{id: "new"}` registration: a tap **creates** a pty
        // (`index-BWsziAR3.js` @ 603 601) and the pty becomes its own tab. There is no shared
        // "terminal" tab to reuse, which is why this branch returns early.
        if (kind == SidePanel.Kind.TERMINAL) {
            paneOpen = true
            region = SidePanel.reduce(region, SidePanel.Event.SelectTab(SidePanel.Pane.SIDE)).state
            scope.launch {
                val info = vm.createPty() ?: return@launch
                val key = "pty:" + info.id
                if (paneTabs.none { it.key == key }) {
                    paneTabs = paneTabs + PaneTab(
                        key = key,
                        kind = SidePanel.Kind.TERMINAL,
                        title = info.title.ifBlank { "Terminal" },
                        icon = OcIcons.Terminal,
                    )
                }
                paneActive = key
            }
            return
        }
        val key = when (kind) {
            SidePanel.Kind.REVIEW -> "review"
            SidePanel.Kind.TERMINAL -> "terminal"
            SidePanel.Kind.FILE -> "open-file"
            // Their `legacy: { context: "main" }` - the Context panel's key is `main`.
            SidePanel.Kind.CONTEXT -> "context:main"
            SidePanel.Kind.BROWSER -> {
                var n = 1
                while (paneTabs.any { it.key == "browser:$n" }) n++
                "browser:$n"
            }
        }
        if (paneTabs.none { it.key == key }) {
            val tab = PaneTab(
                key = key,
                kind = kind,
                title = menuLabel(kind),
                icon = menuIcon(kind),
            )
            paneTabs = paneTabs + tab
        }
        when (kind) {
            SidePanel.Kind.TERMINAL -> showShells = true
            SidePanel.Kind.FILE -> vm.loadFiles(vm.fileDir.value.ifBlank { null })
            SidePanel.Kind.BROWSER -> if (browserUrls[key] == null) {
                browserUrls = browserUrls + (key to "")
            }
            SidePanel.Kind.REVIEW -> Unit
            SidePanel.Kind.CONTEXT -> Unit
        }
        paneActive = key
        paneOpen = true
        // `select.tab`: back to the tab list, so the `new:<serial>` placeholder stops being shown.
        region = SidePanel.reduce(region, SidePanel.Event.SelectTab(SidePanel.Pane.SIDE)).state
    }

    /** Their close: fall forward to the next tab, else back to the previous one. */
    fun closePaneTab(key: String) {
        val index = paneTabs.indexOfFirst { it.key == key }
        if (index < 0) return
        val tab = paneTabs[index]
        paneTabs = paneTabs.filterNot { it.key == key }
        when (tab.kind) {
            // A pty tab *is* the pty: closing it deletes the terminal, as their tab close does.
            SidePanel.Kind.TERMINAL -> vm.killPty(key.removePrefix("pty:"))
            SidePanel.Kind.FILE -> vm.closeFile(key.removePrefix("file:"))
            SidePanel.Kind.REVIEW -> vm.setReviewOpen(false)
            SidePanel.Kind.BROWSER -> browserUrls = browserUrls - key
            // Their panel is stateless between opens - closing it drops nothing.
            SidePanel.Kind.CONTEXT -> Unit
        }
        paneActive = paneTabs.getOrNull(index)?.key ?: paneTabs.getOrNull(index - 1)?.key
        paneRecent = (
            listOf(
                SidePanel.RecentEntry(
                    key = tab.key,
                    title = tab.title,
                    detail = tab.detail,
                    icon = "",
                )
            ) + paneRecent
            ).distinctBy { it.key }.take(SidePanel.RECENT_IN_PALETTE)
    }

    /** Closing the `new:<serial>` placeholder: `close` with `empty = true`, so `emptied` can fire. */
    fun closePlaceholder(id: String) {
        val r = SidePanel.reduce(region, SidePanel.Event.Close(id, empty = true))
        region = r.state
        if (r.effects.any { it is SidePanel.Effect.Emptied }) paneOpen = false
    }

    fun reopenPaneTab(entry: SidePanel.RecentEntry) {
        paneRecent = paneRecent.filterNot { it.key == entry.key }
        val kind = when {
            entry.key.startsWith("file:") -> SidePanel.Kind.FILE
            entry.key.startsWith("browser:") -> SidePanel.Kind.BROWSER
            entry.key.startsWith("context:") -> SidePanel.Kind.CONTEXT
            entry.key == "terminal" -> SidePanel.Kind.TERMINAL
            else -> SidePanel.Kind.REVIEW
        }
        val key = entry.key
        if (paneTabs.any { it.key == key }) {
            paneActive = key
            paneOpen = true
            region = SidePanel.reduce(region, SidePanel.Event.SelectTab(SidePanel.Pane.SIDE)).state
            return
        }
        paneTabs = paneTabs + PaneTab(
            key = key,
            kind = kind,
            title = entry.title,
            detail = entry.detail,
            icon = menuIcon(kind),
        )
        if (kind == SidePanel.Kind.TERMINAL) showShells = true
        paneActive = key
        paneOpen = true
        region = SidePanel.reduce(region, SidePanel.Event.SelectTab(SidePanel.Pane.SIDE)).state
    }

    // `region.sync(...)` - the region re-reads what the registry contributes on every change.
    val sideSnapshot = SidePanel.Snapshot(
        session = active?.id ?: "",
        empty = paneTabs.isEmpty(),
        regionActive = paneActive,
        sideOpen = paneOpen,
        panes = mapOf(
            SidePanel.Pane.SIDE to SidePanel.PaneSnapshot(
                keys = paneTabs.map { it.key },
                active = paneActive,
            ),
            SidePanel.Pane.SPLIT to SidePanel.PaneSnapshot(),
            SidePanel.Pane.BOTTOM to SidePanel.PaneSnapshot(),
        ),
    )
    LaunchedEffect(sideSnapshot) {
        val r = SidePanel.reduce(region, SidePanel.Event.Sync(sideSnapshot))
        region = r.state
        if (r.effects.any { it is SidePanel.Effect.Emptied }) paneOpen = false
    }


    val queued by vm.inbox.collectAsState()
    // their permission gate sits between the timeline and the composer, exactly like theirs
    val perms by vm.permissions.collectAsState()
    val forms by vm.forms.collectAsState()
    // `question.v2.*` - collected up here so the live-status band can count it as a gate
    val pendingQuestions by vm.questions.collectAsState()
    // PC setting, their words: "Background activity � Show running subagents and shells above the
    // composer". A shell counts only when it is *not* what he is watching: a call from an older
    // turn still running (he moved on), or the live turn's call while a prompt waits behind it.
    val bgShells = remember(turns, queued, sending) {
        val older = turns.dropLast(1).flatMap { it.parts }
        val live = if (queued.isNotEmpty()) turns.lastOrNull()?.parts.orEmpty() else emptyList()
        (older + live).filter { it.type == "tool" && isShellPart(it) && isRunningPart(it) }
    }
    // Their capsule names *both* groups (`session.activity.summary` = "{{subagents}} · {{shells}}"),
    // so a turn running only subagents still has something to say. Same window as `bgShells`:
    // a child call from an older turn that he moved on from, or the live turn's while a prompt
    // waits behind it. ZAPP never counted these, so a subagent-only turn drew no capsule at all.
    val bgAgents = remember(turns, queued, sending) {
        val older = turns.dropLast(1).flatMap { it.parts }
        val live = if (queued.isNotEmpty()) turns.lastOrNull()?.parts.orEmpty() else emptyList()
        (older + live).filter { it.type == "tool" && isSubagentPart(it) && isRunningPart(it) }
    }
    // Split from the block below on purpose.
    //
    // This used to be keyed on `sessions.size` as well, so the moment a turn created a child
    // session - or anything renamed, forked or archived - the key changed and the whole
    // transcript was re-read while the reply was still arriving. A subagent starts a session,
    // so a turn with one child paid for a full reload mid-stream. Only the *selection* may
    // trigger a load; the list size is not a reason to re-read a conversation.
    LaunchedEffect(active?.id) {
        active?.let {
            vm.loadMessages(it)
            vm.loadInbox(it)
        }
    }
    LaunchedEffect(active?.id, drafting) {
        // Desktop's home pane is the session list when no tab is open. Our single pane has no
        // home route, so with nothing selected - and no draft tab of his own - the board is
        // what he sees, never a blank chat that looks like the conversation was lost.
        //
        // `sessions.size` used to be one of the keys, and the list refreshes on a timer, so every
        // refresh re-ran this and forced the board back open after he had closed it. The key is
        // now the thing that actually changes what should be on screen - the open tab - and his
        // own close is remembered in `boardDismissed` until he opens a session again.
        if (active != null) boardDismissed = false
        if (active == null && !drafting && sessions.isNotEmpty() && !boardDismissed) {
            showSessions = true
        }
    }
    LaunchedEffect(Unit) { vm.loadExtensions() }
    LaunchedEffect(Unit) { vm.loadShells() }

    // Their `importSession` (`index-BWsziAR3.js` @ 83 741) opens a picker restricted to
    // `application/json` + `json` and hands the file text to the SDK. SAF is the phone's picker;
    // `OpenDocument` with one mime type is the closest thing to their single-file dialog.
    //
    // The file is NOT read here. It is streamed from the picker's URI straight into the request
    // body (`MainViewModel.importSessionStream`), so the import is bounded by disk, not by heap.
    //
    // the owner, 22 Nov: *"GUSTO KO AUTOMATIC NA NAKIKITA NILA SA CP PARA HINDI NA MAG HANAP"*.
    // `EXTRA_INITIAL_URI` points the picker straight at `Download/ZAPP`, which is the folder every
    // export lands in - his own exports and anything pushed from the PC. Before this the picker
    // opened wherever it was left last, so finding the file meant navigating the whole tree.
    val importPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        if (res.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = res.data?.data ?: return@rememberLauncherForActivityResult
        vm.importSessionStream(uri)
    }

    // `command.session.export` - "Export session" / "Export the full session transcript as JSON".
    //
    // This used to be markdown through the share sheet, which produced a document `Import session`
    // could never read back. Their export is the same `{info, messages}` JSON the Context tab
    // writes, so the round trip works: export here, import there, on any machine.
    //
    // The filename is computed first (their `exportFilename`), so the save dialog opens already
    // named the way their own save dialog is.
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }
    val exportPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val doc = pendingExport
        pendingExport = null
        if (uri == null || doc == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    appCtx.contentResolver.openOutputStream(uri)?.use { o ->
                        o.write(doc.second.toByteArray(Charsets.UTF_8))
                    } != null
                }.getOrDefault(false)
            }
            if (ok) vm.showToast("Session exported", doc.first)
            else vm.showToast("Failed to export session", "the file could not be written")
        }
    }

    Box(Modifier.fillMaxSize()) {
    // Split screen, not a modal. `DismissibleNavigationDrawer` pushes the chat aside by the
    // panel's width, so the conversation stays visible next to the list - the way a desktop
    // sidebar behaves. No scrim, therefore no dimmed grey wash over the app.
    DismissibleNavigationDrawer(
        drawerState = drawer,
        // the owner, 2026-11-24: *"PAG NAG RIGHT AND LEFT DRAG AKO BUG NA OOPEN YUNG SA LEFT SIDE
        // BAR IMBIS NAG S-SCROLL LANG AKO"*. Material's drawer opens on a horizontal drag
        // ANYWHERE over the content, not just at the edge - so every sideways drag inside the
        // app (the pane's tab strip, a code body, the browser, the transcript) was being read as
        // "open the sidebar". Their desktop has no such gesture: the sidebar opens from the menu
        // control and nothing else. Gestures are off; the `Sessions` control and the drawer's own
        // rows still open and close it.
        gesturesEnabled = false,
        drawerContent = {
            // Their sidebar (`shell.tsx:246`, `[data-slot=vertical-tabs-sidebar]`) is one flat
            // `bg-deep` plane: `ps 11 / pe 3`, `pt 8`, 10 px bottom, `tabsWidth: 240`.
            Column(
                Modifier
                    .fillMaxHeight()
                    .width(180.dp)
                    .background(Bg)
                    // The drawer is a sibling of the chat, not a child of it, so it does not
                    // inherit ChatScreen's status-bar inset. Without this the header (and the
                    // `+` / `X` in it) drew at y=0 under the clock and the system swallowed taps.
                    .statusBarsPadding()
                    .padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 10.dp),
            ) {
                    // Their menubar: `OpenCode`, then File / Edit / View / Go / Window / Help
                    // (`San` in `out/renderer/assets/main-*.js`). Every row below is their
                    // `desktop.menu.*` label and their `accelerator.windows` shortcut. A row whose
                    // action only exists on a desktop is disabled rather than faked - their own
                    // menu disables commands it cannot run.
                    if (appMenuOpen) {
                        val menuDraft = active?.id ?: "new"
                        AppMenuPanel(
                            listOf(
                                "File" to listOf(
                                    AppMenuRow("New Session") {
                                        appMenuOpen = false
                                        scope.launch { drawer.close() }
                                        vm.newSession()
                                    },
                                    AppMenuRow("Settings") {
                                        appMenuOpen = false
                                        scope.launch { drawer.close() }
                                        settingsStart = null
                                        showSettings = true
                                    },
                                    AppMenuRow("New Window") {
                                        appMenuOpen = false
                                        scope.launch { drawer.close() }
                                        boardDismissed = false
                                        showSessions = true
                                    },
                                    AppMenuRow("Close Window") {
                                        appCtx.findActivity()?.finish()
                                    },
                                ),
                                "Edit" to listOf(
                                    AppMenuRow("Undo", enabled = false),
                                    AppMenuRow("Redo", enabled = false),
                                    AppMenuRow("Cut", enabled = false),
                                    // The composer keeps its own buffer; the draft store is the
                                    // only text this surface can read without stealing focus.
                                    AppMenuRow("Copy") {
                                        clipboard.setText(
                                            AnnotatedString(vm.draftText(menuDraft)),
                                        )
                                        appMenuOpen = false
                                    },
                                    AppMenuRow("Paste", enabled = false),
                                    AppMenuRow("Delete", enabled = false),
                                    AppMenuRow("Select All", enabled = false),
                                ),
                                "View" to listOf(
                                    AppMenuRow("Toggle Sidebar") {
                                        appMenuOpen = false
                                        scope.launch { drawer.close() }
                                    },
                                    AppMenuRow("Toggle Terminal") {
                                        appMenuOpen = false
                                        scope.launch { drawer.close() }
                                        showShells = true
                                    },
                                    AppMenuRow("Reload") {
                                        appMenuOpen = false
                                        active?.let { vm.loadMessages(it) }
                                    },
                                    AppMenuRow("Toggle Developer Tools", enabled = false),
                                    AppMenuRow("Actual Size", enabled = false),
                                    AppMenuRow("Zoom In", enabled = false),
                                    AppMenuRow("Zoom Out", enabled = false),
                                    AppMenuRow("Toggle Full Screen", enabled = false),
                                ),
                                "Go" to listOf(
                                    AppMenuRow("Back", enabled = false),
                                    AppMenuRow("Forward", enabled = false),
                                    // `session.previous` / `session.next` walk the tab order; the
                                    // phone's tab order is the drawer's session list.
                                    AppMenuRow("Previous Session") {
                                        val i = sessions.indexOfFirst { it.id == active?.id }
                                        val prev = sessions.getOrNull(i - 1)
                                        if (i > 0 && prev != null) {
                                            appMenuOpen = false
                                            scope.launch { drawer.close() }
                                            vm.open(prev)
                                        }
                                    },
                                    AppMenuRow("Next Session") {
                                        val i = sessions.indexOfFirst { it.id == active?.id }
                                        val next = sessions.getOrNull(i + 1)
                                        if (i >= 0 && next != null) {
                                            appMenuOpen = false
                                            scope.launch { drawer.close() }
                                            vm.open(next)
                                        }
                                    },
                                ),
                                "Window" to listOf(
                                    AppMenuRow("Minimize") {
                                        appMenuOpen = false
                                        scope.launch { drawer.close() }
                                        appCtx.findActivity()?.moveTaskToBack(true)
                                    },
                                    AppMenuRow("Maximize", enabled = false),
                                    AppMenuRow("Close Window") {
                                        appCtx.findActivity()?.finish()
                                    },
                                ),
                                "Help" to listOf(
                                    // Ours, not theirs. These four rows used to open
                                    // opencode.ai/docs, OpenCode's Discord invite and OpenCode's
                                    // GitHub issue tracker - another product's support desk inside
                                    // ZAPP's own menu. About is now a real screen, and the two
                                    // outward links point at ZAPP's own repository.
                                    AppMenuRow("About ZappCode") {
                                        appMenuOpen = false
                                        aboutOpen = true
                                    },
                                    AppMenuRow("Join the Discord") {
                                        appMenuOpen = false
                                        openExternal(
                                            appCtx,
                                            "https://discord.gg/$DISCORD_INVITE",
                                        )
                                    },
                                    AppMenuRow("How to use") {
                                        appMenuOpen = false
                                        aboutOpen = true
                                    },
                                    // `logs.export`. Their main process zips the log directory; we
                                    // keep no log file, so the `ZAPP` logcat tag is the equivalent.
                                    AppMenuRow("Export Logs\u2026") {
                                        appMenuOpen = false
                                        scope.launch {
                                            val dump = withContext(Dispatchers.IO) { readZappLog() }
                                            shareText(appCtx, dump, "text/plain")
                                        }
                                    },
                                    AppMenuRow("Source Code") {
                                        appMenuOpen = false
                                        openExternal(
                                            appCtx,
                                            "https://github.com/zappminazuki-ctrl/ZAPP-Net",
                                        )
                                    },
                                    AppMenuRow("Report a Bug") {
                                        appMenuOpen = false
                                        openExternal(
                                            appCtx,
                                            "https://github.com/zappminazuki-ctrl/ZAPP-Net/issues/new",
                                        )
                                    },
                                ),
                            ),
                            onClose = { appMenuOpen = false },
                        )
                    } else {
                    // `[data-slot=sidebar-header]` - `h-12 items-center gap-2`. Theirs holds the
                    // Home action and the collapse toggle; the phone has no toggle, so the right
                    // slot carries `tab.new`.
                    Row(
                        Modifier.fillMaxWidth().height(38.dp).padding(start = 6.dp, end = 0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Sessions",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    vm.newSession()
                                    scope.launch { drawer.close() }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                OcIcons.Plus,
                                "New session",
                                tint = Accent,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        // Every panel gets a way out that is not a guess: the scrim closes too,
                        // but a visible X is the one he can see.
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { scope.launch { drawer.close() } },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                OcIcons.Close,
                                "Close",
                                tint = TextMid,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    // Their `sessions.search` opens a dialog; ours filters inline. No fill, no
                    // border: a lifted grey bar across the panel is exactly the look we banned.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            OcIcons.Magnifier,
                            null,
                            tint = TextMid,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        BasicTextField(
                            value = search,
                            onValueChange = { search = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 12.sp, color = TextPrimary),
                            cursorBrush = SolidColor(Accent),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                if (search.isBlank()) {
                                    Text(
                                        "Search…",
                                        fontSize = 12.sp,
                                        color = TextDim,
                                    )
                                }
                                inner()
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                val shown = if (search.isBlank()) sessions
                else sessions.filter { (it.title ?: "").contains(search, ignoreCase = true) }
                val runs by vm.activeRuns.collectAsState()
                // `weight(1f)` is load-bearing: without it the list measures to the full
                // remaining height and pushes the footer rows (Extensions, Settings) out of
                // the sheet, where they are clipped and unreachable.
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(shown) { s ->
                        val selected = s.id == active?.id
                        // `ProjectSessionRow` (`project-session-row.tsx:167`): `h-7 w-full gap-2
                        // rounded-[6px] ps-8 pe-2 text-[13px] font-medium`, active `text-base`,
                        // idle `text-faint`, a 16 px leading slot at `start-2` and a 16 px
                        // trailing slot for the status mark. `ps-8` is the 8 px inset plus that
                        // slot plus the 8 px gap, so the title starts at 32 px.
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selected) Accent.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable {
                                    vm.open(s)
                                    scope.launch { drawer.close() }
                                }
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Their leading slot is the open-tab dot or the project avatar; ours
                            // marks the session that is open, in our purple.
                            Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
                                if (selected) {
                                    Box(
                                        Modifier
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(Accent),
                                    )
                                }
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                com.zapp.net.SessionTitle.display(s.title, !s.parentId.isNullOrBlank()),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (selected) TextPrimary else Color(0xFFB3AAC2),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            // Their trailing slot is an `ActivityRing` while the session runs and
                            // a 6 px `text-base` dot when it is unread.
                            Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
                                if (runs.containsKey(s.id)) {
                                    Box(
                                        Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(TextPrimary),
                                    )
                                }
                            }
                        }
                    }
                }
                // Their sidebar footer is `mt-2` of the same action rows, no divider
                // (`titlebar.tsx:883`).
                Spacer(Modifier.height(8.dp))
                // Their project row carries `Import session` (`canImportSession` is
                // `!!openAttachmentPickerDialog`); the drawer is our project list, so the action
                // sits here, next to Extensions.
                //
                // Export sits directly above it. the owner, 22 Nov: *"DITO SA CP MAY EXPORT AND
                // IMPORT"* - the pair is one round trip, and leaving Export only in the header
                // `...` menu made Import look like it was missing.
                DrawerAction(
                    icon = OcIcons.ArrowUp,
                    label = "Export session",
                    enabled = active != null,
                ) {
                    scope.launch { drawer.close() }
                    val sid = active?.id
                    if (sid != null) {
                        vm.exportSessionToDownloads(sid) { name, json ->
                            pendingExport = name to json
                            exportPicker.launch(name)
                        }
                    }
                }
                DrawerAction(
                    icon = OcIcons.Download,
                    label = if (importing) "Importing…" else "Import session",
                    enabled = !importing,
                ) {
                    scope.launch { drawer.close() }
                    importPicker.launch(zappImportIntent(appCtx))
                }
                // `settings-gear` belongs to Settings, so Extensions takes the grid glyph - two
                // gears side by side in one drawer reads as a mistake.
                DrawerAction(icon = OcIcons.GridPlus, label = "Extensions") {
                    scope.launch { drawer.close() }
                    ext = ExtPanel.MENU
                }
                // Their Settings is a root route (`/settings?tab=general`), not a sidebar item;
                // on the phone the drawer footer is the closest thing to the desktop's chrome.
                DrawerAction(icon = OcIcons.Gear, label = SettingsCopy.TITLE) {
                    scope.launch { drawer.close() }
                    settingsStart = null
                    showSettings = true
                }
                // Their menubar is its own popover with six groups; the drawer is our only chrome,
                // so it gets one row that opens them (`San`, `out/renderer/assets/main-*.js`).
                DrawerAction(icon = OcIcons.Menu, label = "Menu") {
                    appMenuOpen = true
                }
            }
            }
        },
    ) {
        // `titlebar.tsx:231` - `bottom = mobile() && settings.general.mobileTitlebarPosition() ===
        // "bottom"`. Their `mobile()` is the `(max-width: 767px)` media query, which is this phone.
        // The store is written by the Preferences page; `version` is the signal to recompose.
        val prefsTick by com.zapp.net.settings.SettingsPrefs.version.collectAsState()
        val titlebarBottom = remember(prefsTick) {
            com.zapp.net.settings.SettingsPrefs.string(
                com.zapp.net.settings.SettingsPrefs.KEY_TITLEBAR_POSITION,
                com.zapp.net.settings.SettingsPrefs.DEFAULT_TITLEBAR_POSITION,
            ) == "bottom"
        }
        Column(
            Modifier
                .fillMaxSize()
                .background(Bg)
                .statusBarsPadding(),
        ) {
            // `titlebar.tsx:231`/`:403` - `bottom = mobile() && mobileTitlebarPosition() ===
            // "bottom"`, and the titlebar row gets `order-last`. The row does not change, only
            // where it sits in the column, so it is one lambda rendered at one of two places.
            val titleStrip: @Composable () -> Unit = {
                // ---- tab strip: menu, grid, search, open-session tabs, + ----
                Row(
                    Modifier.fillMaxWidth().height(36.dp).background(Surface)
                        .padding(horizontal = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Their titlebar row is three `IconButton variant="ghost-muted" size="large"`
                    // (28px) holding 16px glyphs, and all three resolve to
                    // `--v2-text-text-faint` = `--v2-grey-600` = #808080. Measured off the live
                    // PC window: hamburger 12x8 at #808080, grid 14x14 at #7D7D7D, search 12x12
                    // at #808080 - one tint, one box. Ours had three tints (TextMid, TextDim,
                    // TextDim) and three glyph sizes (17, 13, 13) in a single row, which is what
                    // read as wrong next to the pill. One tint, one 16dp box, one 28dp target.
                    Box(
                        Modifier.size(28.dp).clickable { scope.launch { drawer.open() } },
                        contentAlignment = Alignment.Center,
                    ) { Icon(OcIcons.Menu, "Sessions", tint = TextFaint, modifier = Modifier.size(16.dp)) }
                    Box(
                        Modifier.size(28.dp).clickable {
                            // An explicit tap always wins over an earlier dismissal.
                            boardDismissed = false
                            showSessions = true
                        },
                        contentAlignment = Alignment.Center,
                    ) { GridDots(TextFaint, 16) }
                    Box(
                        Modifier.size(28.dp).clickable { showFinder = true },
                        contentAlignment = Alignment.Center,
                    ) { GlassIcon(TextFaint, 16) }
                    LazyRow(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // `shell/tabs/tabs.tsx`: the strip is the *open tab list*, in open order —
                        // not "recent sessions". A draft tab is its own tab and stays put.
                        items(openTabs) { t ->
                            val selected = if (t.draft) t.id == activeDraft else t.id == active?.id
                            var tabMenu by remember(t.id) { mutableStateOf(false) }
                            Box {
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (selected) Layer2 else Color.Transparent)
                                        .clickable {
                                            if (t.draft) {
                                                vm.openDraft(t.id)
                                            } else {
                                                vm.openById(t.id)
                                            }
                                        }
                                        .padding(start = 4.dp, end = if (selected) 5.dp else 8.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    SessionAvatar(t.title)
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        t.title,
                                        fontSize = 11.sp,
                                        color = if (selected) TextPrimary else TextMid,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 118.dp),
                                    )
                                    if (selected) {
                                        Spacer(Modifier.width(6.dp))
                                        Box(Modifier.size(12.dp).clickable { tabMenu = true }, contentAlignment = Alignment.Center) {
                                            GridDots(TextDim, 10)
                                        }
                                    }
                                }
                                DropdownMenu(expanded = tabMenu, onDismissRequest = { tabMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Close tab", fontSize = 12.sp, color = TextPrimary) },
                                        onClick = {
                                            tabMenu = false
                                            vm.closeTab(t.id)
                                        },
                                    )
                                    // `tab-nav.tsx:93-117`: Close others / Close tabs to the right,
                                    // each disabled when there is nothing to close.
                                    val hasOthers = openTabs.any { it.id != t.id }
                                    DropdownMenuItem(
                                        enabled = hasOthers,
                                        text = {
                                            Text(
                                                "Close other tabs",
                                                fontSize = 12.sp,
                                                color = if (hasOthers) TextPrimary else TextDim,
                                            )
                                        },
                                        onClick = {
                                            tabMenu = false
                                            vm.closeOtherTabs(t.id)
                                        },
                                    )
                                    val toRight = openTabs.dropWhile { it.id != t.id }.drop(1)
                                    DropdownMenuItem(
                                        enabled = toRight.isNotEmpty(),
                                        text = {
                                            Text(
                                                "Close tabs to the right",
                                                fontSize = 12.sp,
                                                color = if (toRight.isNotEmpty()) TextPrimary else TextDim,
                                            )
                                        },
                                        onClick = {
                                            tabMenu = false
                                            vm.closeTabsToRight(t.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    // `titlebar.tsx:1611`: the `+` is their `Menu.Context.Trigger` — click makes a
                    // tab, the context menu lists recently closed ones. Long-press is the touch
                    // equivalent of that right-click.
                    var recentOpen by remember { mutableStateOf(false) }
                    Box(
                        Modifier.size(30.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .combinedClickable(
                                onClick = { vm.newSession() },
                                onLongClick = {
                                    vm.refreshRecentlyClosed()
                                    recentOpen = true
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(OcIcons.Plus, "New session", tint = TextDim, modifier = Modifier.size(17.dp))
                    }
                    RecentlyClosedDialog(vm, recentOpen) { recentOpen = false }
    AboutZappDialog(aboutOpen) { aboutOpen = false }
    // ---- background shells, the chip above the composer ----
    //
    // the owner, 22 Nov: "and also the shell bro and also move to the background bro thats the only
    // we need rn". The chip set `showShells = true` and NOTHING was drawn from it - the flag was
    // only used to start a poll loop, so tapping the chip refreshed data into an invisible list.
    // Desktop's chip opens the running-work list; this is that list, as a sheet, over the chat.
    if (showShells) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showShells = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.72f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(Bg),
            ) {
                ShellsPanel(
                    shells = shells,
                    visible = true,
                    onKill = { vm.killShell(it) },
                    output = { id -> vm.shellOutputOf(id) },
                    onClose = { showShells = false },
                    live = bgShells,
                    onRun = { vm.runShell(it) },
                    onDetach = { cmd, id -> vm.detachShell(id, cmd) },
                    chrome = true,
                )
            }
        }
    }
                }

                // ---- breadcrumb + usage chip ----
                Row(
                    Modifier.fillMaxWidth().height(30.dp).background(Surface)
                        .padding(start = 9.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val dir = active?.project ?: "Default Project"
                    SessionAvatar(active?.title, size = 14)
                    Spacer(Modifier.width(6.dp))
                    var sessionMenu by remember { mutableStateOf(false) }
                    var renameOpen by remember { mutableStateOf(false) }
                    var moveOpen by remember { mutableStateOf(false) }
                    var deleteOpen by remember { mutableStateOf(false) }
                    // PC's `sh` location control (`route-9` ~171.7k): project / directory, and its
                    // own menu carries "Choose directory" - that is our move dialog.
                    Row(
                        Modifier.clip(RoundedCornerShape(4.dp)).clickable { moveOpen = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(dir, fontSize = 10.sp, color = TextDim, maxLines = 1)
                    }
                    Text(" / ", fontSize = 10.sp, color = Border)
                    Text(
                        active?.title ?: "OpenCode",
                        fontSize = 10.5.sp, color = TextPrimary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 132.dp),
                    )
                    Box {
                        Text(
                            "⋯",
                            fontSize = 11.sp, color = TextDim,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(enabled = active != null) { sessionMenu = true }
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                        DropdownMenu(
                            expanded = sessionMenu,
                            onDismissRequest = { sessionMenu = false },
                            modifier = Modifier.background(Layer1),
                            containerColor = Layer1,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            SessionMenuItem("Rename") { sessionMenu = false; renameOpen = true }
                            SessionMenuItem("Export session") {
                                sessionMenu = false
                                val sid = active?.id
                                if (sid != null) {
                                    vm.exportSessionToDownloads(sid) { name, json ->
                                        pendingExport = name to json
                                        exportPicker.launch(name)
                                    }
                                }
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                            SessionMenuItem("Delete…", danger = true) { sessionMenu = false; deleteOpen = true }
                        }
                    }
                    if (renameOpen && active != null) {
                        RenameDialog(
                            initial = active?.title.orEmpty(),
                            onDismiss = { renameOpen = false },
                            onConfirm = { t ->
                                renameOpen = false
                                vm.renameSession(active!!.id, t)
                            },
                        )
                    }
                    if (moveOpen && active != null) {
                        MoveDialog(
                            initial = active?.dir.orEmpty(),
                            onDismiss = { moveOpen = false },
                            onConfirm = { dir ->
                                moveOpen = false
                                vm.moveSessionTo(dir)
                            },
                        )
                    }
                    if (deleteOpen && active != null) {                    ConfirmDialog(
                            title = "Delete session?",
                            body = "\"${active?.title ?: "(untitled)"}\" will be removed from the server.",
                            confirm = "Delete",
                            onDismiss = { deleteOpen = false },
                            onConfirm = {
                                deleteOpen = false
                                active?.let { vm.deleteSession(it.id) }
                            },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // PORT of `gui-plugins/src/context/indicator.tsx` - their real source, read out
                    // of the renderer sourcemap, not the minified chunk. Four memos, in their order:
                    //
                    //   context() = lastAssistantWithUsage(messages, info.revert?.messageID)
                    //               total = input + output + reasoning + cache.read + cache.write
                    //               usage = model.limit.context ? round(total/limit*100) : null
                    //   spent     = session.cost(id)   // root: family sum; child: its own cost
                    //   showCost  = spent > 0
                    //               || (ref.providerID !== "opencode-go"
                    //                   && (!model || model.cost.some(tier => any tier > 0)))
                    //               ref = info.model ?? last assistant message's model
                    //   tokens    = Intl compact, 0 fraction digits, lowercased -> "102k"
                    //
                    // Two things this fixes from the previous port. The **boundary**: a completed
                    // compaction or a staged revert hides usage at or after it, so the pill reads
                    // `lastAssistantWithUsage`, never "the last assistant message". The **sum**:
                    // `session.cost(id)` walks the family for a root session (`data.ts:1582`), so a
                    // root's spend includes its subagents and a child never shows the family total.
                    val revertId = remember(totals) {
                        (totals?.revert as? kotlinx.serialization.json.JsonObject)
                            ?.let { it["messageID"] as? kotlinx.serialization.json.JsonPrimitive }
                            ?.content
                    }
                    val infos = remember(messages) { messages.map { it.info } }
                    val lastAssistant = remember(infos, revertId) {
                        com.zapp.net.api.ContextStats.lastAssistantWithUsage(infos, revertId)
                    }
                    val tokenTotal = lastAssistant?.tokens?.context ?: 0L
                    val allModels by vm.models.collectAsState()
                    // `ref = info()?.model ?? messages().findLast(assistant)?.model` - the session's
                    // own model wins, and only then the message's.
                    val refProvider = totals?.model?.providerID ?: lastAssistant?.providerID
                    val refModelId = totals?.model?.id ?: lastAssistant?.modelID
                    val modelMeta = remember(refModelId, refProvider, allModels) {
                        allModels.firstOrNull {
                            it.modelID == refModelId && it.providerID == refProvider
                        }
                    }
                    val usagePct = modelMeta?.limit?.context
                        ?.takeIf { it > 0L }
                        ?.let { Math.round((tokenTotal.toDouble() / it) * 100.0).toInt() }
                    val spent = remember(totals, sessions) {
                        val t = totals ?: return@remember 0.0
                        if (t.parentId != null) return@remember t.cost ?: 0.0
                        val byParent = sessions.groupBy { it.parentId }
                        var sum = 0.0
                        val seen = mutableSetOf<String>()
                        val queue = ArrayDeque<String>().apply { add(t.id) }
                        while (queue.isNotEmpty()) {
                            val id = queue.removeFirst()
                            if (!seen.add(id)) continue
                            val s = if (id == t.id) t else sessions.firstOrNull { it.id == id }
                            sum += s?.cost ?: 0.0
                            byParent[id]?.forEach { queue.add(it.id) }
                        }
                        sum
                    }
                    totals?.let { _ ->
                        // `showCost` in their file is three branches: a paid session always
                        // shows; `opencode-go` always hides; anything else hides when every
                        // price tier is zero. That last branch is why a free model prints no
                        // cost on PC at all.
                        //
                        // the owner wants the spend on screen even when it is `$0.00` - *"hindi ko
                        // naman nakikita yung cost"* - so this is our one deliberate deviation:
                        // hide only for `opencode-go`, show everything else. The gap is recorded
                        // in ZAPP-PARITY-MATRIX.md rather than left implicit.
                        val costShown = refProvider != "opencode-go"
                        // `index-BWsziAR3.js` @ 562.3k - `fz`, the `session.header` order-10 usage
                        // control with `variant` unset, i.e. the `button` branch (not `indicator`,
                        // which is the ring alone, and not `compact`, which is the dropdown):
                        //
                        //   <span class="flex items-center gap-2 whitespace-nowrap
                        //                group-active:text-v2-text-text-muted">
                        //     progress-circle(compact, 16, strokeWidth 2) <span>{tokens}</span>
                        //   </span>
                        //   [ <span class="w-1.5 ...">·</span> <span>{usd}</span> ]   when p()
                        //
                        // button-v2 `ghost-muted`, style `padding: 0 6px`,
                        // `color: var(--v2-text-text-faint)`, `onClick: layout.toggle("main")` and
                        // `aria-label: usage.toggle` = "Toggle session context". The sidebar is our
                        // drawer, so the tap opens it. `totals` is `session.cost(id)`.
                        // `indicator.tsx` `openContext` - the non-compact branch's `onClick`:
                        //
                        //   const openContext = () => {
                        //     if (!session.id) return
                        //     catalog.retry()
                        //     layout.toggle(`${ctx.id}:main`, props.session)
                        //   }
                        //
                        // `ctx.id` is the plugin's `"context"`, so the panel key is
                        // `context:main` - the same key `legacy: { context: "main" }` maps to.
                        // `toggle` closes it when it is the visible panel and opens it otherwise.
                        //
                        // It used to open the session drawer. That is not what the pill does on
                        // PC, and the drawer is already reachable from the strip's first icon.
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    if (totals?.id == null) return@clickable
                                    if (paneOpen && paneActive == "context:main") {
                                        closePaneTab("context:main")
                                        if (paneTabs.isEmpty()) {
                                            paneOpen = false
                                            paneFocused = false
                                        }
                                    } else {
                                        openPaneKind(SidePanel.Kind.CONTEXT)
                                    }
                                }
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ProgressRing(usagePct ?: 0)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                compactCount(tokenTotal),
                                fontSize = 13.sp,
                                fontWeight = FontWeight(440),
                                color = TextFaint,
                            )
                            if (costShown) {
                                // `gap-2` applies between every child of the pill's inner span,
                                // so the `·` gets 8px on BOTH sides, not just after the tokens.
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.width(6.dp), contentAlignment = Alignment.Center) {
                                    Text("\u00B7", fontSize = 13.sp, color = TextFaint)
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    usd(spent),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight(440),
                                    color = TextFaint,
                                )
                            }
                        }
                    }
                    // the owner's call: this control beside the cost opens **Extensions** straight away.
                    // Their `details/popover.tsx` puts `window-analytics` here and hangs the
                    // Session details card off it, whose only useful rows are Extensions and
                    // Changes - too many taps on a phone. The glyph stays, the card does not.
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (ext != ExtPanel.NONE) PressedOverlay else Color.Transparent)
                            .clickable {
                                ext = if (ext == ExtPanel.NONE) ExtPanel.MENU else ExtPanel.NONE
                            }
                            .semantics { contentDescription = "Extensions" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            OcIcons.WindowAnalytics,
                            null,
                            tint = if (ext != ExtPanel.NONE) IconBase else TextFaint,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    // `screen.tsx` `data-slot="session-review-toggle"` - their `SessionReviewToggle`.
                    // The fill follows the panel's travel (`reviewGlyphFill`), never snaps.
                    val reviewOpen by animateFloatAsState(
                        if (paneOpen) 1f else 0f,
                        tween(160),
                        label = "review-toggle",
                    )
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (paneOpen) PressedOverlay else Color.Transparent)
                            // `region.syncSideOpen(...)` - their toggle only flips the flag; the sync
                            // then opens one empty tab if the region has none and never had one.
                            .clickable {
                                if (paneOpen) {
                                    paneOpen = false
                                    paneFocused = false
                                } else {
                                    paneOpen = true
                                    paneActive = null
                                    paneFocused = true
                                }
                            }
                            .semantics { contentDescription = "Toggle review" },
                        contentAlignment = Alignment.Center,
                    ) {
                        ReviewToggleGlyph(reviewOpen, if (paneOpen) IconBase else TextFaint)
                    }
                    if (sending) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            OcIcons.Stop, "Stop",
                            tint = Accent, modifier = Modifier.size(13.dp).clickable { vm.stop() },
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            }
            // Their `:425`/`:437` flip the safe-area padding with the row; ours rides on the
            // Column, so only the order changes here.
            if (!titlebarBottom) titleStrip()

            // ---- conversation ----
            // one list per session: reopening a chat starts at its newest instead of
            // inheriting the scroll of whichever chat he was in before
            val listState = remember(active?.id) {
                androidx.compose.foundation.lazy.LazyListState()
            }
            // `follow` is his intent: it drops the moment HIS finger drags the list, and comes
            // back only when a scroll settles with the newest turn fully in view. Growth never
            // changes it, so a streaming reply can't pull the page out from under him.
            var follow by remember { mutableStateOf(true) }
            LaunchedEffect(active?.id) { follow = true }

    // The session's live terminals, so a pty that outlived a restart is still reachable - their
    // panel calls `persistentPty.list` before it offers to create a numbered one.
    LaunchedEffect(active?.id) { vm.loadPtys() }

            // Finger = drag. Programmatic scrolls (a new prompt, the pill) go through
            // SideEffect and are ignored here, which is why they can't switch follow off.
            val fingerScroll = remember {
                object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                    override fun onPreScroll(
                        available: androidx.compose.ui.geometry.Offset,
                        source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
                    ): androidx.compose.ui.geometry.Offset {
                        // PC detaches only on an upward scroll *over overflowing content*:
                        //   machine.ts:108  `event.upward && event.overflow && state.type !== "detached"`
                        // Without the overflow test a nudge on a short conversation - where there
                        // is nothing to scroll - turned the pin off for nothing.
                        if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput &&
                            available.y > 0f &&   // finger moving up = reading back; a downward
                                                  // nudge at the bottom is not "leaving"
                            listState.canScrollForward
                        ) follow = false
                        return androidx.compose.ui.geometry.Offset.Zero
                    }
                }
            }

            // One drain for every jump. A lazy list's `scrollBy` consumes only what the CURRENT
            // layout can give it, so a single 1e6 call on a turn taller than the viewport
            // stopped short and left the newest line below the fold. Repeat while there is
            // travel left; the content end clamps the last pass.
            suspend fun pinToEnd(): Float {
                var total = 0f
                var passes = 0
                while (listState.canScrollForward && passes++ < 24) {
                    val moved = listState.scrollBy(1_000_000f)
                    total += moved
                    if (moved == 0f) break
                }
                if (listState.canScrollForward) {
                    android.util.Log.e("ZAPP", "pin stopped early after $passes passes (moved=$total)")
                }
                return total
            }

            // Landed back at the newest end under his own momentum -> follow again.
            LaunchedEffect(listState) {
                androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }
                    .collect { moving ->
                        if (!moving && !listState.canScrollForward) follow = true
                    }
            }

            // PC's `anchorTo: "end"` while following (`virtualizer.tsx:392-403`): the newest
            // row's bottom sits on the viewport end on every layout, with no transition.
            //
            // This is the half that was missing for a LIVE reply. The gap collector below only
            // reacts once the overflowing row has been MEASURED, and a row that was just added
            // (or is still growing below the fold) has no measurement yet, so the answer gained
            // scroll travel without the page moving. That produced the repeated
            // `jump: ... moved=3289.9375 canForward=false` clicks in the 24 Nov log: every tap
            // landed exactly on the bottom, then the next deltas left it behind again.
            //
            // `canScrollForward` is the honest test - it is true exactly when there is travel
            // left - so pinning whenever it turns true while he is following cannot be fooled by
            // an unmeasured row, and costs nothing at the bottom (the first `scrollBy` moves 0).
            LaunchedEffect(listState) {
                androidx.compose.runtime.snapshotFlow { listState.canScrollForward }
                    .collect { can ->
                        if (!can || !follow) return@collect
                        var passes = 0
                        while (listState.canScrollForward && passes++ < 24) {
                            if (listState.scrollBy(1_000_000f) == 0f) {
                                // a fresh row may not be laid out yet: wait one frame, retry once
                                androidx.compose.runtime.withFrameNanos { }
                                if (listState.scrollBy(1_000_000f) == 0f) break
                            }
                        }
                    }
            }

            // Follow just came back (settle, session change, the pill): land on the end now.
            LaunchedEffect(follow) {
                if (follow) pinToEnd()
            }

            // The bottom pin. Computed from the real layout gap, never a pixel guess: the old
            // `scrollToItem(last, 1_000_000)` overshot and parked the list past its content,
            // which is what produced the blank page. Applied as a short linear animation so a
            // streaming reply glides instead of stepping � a raw `scrollBy` per delta reads as
            // "not smooth" even at 60 fps (measured: 0 janky frames, 99th 28 ms).
            LaunchedEffect(listState) {
                androidx.compose.runtime.snapshotFlow {
                    val li = listState.layoutInfo
                    val last = li.visibleItemsInfo.lastOrNull()
                    if (last == null) 0
                    else li.viewportEndOffset - (last.offset + last.size) - li.afterContentPadding
                }.collect { gap ->
                    // PC's answer to this whole problem is one line in their virtualizer:
                    //
                    //   virtualizer.tsx:392   get anchorTo() { return following ? "end" : "start" }
                    //   virtualizer.tsx:403   get paddingEnd() { return tailSize() }
                    //
                    // While the reader is following, the newest row's bottom is placed at the
                    // end of the viewport on every measurement, with NO transition.
                    // `machine.ts` tracks intent only - there is no scroll animation anywhere in
                    // their timeline. So this is a snap, once per layout change.
                    //
                    // Ours animated every positive gap over 180 ms. At 7 deltas a second each
                    // animation was cancelled and restarted by the next one, so the view trailed
                    // the text instead of sitting on the bottom - which is the second half of
                    // the owner's 2026-11-24 report: "HINDI FIXED POSITION SA BABA LIKE SAME SA PC
                    // OPENCODE ... NASA BABA LAHAT". The animation is gone.
                    //
                    // Only a negative gap acts. A positive gap means the content is shorter than
                    // the viewport, and PC top-aligns that case - its own comment at
                    // virtualizer.tsx:400 says an empty projection still only gets the bottom
                    // spacer, so there is nothing to pin to.
                    //
                    // `scrollBy` is clamped at the content end. A pixel `scrollOffset` is not,
                    // and that overshoot is what once parked the list past its content and
                    // produced the blank page.
                    if (!follow || gap >= 0) return@collect
                    listState.scrollBy(-gap.toFloat())
                }
            }

            // a brand-new prompt returns him to the latest, like the PC does
            LaunchedEffect(lastUserId) {
                if (lastUserId == null || turns.isEmpty()) return@LaunchedEffect
                follow = true
                // `scrollToItem` aligns the newest turn's TOP with the viewport top, so a reply
                // taller than the screen left him reading its first line - his words: "pag nag
                // reply ka bumabalik sa taas". `scrollBy` is clamped at the content end (a
                // pixel `scrollOffset` is not, which is the overshoot that produced a blank
                // page), so this lands on the true bottom in one step.
                listState.scrollToItem(turns.lastIndex)
                pinToEnd()
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                // the owner, 2026-11-24: *"FCK YOU I CANT SCROLL I SAID LOCK IT YOUR SYSTEM AND MY
                // FINGER IS NOT THE SAME"*, on the pane the header button opens. The pane is drawn
                // OVER this list, and Compose hands a pointer to every node under it, so a drag
                // inside the pane scrolled the transcript behind it as well - measured on device:
                // one swipe in the Browser pane moved the page AND the chat text (`abap, actio...`
                // from y=324 to y=246). While the pane is open the transcript does not take user
                // scrolls at all, so nothing under his finger can move except the pane.
                userScrollEnabled = !paneOpen,
                modifier = Modifier
                    .fillMaxSize()

                    .nestedScroll(fingerScroll),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp,
                    // PC's `message-nav` is an absolutely positioned overlay, it does not
                    // reserve a gutter — so the text keeps only the 12px page inset and the
                    // ticks float on top of the last few pixels.
                    end = 12.dp,
                    top = 10.dp,
                    // The `Jump to latest` pill floats over the list by design, but with only
                    // 10dp below the last turn it covered the final two lines of the answer.
                    // This used to flip 52dp -> 10dp the instant `follow` turned true, which
                    // re-laid out the list in the middle of the jump animation - that shift is
                    // the bounce the owner saw. It is now constant: the last message always ends
                    // 52dp above the composer, and the pill lives inside that gap.
                    bottom = 52.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // older history pages in above everything already on screen; it never
                // replaces the list, so what he is reading cannot move under his eyes
                if (hasOlder) {
                    item(key = "older") {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 2.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                "Load older",
                                fontSize = 10.5.sp,
                                color = TextMid,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Surface2)
                                    .clickable { vm.loadOlderMessages() }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                // `contentType` lets the lazy list reuse the same slot layout for every turn
                // instead of throwing the composed subtree away when a row scrolls out and a
                // different-shaped one scrolls in. Without it a long session allocated a new
                // subtree per row and the fling stuttered.
                itemsIndexed(turns, key = { _, t -> t.key }, contentType = { _, _ -> "turn" }) { index, t ->
                    // `TimelineRow.TurnGap` — the spacer before every turn but the first.
                    // The renderer's `de` sets `height: above ? 4 : 16` (px), so a gap that
                    // opens the list is 4 and one between two turns is 16.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = if (index > 0) 16.dp else 4.dp)
                    ) {
                    TurnRow(
                        t,
                        isLast = t.key == turns.lastOrNull()?.key,
                        latestUser = t.user?.info?.id != null && t.user.info.id == lastUserId,
                        sending = sending,
                        liveId = liveId,
                        detail = timelineDetail,
                        showTurnDiffs = showTurnDiffs,
                        foldUserMessages = foldUserMessages,
                        onFork = { before -> active?.let { vm.fork(it.id, before) } },
                        onUndo = { msg -> active?.let { vm.undo(it.id, msg) } },
                        onReply = { replySeed = it },
                        onEdit = { msg, txt ->
                            active?.let { a ->
                                replySeed = txt
                                vm.undo(a.id, msg)
                            }
                        },
                        // `markdown-session-links.ts:23` — the label is the live session
                        // title, so a rename re-labels every reference to it.
                        sessionTitle = { id ->
                            vm.sessions.value.firstOrNull { it.id == id }
                                ?.title?.takeIf { it.isNotBlank() }
                        },
                        onOpenSession = { id -> vm.openById(id) },
                        // `inlineCodeKind` — a code span that names a file opens it.
                        onOpenFile = { path -> vm.openFile(path) },
                        onReadImage = { src -> vm.readImageBytes(src) },
                    )
                    }
                }
            }
            // PC's `message-nav` tick rail is removed on touch: on a desktop it lives in its
            // own gutter, on a phone it lands on top of the prose and reads as a hard line
            // down the right edge. He asked for it gone by name, so it is gone.
            // he scrolled away from a live turn: one small way back, floating over the list
            // instead of a row inside it (a row appearing mid-list moves everything he reads)
            if (!follow && listState.canScrollForward) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Surface2)
                        .border(1.dp, Line, RoundedCornerShape(50))
                        .clickable {
                            if (turns.isEmpty()) return@clickable
                            // follow FIRST: the pin loop and the layout collector both read it,
                            // and setting it after the scroll let a still-live reply add travel
                            // in between - the pill then reappeared a second later.
                            follow = true
                            scope.launch {
                                // PC animates nothing: its timeline moves the window and the
                                // next layout shows it (`virtualizer.tsx`, no transition
                                // anywhere). A per-frame glide over a long session is what
                                // made a far jump lag - the owner, 24 Nov: *"LAG PAG NA JUMP TO
                                // LATEST AKO SA MALAYO HINDI SMOOTH"*. One snap, clamped at
                                // the content end, drained pass by pass: a single `scrollBy`
                                // consumes only the current layout's travel, so a tall turn
                                // parked short of its own end and `follow` looked dead.
                                val moved = pinToEnd()
                                val li = listState.layoutInfo
                                val l = li.visibleItemsInfo.lastOrNull()
                                val gap = if (l == null) 0 else
                                    li.viewportEndOffset - (l.offset + l.size) - li.afterContentPadding
                                android.util.Log.e(
                                    "ZAPP",
                                    "jump: turns=${turns.lastIndex} total=${li.totalItemsCount}" +
                                        " lastVis=${l?.index ?: -1} moved=$moved" +
                                        " canForward=${listState.canScrollForward} gap=$gap",
                                )
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Jump to latest", fontSize = 10.5.sp, color = TextMid)
                    Spacer(Modifier.width(4.dp))
                    Text("\u2304", fontSize = 10.sp, color = TextDim)
                }
            }

            // `screen.tsx:508` `data-slot="session-side-panel-presence"` -> `side-region.tsx:118`:
            // the side region is an `<aside>` pinned to the row's END, `h-full shrink-0 min-w-0
            // flex overflow-hidden bg-v2-background-bg-base rounded-[10px]
            // shadow-[--v2-elevation-raised]`. It is a pane lying on the session, not another
            // screen: the transcript keeps its width and place, the header toggle stays live.
            //
            // Width: `session-panel-width.ts:11` `SIDE_PANE_WIDTH_MIN = 320` is the pane's own
            // hard minimum, and `sessionPanelWidthCss` keeps 8px between it and the chat, so the
            // pane is `320dp` clamped to `screenWidth - 8dp`.
            if (paneOpen) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(
                            minOf(
                                320.dp,
                                LocalConfiguration.current.screenWidthDp.dp - 8.dp,
                            )
                        )
                        .fillMaxHeight()
                        .padding(top = 4.dp, bottom = 4.dp)
                        .shadow(10.dp, RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .background(Bg),
                ) {
                    SidePane(
                        tabs = paneTabs,
                        activeKey = paneActive,
                        // `Wa.Content value={shownNewTab ?? pane.active() ?? "empty"}`.
                        placeholder = newTabChosen,
                        focused = paneFocused,
                        recent = paneRecent,
                        onSelect = { key ->
                            paneActive = key
                            paneFocused = true
                            // `select.tab` - the placeholder stops being shown.
                            region = SidePanel.reduce(
                                region,
                                SidePanel.Event.SelectTab(SidePanel.Pane.SIDE),
                            ).state
                        },
                        onClose = { closePaneTab(it) },
                        onOpen = { kind ->
                            paneFocused = true
                            openPaneKind(kind)
                        },
                        onReopen = { entry ->
                            paneFocused = true
                            reopenPaneTab(entry)
                        },
                        // `open.new-tab` -> `iC`: a fresh `new:<serial>`, appended and chosen.
                        onEmptyTab = {
                            paneFocused = true
                            region = SidePanel.open(region, SidePanel.Pane.SIDE)
                            paneOpen = true
                        },
                        // The placeholder's own close: `dismissNewTab` -> `close` with `empty`.
                        onClosePane = {
                            val id = sidePane.chosen ?: sidePane.ids.lastOrNull()
                            if (id != null) closePlaceholder(id) else paneOpen = false
                        },
                    ) { tab ->
                        when (tab.kind) {
                            SidePanel.Kind.REVIEW -> ReviewPanel(
                                sessionKey = active?.id ?: "",
                                visible = true,
                                modes = reviewModes,
                                mode = reviewMode,
                                onMode = { vm.selectReviewMode(it) },
                                files = reviewFiles,
                                noVcs = reviewNoVcs,
                                loading = reviewLoading,
                                error = reviewError,
                                openFiles = reviewOpenFiles,
                                activeFile = reviewActiveFile,
                                onToggleFile = { vm.toggleReviewFile(it) },
                                onExpandAll = { vm.toggleReviewExpandAll() },
                                onFocusFile = { vm.focusReviewFile(it) },
                                onRetry = { vm.loadReview() },
                                onInitRepo = { vm.createRepository() },
                                onOpenFile = { vm.openFile(it) },
                                onClose = { closePaneTab(tab.key) },
                                stagedRevert = active?.revert != null,
                                onClearRevert = { vm.clearRevert() },
                                onOpenTerminal = { openPaneKind(SidePanel.Kind.TERMINAL) },
                            )

                            SidePanel.Kind.FILE ->
                                if (tab.key == "open-file") {
                                    OpenFilePane(
                                        rows = fileRows,
                                        dir = fileDir,
                                        changed = collectChangedFiles(messages)
                                            .mapNotNull { it.file }
                                            .distinct(),
                                        tab = fileTab,
                                        onTab = { fileTab = it },
                                        onDir = { visitDir(it) },
                                        onPick = { vm.openFile(it) },
                                        canBack = dirAt > 0,
                                        canForward = dirAt < dirHistory.lastIndex,
                                        onBack = {
                                            if (dirAt > 0) {
                                                dirAt--
                                                vm.loadFiles(dirHistory[dirAt].ifBlank { null })
                                            }
                                        },
                                        onForward = {
                                            if (dirAt < dirHistory.lastIndex) {
                                                dirAt++
                                                vm.loadFiles(dirHistory[dirAt].ifBlank { null })
                                            }
                                        },
                                    )
                                } else {
                                    fileViews[tab.key.removePrefix("file:")]?.let { FileBody(it) }
                                }

                            SidePanel.Kind.TERMINAL ->
                                // A `pty:` tab is one persistent pty and renders the terminal;
                                // the legacy `terminal` key still shows the background shells.
                                vm.ptySession(tab.key.removePrefix("pty:"))?.let { sess ->
                                    TerminalView(sess)
                                } ?: ShellsPanel(
                                    shells = shells,
                                    visible = true,
                                    onKill = { vm.killShell(it) },
                                    output = { id -> vm.shellOutputOf(id) },
                                    onClose = { closePaneTab(tab.key) },
                                    live = bgShells,
                                    onRun = { vm.runShell(it) },
                                    onDetach = { cmd, id -> vm.detachShell(id, cmd) },
                                    chrome = false,
                                )

                            SidePanel.Kind.BROWSER -> BrowserPane(
                                startUrl = browserUrls[tab.key].orEmpty(),
                                onUrl = { browserUrls = browserUrls + (tab.key to it) },
                            )

                            // `context/renderer.tsx` - on a phone placement it renders
                            // `<SessionContextTab>` directly, with no padding wrapper.
                            SidePanel.Kind.CONTEXT -> SessionContextPanel(vm)
                        }
                    }
                }
            }
            }

            // Their live-turn footer. The label is `session.timeline.status.*`: `Working`,
            // or `Waiting for shell / approval / your answer`. It is NOT the in-turn step
            // vocabulary (`Thinking`, `Running commands` �) � those live inside the run rows.
            // A fixed-height band so the timeline never reflows when a turn starts or lands.
            // Their `background.blocking()`: only the tools that are still *inside* the live
            // turn can be moved out. With none running the server call is a documented no-op
            // ("idle requests are a no-op"), so the control must not be offered at all �
            // showing it on every busy turn is what made it look broken.
            val blocking = if (!sending) emptyList() else turns.lastOrNull()?.parts.orEmpty()
                .filter { p ->
                    // `task` is the server's name for a child session; `subagent` is the older
                    // alias. The timeline already draws both as a TaskCard, so the command that
                    // moves running work to the background has to see both as well - otherwise
                    // a turn blocked on `task` showed no way to detach it.
                    p.type == "tool" && isRunningPart(p) &&
                        (isShellPart(p) || isSubagentPart(p))
                }
            Box(
                Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
            // A compaction has its own label (`ui.messagePart.compaction.running`) and does not
            // set `sending`, so the band has to be open for it too - otherwise the app looks
            // idle while the server rewrites the whole history.
            val compactingNow by vm.compacting.collectAsState()
            if (sending || compactingNow) {
                // the owner, 22 Nov: "I DONT SEE HER CHAT WRITING ITS JUST A PAUSE AND THEN A SEC
                // BLINK". Measured on this phone at 15:49 with a three-word prompt: first token
                // at 2.57 s, tokens 132 ms apart, then ONE gap of 16 671 ms, then the turn ended.
                // A sixteen-second hole with a blinking caret in it is indistinguishable from a
                // dead turn, and that is the whole complaint. The band already says "Working";
                // it now also counts, so a gap reads as time passing instead of as a hang.
                //
                // Stepped once a second on purpose - an infinite transition here is what held
                // this screen at 119 fps and 115% CPU earlier today.
                var elapsed by remember { mutableStateOf(0L) }
                LaunchedEffect(Unit) {
                    val started = System.currentTimeMillis()
                    while (true) {
                        elapsed = (System.currentTimeMillis() - started) / 1000
                        kotlinx.coroutines.delay(1_000)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // `session.timeline.status.*` + `ui.sessionTurn.status.*`, not a fixed
                    // "Working". The band used to be hard-coded while a dead `StatusLine`
                    // claimed otherwise, so a turn blocked on his approval looked identical to
                    // a turn that was running. Gates outrank the step: a permission, a question
                    // or a shell request means the turn is not progressing.
                    val live = turns.lastOrNull()?.parts.orEmpty()
                    val lastPart = live.lastOrNull()
                    ShimmerText(
                        TurnStatus.footer(
                            last = lastPart,
                            running = lastPart != null && isRunningPart(lastPart),
                            // A permission has no status field on this server: presence in the
                            // list IS the pending state. Same for a question request - and a
                            // question is a real gate now, not just a form.
                            approvals = perms.size,
                            answers = forms.size + pendingQuestions.size,
                            // `waitingShell`: a shell entry that is queued but has not started.
                            // The extract gives the string, not the condition; `pending` is the
                            // only status on `ShellEntry` that means "not running yet".
                            shells = shells.count { it.status == "pending" },
                            compacting = compactingNow,
                        ),
                    )
                    // The clock, in the same dim ink as the label, so a long first-token wait
                    // and a long gap between tokens both look like progress.
                    Text(
                        "  \u00b7  ${elapsed}s",
                        fontSize = 11.sp,
                        color = TextDim,
                    )
                    if (blocking.isNotEmpty()) {
                        Spacer(Modifier.width(14.dp))
                        Text(
                            "Move to background",
                            fontSize = 11.sp,
                            color = TextMid,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { vm.moveToBackground() }
                                .padding(horizontal = 3.dp, vertical = 2.dp),
                        )
                    }
                }
            } else if (chatStatus.isNotBlank() && chatStatus != "Connected" &&
                !chatStatus.startsWith("Reconnected automatically")
            ) {
                Text(
                    chatStatus,
                    fontSize = 10.sp,
                    // A failure is red (`--v2-state-fg-danger` = v2-red-500), not grey. The
                    // writer sets `statusFailed`; nothing here reads the words.
                    color = if (chatStatusFailed) Danger else TextDim,
                    maxLines = 1,
                )
            }
            }

            // Their capsule, ported from `activitySummary` (`i18n/language.tsx:701`).
            //
            // Two corrections to what this used to do:
            //
            //   * it counted `shells.size` - every shell the session had ever finished, because
            //     `/api/shell` keeps exited rows. Their description says "Show **running**
            //     subagents and shells", so the capsule stayed up and kept climbing after the
            //     work was done. Now `count { it.running }`.
            //   * it never counted subagents. Their sentence names both groups and joins them
            //     with " · ", so a turn running two children and no shell read nothing here.
            //
            // Zero is silent on their side (`counts.shells ? … : undefined`), and `label`
            // returns "" for that, so the whole row disappears rather than printing "0 shells".
            val runningShells = shells.count { it.running } + bgShells.size
            val runningAgents = bgAgents.size
            val activity = ActivitySummary.label(runningAgents, runningShells)
            // Their gate, `route.js` 646 760: `enabled: () => n.general.backgroundActivity() !== 'off'`.
            // ZAPP stored this key since Pass 40 and never read it, so the capsule could not be
            // turned off. `version` is watched so a change in Settings lands without a restart.
            val bgActivityVersion by com.zapp.net.settings.SettingsPrefs.version.collectAsState()
            val bgEnabled = remember(bgActivityVersion) {
                com.zapp.net.settings.SettingsPrefs.string(
                    appCtx,
                    com.zapp.net.settings.SettingsPrefs.KEY_BACKGROUND_ACTIVITY,
                    com.zapp.net.settings.SettingsPrefs.DEFAULT_BACKGROUND_ACTIVITY,
                ) != "off"
            }
            if (bgEnabled && activity.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShellChip(
                        count = runningShells,
                        running = true,
                        label = activity,
                        onClick = { showShells = true; vm.loadShells() },
                    )
                }
            }

            val req = perms.firstOrNull()
            if (req != null) {
                PermissionCard(
                    req = req,
                    extra = perms.size - 1,
                    onDecision = { d -> vm.answerPermission(req, d) },
                )
            }

            // a form is the other way a tool waits on him: same slot, above the composer
            val fm = forms.firstOrNull()
            if (fm != null) {
                FormCard(vm = vm, form = fm, extra = forms.size - 1)
            }

            // `question.v2.*` - the `question` tool. It blocks the turn exactly like a
            // permission does, and before this card the request arrived and nothing
            // read it, so the turn hung with no way to move it from the phone.
            val qr = pendingQuestions.firstOrNull()
            if (qr != null) {
                QuestionCard(vm = vm, req = qr, extra = pendingQuestions.size - 1)
            }

            // The real quota, in the slot where their desktop puts it: one card, their copy,
            // and no dead end - an address-scoped limit is already rotating behind it.
            val wall by vm.quotaWall.collectAsState()
            val tooBig by vm.contextTooBig.collectAsState()

            // `session-free-limit` occupies the composer slot. Their composer tree
            // (`route-BPCnB8DM.js`, 710 736) is
            //   `when !x.active() -> <composer>   fallback -> <session-free-limit card>`
            // so while the free window is live and nothing is sending, the card IS the
            // composer - it is not a banner above it. `x.active()` is
            // `!sending && resetAt !== null && resetAt > now` (`GL`, 653 220), and the card
            // retires on its own clock, which their 30 s interval drives.
            val limitCtx = LocalContext.current
            var clockMs by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(30_000)
                    clockMs = System.currentTimeMillis()
                }
            }
            val persistedFreeUntil = remember(clockMs, sending, wall) {
                runCatching {
                    com.zapp.net.settings.GuardPrefs.freeLimitUntil(limitCtx)
                }.getOrDefault(0L)
            }
            // ONE card, never two.            //
            // the owner, 2026-11-24: *"MAY DALAWANG FREE LIMIT REACHED DIALOG"*. Both this card
            // and `QuotaCard` carried the same failure: the live refusal set `quotaWall`, and
            // the window the guard persists from the same refusal armed `FreeLimitCard` in the
            // same slot. One wall drew two "Free limit reached" panels.
            //
            // The live refusal also states its own deadline (`retryAfterMs`), so read that
            // first: the single card appears immediately and shows the server's real clock,
            // even in the gap before the guard has written the preference.
            // The live refusal states its own deadline (`retryAfterMs`). Anchor it to the moment
            // that wall reached the screen, NOT to the 30 s tick: `clockMs + retryAfterMs`
            // re-anchored the deadline on every tick, so one wall kept the card up forever on
            // an idle screen. Measured 24 Nov: no wall in logcat for 30 min, card still up.
            val wallArrivedMs = remember(wall) { System.currentTimeMillis() }
            val wallFreeUntil = (wall as? com.zapp.net.guard.QuotaWall.FreeLimit)
                ?.retryAfterMs
                ?.takeIf { it > 0L }
                ?.let { wallArrivedMs + it }
            val freeUntil = wallFreeUntil ?: persistedFreeUntil
            val freeActive = !sending && freeUntil > clockMs

            // the owner, 2026-11-24: *"BAT HINDI MAKA CHAT?"* - the free-budget note must never
            // take the composer away. Their tree swaps the card IN (`when !x.active()`,
            // 710 736), but ZAPP is free and unlimited by his design, so the note sits
            // ABOVE the box and the box is always there to type in.
            if (freeActive) {
                FreeLimitCard(resetAt = freeUntil)
            }
            run {
                // `QuotaCard` owns every OTHER wall and the oversized-context case. A free
                // limit was drawn above, and its `wall.message` still carries the "Connect a
                // provider or subscribe to OpenCode Go" sentence the owner ordered removed, so it
                // must not be drawn a second time here.
                val otherWall = wall?.takeIf { it !is com.zapp.net.guard.QuotaWall.FreeLimit }
                if (otherWall != null || tooBig != null) {
                    QuotaCard(
                        wall = otherWall,
                        tooBig = tooBig,
                        onClose = {
                            vm.quotaWall.value = null
                            vm.contextTooBig.value = null
                        },
                        onNewChat = {
                            vm.quotaWall.value = null
                            vm.contextTooBig.value = null
                            vm.newSession()
                        },
                    )
                }

                Composer(
                vm = vm,
                sending = sending,
                queued = queued,
                seed = replySeed,
                onSeedUsed = { replySeed = null },
                onEditQueued = { id, txt ->
                    vm.cancelQueued(id)
                    replySeed = txt
                },
                onSend = { t, f, q ->
                    val sk = attachedSkills
                    val ok = vm.send(t, f, queue = q, skills = sk)
                    // a refused send keeps the skill chips too, not just the text
                    if (ok) attachedSkills = emptyList()
                    ok
                },
                onCancelQueued = { vm.cancelQueued(it) },
                onShell = { cmd -> vm.sendShell(cmd) },
                onMoveToQueue = { vm.moveToQueue(it) },
                onMoveToSteer = { vm.moveToSteer(it) },
                onReview = { openPaneKind(SidePanel.Kind.REVIEW) },
                // Typing in the composer takes DOM focus off `#review-panel` on their side, so the
                // selected tab's title drops back to `--v2-text-text-muted`.
                onFocusChanged = { hasFocus -> if (hasFocus) paneFocused = false },
                draftScope = active?.id ?: activeDraft ?: "new",
                skills = attachedSkills,
                onRemoveSkill = { id -> attachedSkills = attachedSkills.filterNot { it.id == id } },
                onManageModels = {
                    // `dialog.model.manage` from the picker's footer - their Manage models
                    // dialog, which on our side is the Models page of Settings.
                    settingsStart = Page.MODELS
                    showSettings = true
                },
            )
            }
            if (titlebarBottom) titleStrip()
        }
    }

    ExtensionsOverlay(
        vm,
        ext,
        onAttachSkill = { s ->
            val pick = com.zapp.net.api.SkillPick(s.id, s.name.ifBlank { s.id })
            attachedSkills = (attachedSkills + pick).distinctBy { it.id }
        },
        onOpenUsage = {
            ext = ExtPanel.NONE
            showContext = true
        },
        onDraft = { prompt -> vm.draftWithPrompt(prompt) },
        onManageMcp = {
            // Their `mcp.toggle` is the `/mcp` dialog; ours is the Extensions page of Settings,
            // which is the only surface that lists every MCP server with a switch.
            settingsStart = Page.EXTENSIONS
            showSettings = true
        },
    ) { ext = it }
    SearchPopover(vm, showFinder) { showFinder = false }
    // Closing the board is a decision he made, so it is remembered. Without this the board came
    // back on its own the next time the session list refreshed or a tab changed.
    SessionsOverlay(vm, showSessions) {
        showSessions = false
        boardDismissed = true
    }
    // File and Terminal are tabs of the side pane now, so their standalone overlays are gone.
    // `FileOverlay` remains for any surface that still wants the full-screen viewer.
    // Full-screen, like their `layout.route().type === "settings"` - it replaces the app
    // rather than floating over it, so the drawer is unreachable while it is open.
    if (showSettings) SettingsScreen(vm, settingsStart) {
        showSettings = false
        settingsStart = null
    }

    // Their Context tab - `mobile.title: "Usage"` on their phone build. Full-screen for the same
    // reason Settings is: it is a destination, not a popover.
    if (showContext) ContextTab(vm) { showContext = false }

    // `toast.permissions.autoaccept.*` - PC raises a toast when the flag flips. Two lines, no
    // buttons, gone in under three seconds; drawn last so it sits over every other surface.
    vm.toast.collectAsState().value?.let { t ->
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 104.dp, start = 24.dp, end = 24.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Surface)
                .border(1.dp, Border, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text(t.title, fontSize = 11.5.sp, color = TextPrimary)
            Text(t.description, fontSize = 10.5.sp, color = TextDim)
        }
    }
    }
}

@Composable
private fun TurnRow(
    t: Turn,
    isLast: Boolean,
    latestUser: Boolean,
    sending: Boolean,
    /** the assistant message the wire fed on this turn - see `MainViewModel.liveMsgId` */
    liveId: String?,
    onFork: (String?) -> Unit,
    onUndo: (String) -> Unit,
    onReply: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    /** `markdown-session-links.ts:9` — a reference in the prose opens that session. */
    sessionTitle: ((String) -> String?)? = null,
    onOpenSession: ((String) -> Unit)? = null,
    /** `inlineCodeKind` (`markdown-inline-code-kind.ts:45`) — a code span that names a file. */
    onOpenFile: ((String) -> Unit)? = null,
    /** `markdown-image.ts` - the reader behind `![alt](src)`. */
    onReadImage: (suspend (String) -> ByteArray?)? = null,
    /** `settings.general.timelineDetail`, defaulting to the shipped `compact` preset. */
    detail: TimelineDetail.Detail = TimelineDetail.default,
    /** `settings.general.showTurnDiffs` - default false, so the index is opt-in. */
    showTurnDiffs: Boolean = false,
    /** `settings.general.foldUserMessages` - default false, so the fold is opt-in. */
    foldUserMessages: Boolean = false,
) {
    val u = t.user
    if (u != null) {
        val userImages = imageParts(u)
        val userBody = text_of(u)
        val clip = LocalClipboardManager.current
        var menu by remember(u.info.id) { mutableStateOf(false) }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            // Their message menu, ported 1:1 from route-CMUQLRzL.js:
            //   <time> / Copy message / Fork from here / [Reply] / --- / Undo | Revert to here
            // `Undo` when it is the newest user message, `Revert to here` otherwise; both rewind
            // the session and hand the text back to the composer (`undo-rewind` draft).
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                // `Q.GroupLabel` - the `<time>`, or `ui.message.pending` ("Pending") when the
                // message has not been stamped yet.
                Text(
                    MessageCopy.stamp(u.info.time?.created),
                    fontSize = 10.5.sp,
                    color = TextDim,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                )
                SessionMenuItem(MessageCopy.COPY_MESSAGE) {
                    menu = false
                    clip.setText(AnnotatedString(userBody))
                }
                SessionMenuItem(MessageCopy.FORK) {
                    menu = false
                    onFork(t.answer?.id)
                }
                // our addition on top of their set, he asked for it by name
                SessionMenuItem("Reply") {
                    menu = false
                    val quote = userBody.lineSequence().joinToString("\n") { "> $it" }
                    onReply(quote + "\n\n")
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.dp).background(Line))
                // `!!e.latest ? ui.message.undoMessage : ui.message.revertMessage`
                SessionMenuItem(MessageCopy.revertLabel(latestUser)) {
                    menu = false
                    onEdit(u.info.id, userBody)
                }
            }
            // `[data-slot=user-message-body]`: `width:fit-content; max-width:min(82%,64ch);
            // margin-inline-start:auto`. `fillMaxWidth(0.82f)` forced the width instead of
            // wrapping the content, which is what clipped "continue" down to "cont".
            // PC's `64ch` at 14px ≈ 430dp, so 82% of the phone wins by a wide margin.
            val bubbleMax = (LocalConfiguration.current.screenWidthDp * 0.82f).dp
            Column(
                Modifier
                    .widthIn(max = bubbleMax)
                    .background(Surface2, RoundedCornerShape(10.dp))
                    .pointerInput(u.info.id) {
                        detectTapGestures(onLongPress = { menu = true })
                    }
                    // `[data-slot=user-message-text]`: padding 8px 12px, radius 10
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                val txt = text_of(u)
                if (txt.isNotBlank()) {
                    // 13px is PC's own `--font-size-small` step; a desktop 14px only fits
                    // ~58 characters across a phone, which is what made the transcript read
                    // heavy. 13/20 keeps PC's compact-line rhythm at a phone measure.
                    //
                    // `[data-slot=user-message-fold]` - a long prompt folds to 8 line-heights
                    // with a 40px fade and a `Read more` toggle. PC's rule is
                    // `Vl = { over: 12, show: 8 }`; before this a pasted wall of text simply
                    // filled the screen.
                    FoldableUserText(
                        id = u.info.id,
                        text = txt,
                        sessionTitle = sessionTitle,
                        onOpenSession = onOpenSession,
                        onOpenFile = onOpenFile,
                        enabled = foldUserMessages,
                    )
                }
                userImages.forEach { ImageBlock(it) }
                // non-image attachments: name only, like their file rows
                fileNames(u).forEach { n ->
                    Row(
                        Modifier.padding(top = 5.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Surface)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            OcIcons.Plus, null,
                            tint = TextDim,
                            modifier = Modifier.size(11.dp).graphicsLayer { rotationZ = 45f },
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(n, fontSize = 10.sp, color = TextMid, maxLines = 1)
                    }
                }
            }
        }
    }
    if (t.parts.isEmpty()) return

    val parts = t.parts
    // `timeline-next/projection.ts:230 workedDuration(user, steps)`: from the turn's start
    // (the user's message, else the first step) to the end of the step he stopped (its
    // `completed`, else its parts' latest time). Only `Worked` uses it.
    val workedMs = remember(parts, t.user?.info?.time?.created) {
        val start = t.user?.info?.time?.created ?: parts.firstOrNull()?.time?.created
        val end = parts.mapNotNull { it.time?.completed }.maxOrNull()
            ?: parts.mapNotNull { it.time?.created }.maxOrNull()
        if (start == null || end == null) null else (end - start).coerceAtLeast(0)
    }
    val segs = remember(parts, detail, workedMs) { segments(parts, detail, workedMs) }
    // live == the server is busy (time.busy > time.idle), same signal their timeline uses.
    // Plus: this is the message the wire actually fed on the current turn. That second term is
    // what makes the reveal survive `session.idle` landing in the same breath as the text.
    // Nothing here may depend on the scroll position: a row that changes its own height while
    // he reads is what made the page shift under his thumb.
    //
    // `wireFed` is deliberately NOT gated on `isLast`.
    //
    // Measured 23 Nov 22:36: the 400-word story turn took two stream resets
    // (`stream reset x2 on 162.159.192.9:2408`), the transcript was replaced, and the
    // resync came back with the reply as a new trailing part. `active` carried `isLast`,
    // so it went false while `liveMsgId` still pointed at exactly this message - the
    // reveal then initialised at full length and drew 12 400 bytes in one step
    // (`rows=6 bytes=12400`, one sample). A message the wire fed on this turn must type
    // out whether or not the server happened to put a newer row after it.
    val wireFed = liveId != null && t.answer?.id == liveId
    // `active` used to carry `wireFed`, and `liveMsgId` is only cleared when another session is
    // opened - so the newest finished turn stayed "live" for the rest of the session and never
    // drew its footer at all. the owner, 24 Nov: *"PAG NATAPOS NA SYA WALANG COPY"*. The footer
    // (copy, revert, fork, model strip) belongs to a turn that is no longer sending; the reveal
    // still keys on `wireFed`, which is what it was for.
    val active = isLast && sending

    // assistant-message: `flex-direction:column; gap:12px`
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        segs.forEachIndexed { segIndex, seg ->
            when (seg) {
                // Hold to select, then drag the handles and copy - Compose's own selection
                // container gives the platform behaviour (magnifier, handles, Copy/Share).
                is Seg.Prose -> androidx.compose.foundation.text.selection.SelectionContainer {
                    // Letter-by-letter reveal, the owner 22 Nov: "hindi nalang tayo gagawa ng
                    // animation like type writer animation word by word ... letters to letters
                    // but fast and it can saw like the opencode official".
                    //
                    // Why this exists at all: the server does not stream tokens. Measured on
                    // this phone, one turn arrived as 7 text deltas of ~137 characters at
                    // ~200 ms apart, so the official client draws 20 words at a time too - it
                    // just never looks like a jump because the block lands while the eye is
                    // still moving. Typing the *new tail* out over ~0.5 s gives the same feel
                    // from the same bytes.
                    //
                    // `shown` starts at the full length on purpose. A segment composed for the
                    // first time - history, a reopened session, a scroll-back - is therefore
                    // drawn instantly and never re-typed. Only a segment whose text GROWS
                    // animates, and it only ever animates the characters it just gained.
                    val full = seg.text
                    // A segment that is live the first time it is composed starts at zero, so the
                    // *first* block of a reply types out too - not just the ones that grow after
                    // it. History, a reopened session and scroll-back are never `active`, so they
                    // start at full length and are never re-typed.
                    var shown by remember(segIndex) {
                        // `wireFed` is part of the starting condition for the same reason it is
                        // part of `active`: a transcript replaced mid-turn composes this block
                        // for the first time while `isLast` may already be false, and starting
                        // at `full.length` is what teleported the reply.
                        //
                        // KEYED ON `segIndex`. It was `remember { }` with no key, inside a
                        // `forEach` over a list whose shape changes during a turn (tool card
                        // first, prose after). Compose then reuses that slot for the next
                        // segment, so `shown` survived a segment swap and the initial block of a
                        // reply could be drawn at full length with no `reveal:` line in the log
                        // at all - measured 23 Nov 22:59, the whole turn printed zero reveal
                        // lines. Keyed, every segment gets its own state and its own line.
                        val start = if (active || wireFed) 0 else full.length
                        android.util.Log.e(
                            "ZAPP",
                            "reveal: seg=$segIndex msg=${t.answer?.id?.take(12)} start=$start" +
                                " len=${full.length} active=$active wireFed=$wireFed" +
                                " isLast=$isLast sending=$sending",
                        )
                        mutableStateOf(start)
                    }
                    // A segment composed BEFORE the wire told us which message is live starts at
                    // full length - correctly, because at that instant it looked like history.
                    // When `wireFed` then arrives for this very message, that block has to be
                    // re-typed from zero, or the first block of every reply is still a jump.
                    // Runs once per segment: `seeded` is what stops it from looping.
                    var seeded by remember(segIndex) { mutableStateOf(false) }
                    LaunchedEffect(segIndex) {
                        if (!seeded && (active || wireFed) && full.isNotEmpty()) {
                            seeded = true
                            if (shown >= full.length) {
                                android.util.Log.e(
                                    "ZAPP",
                                    "reveal: seg=$segIndex adopted live at len=${full.length}",
                                )
                                shown = 0
                            }
                        }
                    }
                    // `active` is deliberately NOT in the key. The server ends a turn with one
                    // whole-text delta and then `session.idle` in the same breath, so
                    // `isLast && sending` went false while the reveal was still running and this
                    // effect restarted into the `!active` branch - snap, the text jumped to full.
                    // the owner, 23 Nov: "its the same even yo chat cant reply agad".
                    //
                    // Starting value still comes from `active`: history and scroll-back begin at
                    // full length, a live first block begins at zero. But once the reveal owns
                    // characters it finishes them, whatever the turn status says.
                    LaunchedEffect(full) {
                        while (shown < full.length) {
                            val backlog = full.length - shown
                            // ~30 frames (half a second) to close any backlog, but never less
                            // than one character per frame, so a slow trickle still reads as
                            // typing rather than as a freeze.
                            //
                            // The per-frame cap is what stops a *recovered* reply from
                            // teleporting. `backlog / 30` alone closes any backlog in the same
                            // ~30 frames, so 137 characters and 3 000 characters both landed
                            // in about half a second - the whole answer appearing at once.
                            // Measured 23 Nov: when the provider ends a turn early the app reads
                            // the transcript once, and that single segment can be the whole
                            // reply. Capped at 24 characters a frame (~1 400/s at 60 fps) a
                            // 3 000-character block types for ~2 s, which is still faster than
                            // reading and no longer a jump.
                            val step = maxOf(1, minOf(backlog / 30, 24))
                            shown = minOf(full.length, shown + step)
                            kotlinx.coroutines.delay(16)
                        }
                    }
                    MarkdownText(
                        if (shown >= full.length) full else full.substring(0, shown),
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = 13.sp,
                        sessionTitle = sessionTitle,
                        onSession = onOpenSession,
                        onOpenFile = onOpenFile,
                        readImage = onReadImage,
                    )
                }
                is Seg.Run -> RunRow(seg.parts, live = active)
                is Seg.Thought -> ThoughtRow(seg.parts, live = active)
                is Seg.Todos -> TodosBlock(seg.items)
                is Seg.Note -> NoticeRow(seg.text)
                is Seg.Sys -> SystemRow(seg.text, seg.sub)
                is Seg.Outcome -> OutcomeRow(seg.outcome, seg.error)
                is Seg.Compaction -> LabelDivider(compactionLabel(seg.status))
                Seg.Divider -> LabelDivider("Interrupted")
                Seg.Activity -> Unit
                is Seg.Worked -> WorkedRow(seg.ms)
                is Seg.Retry -> RetryRow(seg.attempt, seg.next, seg.message)
            }
        }
        // PC: the copy glyph sits under every finished reply; holding the message reveals
        // `Build � DeepSeek V4.1 Flash Free Max � 2m 3s � 198.1 tok/s` (oc capture 1).
        // PC's `session-turn-diffs-group`: the turn ends with an index of every file it
        // touched — `N files changed`, 44px header, one compact row per file that expands
        // into the same diff view the Review panel uses.
        if (!active && showTurnDiffs) {
            val changed = remember(parts) {
                parts.flatMap { it.state?.metadata?.files.orEmpty() }
                    .filter { !it.file.isNullOrBlank() }
                    .distinctBy { it.file }
            }
            if (changed.isNotEmpty()) TurnDiffs(changed)
        }
        // `sources/experiment.ts:115` — the "N sources" chip under a reply that searched the web.
        // It only draws when `sources.ts` found something, so a turn without a search is unchanged.
        if (!active) SourcesChip(parts)
        if (!active && t.answer != null) MessageActions(t.answer, parts)
    }
}

/**
 * `[data-slot=user-message-fold]` - the fold on a long user message.
 *
 * PC's rule, measured from the compiled renderer (`route-CMUQLRzL.js:73875`):
 *
 * ```
 * var Vl = { over: 12, show: 8 }
 * i() = lines > Vl.over            // "is it worth folding"
 * o() = i() && !expanded           // "is it folded right now"
 * max-height: `${Vl.show}lh`       // 8 line-heights, not pixels
 * [data-folded] mask-image: linear-gradient(#000 calc(100% - 40px), #0000)
 * ```
 *
 * and the toggle, from `index-CbZCBGB7.css`:
 *
 * ```
 * height:24px; font-size:13px; line-height:--line-height-compact; color:--v2-text-text-muted;
 * gap:2px; margin-top:2px; font-weight:530
 * [aria-expanded=true] [data-slot=icon-svg] { transform: rotate(180deg) }
 * ```
 *
 * The line count is measured, not guessed: `lineHeight` is 20sp, so `8lh` is
 * `8 * 20.sp` converted with the live font scale - the same quantity as PC's `lh` unit.
 * The counter only ever grows, so a clipped layout pass can never un-fold the block.
 */
@Composable
private fun FoldableUserText(
    id: String,
    text: String,
    sessionTitle: ((String) -> String?)?,
    onOpenSession: ((String) -> Unit)?,
    /** `@path` in his own words opens the file, like their PC does. */
    onOpenFile: ((String) -> Unit)? = null,
    /** `settings.general.foldUserMessages`, default false - off means the old plain text. */
    enabled: Boolean = false,
) {
    var lines by remember(id, text) { mutableIntStateOf(0) }
    var expanded by remember(id) { mutableStateOf(false) }
    val lineHeight = 20.sp
    val lhDp = with(androidx.compose.ui.platform.LocalDensity.current) { lineHeight.toDp() }
    val cut = enabled && MessageCopy.folded(lines, expanded)

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .then(if (cut) Modifier.heightIn(max = lhDp * MessageCopy.FOLD_SHOW) else Modifier)
                .clipToBounds()
                .then(
                    if (cut) {
                        Modifier
                            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                            .drawWithContent {
                                drawContent()
                                val fade = MessageCopy.FOLD_FADE_DP.dp.toPx().coerceAtMost(size.height)
                                if (size.height > 0f && fade > 0f) {
                                    drawRect(
                                        brush = Brush.verticalGradient(
                                            0f to Color.Black,
                                            1f - fade / size.height to Color.Black,
                                            1f to Color.Transparent,
                                        ),
                                        blendMode = BlendMode.DstIn,
                                    )
                                }
                            }
                    } else {
                        Modifier
                    },
                ),
        ) {
            Text(
                remember(text, sessionTitle, onOpenSession, onOpenFile) {
                    plainSessionSpan(
                        text, TextPrimary, sessionTitle, onOpenSession, onOpenFile,
                    )
                },
                fontSize = 13.sp,
                lineHeight = lineHeight,
                color = TextPrimary,
                onTextLayout = { r -> if (r.lineCount > lines) lines = r.lineCount },
            )
        }
        if (enabled && MessageCopy.foldable(lines)) {
            // PC's toggle sits at the block's inline-start (`width:fit-content` inside a
            // left-aligned body), not at the bubble's right edge, so it is wrapped in a
            // full-width row that starts at the left.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Row(
                    Modifier
                        .padding(top = 2.dp)
                        .height(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        MessageCopy.foldLabel(expanded),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight(530),
                        color = TextMid,
                        maxLines = 1,
                    )
                    Icon(
                        OcIcons.ChevronDown,
                        contentDescription = null,
                        tint = TextDim,
                        modifier = Modifier
                            .size(14.dp)
                            .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                    )
                }
            }
        }
    }
}

/**
 * `[data-slot=text-part-copy-wrapper]` - the copy control and the meta strip under a reply.
 *
 * PC's structure (`route-CMUQLRzL.js` `$l()`, function `g()`):
 *
 * ```
 * <footer style="justify-content:flex-start; align-items:center; gap:4px;
 *                height:20px; margin-top:8px">
 *   <CopyButton label={copied ? "Copied" : "Copy response"} size="small" onClick=copy(text)>
 *   if (items.length) <MetaList items={items} slot="text-part" />
 *   if (speed)        <Speed>{speed}</Speed>
 * ```
 *
 * So the **label is always visible** - it is the button's own text, not a hover affordance.
 * ZAPP only had the glyph, so the control never said what it did; `Copied` then replaced the
 * duration instead of the label, which is why the feedback looked like a glitch.
 *
 * The items are `[duration, interrupted?]` on a phone (`ie('(max-width: 767px)')` drops the
 * agent and model), and holding the row adds them back.
 */
@Composable
private fun MessageActions(info: MessageInfo, parts: List<Part>) {
    var meta by remember(info.id) { mutableStateOf(false) }
    var copied by remember(info.id) { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    val body = remember(parts) {
        parts.filter { it.type == "text" }.mapNotNull { it.text?.trim() }
            .filter { it.isNotEmpty() }.joinToString("\n\n")
    }
    // route-CMUQLRzL.js `$l()`: on <=767px the agent+model labels are dropped from the strip,
    // only the duration survives, so the phone row is `2m 3s` and holding adds the rest.
    val line = remember(info) { metaLine(info) }
    val compact = remember(info) { metaDuration(info) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(MessageCopy.COPY_RESET_MS)
            copied = false
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            // holding anywhere on the footer line reveals the agent/model/speed strip,
            // the way hovering the message does on the PC
            .pointerInput(info.id) {
                detectTapGestures(onLongPress = { meta = !meta })
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(5.dp))
                .pointerInput(body) {
                    detectTapGestures(
                        onTap = {
                            if (body.isNotEmpty()) {
                                clip.setText(AnnotatedString(body))
                                copied = true
                            }
                        },
                    )
                }
                .padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CopyGlyph(if (copied) Accent else TextDim)
            // `copied ? ui.message.copied : ui.message.copyResponse`
            Text(
                if (copied) MessageCopy.COPIED else MessageCopy.COPY_RESPONSE,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = if (copied) Accent else TextDim,
                maxLines = 1,
            )
        }

        val shown = if (meta) line else compact
        if (shown.isNotBlank()) {
            SemanticText(
                shown,
                color = TextDim,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Their copy glyph: two offset rounded sheets, outline only. */
@Composable
private fun CopyGlyph(color: Color) {
    Canvas(Modifier.size(11.dp)) {
        val u = size.width / 11f
        val r = CornerRadius(1.6f * u)
        val stroke = Stroke(width = 1.05f * u)
        drawRoundRect(
            color,
            topLeft = Offset(0.6f * u, 3.2f * u),
            size = Size(7.2f * u, 7.2f * u),
            cornerRadius = r,
            style = stroke,
        )
        drawRoundRect(
            color,
            topLeft = Offset(3.2f * u, 0.6f * u),
            size = Size(7.2f * u, 7.2f * u),
            cornerRadius = r,
            style = stroke,
        )
    }
}

/** `Build � DeepSeek V4.1 Flash Free Max � 2m 3s � 198.1 tok/s`, exactly their order. */
/** Their mobile branch: duration only, `2m 3s` / `41s`. */
private fun metaDuration(info: MessageInfo): String {
    val ms = info.time?.durationMs?.takeIf { it > 0 } ?: return ""
    return formatDuration(ms)
}

private fun metaLine(info: MessageInfo): String {
    val out = ArrayList<String>()
    agentLabel(info.agent)?.let { out.add(it) }
    modelLabel(info.modelID, info.variant)?.let { out.add(it) }
    info.time?.durationMs?.takeIf { it > 0 }?.let { out.add(formatDuration(it)) }
    val tokensOut = info.tokens?.output ?: 0L
    val ms = info.time?.streamMs
    if (tokensOut > 0 && ms != null && ms > 0) {
        val tps = tokensOut * 1000.0 / ms
        out.add(String.format(java.util.Locale.US, "%.1f tok/s", tps))
    }
    return out.joinToString(" · ")
}

private fun agentLabel(agent: String?): String? = when (agent?.trim()?.lowercase()) {
    null, "" -> null
    "build" -> "Build"
    "plan" -> "Plan"
    "explore" -> "Explore"
    else -> agent!!.replaceFirstChar { it.uppercase() }
}

private fun modelLabel(id: String?, variant: String?): String? {
    val words = id.orEmpty().split('-', '_').filter { it.isNotBlank() }.map { w ->
        when (val lw = w.lowercase()) {
            "deepseek" -> "DeepSeek"
            "glm" -> "GLM"
            "gpt" -> "GPT"
            "qwen" -> "Qwen"
            "kimi" -> "Kimi"
            "claude" -> "Claude"
            "gemini" -> "Gemini"
            "mini" -> "Mini"
            "max" -> "Max"
            else -> w.replaceFirstChar { it.uppercase() }
        }
    }.toMutableList()
    if (!variant.isNullOrBlank()) words.add(variant.replaceFirstChar { it.uppercase() })
    return words.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

/** `ui.messagePart.compaction.done` = `Compacted`: their hairline-with-a-word divider. */
/**
 * `Ao` in the renderer: a rule, a centred label, a rule. One composable serves all three
 * users of that shape — `TimelineRow.TurnDivider` (`ui.message.interrupted` =
 * "Interrupted") and `CompactionQueued` / the compaction states
 * (`ui.messagePart.compaction.*`).
 */
@Composable
private fun LabelDivider(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(Line))
        Text(
            label,
            fontSize = 10.5.sp,
            color = TextDim,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(Line))
    }
}

/**
 * Their compaction row's label, one per state — `ui.messagePart.compaction.*`:
 * `queued` "Session compaction queued", `running` "Compacting", `done` "Compacted",
 * `failed` "Session compaction failed", `cancelled` "Session compaction cancelled",
 * `interrupted` "Session compaction interrupted", `started` "Session compaction started".
 * The wire part is `type: "compacted"` and its `status` is the state; the bare word
 * "Compacted" was drawn for every one of them, so a queued or failed compaction read as done.
 */
private fun compactionLabel(status: String?): String = when (status?.lowercase()) {
    "queued" -> "Session compaction queued"
    "running", "compacting" -> "Compacting"
    "failed" -> "Session compaction failed"
    "cancelled", "canceled" -> "Session compaction cancelled"
    "interrupted" -> "Session compaction interrupted"
    "started" -> "Session compaction started"
    "done", "completed", null -> "Compacted"
    else -> "Compacted"
}

/**
 * `TimelineRow.Worked` — `ui.timeline.activity.worked` = "Worked {{duration}}", or the bare
 * `ui.timeline.activity.workedUnknown` = "Worked" when the turn never stamped one. The
 * duration is their `ui.message.duration.seconds` = "{{count}}s" under a minute and
 * `ui.message.duration.minutesSeconds` = "{{minutes}}m {{seconds}}s" above it.
 */
@Composable
private fun WorkedRow(ms: Long?) {
    val text = if (ms == null) {
        "Worked"
    } else {
        val s = Math.round(ms / 1000.0)
        if (s < 60) "Worked ${s}s" else "Worked ${s / 60}m ${s % 60}s"
    }
    Text(
        text,
        fontSize = 10.5.sp,
        color = TextDim,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/**
 * `TimelineRow.Retry` — the renderer's `p_`: an error card carrying the server's message
 * (cut at 80 chars plus "."), then `Attempt {{attempt}} - retrying in {{count}}s` counting
 * down to `next`, or `Attempt {{attempt}} - retrying` once it reaches zero.
 *
 * `next` is `retry.at` from the wire, so the countdown is theirs; it only shows while the
 * turn is the active one, which is exactly when this row is emitted.
 */
@Composable
private fun RetryRow(attempt: Int, next: Long, message: String?) {
    // `Math.round((next - Date.now()) / 1000)`, clamped at 0, re-read every second.
    var seconds by remember(next) { mutableStateOf(0) }
    LaunchedEffect(next) {
        while (true) {
            seconds = ((next - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
            kotlinx.coroutines.delay(1_000)
        }
    }
    val shown = remember(message) {
        val m = message.orEmpty()
        when {
            m.isBlank() -> ""
            m.length > 80 -> m.take(80) + "."
            else -> m
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Surface2)
            .border(1.dp, Line, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                OcIcons.Info,
                null,
                tint = Danger,
                modifier = Modifier.size(14.dp).padding(top = 1.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(shown, fontSize = 11.5.sp, color = TextPrimary, maxLines = 4)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (seconds > 0) "Attempt $attempt - retrying in ${seconds}s"
            else "Attempt $attempt - retrying",
            fontSize = 10.5.sp,
            color = TextDim,
        )
    }
}

/** Their `[data-component=todos]`: `padding:10px 0 24px`, 8px between rows, a checkbox and
    the item text; `data-completed` is faint with a strike-through. */
@Composable
private fun TodosBlock(items: List<TodoItem>) {
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { t ->
            Row(verticalAlignment = Alignment.Top) {
                TodoCheck(t.done, t.active)
                Spacer(Modifier.width(10.dp))
                Text(
                    t.content,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = when {
                        t.done -> TextDim
                        t.active -> TextPrimary
                        else -> TextMid
                    },
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** `[data-component=checkbox]`: 14px rounded square, 1px outline, filled accent when done. */
@Composable
private fun TodoCheck(done: Boolean, active: Boolean) {
    Box(
        Modifier
            .padding(top = 3.dp)
            .size(14.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (done) Accent else Color.Transparent)
            .border(1.dp, if (done) Accent else if (active) TextMid else Border, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Icon(
                OcIcons.Check,
                contentDescription = "Done",
                tint = Bg,
                modifier = Modifier.size(10.dp),
            )
        }
    }
}

/** A turn the server refused (quota wall, deprecated model, auth) or a retry it logged.
    PC's `session-timeline-notice` is NOT a card: one faint line,
    `13px / font-[530] / text-v2-text-text-faint`, no border, no fill. The retry attempt is
    already inside the string (`ui.sessionTurn.retry.attemptRetrying` = `Attempt N - line`),
    so there is no second label above it. */
@Composable
private fun NoticeRow(text: String) {
    SemanticText(
        text,
        color = TextDim,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight(530),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

/** A system / switch / inline-shell line. Their transcript puts these inline, dim, with no
    bubble � a notice is part of the run it happened in, not a message of its own. */
@Composable
private fun SystemRow(text: String, sub: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        SemanticText(
            text,
            color = TextDim,
            fontSize = 11.5.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (!sub.isNullOrBlank()) {
            Text(sub, fontSize = 10.sp, color = TextDim.copy(alpha = 0.75f), maxLines = 1)
        }
    }
}

/** `idle` with a non-success outcome. Their marker reads `Interrupted` / `Failed`; without
    it a stopped or refused turn is indistinguishable from one that simply ended.

    The message under it is their `.error-card`, and the rule is exact
    (`index-CbZCBGB7.css`):

    ```
    [data-component=session-turn] .error-card{
      color: var(--v2-text-text-base);
      white-space: pre-wrap;
      overflow-wrap: anywhere;
      word-break: break-word;
      max-height: 240px;
      overflow-y: auto }
    [data-component=session-turn] [data-component=card][data-kind=session-error-card]{
      font-size:13px; line-height:var(--line-height-compact); letter-spacing:-.04px;
      --card-pad-y:0px; --card-pad-l:0px; --card-pad-r:0px }
    ```

    So it is **base**, not muted: `--v2-text-text-base` resolves to `--v2-grey-100`
    (`#FAFAFA`) in the dark theme. Drawing it in `TextMid` made a refusal look like a
    footnote, which is exactly what the owner saw as "walang colour" - the failure text was
    dimmer than the reply above it. It is also `pre-wrap` with a 240 px scroll cap, so the
    message keeps its newlines and is **not** cut short. The old code did
    `substringBefore(": {")`, which silently truncated any error whose payload was JSON. */
@Composable
private fun OutcomeRow(outcome: String, error: String?) {
    val label = when (outcome) {
        "interrupted" -> "Interrupted"
        "failed" -> "Failed"
        else -> outcome.replaceFirstChar { it.uppercase() }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 10.5.sp, color = TextDim)
        if (!error.isNullOrBlank()) {
            val scroll = rememberScrollState()
            SemanticText(
                error,
                color = TextPrimary,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(scroll),
            )
        }
    }
}

/** Their run trigger (`Wp`, route-CMUQLRzL.js): `Used {{count}} {{tools}}` where `tools` is
    the unique tool-label list in first-seen order and count is the tool/shell part count.
    Open while the run is live, collapsed once it lands; the header toggles it. */
@Composable
private fun RunRow(parts: List<Part>, live: Boolean) {
    val tools = remember(parts) { parts.filter { it.type == "tool" } }
    val names = remember(parts) { tools.map { toolLabel(it.name) }.distinct() }
    val thoughts = remember(parts) { parts.count { it.type == "reasoning" } }
    // `Wp`: `s = parts.filter(type==="tool"||type==="shell").length || n` where `n` is the
    // reasoning count — the header counts activity, not rows.
    val count = if (tools.isNotEmpty()) tools.size else thoughts
    // Their `Wp`: `o = busy && reasoning.length > 0 && reasoning.length == parts.length`
    // � the header only says `Thinking <elapsed>` while the group is *nothing but* thoughts.
    // The moment a tool or prose lands it becomes the `Used N �` sentence.
    val allThoughts = parts.isNotEmpty() && parts.all { it.type == "reasoning" }
    val thinking = live && allThoughts

    val head = remember(parts, names, count) {
        // `Qy(e,t)`: `n = parts.filter(type==="reasoning").length`, then
        // `t.plural("ui.messagePart.tools.used", n, {tools: t.plural("ui.messagePart.context.thought", n)})`
        // = `Used {{count}} {{tools}}` — "Used 1 Thought", "Used 3 Thoughts".
        // Their `Used {{count}} {{tools}}` string is identical for one and many.
        val noun = if (tools.isEmpty()) {
            plural(thoughts, "Thought", "Thoughts")
        } else {
            // `t.list([...new Set(...)])` is `Intl.ListFormat`, not a comma join:
            // one -> `Edit`, two -> `Edit and Shell`, three -> `Edit, Shell and Read`.
            listJoin(names)
        }
        buildAnnotatedString {
            withStyle(SpanStyle(color = TextDim)) { append("Used ") }
            withStyle(SpanStyle(color = TextPrimary, fontWeight = FontWeight.Medium)) {
                append("$count ")
                append(noun)
            }
        }
    }
    // `timelinePresets[compact]` says `details: "collapsed"` for shell/edit/thinking - that is
    // each ROW's own detail, never the group. The group itself is open, which is why PC shows
    // `Thought 15s`, the `file-counts` card, `Thought 6s`, ... straight away. Ours defaulted the
    // group to closed for a landed turn, so the rows - and the file icon he asked for - were
    // hidden behind a tap that nothing on screen advertised. the owner, 24 Nov: *"REQUEST THE CARD
    // AND ICON I DONT SEE IT"*.
    // Same disposal problem as the thought row, and the same fix: `remember(parts)` reset the
    // header to open every time a poll rebuilt the list, because a streaming part makes the list
    // compare unequal. the owner, 2026-11-24: the run header could not be kept closed. The first
    // part's id is the row's identity; the choice survives scroll and recomposition with it.
    var open by rememberSaveable(parts.firstOrNull()?.id ?: "run") { mutableStateOf(true) }
    val rows = remember(parts) { mergeEdits(groupReads(parts)) }
    // Their compact preset puts every thinking part of a turn in ONE row
    // (`timeline/detail.ts`: `thinking: { placement: "grouped" }`, and their timeline keys it
    // `thinking:${userMessageID}` - "one row per assistant turn, never one per reasoning part").
    // `groupReads` instead makes one ThoughtGroup per contiguous reasoning run, and the server
    // ships one message per step, so a live turn is `[reasoning, tool, reasoning, tool, ...]`
    // (measured 22 Nov 16:35). That is what stacked a "Thought 7s" under a "Thinking 5s".
    val thoughtsAll = remember(parts) { parts.filter { it.type == "reasoning" } }
    // NO auto-collapse: a run that finishes must never shrink under his thumb. It opens
    // because it was live when it first appeared, and stays that way until he taps the
    // header � the old "closes itself while I read it" behaviour was a bug, not a feature.

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable { open = !open }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (thinking) {
                // `Gr` with `ui.sessionTurn.status.thinking` and `active: true`, then the
                // ticking `{{count}}s` clock that their header shows while it streams
                //
                // Measured 22 Nov 16:28: this read `Thinking 0s` next to a live band saying
                // `Thinking 85s`. It took `parts.firstOrNull()?.time?.created`, and after a
                // refresh the first part of the group is a *newly created* one, so the clock
                // restarted at zero on every poll. The group began when its earliest part did.
                ShimmerText("Thinking", fontSize = 13.sp)
                val started = parts.mapNotNull { it.time?.created }
                    .filter { it > 0L }
                    .minOrNull() ?: 0L
                if (started > 0L) {
                    Spacer(Modifier.width(8.dp))
                    TickingSeconds(started)
                }
                Spacer(Modifier.weight(1f))
            } else {
            Text(
                head,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.width(8.dp))
            CollapseArrow(open)
        }
        if (open) {
            Column(Modifier.fillMaxWidth()) {
                rows.forEachIndexed { i, row ->
                    androidx.compose.runtime.key(
                        when (row) {
                            is ReadGroup -> "rg:" + (row.parts.firstOrNull()?.id ?: i)
                            is ThoughtGroup -> "tg:" + (row.parts.firstOrNull()?.id ?: i)
                            is EditGroup -> "eg:$i:" + (row.files.firstOrNull()?.file ?: "")
                            is Part -> row.id ?: i.toString()
                            else -> i.toString()
                        },
                    ) {
                        when (row) {
                            is ReadGroup -> ReadGroupRow(row.parts)
                            // One row per reasoning step, at the step's own place in the run.
                            // Their live transcript (measured on the desktop client, 24 Nov)
                            // draws `Thought 9s`, `Shell`, `Shell`, `Thought 1s`, `Shell`,
                            // `Thought 2s`, ... inside ONE `Used N ...` run. Folding every
                            // thought of the turn into a single row at the first thought's
                            // position is what dropped them: the owner, 24 Nov - *"WALA ATA SYANG
                            // THOUGHTS DAMING WALA"*.
                            is ThoughtGroup -> ThoughtRow(
                                row.parts,
                                live = live && row.parts.any { isRunningPart(it) },
                                // The run header already says "Thinking" while the group is
                                // nothing but thoughts. Printing the label again inside it
                                // gave two stacked "Thinking" lines (the owner, 20 Nov).
                                headless = thinking,
                            )
                            // `data-component=file-counts`: the edit tool's own row, drawn where the
                            // tool happened - not hoisted out of the run. Their timeline is
                            // chronological (`Thought 15s`, card, `Thought 6s`, card, ...), so the
                            // card sits between the rows that produced it.
                            is EditGroup -> FileCountsCard(row)
                            is Part -> when {
                                row.name == "task" || row.name == "subagent" -> TaskCard(row)
                                row.name == "execute" -> ExecuteCalls(row)
                                else -> ToolRow(row)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Their reasoning row — `Thinking` in the timeline model, keyed `thinking:${userMessageID}`.
    One row per assistant turn, never one per reasoning part: `as` renders
    `ui.sessionTurn.status.thinking` ("Thinking") while it streams and `ui.message.thought`
    ("Thought") once it lands, with the elapsed clock beside it. */
@Composable
private fun ThoughtRow(parts: List<Part>, live: Boolean, headless: Boolean = false) {
    // the owner's rule (20 Nov): the live turn shows its thinking without a tap; a tap records an
    // explicit choice that wins for the rest of it.
    //
    // the owner, 2026-11-24: *"YUNG THINKING NYA PAG MAHABA ... DIKO MA CLOSE AND OPEN"*. The choice
    // was a plain `remember`, and a LazyColumn throws an item's remembered state away the moment
    // the item leaves its composition window. On a long thought the row was re-created
    // mid-scroll, the choice fell back to "follow the turn", and the effect below re-opened it -
    // so a tap read as doing nothing. `rememberSaveable` is kept by the lazy item's own state
    // holder, keyed by the row's first part, so open/closed survives the scroll the way their
    // timeline keeps a row's detail state. -1 = follow the turn, 0 = closed, 1 = open.
    val rowId = parts.firstOrNull()?.id ?: "thought"
    var manualState by rememberSaveable(rowId) { mutableStateOf(-1) }
    val body = remember(parts) {
        parts.mapNotNull { it.text?.trim()?.takeIf { t -> t.isNotEmpty() } }.joinToString("\n\n")
    }
    // No text, no row. A reasoning part that has started but not written anything used to draw a
    // bare "Thinking" header with an expand arrow and nothing underneath - and when the step also
    // had a landed thought that was a second one on screen. the owner, 22 Nov: "DALAWANG THINKING ...
    // YUNG THINKING HINDI KO NAKIKITA TEXT NYA". The live status band already says the turn is
    // working, so an empty row carries no information at all.
    if (body.isEmpty()) return
    val running = live && parts.any { isRunningPart(it) }
    // Opens the moment the turn starts thinking, and stays open after it lands — his
    // standing order is no auto-collapse, so only a tap ever closes this row.
    LaunchedEffect(running) { if (running && manualState == -1) manualState = 1 }
    val open = when (manualState) { 1 -> true; 0 -> false; else -> running }
    // Headless: the enclosing run header is already the label, so this renders the text
    // only. Always expanded — a second collapsible row under "Thinking" is the duplicate.
    if (headless) {
        if (body.isNotEmpty()) ReasoningBlock(body, streaming = running)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = body.isNotEmpty()) {
                    manualState = if (open) 0 else 1
                    // Evidence for the next read: if a tap does nothing on device, this line
                    // either fires (the state changed and the draw is at fault) or is absent
                    // (the event never reached the row). No guessing.
                    android.util.Log.e(
                        "ZAPP",
                        "thought: tap id=$rowId open=$open -> ${if (open) 0 else 1}",
                    )
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                // `Gr` text = `ui.sessionTurn.status.thinking` while it streams, `active: true`
                ShimmerText("Thinking", fontSize = 13.sp)
            } else {
                Text("Thought", fontSize = 13.sp, fontWeight = FontWeight(440), color = TextMid)
            }
            // `Io text={n()}` — a live clock while streaming, the finished duration after.
            // PC renders no node at all when the duration query is empty, which is why a
            // sub-second thought must not print "0s".
            val started = parts.firstOrNull()?.time?.created ?: 0L
            if (running) {
                if (started > 0L) {
                    Spacer(Modifier.width(8.dp))
                    TickingSeconds(started)
                }
            } else {
                val first = parts.firstOrNull()?.time
                val total = if (first?.created != null && first.ran != null) {
                    first.ran - first.created
                } else {
                    parts.sumOf { it.time?.durationMs ?: 0L }
                }
                if (total >= 1000L) {
                    Spacer(Modifier.width(8.dp))
                    Text(formatDuration(total), fontSize = 13.sp, color = TextDim)
                }
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            CollapseArrow(open)
        }
        if (open && body.isNotEmpty()) ReasoningBlock(body, streaming = running)
    }
}

/** Their call row: `Read ChatScreen.kt offset=1258 limit=60` � only the title is bright. */
@Composable
private fun ToolRow(p: Part) {
    var open by remember { mutableStateOf(false) }
    val line = remember(p) { toolLine(p) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                // `height: calc(var(--line-height-compact) + 12px); margin-block: -6px;
                //  padding-block: 6px` on the collapsible trigger inside the next timeline
                .heightIn(min = 32.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable { open = !open }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isRunningPart(p)) {
                // `basic-tool-tool-indicator` is a 16px spinner at the head while it runs
                Spinner(14.dp)
                Spacer(Modifier.width(8.dp))
            }
            // next timeline: title is `--v2-text-text-muted` at weight 440,
            // subtitle and args are `--v2-text-text-base`
            Text(
                line.title,
                fontSize = 13.sp,
                fontWeight = FontWeight(440),
                color = TextMid,
                maxLines = 1,
            )
            if (line.subtitle.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    line.subtitle,
                    fontSize = 13.sp,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            line.args.forEach { arg ->
                Spacer(Modifier.width(8.dp))
                SemanticText(
                    arg,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            line.count?.let { n ->
                Spacer(Modifier.width(8.dp))
                // `ui.messagePart.context.matchCount` = `{{count}} match` / `{{count}} matches`
                SemanticText(
                    plural(n, "$n match", "$n matches"),
                    color = TextMid,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            CollapseArrow(open)
        }
        if (open) ToolDetail(p)
    }
}

/** `[data-component=task-tool-card]`: a subagent is not a tool line — it is a bordered card,
    `.5px` border, `background-base` at 92%, radius 6, padding 8x12, title + description. */
@Composable
private fun TaskCard(p: Part) {
    val st = p.state
    val desc = remember(p) {
        st?.inputString("description")?.takeIf { it.isNotBlank() }
            ?: st?.inputString("prompt")?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    }
    val agent = remember(p) {
        st?.inputString("subagent_type")?.takeIf { it.isNotBlank() }
            ?: st?.inputString("agent")?.takeIf { it.isNotBlank() }
            ?: "subagent"
    }
    val running = isRunningPart(p)
    val summary = remember(p) {
        p.text?.trim()?.takeIf { it.isNotEmpty() }
            ?: st?.content?.mapNotNull { it.text }?.joinToString("\n")?.trim()?.takeIf { it.isNotEmpty() }
    }
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Surface.copy(alpha = 0.92f))
                .border(0.5.dp, Line, RoundedCornerShape(6.dp))
                .clickable { open = !open }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) Spinner(12.dp) else TaskGlyph(TextDim)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    // `ui.tool.agent` = `{{type}} Agent`, `ui.tool.agent.default` = `Agent`
                    if (agent.isBlank()) "Agent" else "$agent Agent",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // `ui.tool.agent.delegating` = `Delegating agent.` while it runs
                    if (running) "Delegating agent." else desc.ifBlank { "Agent" },
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!running && !summary.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                CollapseArrow(open, size = 16.dp)
            }
        }
        if (open && !summary.isNullOrBlank()) {
            Text(
                summary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = TextMid,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 8.dp)
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

/** Small rounded square with a corner arrow — their subagent glyph. */
@Composable
private fun TaskGlyph(tint: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
        val w = size.minDimension
        val st = androidx.compose.ui.graphics.drawscope.Stroke(
            width = w * 0.09f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        val col = tint
        drawRoundRect(
            color = col,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.1f, w * 0.28f),
            size = androidx.compose.ui.geometry.Size(w * 0.62f, w * 0.62f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.14f),
            style = st,
        )
        drawLine(
            col,
            androidx.compose.ui.geometry.Offset(w * 0.55f, w * 0.45f),
            androidx.compose.ui.geometry.Offset(w * 0.92f, w * 0.08f),
            strokeWidth = w * 0.09f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            col,
            androidx.compose.ui.geometry.Offset(w * 0.66f, w * 0.08f),
            androidx.compose.ui.geometry.Offset(w * 0.92f, w * 0.08f),
            strokeWidth = w * 0.09f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            col,
            androidx.compose.ui.geometry.Offset(w * 0.92f, w * 0.08f),
            androidx.compose.ui.geometry.Offset(w * 0.92f, w * 0.34f),
            strokeWidth = w * 0.09f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/** Their running spinner: a 3/4 arc that turns. Used by the subagent card. */
@Composable
private fun Spinner(size: androidx.compose.ui.unit.Dp) {
    // A `rememberInfiniteTransition` asks Compose for a frame at the display's own rate, and it
    // never stops. Measured 22 Nov 13:52 on this phone: a turn stuck on "Working" held
    // `queueBuffer: fps=119.15` and 115% CPU for half an hour, which is exactly why every tap
    // landed seconds late. One rotation is 1.2 s, so 12 steps a second is already smoother than
    // the eye can follow, at a tenth of the cost.
    var angle by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(80)
            angle = (angle + 24) % 360
        }
    }
    androidx.compose.foundation.Canvas(
        Modifier.size(size).graphicsLayer { rotationZ = angle.toFloat() },
    ) {
        val w = this.size.minDimension
        drawArc(
            color = TextMid,
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = w * 0.12f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            ),
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.08f, w * 0.08f),
            size = androidx.compose.ui.geometry.Size(w * 0.84f, w * 0.84f),
        )
    }
}

/** The two shapes an `execute` part arrives in. See [ExecuteChrome] for the source of every
 *  label and number; this is only the drawing.
 *
 *  - `input.calls` - a batch, `[data-component=execute-tool-calls]`: mono 13/440, column gap 6,
 *    a 14px marker, the tool name bright and its arguments muted with an ellipsis.
 *  - `input.code` - the large card, `dm(e)`: `Execute` + status + `N calls`, then a `Code`
 *    section and an `Output` (or `Error`) section, each with its own copy control.
 *
 *  The second shape used to draw nothing: the renderer read `calls`, found none, and returned.
 *  Every real `execute` part on this server is the second shape (`input.code`), so the whole
 *  tool was invisible. */
@Composable
private fun ExecuteCalls(p: Part) {
    val st = p.state
    val code = remember(p) { st?.inputString("code").orEmpty().trim() }
    val calls = remember(p) {
        val arr = st?.inputObject?.get("calls") as? kotlinx.serialization.json.JsonArray
            ?: return@remember emptyList()
        arr.mapNotNull { el ->
            val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val name = s("name") ?: s("tool") ?: return@mapNotNull null
            val args = s("args") ?: s("input") ?: s("arguments") ?: ""
            name to args.replace('\n', ' ')
        }
    }
    if (code.isBlank() && calls.isEmpty()) return

    if (code.isNotBlank()) {
        val running = isRunningPart(p)
        val failed = st?.status == "error" || st?.status == "failed"
        val output = remember(p) {
            st?.content.orEmpty().filter { it.type == "text" }.mapNotNull { it.text }
                .joinToString("\n").trim()
        }
        val errorText = remember(p) {
            st?.error?.message.orEmpty().trim().ifBlank { output.takeIf { failed }.orEmpty() }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    MessageCopy.EXECUTE,
                    fontSize = 12.sp,
                    fontWeight = FontWeight(440),
                    color = TextMid,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    ExecuteChrome.status(failed, running),
                    fontSize = 12.sp,
                    color = if (failed) Danger else TextDim,
                    maxLines = 1,
                )
                if (calls.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        MessageCopy.calls(calls.size),
                        fontSize = 12.sp,
                        color = TextDim,
                        maxLines = 1,
                    )
                }
            }
            ExecuteSection(
                label = MessageCopy.CODE,
                meta = ExecuteChrome.lines(code),
                body = code,
                copyLabel = MessageCopy.COPY_CODE,
            )
            val resultBody = if (failed) errorText else output
            if (resultBody.isNotBlank()) {
                ExecuteSection(
                    label = ExecuteChrome.resultLabel(failed),
                    meta = ExecuteChrome.lines(resultBody),
                    body = resultBody,
                    copyLabel = ExecuteChrome.resultCopyLabel(failed),
                )
            }
        }
        return
    }

    Column(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        calls.forEach { (name, args) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(4.dp)
                            .background(TextDim, RoundedCornerShape(1.dp)),
                    )
                }
                Text(
                    name,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = TextPrimary,
                    maxLines = 1,
                )
                if (args.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        args,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = TextMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** One labelled block of an `execute` card: `Code 12 lines` + copy, then the body. */
@Composable
private fun ExecuteSection(label: String, meta: String, body: String, copyLabel: String) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight(440), color = TextMid, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            SemanticText(meta, color = TextDim, fontSize = 11.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            CopyButton(body, label = copyLabel)
        }
        Box(Modifier.fillMaxWidth().padding(top = 3.dp)) { MonoToolBox(body, bordered = true) }
    }
}

/** PC's `message-nav` in its compact size: ticks down the right edge, the one for the turn at
    the top of the viewport drawn full-width and 2px tall. Tapping a tick goes to that turn. */
@Composable
private fun MessageRail(count: Int, listState: LazyListState, onJump: (Int) -> Unit) {
    val active by remember(count) {
        androidx.compose.runtime.derivedStateOf {
            listState.firstVisibleItemIndex.coerceIn(0, (count - 1).coerceAtLeast(0))
        }
    }
    Column(
        Modifier
            .width(24.dp)
            .fillMaxHeight(0.5f)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (i in 0 until count) {
            Box(
                Modifier
                    .size(width = 24.dp, height = 12.dp)
                    .clickable(
                        interactionSource = remember {
                            androidx.compose.foundation.interaction.MutableInteractionSource()
                        },
                        indication = null,
                    ) { onJump(i) },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .width(if (i == active) 24.dp else 16.dp)
                        .height(if (i == active) 2.dp else 1.dp)
                        .background(if (i == active) TextMid else Line),
                )
            }
        }
    }
}

/** `[data-slot=basic-tool-tool-info-main]`: title, subtitle, then arg spans, then the count. */
private data class ToolLine(
    val title: String,
    val subtitle: String,
    val args: List<String>,
    val count: Int?,
)

/** `[data-component=read-tool-group]`: consecutive reads collapse into one 32px row. */
private data class ReadGroup(val parts: List<Part>)

/** Contiguous reasoning parts of one run are one thought: PC's group items are refs, and a
    thought that keeps arriving is the same thought. */
private data class ThoughtGroup(val parts: List<Part>)

/** One row inside the patch stack: their `apply-patch` accordion item. */
private data class EditFile(
    val file: String,
    val patch: String?,
    val additions: Int,
    val deletions: Int,
    /** `added` / `deleted` / `modified` - the `status` their `yf()` validates. */
    val status: String = "modified",
)

/**
 * `ch(e)` in `route-BPCnB8DM.js` - consecutive edit/write/patch tools are **one** patch
 * group: it flatMaps every tool in the group and emits one file row each. Two edits to two
 * files are therefore two rows in one container, and two edits to the same file are two
 * rows as well - never one card per path like this used to be.
 *
 * Files come from `state.metadata.files` when the server sends them; otherwise they are
 * derived from the tool input exactly the way `ch(e)` does it (see `editFiles`).
 */
private data class EditGroup(val files: List<EditFile>)

private fun isEditTool(p: Part): Boolean =
    p.type == "tool" && p.name.orEmpty().lowercase() in EDIT_TOOLS

/**
 * `ch(e)`, branch for branch:
 *
 * 1. `Hm(Wt(e)).files` non-empty -> those entries, one row each.
 * 2. `edit` with `oldString` + `newString` -> `Sd` line diff; `0/0` emits **no** row.
 * 3. `write` with `content` -> `additions = lines`, `deletions = 0`.
 */
private fun editFiles(p: Part): List<EditFile> {
    val st = p.state
    val sent = st?.metadata?.files.orEmpty()
    if (sent.isNotEmpty()) {
        return sent.mapNotNull { f ->
            val path = f.file?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            EditFile(
                file = path,
                patch = f.patch,
                additions = f.additions,
                deletions = f.deletions,
                status = f.status ?: "modified",
            )
        }
    }
    val name = p.name.orEmpty().lowercase()
    val state = st ?: return emptyList()
    val path = state.inputString("filePath") ?: state.inputString("path") ?: return emptyList()
    if (path.isBlank()) return emptyList()
    when (name) {
        "edit" -> {
            val old = state.inputString("oldString") ?: return emptyList()
            val new = state.inputString("newString") ?: return emptyList()
            val ops = lineOps(splitLines(old), splitLines(new))
            val add = ops.count { it.kind == LineOpKind.Add }
            val del = ops.count { it.kind == LineOpKind.Del }
            if (add == 0 && del == 0) return emptyList()
            return listOf(EditFile(path, unifiedPatch(path, ops), add, del, "modified"))
        }
        "write" -> {
            val content = state.inputString("content") ?: return emptyList()
            val lines = splitLines(content)
            if (lines.isEmpty()) return emptyList()
            val ops = lines.map { LineOp(LineOpKind.Add, it) }
            return listOf(EditFile(path, unifiedPatch(path, ops), lines.size, 0, "modified"))
        }
        else -> return emptyList()
    }
}

private fun mergeEdits(rows: List<Any>): List<Any> {
    val out = ArrayList<Any>()
    var i = 0
    while (i < rows.size) {
        val p = rows[i] as? Part
        if (p == null || !isEditTool(p)) {
            out.add(rows[i])
            i++
            continue
        }
        val files = ArrayList<EditFile>()
        // A part this pass cannot turn into a file row (no path, no content, a text input the
        // parser did not see) used to be swallowed with the whole group - the run header still
        // said `Used 2 Write` and the body under it was empty. the owner, 24 Nov: *"WALA ATA SYANG
        // THOUGHTS DAMING WALA"* / *"nag delete ka nanaman"*. Nothing is dropped now: anything
        // that produced no file row is handed back to the normal tool renderer.
        val kept = ArrayList<Any>()
        while (i < rows.size) {
            val q = rows[i] as? Part ?: break
            if (!isEditTool(q)) break
            val f = editFiles(q)
            if (f.isEmpty()) kept.add(q) else files.addAll(f)
            i++
        }
        if (files.isNotEmpty()) out.add(EditGroup(files))
        out.addAll(kept)
    }
    return out
}

private val EDIT_TOOLS = setOf("edit", "write", "patch")

/** The names their registry treats as the `shell` activity (`toolMode` -> `run`). */
private val SHELL_TOOL_NAMES = setOf("shell", "bash", "execute", "terminal")

private fun groupReads(parts: List<Part>): List<Any> {
    val out = ArrayList<Any>()
    var i = 0
    while (i < parts.size) {
        val p = parts[i]
        if (p.type == "tool" && p.name.orEmpty().lowercase() == "read") {
            var j = i
            while (j < parts.size && parts[j].type == "tool" &&
                parts[j].name.orEmpty().lowercase() == "read"
            ) j++
            if (j - i >= 2) {
                out.add(ReadGroup(parts.subList(i, j).toList()))
                i = j
                continue
            }
        }
        if (p.type == "reasoning") {
            var j = i
            while (j < parts.size && parts[j].type == "reasoning") j++
            out.add(ThoughtGroup(parts.subList(i, j).toList()))
            i = j
            continue
        }
        out.add(p)
        i++
    }
    return out
}

@Composable
private fun ReadGroupRow(reads: List<Part>) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable { open = !open }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Read", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Spacer(Modifier.width(8.dp))
            Text(
                plural(reads.size, "1 file", "${reads.size} files"),
                fontSize = 13.sp,
                color = TextMid,
                modifier = Modifier.weight(1f),
            )
            CollapseArrow(open, size = 16.dp)
        }
        if (open) {
            Column(
                Modifier
                    .padding(start = 12.dp)
                    .padding(bottom = 4.dp),
            ) {
                reads.forEach { ToolRow(it) }
            }
        }
    }
}

private fun oneLine(s: String?): String = s.orEmpty().replace('\n', ' ').trim()

/** `os` in the route chunk: the first key present becomes the subtitle. */
private val BASIC_TOOL_SUBTITLE_KEYS =
    listOf("description", "query", "url", "path", "pattern", "name")

/** `ab(e,t)` from the route chunk. `glob`/`grep`/`list` do not use the generic trigger:
    they show the parent directory as subtitle, the search arguments as args, and the
    result count in `context-tool-group-match-count`. */
private fun parentDir(p: String): String {
    val norm = p.replace('\\', '/').trimEnd('/')
    if (norm.isEmpty()) return "/"
    val cut = norm.lastIndexOf('/')
    return if (cut <= 0) "/" else norm.substring(0, cut)
}

/** Verbatim port of `ls(e)` and `us(e)` (`route-9_N8wbSw.js`):
    `ls = os.flatMap(k => ct(input?.[k]))[0]` — subtitle is the first of `os` that has a value;
    `us = Object.entries(input).filter(k => !os.includes(k)).map(k => `${k}=${v}`).slice(0,3)`. */
private fun toolLine(p: Part): ToolLine {
    val entries = p.state?.inputEntries().orEmpty()
    val name = p.name.orEmpty()
    // Evidence, not a guess: a shell row that cannot resolve a command logs the part's own
    // state once, so logcat answers where the command actually lives instead of another
    // rebuild-by-hypothesis. Tag `ZAPP`, grep `shellrow`.
    if (name.lowercase() in SHELL_TOOL_NAMES && entries.none { it.first == "command" } &&
        p.state?.metadata?.command.isNullOrBlank()
    ) {
        android.util.Log.w("ZAPP", "shellrow ${p.id} state=${p.state}")
    }

    fun value(k: String) = entries.firstOrNull { it.first == k }?.second?.takeIf { it.isNotBlank() }

    // `ab()` — the three search tools
    if (name == "list" || name == "glob" || name == "grep") {
        val dir = parentDir(value("path") ?: "/")
        val args = ArrayList<String>()
        if (name == "glob" || name == "grep") value("pattern")?.let { args.add("pattern=$it") }
        if (name == "grep") value("include")?.let { args.add("include=$it") }
        val count = when (name) {
            "grep" -> p.state?.metadata?.matches ?: p.state?.metadata?.count
            "glob" -> p.state?.metadata?.count
            else -> null
        }
        return ToolLine(toolLabel(name), dir, args, count)
    }

    fun firstOf(keys: List<String>): String {
        for (k in keys) {
            val v = value(k) ?: continue
            return oneLine(v)
        }
        return ""
    }

    val subtitle = firstOf(BASIC_TOOL_SUBTITLE_KEYS)
    // `data-component=shell-submessage` (`gp` in route-BPCnB8DM.js): a shell row's subtitle is
    // the COMMAND itself, never `command=...`. Packing every remaining input as `k=v` printed
    // `Shell command=python3 -m py_compile ...` with `workdir=` stapled on - the owner, 24 Nov:
    // *"THE PLACEMENT ... WT*"*. `workdir` is an input, not part of the line.
    if (name in SHELL_TOOL_NAMES) {
        // Their `e.input.command ?? e.metadata.command` - the metadata is the fallback that
        // keeps the row's command on screen after the input object stops being delivered.
        val cmd = oneLine(value("command").orEmpty())
            .ifBlank { oneLine(p.state?.metadata?.command.orEmpty()) }
        return ToolLine(
            toolLabel(name),
            cmd,
            emptyList(),
            null,
        )
    }
    val args = entries
        .filter { it.first !in BASIC_TOOL_SUBTITLE_KEYS && it.second.isNotBlank() }
        .take(3)
        .map { "${it.first}=${oneLine(it.second)}" }

    return ToolLine(toolLabel(name), subtitle, args, null)
}

/** Set by the chat screen so a deep file card can reach the viewer without a param chain. */
internal object FileOpener {
    var open: (String) -> Unit = {}
}

/** Their `Move to background`: hands a running command to POST /api/shell. */
internal object ShellDetacher {
    /**
     * `id` is the tool part's own id, and it is NOT optional in practice.
     *
     * The endpoint is `POST /api/session/:id/shell { id?, command }` and that `id` is how the
     * server ties the new `/api/shell` entry back to the message part it came from. The desktop
     * always sends `part.id`. We sent `null` at every call site - measured in this build at
     * 23:44, all three detach paths read `vm.detachShell(null, cmd)` - so the command was
     * started as an orphan and the tool card it was supposed to leave stayed exactly where it
     * was. Passing the id is the difference between "moved to background" and "started again".
     */
    var detach: (command: String, partId: String?) -> Unit = { _, _ -> }
}

/** Their `open-file` icon, 20 grid, square caps � `ui.sessionReview.openFile`. */
@Composable
private fun OpenFileGlyph(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val u = size.width / 20f
        val p = Path().apply {
            moveTo(7.916f * u, 2.914f * u); lineTo(2.916f * u, 2.914f * u)
            lineTo(2.916f * u, 17.081f * u); lineTo(17.083f * u, 17.081f * u)
            lineTo(17.083f * u, 12.081f * u)
            moveTo(12.083f * u, 2.914f * u); lineTo(17.083f * u, 2.914f * u)
            lineTo(17.083f * u, 7.914f * u)
            moveTo(9.583f * u, 10.414f * u); lineTo(16.666f * u, 3.331f * u)
        }
        drawPath(p, color, style = Stroke(width = 1.15f * u, cap = StrokeCap.Square))
    }
}

/** Their `split` / `unified` icon, 16 grid � `ui.sessionReviewV2.diffView`. */
@Composable
private fun DiffStyleGlyph(split: Boolean, color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val u = size.width / 16f
        drawRoundRect(
            color,
            topLeft = Offset(1.5f * u, 2.5f * u),
            size = Size(13f * u, 11f * u),
            cornerRadius = CornerRadius(1.2f * u),
            style = Stroke(width = 1.05f * u),
        )
        val bar = color.copy(alpha = 0.5f)
        if (split) {
            drawRect(bar, Offset(3f * u, 4f * u), Size(4f * u, 8f * u))
            drawRect(bar, Offset(9f * u, 4f * u), Size(4f * u, 8f * u))
        } else {
            drawRect(bar, Offset(3f * u, 4f * u), Size(10f * u, 3f * u))
            drawRect(bar, Offset(3f * u, 9f * u), Size(10f * u, 3f * u))
        }
    }
}

/** Their tool registry (`Ug` + aliases `Gg`), reduced to the four activity modes. */
private fun toolMode(name: String?): String {
    val n = when (val raw = name.orEmpty()) {
        "bash" -> "shell"
        "task" -> "subagent"
        "apply_patch" -> "patch"
        else -> raw
    }
    return when (n) {
        "read", "list", "glob", "grep", "webfetch", "websearch", "skill" -> "explore"
        "edit", "write", "patch" -> "edit"
        "shell", "execute" -> "run"
        else -> "tool"
    }
}

/** Verbatim port of the title map in `route-9_N8wbSw.js`:
    `new Map([["read","ui.tool.read"],["list","ui.tool.list"],["glob","ui.tool.glob"],
    ["grep","ui.tool.grep"],["subagent","ui.tool.agent.default"],["webfetch","ui.tool.webfetch"],
    ["websearch","ui.tool.websearch"],["shell","ui.tool.shell"],["execute","ui.tool.execute"],
    ["edit","ui.messagePart.title.edit"],["write","ui.messagePart.title.write"],
    ["skill","ui.tool.skill"],["patch","ui.tool.patch"],["question","ui.tool.questions"]])`
    and its fallback `: u.tool` — an unmapped tool keeps its raw name, not a prettified one. */
private fun toolLabel(name: String?): String = when (name.orEmpty()) {
    "read" -> "Read"
    "list" -> "List"
    "glob" -> "Glob"
    "grep" -> "Grep"
    "subagent" -> "Agent"
    "webfetch" -> "Webfetch"
    "websearch" -> "Web Search"
    "shell" -> "Shell"
    "execute" -> "Execute"
    "edit" -> "Edit"
    "write" -> "Write"
    "skill" -> "Skill"
    "patch" -> "Patch"
    "question" -> "Questions"
    else -> name.orEmpty().ifBlank { "Tool" }
}

private data class RunActivity(val verb: String, val main: String, val others: String)

private fun plural(n: Int, one: String, other: String): String = if (n == 1) one else other

/** English `Intl.ListFormat` in its default `conjunction` style, which is what
    `t.list([...])` resolves to: `A`, `A and B`, `A, B and C`. */
private fun listJoin(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    2 -> "${items[0]} and ${items[1]}"
    else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
}

private fun Part.inputPath(): String? =
    state?.inputString("filePath") ?: state?.inputString("path")

/** `ib(e,t)` — the activity-summary title map. Deliberately different from the basic-tool
    trigger map: `webfetch` is `Fetch`, `websearch` is `Search web`, and `browser` is present. */
private fun toolVerb(name: String?): String = when (name.orEmpty()) {
    "read" -> "Read"
    "list" -> "List"
    "glob" -> "Glob"
    "grep" -> "Grep"
    "browser" -> "Browser"
    "webfetch" -> "Fetch"
    "websearch" -> "Search web"
    "subagent" -> "Agent"
    "shell" -> "Shell"
    "execute" -> "Execute"
    "edit" -> "Edit"
    "write" -> "Write"
    "patch" -> "Patch"
    "question" -> "Questions"
    "skill" -> "Skill"
    else -> name.orEmpty()
}

/** Verbatim port of `rb()`: verb, main clause, and the leftovers joined with `, `. */
private fun runActivity(parts: List<Part>): RunActivity? {
    val tools = parts.filter { it.type == "tool" && it.name != "todowrite" }
    val modes = tools.map { toolMode(it.name) }.toSet()
    val order = listOf("edit", "run", "tool", "explore")
    val chosen = order.firstOrNull { it in modes } ?: return null

    fun inMode(m: String) = tools.filter { toolMode(it.name) == m }
    // `ic()`: `reads` is a Set of paths, `files` a Set of touched files.
    val readPaths = tools.filter { it.name == "read" }.mapNotNull { it.inputPath() }.toSet()
    val editPaths = tools
        .flatMap { p -> p.state?.metadata?.files.orEmpty().mapNotNull { it.file?.takeIf(String::isNotBlank) } }
        .ifEmpty { inMode("edit").mapNotNull { it.inputPath() } }
        .distinct()
    fun count(names: Set<String>) = tools.count { it.name in names }

    /** `s(files)` in `jb`: the same five counters, swapping `reads` for `files` in edit mode. */
    fun exploreBits(asFiles: Boolean): List<String> {
        val out = ArrayList<String>()
        if (readPaths.isNotEmpty()) {
            out.add(
                if (asFiles) plural(readPaths.size, "1 file", "${readPaths.size} files")
                else plural(readPaths.size, "1 read", "${readPaths.size} reads"),
            )
        }
        count(setOf("glob", "grep", "list")).takeIf { it > 0 }
            ?.let { out.add(plural(it, "1 search", "$it searches")) }
        count(setOf("websearch")).takeIf { it > 0 }
            ?.let { out.add(plural(it, "1 web search", "$it web searches")) }
        count(setOf("webfetch")).takeIf { it > 0 }
            ?.let { out.add(plural(it, "1 page", "$it pages")) }
        count(setOf("skill")).takeIf { it > 0 }
            ?.let { out.add(plural(it, "1 skill", "$it skills")) }
        return out
    }

    val commands = inMode("run").size.let { plural(it, "1 command", "$it commands") }
    val toolNames = inMode("tool").mapNotNull { toolVerb(it.name) }.distinct()

    fun modeBits(m: String): List<String> = when (m) {
        "edit" -> if (editPaths.isNotEmpty()) {
            listOf(plural(editPaths.size, "1 edited file", "${editPaths.size} edited files"))
        } else emptyList()
        "run" -> listOf(commands)
        "tool" -> toolNames
        else -> exploreBits(false)
    }

    // `d[a]` - what the picked mode puts after its verb.
    val main = when (chosen) {
        "edit" -> when {
            editPaths.isEmpty() -> ""
            editPaths.size <= 2 -> editPaths.joinToString(", ") { it.substringAfterLast('/') }
            else -> plural(editPaths.size, "1 file", "${editPaths.size} files")
        }
        "run" -> commands
        "tool" -> toolNames.joinToString(", ")
        else -> exploreBits(true).joinToString(", ")
    }
    // `kb`: explored / edited / ran / used
    val verb = when (chosen) {
        "edit" -> "Edited"
        "run" -> "Ran"
        "tool" -> "Used"
        else -> "Explored"
    }

    // `others`: every other mode's bits, then the background work's verbs.
    val others = ArrayList<String>()
    order.filter { it != chosen }.forEach { m -> others.addAll(modeBits(m)) }
    // `bv`/`yv`: background shells and subagents report what happened to them.
    count(setOf("shell", "bash", "execute")).takeIf { it > 0 }?.let { others.add("started $it shell") }
    count(setOf("task", "subagent")).takeIf { it > 0 }
        ?.let { others.add(plural(it, "1 subagent", "$it subagents")) }
    // an empty explore footer adds nothing, so strip blanks the way theirs does
    return RunActivity(verb, main, others.filter { it.isNotBlank() }.joinToString(", "))
}

/** Their `yy()`: interrupted/killed -> `Stopped`, timeout -> `Timed out`, else `Exit N`. */
private fun shellOutcome(p: Part): String? {
    if (p.name != "shell" && p.name != "bash" && p.name != "execute") return null
    val m = p.state?.metadata ?: return null
    if (m.interrupted == true || p.state?.status == "killed") return "Stopped"
    if (m.timeout == true || p.state?.status == "timeout") return "Timed out"
    val code = m.exit ?: return null
    return if (code != 0) "Exit $code" else null
}

/** The one-line tail their rows print after the tool name. */
private fun runDetail(p: Part): String {
    if (p.type == "reasoning") {
        val ms = p.time?.durationMs ?: 0L
        return if (ms > 0) formatDuration(ms) else ""
    }
    val st = p.state
    val raw = st?.inputString("command")
        ?: st?.inputString("filePath")
        ?: st?.inputString("path")
        ?: st?.inputString("pattern")
        ?: st?.inputString("url")
        ?: st?.title
        ?: p.text
        ?: ""
    return raw.replace("\n", " ").trim()
}

/** The guide line their collapsible draws next to an expanded body � copied from
    index-CbZCBGB7.css: `[data-slot=collapsible-content]` takes `padding-inline-start:14px`
    and its `::before` paints a 1px rule at `inset-inline-start:2px`, inset 4px top and
    bottom, in `color-mix(border-base 50%, transparent)`. Without it an open thought reads
    as a loose paragraph instead of belonging to its `Thought` row. */
private fun Modifier.railGuide(): Modifier = this
    .drawBehind {
        val inset = 4.dp.toPx()
        drawRect(
            color = Line.copy(alpha = 0.5f),
            topLeft = Offset(2.dp.toPx(), inset),
            size = Size(1.dp.toPx(), (size.height - inset * 2f).coerceAtLeast(0f)),
        )
    }
    .padding(start = 14.dp)

/** Their live thought: the text grows as deltas land, a caret blinks at the tail and
    the tail line carries a moving highlight, so you can see it still writing. */
@Composable
private fun ReasoningBlock(text: String, streaming: Boolean) {
    // The caret blinks only while the block is actually being written.
    //
    // Measured 22 Nov 11:53: the device sat at `queueBuffer: fps=120.09` with nothing
    // streaming. These two transitions were started unconditionally, and a reasoning block is
    // composed for every finished assistant message that has one, so the transcript held a
    // permanent invalidate loop: the CPU never idled, the phone ran at 39.8 C, and every real
    // state change had to wait its turn behind a 120 fps redraw. That is also why a reply
    // looked late - the deltas were arriving into a frame loop that never rested.
    val caretAlpha = if (streaming) {
        // A block cursor blinking at 1 Hz, stepped 12 times a second.
        //
        // It was a `rememberInfiniteTransition`, which asks Compose for a frame at the
        // display's own rate - and it drives the *colour of a text glyph*, so every one of
        // those frames recomposed, re-laid-out and re-drew that paragraph. Measured 22 Nov
        // 12:10 on this phone: `queueBuffer: fps=118.15` for the whole length of a reply.
        // The streamed text was competing with 120 redraws a second of a cursor that
        // visibly changes twice a second. Same blink, 12 steps instead of 120.
        var a by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) {
            val t0 = System.nanoTime()
            while (true) {
                val ph = ((System.nanoTime() - t0) / 1_000_000_000.0 % 1.04) / 1.04
                a = if (ph < 0.5) (ph / 0.5).toFloat() else ((1.0 - ph) / 0.5).toFloat()
                kotlinx.coroutines.delay(80)
            }
        }
        a
    } else {
        0f
    }
    // A long reasoning block used to be ONE `Text`. Every streamed delta re-scanned the whole
    // block with the semantic colour pass and re-laid-out the whole paragraph, so the longer a
    // thought grew the heavier it got - the owner, 2026-11-24: *"YUNG THINKING NYA PAG MAHABA LAG PAG
    // NAG SCROLL AKO"*. Split on blank lines: paragraphs already on screen keep their own
    // remembered span and Compose skips them, so only the paragraph the stream is still writing
    // is scanned and measured again. Same words, same order, same colour.
    val paras = remember(text) {
        text.split("\n\n").map { it.trim('\n') }.filter { it.isNotEmpty() }
    }
    Column(Modifier.fillMaxWidth().railGuide().padding(vertical = 3.dp)) {
        paras.forEachIndexed { i, para ->
            if (i > 0) Spacer(Modifier.height(4.dp))
            SemanticText(
                para,
                color = if (streaming) TextMid else TextDim,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
            )
        }
        if (streaming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "\u258c",
                    fontSize = 11.5.sp,
                    color = Accent.copy(alpha = caretAlpha),
                )
                Spacer(Modifier.width(6.dp))
                // the owner, 22 Nov: *"the writen animation same as working thinking"*. This label
                // sat still in one flat colour while the header swept a band across its
                // characters. It runs the same per-character band now, tuned to sit under the
                // header rather than compete with it.
                ShimmerText(
                    "writing",
                    fontSize = 10.sp,
                    peak = AccentSoft,
                    base = Accent.copy(alpha = 0.5f),
                )
            }
        }
    }
}

/** user messages stay alone; consecutive assistant messages are one visual turn. */
private data class Turn(
    val key: String,
    val user: MessageWithParts? = null,
    val parts: List<Part> = emptyList(),
    /** last assistant message of the turn: agent, model, timings, tokens for the footer */
    val answer: MessageInfo? = null,
)

private fun buildTurns(list: List<MessageWithParts>): List<Turn> {
    // One item per exchange, keyed by the user message id: assistant messages are
    // appended to the turn that started them, so a growing reply never changes a key
    // and the list can't reset its scroll position while you read.
    val keys = ArrayList<String>()
    val users = ArrayList<MessageWithParts?>()
    val parts = ArrayList<ArrayList<Part>>()
    val answers = ArrayList<MessageInfo?>()

    fun start(key: String, u: MessageWithParts?) {
        keys.add(key)
        users.add(u)
        parts.add(ArrayList())
        answers.add(null)
    }

    for (m in list) {
        if (m.info.role == "user") {
            start("u:" + m.info.id, m)
        } else {
            // Their transcript is one stream: a system notice, a switch row or a shell row
            // belongs to whichever turn was open when it happened, not to a turn of its own.
            if (keys.isEmpty()) start("a:" + m.info.id, null)
            when (m.info.type) {
                // `ui.messagePart.compaction.done` = our `Compacted` divider
                "compaction" -> parts.last().add(Part(id = m.info.id, type = "compacted"))
                // the end-of-turn marker: carries `outcome` (succeeded|failed|interrupted)
                "idle" -> parts.last().addAll(m.parts)
                else -> parts.last().addAll(m.parts)
            }
            // only an assistant message owns the footer (agent / model / tokens / timing)
            if (m.info.type == "assistant") answers[answers.lastIndex] = m.info
        }
    }
    return keys.indices.map { Turn(keys[it], users[it], parts[it], answers[it]) }
}

private sealed interface Seg {
    data class Prose(val text: String) : Seg
    data class Run(val parts: List<Part>) : Seg
    /** `thinking:${userMessageID}` — PC's `compact` preset puts reasoning inside the same
     *  `context` group as its tools, so it is rendered as a row of the run, not beside it. */
    data class Thought(val parts: List<Part>) : Seg
    data class Note(val text: String) : Seg
    /**
     * `timeline-row.ts` `CompactionQueued` and its siblings.
     *
     * Their compaction is ONE row whose label is the compaction's own status, from
     * `ui.messagePart.compaction.*`: `queued` -> "Session compaction queued",
     * `running` -> "Compacting", `done` -> "Compacted", `failed` -> "Session compaction
     * failed", `cancelled` -> "Session compaction cancelled", `interrupted` -> "Session
     * compaction interrupted", `started` -> "Session compaction started".
     *
     * Ours drew the bare word "Compacted" for every one of those states, so a queued or
     * failed compaction read as a finished one.
     */
    data class Compaction(val status: String?) : Seg
    /**
     * `timeline-row.ts` `TurnDivider` - the rule that separates two turns
     * (`key: turn-divider:${'$'}{userMessageID}`).
     */
    data object Divider : Seg
    /**
     * `timeline-row.ts` `Worked` - `ui.timeline.activity.worked` = "Worked {{duration}}",
     * or `workedUnknown` = "Worked" when the turn never stamped a duration. It is the row
     * that stands in for the agent activity of a turn he stopped.
     */
    data class Worked(val ms: Long?) : Seg
    /**
     * `timeline-row.ts` `Retry` — `{attempt: number, message: string, next: number}`.
     * `next` is the server's `retry.at` epoch ms; the row ticks down to it
     * (`p_` in the renderer: `Math.round((next - Date.now()) / 1000)`, clamped at 0).
     * Their strings:
     *   `retry.attemptWaiting` = "Attempt {{attempt}} - retrying in {{count}}s"
     *   `retry.attemptRetryingNow` = "Attempt {{attempt}} - retrying"
     * The message is truncated to 80 chars plus "." before it is shown.
     */
    data class Retry(val attempt: Int, val next: Long, val message: String?) : Seg
    /**
     * `timeline-row.ts` `Activity` — the live status row above undelivered input.
     *
     * The renderer draws nothing for it (`if (W(n(),"Activity")) return null` in
     * `route-BPCnB8DM.js`); it exists so the grouping pass can close the work the model has
     * already seen before anything still waiting for delivery. It is kept as a segment so
     * the group above it cannot merge with the one below, which is the whole point of it.
     */
    data object Activity : Seg
    /** `todowrite`: their `todos` band — one checkbox row per item. */
    data class Todos(val items: List<com.zapp.net.api.TodoItem>) : Seg
    /** A transcript-level line that is neither user nor assistant: system notice, skill
     *  load, agent/model/directory switch, inline shell row. */
    data class Sys(val text: String, val sub: String? = null) : Seg
    /** `idle` outcome � only rendered when the turn did not succeed. */
    data class Outcome(val outcome: String, val error: String?) : Seg
}

private fun segments(
    parts: List<Part>,
    /**
     * `settings.general.timelineDetail`, defaulting to the shipped `compact` preset.
     *
     * Only the two unambiguous answers are acted on here: `hidden` drops the row and
     * `separate` gives the category its own run. `grouped` keeps the renderer's existing
     * fold, so the default preset renders exactly as it did before this contract existed.
     */
    detail: TimelineDetail.Detail = TimelineDetail.default,
    /**
     * `timeline-next/projection.ts:230 workedDuration(user, steps)` — how long a turn he
     * stopped worked. Only the `Worked` row uses it, and only when `hidesActivity(detail)`.
     */
    workedMs: Long? = null,
): List<Seg> {
    val out = ArrayList<Seg>()
    var run = ArrayList<Part>()
    // `Retry` / `Error` are end-of-turn rows in PC's `w_`, not inline rows.
    val pendingNotes = ArrayList<String>()
    // `TimelineRow.Retry` is pushed after every part of the turn, and the turn is the owner.
    val retries = ArrayList<Seg.Retry>()
    // The user stopped this turn: `interruptedAt >= 0` in `appendAssistantSegment`.
    var interrupted = false

    fun flush() {
        if (run.isEmpty()) return
        out.add(Seg.Run(run.toList()))
        run = ArrayList()
    }

    for (p in parts) {
        when {
            p.type == "compacted" -> {
                flush()
                out.add(Seg.Compaction(p.status))
            }
            p.type == "text" && !p.text.isNullOrBlank() -> {
                flush()
                out.add(Seg.Prose(p.text.trim()))
            }
            p.type == "tool" && p.name.orEmpty().lowercase() in setOf("todowrite", "todo") &&
                p.state?.todos()?.isNotEmpty() == true -> {
                flush()
                out.add(Seg.Todos(p.state.todos()))
            }
            // `case \`diff\`: S(C), C=[], m.push(new $.TurnDiff(...))` pushes the row into the same
            // chronological sequence the parts already have. The edit part stays in the run and the
            // card is drawn at that exact position by `FileCountsCard` (see `is EditGroup`), so a
            // shell row that came first stays first and never ends up under the card.
            p.type == "tool" && p.name.orEmpty().lowercase() in EDIT_TOOLS -> {
                run.add(p)
            }
            p.type == "tool" || p.type == "reasoning" -> {
                // `timelineCategory` + the preset's placement (`timeline/detail.ts:201-227`).
                // `hidden` really means hidden; `separate` means its own run; `grouped` is
                // the fold this renderer already had.
                val cat = TimelineDetail.category(p.type, p.name)
                when (if (cat == null) TimelineDetail.Placement.Grouped else detail.of(cat).placement) {
                    TimelineDetail.Placement.Hidden -> Unit
                    TimelineDetail.Placement.Separate -> {
                        flush()
                        out.add(Seg.Run(listOf(p)))
                    }
                    TimelineDetail.Placement.Grouped -> run.add(p)
                }
            }
            // a turn the server killed (quota wall, deprecated model, auth) carries its
            // reason on the message, not in content. Dropping it made a failed run look
            // exactly like a run that never started.
            p.type == "error" && !p.text.isNullOrBlank() -> {
                // same rule as `Retry`: PC's `Error` row is pushed at the end of the turn
                pendingNotes.add(p.text.trim())
            }
            p.type == "system" && !p.text.isNullOrBlank() -> {
                flush()
                // `timelineNoticePlacement`: a `hidden` notices category swallows the row,
                // and only a notice that is required gets to survive it.
                if (detail.of("notices").placement != TimelineDetail.Placement.Hidden) {
                    out.add(Seg.Sys(p.text.trim()))
                }
            }
            p.type == "switch" && !p.name.isNullOrBlank() -> {
                flush()
                // `ui.sessionTimeline.notice.*`: `Agent changed`, `Switched to {{model}}`,
                // `Switched variant to {{variant}}`, `Moved to`
                val label = when (p.text?.lowercase()) {
                    "agent" -> "Agent changed"
                    "model" -> "Switched to ${p.name}"
                    "variant" -> "Switched variant to ${p.name}"
                    "directory", "moved" -> "Moved to ${p.name}"
                    else -> p.text?.takeIf { it.isNotBlank() } ?: "Agent changed"
                }
                if (detail.of("notices").placement != TimelineDetail.Placement.Hidden) {
                    out.add(Seg.Sys(label))
                }
            }
            p.type == "shellrow" && !p.text.isNullOrBlank() -> {
                flush()
                val exit = p.name
                val failed = TimelineDetail.shellResultFailed(
                    timeout = p.status == "timeout",
                    exit = exit?.toIntOrNull(),
                )
                // `timelineNoticePlacement` for a shell notice: `hidden` swallows it unless
                // the process actually failed, which must never be silent.
                if (detail.of("shell").placement != TimelineDetail.Placement.Hidden || failed) {
                    out.add(
                        Seg.Sys(
                            "Ran ${oneLine(p.text)}",
                            when {
                                exit != null && exit != "0" -> "exit $exit"
                                else -> p.status
                            },
                        ),
                    )
                }
            }
            p.type == "retry" -> {
                // `w_` pushes `Retry` after every part of the turn has been emitted
                // (`p(m)` then `if (t.at(-1) === s) ... o.push(new Retry(...))`), so it must
                // not split the group it happened inside. Their row carries the server's
                // `retry.at`, which is what turns the line into a countdown.
                retries.add(
                    Seg.Retry(
                        attempt = p.name?.toIntOrNull() ?: 1,
                        next = p.time?.created ?: 0L,
                        message = p.text,
                    ),
                )
            }
            // `succeeded` needs no marker; `failed`/`interrupted` do, or a stopped turn
            // looks identical to a turn that ended cleanly.
            //
            // `interrupted` is a `TurnDivider` in their model, not an error row: the divider's
            // label is `ui.message.interrupted` = "Interrupted", which is the same word our
            // Outcome row printed, so the divider replaces it instead of doubling it.
            p.type == "idle" && !p.text.isNullOrBlank() && p.text != "succeeded" -> {
                flush()
                if (p.text == "interrupted") {
                    interrupted = true
                    // Where the setting hides all of the agent's activity, the work stopped
                    // says how long it ran before the divider draws the line.
                    if (TimelineDetail.hidesActivity(detail)) out.add(Seg.Worked(workedMs))
                    out.add(Seg.Divider)
                } else {
                    out.add(Seg.Outcome(p.text, p.status))
                }
            }
            // `touched` is a per-step file list; the Review panel already owns that view
            p.type == "touched" -> Unit
        }
    }
    flush()
    // `w_` pushes `Retry` / `Error` after every part of the turn, so they close the
    // transcript instead of splitting the group they happened inside. The wire replays each
    // attempt; only the newest survives.
    retries.distinctBy { it.attempt to it.next }.lastOrNull()?.let { out.add(it) }
    // The server states a refusal twice: `error` on the assistant message (which becomes an
    // `error` part and lands in pendingNotes) and again on the separate `idle` row (which
    // becomes Seg.Outcome). Measured 04:53 on ses_e0a8e6380ffeYvTOjiiJGmPduI, both carried
    // `error.type=provider.free-limit` with the identical sentence, and the screen printed
    // it twice under a single `Failed`. The Outcome row is the truthful owner - it is the
    // turn's own end marker - so a note repeating it is dropped.
    val outcomeErrors = out
        .filterIsInstance<Seg.Outcome>()
        .mapNotNull { it.error?.trim() }
        .toSet()
    pendingNotes
        .distinctBy { it.substringAfter(" - ") }
        .filterNot { outcomeErrors.contains(it.trim()) }
        .forEach { out.add(Seg.Note(it)) }
    return out
}


/** shell-ish tool names on this server: `shell` is what it emits. */
private fun isShellPart(p: Part): Boolean =
    p.name.orEmpty().lowercase() in setOf("shell", "bash", "execute", "terminal")

/** A child session. The timeline draws these as `TaskCard`, not as a tool line. */
private fun isSubagentPart(p: Part): Boolean =
    p.name.orEmpty().lowercase() in setOf("task", "subagent")

/** still running: no exit yet. A finished part always carries a status or a completed stamp. */
private fun isRunningPart(p: Part): Boolean {
    val st = p.state?.status?.lowercase()
    if (st != null) return st == "running" || st == "pending" || st == "started"
    return p.time?.completed == null && p.time?.ran == null
}

/** "Thought 43s ?" / "Used 2 Write, Edit ?" � one shared rail toggle. */
/** `[data-slot=collapsible-arrow]`: a 24px box at the inline end holding a down chevron that
    turns to point right when collapsed. PC hides it until hover; touch has no hover, so it
    stays visible — the one forced deviation, same as the code-block copy control. */
@Composable
private fun CollapseArrow(open: Boolean, size: androidx.compose.ui.unit.Dp = 24.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            OcIcons.ChevronDown,
            contentDescription = if (open) "Collapse" else "Expand",
            tint = TextDim,
            modifier = Modifier
                .size(14.dp)
                .graphicsLayer { rotationZ = if (open) 0f else -90f },
        )
    }
}

@Composable
private fun ExpandRow(
    label: String,
    meta: String = "",
    labelColor: Color = TextDim,
    labelWeight: FontWeight = FontWeight.Normal,
    annotated: AnnotatedString? = null,
    detail: String = "",
    boxed: Boolean = true,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { open = !open }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (annotated != null) {
                Text(
                    annotated,
                    fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            } else {
                Text(
                    label,
                    fontSize = 12.5.sp, color = labelColor, maxLines = 1,
                    fontWeight = labelWeight,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (detail.isBlank()) Modifier.weight(1f, fill = false) else Modifier,
                )
            }
            if (detail.isNotBlank()) {
                Spacer(Modifier.width(7.dp))
                Text(
                    detail,
                    fontSize = 12.5.sp, color = TextDim, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (meta.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(meta, fontSize = 10.sp, color = TextDim)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "›",
                fontSize = 12.sp,
                color = TextDim,
                modifier = Modifier.rotate(if (open) 90f else 0f),
            )
        }
        if (open) {
            // PC shape: the RUN gets one rounded container; the calls inside it are plain
            // rows behind a rail. A box per call is what made it "daming line", and the
            // close arrow belongs to the header line, not to the bottom of the body.
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 4.dp)
                    .then(
                        if (boxed) Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, Line, RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                        else Modifier
                    ),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 2.dp),
                ) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .heightIn(min = 14.dp)
                            .background(Line),
                    )
                    Column(Modifier.fillMaxWidth().padding(start = 8.dp)) { content() }
                }
            }
        }
    }
}

/** Their `ui.message.duration.*`: seconds / minutes+seconds / hours+minutes. Never ms �
    a `807ms` stamp is not something the desktop ever prints. */
private fun formatDuration(ms: Long): String = MessageCopy.duration(ms)

/** One call inside the panel: file cards for edits, command + output for shell, images inline. */
@Composable
private fun ToolDetail(p: Part) {
    val st = p.state
    val files = st?.metadata?.files.orEmpty()
    val images = st?.content.orEmpty()
        .filter { it.type == "file" && it.uri?.startsWith("data:image") == true }
    val texts = st?.content.orEmpty()
        .filter { it.type == "text" }
        .mapNotNull { it.text }
        .joinToString("\n")
        .trim()
    val state = st?.status.orEmpty()

    Column(Modifier.fillMaxWidth().railGuide().padding(vertical = 3.dp)) {
        val meta = st?.metadata
        val exitCode = meta?.exit
        val timedOut = meta?.timeout == true
        val interrupted = meta?.interrupted == true
        val isShellRow = p.name == "shell" || p.name == "bash"
        val failed = if (isShellRow) {
            ShellChrome.failed(state, exitCode, timedOut)
        } else {
            state == "error" || state == "failed"
        }
        if (isShellRow) {
            // `ui.tool.shell.*`, verbatim. Their row prints one line and only when there is
            // something to say: "Writing command…" while it runs, then the exit code, the
            // timeout, or the cancel. A clean exit prints nothing - there is no "finished"
            // key in the bundle, and the old `"Shell " + state` line ("Shell finished",
            // "Shell error") was ours.
            val line = ShellChrome.label(state, exitCode, timedOut, interrupted)
            if (line.isNotEmpty()) {
                Text(
                    line,
                    fontSize = 10.sp,
                    color = if (failed) Danger else TextDim,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        } else {
            // PC never prints a raw state word. `streaming` / `running` / `pending` are
            // internal — while the part is live the row already carries a spinner, so a
            // literal "streaming" under it was pure noise. Only a real outcome gets a line:
            // `ui.tool.shell.error` / `.timeout` / `.cancelled`.
            val label = when (state) {
                "error", "failed" -> "Error"
                "timeout", "timedout" -> "Timed out"
                "cancelled", "canceled" -> "Cancelled"
                "interrupted" -> "Interrupted"
                else -> ""
            }
            if (label.isNotEmpty()) {
                Text(
                    label,
                    fontSize = 10.sp,
                    color = if (failed) Danger else TextDim,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
        if (files.isNotEmpty()) {
            files.forEach { FileCard(it) }
        } else {
            val head = when (p.name) {
                "shell", "bash" -> st?.inputString("command")
                    ?.takeIf { it.isNotBlank() }
                    ?: st?.metadata?.command
                "write", "edit", "read" -> st?.inputString("path")
                "grep", "glob" -> st?.inputString("pattern")
                "task" -> st?.inputString("description")
                else -> null
            }?.trim().orEmpty().ifBlank { st?.title.orEmpty().trim() }
            val shellLike = p.name == "shell" || p.name == "bash"
            if (head.isNotBlank()) {
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f)) { MonoToolBox(head, bordered = shellLike) }
                    CopyButton(head, label = MessageCopy.COPY)
                }
            }
            if (texts.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth().padding(top = if (head.isNotBlank()) 6.dp else 0.dp),
                ) {
                    Box(Modifier.weight(1f)) { MonoToolBox(texts, bordered = shellLike) }
                    CopyButton(
                        texts,
                        label = if (failed) MessageCopy.COPY_ERROR else MessageCopy.COPY_OUTPUT,
                    )
                }
            }
        }
        images.forEach { ImageBlock(it.uri!!) }
    }
}

/** `[data-component=bash-output]`: `.5px` border, radius 6, `pre` padding 12, max-height 240
    with the scrollbar hidden. `tool-output` is the same cap without the frame. Both are
    mono 13/20 `text-base`. */
@Composable
private fun MonoToolBox(text: String, bordered: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .then(
                if (bordered) {
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(0.5.dp, Line, RoundedCornerShape(6.dp))
                } else {
                    Modifier
                },
            )
            .heightIn(max = 240.dp),
    ) {
        SemanticText(
            text,
            color = TextMid,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(if (bordered) 12.dp else 0.dp),
        )
    }
}

/** Their `text-shimmer` (index-CbZCBGB7.css): every glyph shares one 1.2 s band, each one
    delayed 45 ms behind the last, base colour `text-weak`, peak `text-strong`. A single wide
    gradient across the word reads as a static label on a phone, which is why the animation
    looked gone � this walks the highlight glyph by glyph, exactly like their CSS delay. */
@Composable
private fun ShimmerText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit = 11.sp,
    peak: Color = TextPrimary,
    base: Color = TextDim,
) {
    // 30 updates a second, not 120.
    //
    // This used to be a `rememberInfiniteTransition`, which asks Compose for a frame at the
    // display's own rate. Measured 22 Nov 11:53 on this phone: `queueBuffer: fps=120.09` held
    // for the entire duration of a turn, because this label is on screen the whole time a
    // reply runs. The band moves in 1.2 s, so 30 steps is already smoother than the eye can
    // follow, and every frame that is not drawn is a frame the streamed text gets instead.
    var phase by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val startedAt = System.nanoTime()
        while (true) {
            phase = ((System.nanoTime() - startedAt) / 1_000_000_000.0 % 1.2 / 1.2).toFloat()
            kotlinx.coroutines.delay(33)
        }
    }
    val chars = text.toList()
    // their window is 5.2ch wide; on a short word that is most of it, so scale by length
    val band = (5.2f / chars.size.coerceAtLeast(1)).coerceIn(0.18f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        chars.forEachIndexed { i, ch ->
            // animation-delay: 45ms * index * -1  ?  a phase offset per character
            val local = ((phase - i * 0.0375f) % 1f + 1f) % 1f
            val k = (1f - kotlin.math.abs(local - 0.5f) / (band / 2f)).coerceIn(0f, 1f)
            Text(
                ch.toString(),
                color = androidx.compose.ui.graphics.lerp(base, peak, k),
                fontSize = fontSize,
                maxLines = 1,
            )
        }
    }
}

/** `Thinking 4s` � their live counter, ticking off the moment the step started. */
@Composable
private fun TickingSeconds(started: Long) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(started) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val secs = ((now - started) / 1000L).coerceAtLeast(0L)
    SemanticText("${secs}s", color = TextDim, fontSize = 11.sp)
}

/** Their keycap: mono, 9sp, on the surface tint, sitting right after the action label. */
@Composable
private fun KeyCapHint(text: String) {
    Text(
        text,
        fontSize = 9.sp,
        color = TextDim,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Surface2)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** Their `copy` / `check` pair, 16 grid (`icon-C5tP_h6Y.js`): the shell block's
 *  `ui.message.copy` on the command and `ui.message.copyOutput` on the output.
 *
 *  PC gives each one a distinct tooltip (`ui.tool.execute.copyCode` on a code section,
 *  `ui.tool.execute.copyOutput` on its output, `ui.toolErrorCard.copyError` on a failure) and
 *  the same tooltip is the button's `aria-label`. A finger has no tooltip, so the label goes
 *  into the accessibility name instead - the one place where dropping a hover-only affordance
 *  would otherwise leave an unnamed button. */
@Composable
private fun CopyButton(
    text: String,
    tint: Color = TextDim,
    label: String = MessageCopy.COPY,
) {
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(MessageCopy.COPY_RESET_MS)
            copied = false
        }
    }
    Box(
        Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(4.dp))
            .semantics { contentDescription = if (copied) MessageCopy.COPIED else label }
            .clickable {
                clip.setText(AnnotatedString(text))
                copied = true
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(11.dp)) {
            val u = size.width / 16f
            val st = Stroke(width = 1.1f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val col = if (copied) Accent else tint
            if (copied) {
                val p = Path().apply {
                    moveTo(4.5f * u, 7.5f * u); lineTo(7f * u, 11.5f * u); lineTo(11.5f * u, 4.5f * u)
                }
                drawPath(p, col, style = st)
            } else {
                val a = Path().apply {
                    moveTo(9.5f * u, 3.5f * u); lineTo(9.5f * u, 1.5f * u); lineTo(1.5f * u, 1.5f * u)
                    lineTo(1.5f * u, 10.5f * u); lineTo(4.5f * u, 10.5f * u)
                }
                val b = Path().apply {
                    moveTo(6.5f * u, 14.5f * u); lineTo(6.5f * u, 5.5f * u); lineTo(14.5f * u, 5.5f * u)
                    lineTo(14.5f * u, 14.5f * u); close()
                }
                drawPath(a, col, style = st)
                drawPath(b, col, style = st)
            }
        }
    }
}

/** Their file row: type glyph, path, +adds/-dels, tap to see the patch. */
@Composable
private fun TurnDiffs(files: List<PartFile>) {
    var all by remember(files) { mutableStateOf(false) }
    val shown = if (all) files else files.take(3)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // `ui.sessionTurn.diffs.changed.one` = `{{count}} Changed file`
                plural(files.size, "${files.size} Changed file", "${files.size} Changed files"),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
            )
            if (files.size > 3) {
                Spacer(Modifier.width(8.dp))
                Text(
                    if (all) "Show less" else "Show all",
                    fontSize = 14.sp,
                    // `[data-slot=session-turn-diffs-toggle] { color: --v2-text-text-accent }`
                    color = Link,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { all = !all }
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                )
            }
        }
        shown.forEach { DiffIndexRow(it) }
        if (!all && files.size > 3) {
            Text(
                "Show all ${files.size}",
                fontSize = 13.sp,
                color = TextDim,
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 6.dp)
                    .clickable { all = true },
            )
        }
    }
}

/** `session-turn-diff-trigger`: directory muted, filename strong, +N/-N, chevron. */
@Composable
private fun DiffIndexRow(f: PartFile) {
    var open by remember { mutableStateOf(false) }
    val full = (f.file ?: "").replace('\\', '/')
    val name = full.substringAfterLast('/')
    val dir = full.substringBeforeLast('/', "")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .clickable { open = !open }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileGlyph(full)
            Spacer(Modifier.width(7.dp))
            // The path takes the whole slack in ONE weight. Giving `dir`, `name` and a trailing
            // spacer a weight each split the slack three ways, which parked `+N -N` in the middle
            // of the row instead of flush right the way `diff-changes` does (`justify-content:
            // flex-end`). the owner, 20 Nov: *"nasa gitna ang bobo mo naman na"*.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (dir.isNotEmpty()) {
                    Text(
                        "$dir/",
                        fontSize = 13.sp,
                        // `[data-slot=session-turn-diff-directory] { color: --v2-text-text-muted }`
                        color = TextMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Text(
                    name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            // `data-display/diff-changes` - bare coloured text, right-aligned, no background
            DiffChanges(f.additions, f.deletions)
            Spacer(Modifier.width(4.dp))
            CollapseArrow(open, size = 16.dp)
        }
        if (open && !f.patch.isNullOrBlank()) {
            DiffView(f.patch, full)
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * PC's edit rows, the ones the owner pointed at in his screenshot. There are two shapes and this
 * draws the one the transcript shows:
 *
 * `route-BPCnB8DM.js` -> `ch(e)` builds a patch group from every consecutive edit/write/patch
 * tool, and `dh` renders it as an `apply-patch` accordion: one 32px trigger per file,
 * `apply-patch-directory` muted + ellipsized, `apply-patch-filename` bright, `diff-changes`
 * (`data-appearance=standard`) and `chevrons-up-down`; the body is the patch.
 *
 * `[data-component=file-counts]` is their *script-changes* row (`data-script-changes` wrapper,
 * `6px 0` padding, no border) and is a different component - it is not what the transcript shows
 * for an edit tool, so it is not what this draws.
 */
@Composable
private fun FileCountsCard(row: EditGroup) {
    // `[data-component=accordion][data-scope=apply-patch]`: one bordered stack, every file a
    // 32px trigger row, adjacent borders collapsed with `margin-top:-1px`. Two edits are two
    // rows here, never two separate cards.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Line, RoundedCornerShape(8.dp)),
    ) {
        row.files.forEachIndexed { idx, f ->
            if (idx > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            EditFileRow(f)
        }
    }
}

/** One `apply-patch` item: 32px trigger, icon, directory (muted, shrinks), filename (bright),
    diff-changes, `chevrons-up-down`; the body is the patch. */
@Composable
private fun EditFileRow(f: EditFile) {
    var open by remember(f.file, f.patch) { mutableStateOf(false) }
    val full = f.file.replace('\\', '/')
    val name = full.substringAfterLast('/')
    val dir = full.substringBeforeLast('/', "")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clickable(enabled = !f.patch.isNullOrBlank()) { open = !open }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileMarkBadge(full, box = 16.dp)
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (dir.isNotEmpty()) {
                    Text(
                        "$dir/",
                        fontSize = 13.sp,
                        color = TextMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Text(
                    name,
                    fontSize = 13.sp,
                    color = TextPrimary,
                    maxLines = 1,
                    // `apply-patch-filename` never gives way; the directory carries the ellipsis.
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            when (f.status) {
                // `ui.patch.action.created` / `.deleted` are drawn in the additions/deletions
                // slots of a `diff-changes` (`dh` in route-BPCnB8DM.js), not as a pill.
                "added" -> Text("Created", fontSize = 13.sp, color = DiffAdd)
                "deleted" -> Text("Deleted", fontSize = 13.sp, color = DiffDel)
                else -> DiffChanges(f.additions, f.deletions, fontSize = 13.sp)
            }
            Spacer(Modifier.width(16.dp))
            Icon(
                OcIcons.ChevronsUpDown,
                contentDescription = if (open) "Collapse" else "Expand",
                tint = TextDim,
                modifier = Modifier.size(14.dp),
            )
        }
        if (open && !f.patch.isNullOrBlank()) DiffView(f.patch, full)
    }
}

/** Their file row: type glyph, path, +adds/-dels, tap to see the patch. */
@Composable
private fun FileCard(f: PartFile) {
    var open by remember { mutableStateOf(false) }
    // their default `layout.diffStyle` is `split` (current-BpUjPsAD.js, global store default)
    var split by remember { mutableStateOf(true) }
    val full = (f.file ?: "").replace('\\', '/')
    val name = full.substringAfterLast('/')
    val dir = full.substringBeforeLast('/', "")
    val label = remember(full) {
        buildAnnotatedString {
            val head = full.substringBefore('/')
            val tail = full.substringAfter('/', "")
            withStyle(SpanStyle(color = Syn.Keyword, fontSize = 10.5.sp)) { append(head) }
            if (tail.isNotBlank()) {
                withStyle(SpanStyle(color = TextPrimary, fontSize = 10.5.sp)) { append("/$tail") }
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Surface2)
                .border(1.dp, Line, RoundedCornerShape(8.dp))
                .clickable { open = !open }
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileGlyph(full)
            Spacer(Modifier.width(7.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            // `data-display/diff-changes` - bare coloured text, right-aligned, no background
            DiffChanges(f.additions, f.deletions)
            Spacer(Modifier.width(4.dp))
            // theirs only surfaces these two on hover; on a phone that means "opened"
            if (open) {
                // `ui.sessionReview.openFile` � opens the server's copy of the file
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { FileOpener.open(full) },
                    contentAlignment = Alignment.Center,
                ) { OpenFileGlyph(TextDim) }
                // `ui.sessionReviewV2.diffView` � split / unified
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { split = !split },
                    contentAlignment = Alignment.Center,
                ) { DiffStyleGlyph(split, if (split) TextMid else TextDim) }
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { open = !open }
                    .padding(horizontal = 3.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    OcIcons.ChevronDown, if (open) "Collapse" else "Expand",
                    tint = TextDim,
                    modifier = Modifier.size(13.dp).rotate(if (open) 180f else 0f),
                )
            }
        }
        if (open && !f.patch.isNullOrBlank()) {
            DiffView(f.patch!!, full, split)
        }
    }
}

/** Unified diff the way their editor draws it: two gutters, tinted rows, syntax colour.
 *  `split` is their second `diffStyle`: old and new side by side instead of stacked.
 *
 *  `wrap` is the review's `overflow` prop: `review/mobile.tsx:21` passes `"wrap"` whenever the
 *  global `mobileDiff.wrap` setting is on, and `gui-plugins/src/review/index.ts:48` defaults it
 *  to `true`. A wrapped diff has no horizontal scroll and lets a long line fall under the
 *  gutters; a scrolled one keeps every line on one row. */
@Composable
internal fun DiffView(patch: String, path: String, split: Boolean = false, wrap: Boolean = true) {
    val lang = path.substringAfterLast('.', "").lowercase()
    if (split) {
        SplitDiff(patch, lang)
        return
    }
    var oldNo = 0
    var newNo = 0
    val lines = remember(patch) {
        patch.lines().take(200).map { it.removeSuffix("\r") }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            // `--diffs-bg` is the diff container's own background (`--color-background-stronger`),
            // one step above the page's `#101010`.
            .background(DiffBg)
            .border(1.dp, Line, RoundedCornerShape(8.dp))
            .then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())),
    ) {
        lines.forEach { raw ->
            when {
                raw.startsWith("@@") -> {
                    val m = Regex("@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@").find(raw)
                    if (m != null) {
                        oldNo = m.groupValues[1].toIntOrNull() ?: oldNo
                        newNo = m.groupValues[2].toIntOrNull() ?: newNo
                    }
                    Text(
                        raw,
                        fontSize = 9.sp,
                        lineHeight = 14.sp,
                        color = DiffHunkText,
                        fontFamily = FontFamily.Monospace,
                        softWrap = wrap,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(hunkWash())
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                raw.startsWith("+++") || raw.startsWith("---") ||
                    raw.startsWith("Index:") || raw.startsWith("diff ") ||
                    raw.startsWith("===") || raw.startsWith("*** ") -> Unit
                raw.startsWith("+") -> {
                    // The rail keeps the palette verbatim; the `+1` gutter is the readable lift of
                    // it, because `#2DB6A3` clears the floor and a numeral at 8.5sp needs it.
                    DiffRow(oldNo = null, newNo = newNo, mark = "+", text = raw.drop(1), lang = lang,
                        tint = diffWash(DiffAddBg), markColor = DiffAdd, rail = DiffBarAdd, wrap = wrap)
                    newNo++
                }
                raw.startsWith("-") -> {
                    DiffRow(oldNo = oldNo, newNo = null, mark = "-", text = raw.drop(1), lang = lang,
                        tint = diffWash(DiffDelBg), markColor = DiffDel, rail = DiffBarDel, wrap = wrap)
                    oldNo++
                }
                else -> {
                    val body = if (raw.startsWith(" ")) raw.drop(1) else raw
                    DiffRow(oldNo = oldNo, newNo = newNo, mark = " ", text = body, lang = lang,
                        tint = null, markColor = DiffNum, wrap = wrap)
                    oldNo++
                    newNo++
                }
            }
        }
    }
}

/** Side-by-side half of their `diffStyle: split`: a removed run and the added run that
 *  replaces it are paired row by row, context lines span both halves. */
@Composable
private fun SplitDiff(patch: String, lang: String) {
    val rows = remember(patch) { splitRows(patch) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DiffBg)
            .border(1.dp, Line, RoundedCornerShape(8.dp)),
    ) {
        rows.forEach { r ->
            when (r) {
                is SplitRow.Hunk -> Text(
                    r.text,
                    fontSize = 9.sp,
                    lineHeight = 14.sp,
                    color = DiffHunkText,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(hunkWash())
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
                is SplitRow.Pair -> Row(Modifier.fillMaxWidth().heightIn(min = 15.dp)) {
                    SplitCell(r.leftNo, r.left, lang, diffWash(DiffDelBg), DiffBarDel, DiffDel, Modifier.weight(1f))
                    Box(Modifier.fillMaxHeight().width(1.dp).background(Line))
                    SplitCell(r.rightNo, r.right, lang, diffWash(DiffAddBg), DiffBarAdd, DiffAdd, Modifier.weight(1f))
                }
            }
        }
    }
}

private sealed interface SplitRow {
    data class Hunk(val text: String) : SplitRow
    data class Pair(val leftNo: Int?, val left: String?, val rightNo: Int?, val right: String?) : SplitRow
}

private fun splitRows(patch: String): List<SplitRow> {
    val out = ArrayList<SplitRow>()
    var oldNo = 0
    var newNo = 0
    var dels = ArrayList<Pair<Int, String>>()
    var adds = ArrayList<Pair<Int, String>>()

    fun flush() {
        val n = maxOf(dels.size, adds.size)
        for (i in 0 until n) {
            val l = dels.getOrNull(i)
            val r = adds.getOrNull(i)
            out.add(SplitRow.Pair(l?.first, l?.second, r?.first, r?.second))
        }
        dels = ArrayList()
        adds = ArrayList()
    }

    patch.lines().take(400).map { it.removeSuffix("\r") }.forEach { raw ->
        when {
            raw.startsWith("@@") -> {
                flush()
                val m = Regex("@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@").find(raw)
                if (m != null) {
                    oldNo = m.groupValues[1].toIntOrNull() ?: oldNo
                    newNo = m.groupValues[2].toIntOrNull() ?: newNo
                }
                out.add(SplitRow.Hunk(raw))
            }
            raw.startsWith("+++") || raw.startsWith("---") || raw.startsWith("Index:") ||
                raw.startsWith("diff ") || raw.startsWith("===") || raw.startsWith("*** ") -> Unit
            raw.startsWith("+") -> {
                adds.add(newNo to raw.drop(1))
                newNo++
            }
            raw.startsWith("-") -> {
                dels.add(oldNo to raw.drop(1))
                oldNo++
            }
            else -> {
                flush()
                val body = if (raw.startsWith(" ")) raw.drop(1) else raw
                out.add(SplitRow.Pair(oldNo, body, newNo, body))
                oldNo++
                newNo++
            }
        }
    }
    flush()
    return out
}

/**
 * the owner, 20 Nov: *"wala na yung gradiant ang gusto kunin mo yung style ng color nya ilagay mo sa
 * kanila same"* - flat fills in the code block's own colour family, no fade. The rows keep a
 * whisper of hue so an add and a delete are still distinguishable at a glance.
 */
private fun diffWash(base: Color): Brush = SolidColor(base)

/** The `@@ … @@` band: neutral, never the violet we used to paint here. */
private fun hunkWash(): Brush = SolidColor(DiffHunkBg)

@Composable
private fun SplitCell(
    no: Int?,
    text: String?,
    lang: String,
    tint: Brush?,
    rail: Color?,
    num: Color? = null,
    mod: Modifier,
) {
    Row(mod.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(if (text == null) Color.Transparent else rail ?: Color.Transparent),
        )
        Text(
            no?.toString().orEmpty(),
            fontSize = 8.5.sp,
            color = if (text != null) (num ?: rail ?: DiffNum) else DiffNum,
            fontFamily = FontFamily.Monospace,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(22.dp).padding(end = 3.dp),
        )
        if (text != null) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (tint != null) Modifier.background(tint) else Modifier),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    remember(text, lang) { highlightCode(text, lang) },
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DiffRow(
    oldNo: Int?,
    newNo: Int?,
    mark: String,
    text: String,
    lang: String,
    tint: Brush?,
    markColor: Color,
    rail: Color? = null,
    wrap: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
        // A wrapped row grows downward, so its gutters stay level with the first line.
        verticalAlignment = if (wrap) Alignment.Top else Alignment.CenterVertically,
    ) {
        // `[data-indicators='bars'] [data-column-number]::before` - a 2px bar at `left: 0` of the
        // gutter, i.e. the very left edge of the row. No `+`/`-` glyph in bars mode.
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(rail ?: Color.Transparent),
        )
        // The number gutter is NOT tinted: their row background starts after the last digit, so
        // the numbers stay on the diff's own `#151515`. Unified view prints one number per row
        // (old for `-`, new otherwise), coloured on a changed row.
        Text(
            (oldNo ?: newNo)?.toString().orEmpty(),
            fontSize = 8.5.sp,
            color = if (rail != null) markColor else DiffNum,
            fontFamily = FontFamily.Monospace,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(28.dp).padding(end = 6.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(if (tint != null) Modifier.background(tint) else Modifier),
            contentAlignment = if (wrap) Alignment.TopStart else Alignment.CenterStart,
        ) {
            Text(
                remember(text, lang) { highlightCode(text, lang) },
                fontSize = 9.5.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                softWrap = wrap,
                modifier = if (wrap) Modifier.fillMaxWidth().padding(end = 8.dp)
                else Modifier.padding(end = 8.dp),
            )
        }
    }
}

/**
 * Their file row's type icon. The decision table is `ui/FileIcons.kt` (a port of
 * `choose-icon-name.ts`); this is only the call site every surface shares.
 */
@Composable
internal fun FileGlyph(path: String) {
    FileMarkBadge(path)
}

/** read tool images arrive as data:image/... � decode off the main thread, show full width. */
@Composable
private fun ImageBlock(uri: String) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = Base64.decode(uri.substringAfter("base64,", ""), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    var open by remember { mutableStateOf(false) }
    val b = bmp ?: return
    // A thumbnail, not a page-wide block: a 13 MP screenshot used to push the whole reply off
    // the screen. Tap opens it full-screen; the viewer closes on any tap.
    Image(
        b,
        contentDescription = "image",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .padding(top = 6.dp)
            .widthIn(max = 132.dp)
            .heightIn(max = 96.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, Line, RoundedCornerShape(6.dp))
            .clickable { open = true },
    )
    if (open) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { open = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { open = false },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    b,
                    contentDescription = "image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                // A visible way out. Any tap closes, but he asked for an X - "WALANG X BUTTON
                // SA FILES IMAGES ETC" - so the corner carries one.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(14.dp)
                        .size(36.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.14f))
                        .clickable { open = false },
                    contentAlignment = Alignment.Center,
                ) { Text("\u2715", fontSize = 15.sp, color = Color.White) }
            }
        }
    }
}

@Composable
private fun SessionAvatar(title: String?, size: Int = 15) {
    Box(
        Modifier.size(size.dp).background(AccentContainer, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title?.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
            style = TextStyle(
                fontSize = (size * 0.62f).sp,
                lineHeight = (size * 0.62f).sp,
                color = AccentSoft,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
        )
    }
}

/** Their `bullet-list` glyph, 16 grid, round joins � `ui.message.moveToQueue`. */
@Composable
private fun BulletListGlyph(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        val u = size.width / 16f
        val st = Stroke(width = 1.1f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = Path().apply {
            moveTo(8.5f * u, 4.5f * u); lineTo(14.5f * u, 4.5f * u)
            moveTo(8.5f * u, 11.5f * u); lineTo(14.5f * u, 11.5f * u)
            moveTo(1.5f * u, 2.5f * u); lineTo(5.5f * u, 2.5f * u)
            lineTo(5.5f * u, 6.5f * u); lineTo(1.5f * u, 6.5f * u); close()
            moveTo(1.5f * u, 9.5f * u); lineTo(5.5f * u, 9.5f * u)
            lineTo(5.5f * u, 13.5f * u); lineTo(1.5f * u, 13.5f * u); close()
        }
        drawPath(p, color, style = st)
    }
}

/** Their `grid-plus` glyph: three square outlines and a plus in the fourth cell. */
@Composable
private fun GridDots(color: Color, size: Int) {
    Canvas(Modifier.size(size.dp)) {
        val u = this.size.width / 16f      // their viewBox is 16
        val stroke = 1.15f * u
        fun cell(x: Float, y: Float) {
            drawRect(
                color,
                topLeft = Offset(x * u, y * u),
                size = Size(4.667f * u, 4.667f * u),
                style = Stroke(stroke),
            )
        }
        cell(2f, 2f)       // top-left
        cell(9.333f, 2f)   // top-right
        cell(2f, 9.333f)   // bottom-left
        // plus
        drawLine(color, Offset(9.333f * u, 11.667f * u), Offset(14f * u, 11.667f * u), stroke)
        drawLine(color, Offset(11.667f * u, 9.333f * u), Offset(11.667f * u, 14f * u), stroke)
    }
}

/** Desktop behaviour after CTRL B: the turn keeps running server-side and the timeline
    only shows a quiet `Working`. Tapping it brings the live view back. */
@Composable
private fun ParkedRow(onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onBack() }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Working", fontSize = 13.sp, color = TextDim)
    }
}

/** Their `magnifying-glass`: 4.5 radius ring plus a 45� handle. */
@Composable
private fun GlassIcon(color: Color, size: Int) {
    Canvas(Modifier.size(size.dp)) {
        val u = this.size.width / 16f
        val stroke = 1.25f * u
        drawCircle(color, radius = 4.5f * u, center = Offset(7f * u, 7f * u), style = Stroke(stroke))
        drawLine(color, Offset(10.5f * u, 10.5f * u), Offset(13.5f * u, 13.5f * u), stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun ReviewToggleGlyph(open: Float, color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val u = this.size.width / 20f
        withTransform({ scale(u, u, pivot = Offset.Zero) }) {
            // the catalog's mirrored group
            withTransform({ scale(-1f, 1f, pivot = Offset(10f, 10f)) }) {
                if (open > 0.001f) {
                    withTransform({
                        translate(2.917f * (1f - open), 0f)
                        scale(open, 1f, pivot = Offset.Zero)
                    }) {
                        drawPath(ReviewFillPath, color)
                    }
                }
                drawPath(
                    ReviewOutlinePath,
                    color,
                    style = Stroke(1f, cap = StrokeCap.Square, join = StrokeJoin.Miter),
                )
            }
        }
    }
}

/*
 * `session-header-actions.tsx` `SessionReviewToggle`'s own markup, path for path:
 *
 *   <g transform="matrix(-1 0 0 1 20 0)">
 *     <path d="M2.917 2.916H7.917V17.083H2.917Z" fill="currentColor"
 *           transform="matrix(open 0 0 1 2.917*(1-open) 0)"/>
 *     <path d="M2.917 2.916H17.084V17.083H2.917ZM7.917 2.916V17.083"
 *           stroke="currentColor" stroke-linecap="square"/>
 *   </g>
 *
 * 20 unit viewBox drawn at 16 (their `Icon` never sets a stroke width, so it is 1 unit) and
 * the pane fill grows out of the panel edge as `open` goes 0 -> 1.
 */
private val ReviewOutlinePath: Path by lazy {
    PathParser().parsePathString("M2.917 2.916H17.084V17.083H2.917ZM7.917 2.916V17.083").toPath()
}

private val ReviewFillPath: Path by lazy {
    PathParser().parsePathString("M2.917 2.916H7.917V17.083H2.917Z").toPath()
}



/** PORT of `ProgressCircle` (`main-CLeW75FU.js` @ ~2.359M, CSS
    `[data-component=progress-circle]`):
    - `transform: rotate(-90deg)`, so 0% starts at 12 o'clock
    - compact appearance: size 14, strokeWidth 1.5
    - radius = size/2, ring radius = radius - stroke/2, dash = C * (1 - pct/100)
    - track `--progress-circle-background`, arc `--progress-circle-progress`
    `fz` passes `appearance: compact, size: 16, strokeWidth: 2`, and the `compact` rules in
    `index-CbZCBGB7.css` pin the track to `--v2-background-bg-layer-04` and the arc to
    `--v2-icon-icon-base`. Dark block: #5C5C5C and #DBDBDB. */
@Composable
private fun ProgressRing(percent: Int, size: Dp = 16.dp) {
    // Exact numbers out of `progress-circle.tsx` + `main-BDySOyg3.css`:
    //
    //   viewBox 14, size 16, compact strokeWidth default 1.5
    //   radius = 7 - 1.5/2 = 6.25 (viewBox units) -> 7.143 dp at 16 dp
    //   `[data-component=progress-circle]{transform:rotate(-90deg)}`  -> arc starts at 12
    //   no `stroke-linecap` in the CSS -> SVG default `butt`, not round
    //   `stroke-dashoffset` transitions .35s cubic-bezier(.65,0,.35,1)
    //
    // The stroke is 1.5/14 of the box, i.e. 1.714 dp at 16 dp - the old 2.dp was the
    // `indicator` appearance's stroke (3/16), which made the pill's ring read heavy.
    val pct by animateFloatAsState(
        percent.coerceIn(0, 100).toFloat(),
        tween(350, easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)),
        label = "usage-ring",
    )
    val stroke = size * (1.5f / 14f)
    Canvas(Modifier.size(size).rotate(-90f)) {
        val w = stroke.toPx()
        val r = (size.toPx() - w) / 2f
        drawCircle(RingTrack, radius = r, style = Stroke(width = w))
        if (pct > 0f) {
            drawArc(
                color = RingArc,
                startAngle = 0f,
                sweepAngle = 360f * pct / 100f,
                useCenter = false,
                style = Stroke(width = w, cap = StrokeCap.Butt),
                topLeft = androidx.compose.ui.geometry.Offset(center.x - r, center.y - r),
                size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            )
        }
    }
}

/** `Intl.NumberFormat(locale,{notation:'compact',maximumFractionDigits:0})` then
    `.toLocaleLowerCase()` - so `58k`, `1m`, `948`. */
private fun compactCount(n: Long): String = when {
    n < 1_000 -> n.toString()
    n < 1_000_000 -> "${Math.round(n / 1_000.0)}k"
    n < 1_000_000_000 -> "${Math.round(n / 1_000_000.0)}m"
    else -> "${Math.round(n / 1_000_000_000.0)}b"
}
/** `Intl.NumberFormat(locale,{style:'currency',currency:'USD'})` */
private fun usd(v: Double): String = "$${"%.2f".format(v)}"

private fun toolName(p: Part): String =
    p.name.orEmpty().ifBlank { "tool" }.replaceFirstChar { it.uppercase() }

@Composable
private fun SessionMenuItem(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 12.5.sp,
        color = if (danger) DiffDel else TextPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 11.dp),
    )
}

/**
 * `shell/titlebar/recently-closed-tabs-menu.tsx`: newest first, only `info.prompted === true`
 * (`:79`), capped at 10 (`:97`), and the empty copy is `session.tabs.closed.empty`
 * ("No recently closed sessions", `en.ts:1197`). Selecting one calls `reopenClosedTab`
 * (`:341`), which restores the saved index (`tabs.tsx:345`).
 */
@Composable
private fun RecentlyClosedDialog(vm: MainViewModel, open: Boolean, onClose: () -> Unit) {
    if (!open) return
    val rows by vm.recentlyClosed.collectAsState()
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Layer1,
        title = {
            Text(
                "Recently closed",
                fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Medium,
            )
        },
        text = {
            if (rows.isEmpty()) {
                Text("No recently closed sessions", fontSize = 12.5.sp, color = TextDim)
            } else {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    rows.forEach { e ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    onClose()
                                    vm.reopenClosedTab(e.id)
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SessionAvatar(e.title)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                e.title ?: "Session",
                                fontSize = 12.5.sp,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text("Close", fontSize = 12.sp, color = TextDim)
            }
        },
    )
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, fontSize = 12.sp, color = TextDim, modifier = Modifier.width(84.dp))
        Text(value, fontSize = 12.sp, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * ZappCode's About screen - his product, his links.
 *
 * The menu used to send people to opencode.ai/docs, OpenCode's Discord and OpenCode's GitHub
 * issue tracker. All three belong to another product. Everything here is ZappCode's own, and
 * the version/install time/package are read from the installed APK at runtime so they can never
 * drift from the build he shipped.
 *
 * Discord is his real server: `RQ6NVspT2u` is `DEFAULT_INVITE` in `Onboarding.kt:127`, the same
 * code the onboarding card and the Discord invite card already use. One code, one place.
 */
@Composable
private fun AboutZappDialog(open: Boolean, onClose: () -> Unit) {
    if (!open) return
    val ctx = LocalContext.current
    val info = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
    }
    val version = info?.versionName.orEmpty().ifBlank { "1.0" }
    val installed = remember {
        info?.let {
            android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", it.lastUpdateTime).toString()
        }.orEmpty()
    }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Layer1,
        title = {
            Column {
                Text("ZappCode", fontSize = 16.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                Text(
                    "Your own tunnel. Your own agent.",
                    fontSize = 11.5.sp, color = TextDim,
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                AboutRow("Version", version)
                AboutRow("Installed", installed)
                AboutRow("Package", ctx.packageName)

                Spacer(Modifier.height(14.dp))
                Text("How to use", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                HowToRow("1", "Open Guard, tap Repair (one tap). Five stages run and WARP is verified.")
                HowToRow("2", "Open Chat, pick a session or start one, then type your prompt.")
                HowToRow("3", "Long commands: tap Move to background. The work keeps running in the Shell sheet.")
                HowToRow("4", "Guard shows your public /64. Rotate it any time with Rotate /64 Now.")

                Spacer(Modifier.height(14.dp))
                Text("Community", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(Accent.copy(alpha = 0.16f))
                        .clickable { openExternal(ctx, "https://discord.gg/$DISCORD_INVITE") }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Join the Discord", fontSize = 12.5.sp, color = AccentSoft)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "discord.gg/$DISCORD_INVITE",
                    fontSize = 11.sp, color = TextDim,
                )

                Spacer(Modifier.height(14.dp))
                Text(
                    "Design, branding and the tunnel are ZAPP's. The protocol and the behaviour " +
                        "are ported from OpenCode Desktop, MIT licensed.",
                    fontSize = 11.sp, color = TextDim,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text("Close", fontSize = 12.sp, color = TextDim)
            }
        },
    )
}

@Composable
private fun HowToRow(n: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(n, fontSize = 11.5.sp, color = Accent, modifier = Modifier.width(16.dp))
        Text(text, fontSize = 11.5.sp, color = TextMid)
    }
}

@Composable
internal fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {    var value by remember { mutableStateOf(initial) }
    TextPromptDialog(
        title = "Rename session",
        initial = initial,
        confirm = "Save",
        value = value,
        onChange = { value = it },
        onDismiss = onDismiss,
        onConfirm = { onConfirm(value.trim()) },
    )
}

/** `Move to directory�` � their `POST /api/session/:id/move {directory}`. The same session
 *  keeps its history, only its working directory changes. */
@Composable
private fun MoveDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    TextPromptDialog(
        title = "Move session",
        initial = initial,
        confirm = "Move",
        value = value,
        onChange = { value = it },
        onDismiss = onDismiss,
        onConfirm = { onConfirm(value.trim()) },
    )
}

/**
 * `ui.promptInput.history` - "Prompt history".
 *
 * Theirs is a popover: `history.search` = "Search prompt history.", entries newest first, each
 * labelled with its prompt text and described by its filenames (`e.prompt.flatMap(...)`), and
 * picking one restores it into the composer (`history.apply`). Nothing was invented here; the
 * only difference is that a phone has no arrow key, so this opens from a control.
 */
@Composable
private fun PromptHistoryDialog(
    entries: List<com.zapp.net.settings.PromptHistory.Entry>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    val shown = remember(entries, q) {
        val s = q.trim().lowercase()
        if (s.isEmpty()) entries else entries.filter { it.text.lowercase().contains(s) }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Surface)
                .border(1.dp, Line, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text("Prompt history", fontSize = 14.sp, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = q,
                onValueChange = { q = it },
                singleLine = true,
                placeholder = { Text("Search prompt history.", fontSize = 12.5.sp, color = TextDim) },
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Line,
                    cursorColor = Accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            if (shown.isEmpty()) {
                // `ui.list.empty` = "No results"
                Text("No results", fontSize = 12.5.sp, color = TextDim, modifier = Modifier.padding(8.dp))
            } else {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    shown.forEach { e ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onPick(e.text); onDismiss() }
                                .padding(horizontal = 6.dp, vertical = 9.dp),
                        ) {
                            Text(
                                e.text,
                                fontSize = 12.5.sp,
                                color = TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (e.files.isNotBlank()) {
                                Text(e.files, fontSize = 11.sp, color = TextDim, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextPromptDialog(
    title: String,
    initial: String,
    confirm: String,
    value: String,
    onChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        titleContentColor = TextPrimary,
        textContentColor = TextMid,
        title = { Text(title, fontSize = 14.sp, color = TextPrimary) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Line,
                    cursorColor = Accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = value.isNotBlank()) {
                Text(confirm, color = if (value.isNotBlank()) AccentSoft else TextDim, fontSize = 12.5.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextDim, fontSize = 12.5.sp) }
        },
    )
}

@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(title, fontSize = 14.sp, color = TextPrimary) },
        text = { Text(body, fontSize = 12.5.sp, color = TextMid) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = DiffDel, fontSize = 12.5.sp) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextDim, fontSize = 12.5.sp) }
        },
    )
}

/** Export� hands the transcript to whatever app he picks (Drive, chat, files). */
/**
 * `Import session`, opened directly inside `Download/ZAPP`.
 *
 * SAF has no "start in this folder" argument on the contract, but the platform does:
 * `EXTRA_INITIAL_URI` with a `DocumentsContract` folder Uri.
 *
 * The wildcard MIME type is deliberate. A file pushed from the PC has no registered MIME type
 * until the media scanner sees it, so an `application/json` filter hides exactly the files that
 * came from the PC - the opposite of what he asked for.
 */
private fun zappImportIntent(ctx: Context): Intent {
    val folder = runCatching {
        android.provider.DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Download/ZAPP",
        )
    }.getOrNull()
    val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*")
    if (folder != null) i.putExtra("android.provider.extra.INITIAL_URI", folder)
    return i
}

private fun shareText(ctx: Context, text: String, mime: String) {
    runCatching {
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(
            Intent.createChooser(i, "Export session").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Their `openExternal` bridge - every `href` menu item goes through it. */
private fun openExternal(ctx: Context, url: String) {
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * `logs.export`. Their main process zips the log directory; we keep no log file, so the app's own
 * logcat tag is the equivalent - every `Log.*` call in this app uses the `ZAPP` tag.
 */
private fun readZappLog(): String = runCatching {
    val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-s", "ZAPP:*"))
    val out = p.inputStream.bufferedReader().use { it.readText() }
    p.waitFor()
    out.ifBlank { "No ZAPP log lines." }
}.getOrElse { "logcat unavailable: ${it.message}" }

/** `SidebarAction` (`titlebar.tsx:76`): 28 px row, 16 px icon, 8 px gap, 13 px faint. */
@Composable
private fun DrawerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Black and white: an enabled row is `TextPrimary`, a disabled one drops to `TextDim`.
        // The old ink was a lavender (`#A79FB8`) with a violet-grey disabled state - that is
        // exactly the purple the owner pointed at on 22 Nov.
        val ink = if (enabled) TextPrimary else TextDim
        Icon(icon, null, tint = ink, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            fontSize = 12.sp,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun text_of(m: MessageWithParts): String =
    m.parts.filter { it.type == "text" }.joinToString("\n") { it.text.orEmpty() }.trim()

/** Attached files that are not images: shown by name, not rendered. */
private fun fileNames(m: MessageWithParts): List<String> =
    m.parts.mapNotNull { p ->
        val u = p.url
        if (p.type == "image" && u != null && !u.startsWith("data:image")) p.text else null
    }

/** Images the agent sent or the user attached � data: URLs at part level. */
private fun imageParts(m: MessageWithParts): List<String> =
    m.parts.mapNotNull { p ->
        val u = p.url
        if ((p.type == "file" || p.type == "image") && u != null && u.startsWith("data:image")) u else null
    }

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return c as? Activity
}

/**
 * Their permission card. A tool cannot run until this is answered, so when the transcript
 * looks frozen this is usually what is actually happening. Shape copied from the server's
 * `Permission.Request`: an action, the resources it touches, and three decisions.
 */
/** `38 801 000 ms` -> `10h 46m`, so the card can state the server's own window. */
private fun duration(ms: Long): String {
    val totalMin = (ms / 60_000).coerceAtLeast(0)
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        totalMin > 0 -> "${m}m"
        else -> "${ms / 1000}s"
    }
}

/**
 * Their free-limit card (`session.freeLimit.*`), and the honest statement of what a
 * rotation can and cannot fix. Copy is the product's own; nothing here is invented.
 *
 * Measured 2026-11-19 23:16, one address, one minute: a 5 888-token session answered 35
 * prompts in a row, while a 108 416-token session was refused on its first. The refusal
 * follows the size of the request, so when [tooBig] is set the card says that instead of
 * promising a fresh /64 will clear it - and offers the only thing that actually helps.
 */
@Composable
private fun QuotaCard(    wall: com.zapp.net.guard.QuotaWall?,
    tooBig: Long?,
    onClose: () -> Unit,
    onNewChat: () -> Unit,
) {
    val title = when {
        wall == null -> "This chat is too big for the free tier"
        wall is com.zapp.net.guard.QuotaWall.FreeLimit -> "Free limit reached"
        wall is com.zapp.net.guard.QuotaWall.GoLimit -> "OpenCode Go usage limit reached"
        wall is com.zapp.net.guard.QuotaWall.BudgetLimit ->
            if (wall.perModel) "Usage limit reached" else "Monthly budget reached"
        wall is com.zapp.net.guard.QuotaWall.RateLimit -> "Rate limited"
        else -> "Usage limit reached"
    }
    val sub = when {
        tooBig != null ->
            "${tooBig / 1000}k tokens in this chat. The free tier refuses a request that " +
                "size on every address, so a new chat is the only thing that helps."
        wall != null && wall.retryAfterMs != null && (wall.retryAfterMs ?: 0L) > 0 ->
            // The server states its own window; showing it is the only honest answer to
            // "why is the token not reset" - it is on a clock, not per message.
            //
            // "A new chat answers now" used to be appended here. It was NOT true: measured
            // 23 Nov 23:49, a fresh prompt in a fresh chat came back `Free limit reached` in
            // the same second, and the log read `Switched to deepseek-v4.1-flash-free`. The
            // line promised the user something the server did not do, which is exactly the
            // claim that makes us look wrong to the people using this. State the clock only.
            "Server resets this in ${duration(wall.retryAfterMs ?: 0L)}. " +
                "Nothing on the phone moves that clock."
        wall != null && wall is com.zapp.net.guard.QuotaWall.FreeLimit && wall.addressScoped ->
            "Rotating to a fresh /64 \u2014 your prompt is back in the box."
        wall != null && wall.addressScoped -> "Rotating to a fresh /64\u2026"
        else -> "This one follows the account, not the tunnel \u2014 a new /64 will not clear it."
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 11.5.sp, color = TextPrimary)
            Spacer(Modifier.weight(1f))
            Text(
                "dismiss",
                fontSize = 10.sp,
                color = TextDim,
                modifier = Modifier.clickable { onClose() },
            )
        }
        if (wall != null) {
            Spacer(Modifier.height(3.dp))
            Text(wall.message, fontSize = 10.5.sp, color = TextMid)
        }
        Spacer(Modifier.height(3.dp))
        Text(sub, fontSize = 10.sp, color = AccentSoft)
        if (tooBig != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "New chat",
                fontSize = 10.5.sp,
                color = AccentSoft,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Surface2)
                    .clickable { onNewChat() }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
        }
    }
}

/**
 * `session-free-limit` — their free-budget card.
 *
 * Markup (`route-BPCnB8DM.js`, `VL` at 652 360):
 *
 * ```
 * <div data-component=session-free-limit role=status
 *      class="flex flex-col gap-4 rounded-xl bg-v2-background-bg-base p-2
 *             [[data-color-scheme=dark]_&]:bg-v2-background-bg-layer-01
 *             shadow-[var(--v2-elevation-raised)]">
 *   <div class="flex flex-col gap-0.5 ps-1.5 pe-1 pt-1">
 *     <div class="text-[13px] font-[530] text-v2-text-text-base">        <- session.freeLimit.title
 *     <div class="text-[13px] font-[440] text-v2-text-text-muted">       <- .reset
 * ```
 *
 * Their footer row is `Subscribe to Go · $10/mo` + `Connect provider`. the owner's order,
 * 2026-11-24: *"REMOVE THE CONNECT TO PROVIDER AND SUBSCRIBE TO WTF THIS IS FREE
 * UNLIMITED APP I BUILD"*. ZAPP has no provider to connect and nothing to subscribe to,
 * so the row is gone and the sentence that named them is gone with it. What is left is
 * the only true thing the card carried: the clock on the free budget.
 *
 * Second order, same day: *"BAT HINDI MAKA CHAT?"* - the card must not own the composer
 * slot. It draws as a note above the box, and the box stays.
 *
 * The reset clock is their `Intl.DateTimeFormat`: time-only when the deadline is today,
 * `dateStyle: medium` + `timeStyle: short` otherwise (`KL`, 653 300).
 */
@Composable
private fun FreeLimitCard(resetAt: Long?) {
    fun stamp(ms: Long): String {
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
        val d = java.util.Date(ms)
        return if (day.format(d) == day.format(java.util.Date())) {
            java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(d)
        } else {
            java.text.DateFormat
                .getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                .format(d)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            // `rounded-xl` = 12px, `p-2` = 8px
            .clip(RoundedCornerShape(12.dp))
            .background(Surface2)
            .padding(8.dp),
    ) {
        Column(Modifier.padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Text(
                "Free limit reached",
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
                color = TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (resetAt != null) {
                    "Free usage resets at ${stamp(resetAt)}."
                } else {
                    "The free budget is on its own clock. Nothing on the phone moves it."
                },
                fontSize = 13.sp,
                fontWeight = FontWeight(440),
                color = TextMid,
            )
        }
    }
}

@Composable
private fun PermissionCard(
    req: com.zapp.net.api.PermissionRequest,
    extra: Int,
    onDecision: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(PermissionCopy.HEADING, fontSize = 11.5.sp, color = TextDim)
            if (extra > 0) {
                Spacer(Modifier.weight(1f))
                Text("+$extra more", fontSize = 10.sp, color = TextDim)
            }
        }
        // Their body, in their order: the tool's description, or the raw action in a code span
        // when the action is one they have no words for. No title line - `HEADING` already names
        // the card, and their card never repeats the tool above its own description.
        val description = PermissionCopy.description(req.action)
        if (description != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                description,
                fontSize = 10.sp,
                color = AccentSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (req.action.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                req.action,
                fontSize = 10.sp,
                color = TextMid,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // `request.message` - their second row, only when the server sent one.
        req.message?.takeIf { it.isNotBlank() }?.let { message ->
            Spacer(Modifier.height(4.dp))
            Text(message, fontSize = 10.sp, color = TextMid)
        }

        // `request.metadata.input` - their `permission-input` block, `key: value` per line.
        val inputLines = PermissionCopy.inputLines(req.metadata)
        if (inputLines.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            inputLines.forEach { line ->
                Text(
                    line,
                    fontSize = 10.sp,
                    color = TextFaint,
                    fontFamily = FontFamily.Monospace,
                    softWrap = true,
                )
            }
        }

        // `request.resources` - all of them, `break-all`. Theirs has no cap and no "+N more".
        req.resources.forEach { r ->
            Text(
                r,
                fontSize = 10.sp,
                color = TextMid,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Spacer(Modifier.height(9.dp))
        // Their footer order: Deny, Allow always, Allow once. `once` is the submit variant.
        Row(verticalAlignment = Alignment.CenterVertically) {
            PermChip(PermissionCopy.DENY) { onDecision("reject") }
            Spacer(Modifier.width(8.dp))
            PermChip(PermissionCopy.ALLOW_ALWAYS) { onDecision("always") }
            Spacer(Modifier.width(8.dp))
            PermChip(PermissionCopy.ALLOW_ONCE, primary = true) { onDecision("once") }
        }
    }
}

@Composable
private fun PermChip(text: String, primary: Boolean = false, onClick: () -> Unit) {    Text(
        text,
        fontSize = 11.sp,
        color = if (primary) AccentSoft else TextMid,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (primary) AccentContainer else Surface)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun Composer(
    vm: MainViewModel,
    sending: Boolean,
    queued: List<InboxItem>,
    seed: String?,
    draftScope: String,
    skills: List<com.zapp.net.api.SkillPick>,
    onRemoveSkill: (String) -> Unit,
    onSeedUsed: () -> Unit,
    onEditQueued: (String, String) -> Unit,
    onSend: (String, List<OutAttachment>, Boolean) -> Boolean,
    /** PC's shell mode: `POST /api/session/{id}/shell`. Same `true`-means-cleared contract. */
    onShell: (String) -> Boolean,
    onCancelQueued: (String) -> Unit,
    onMoveToQueue: (String) -> Unit,
    onMoveToSteer: (String) -> Unit,
    onReview: () -> Unit,
    /** `data-focused` on `#review-panel`: false the moment he types in the composer. */
    onFocusChanged: (Boolean) -> Unit,
    /** `dialog.model.manage` from the model picker's footer action. */
    onManageModels: () -> Unit = {},
) {
    var text by remember(draftScope) { mutableStateOf(vm.draftText(draftScope)) }
    // `layout.draft({ prompt })`: the Extensions panel's `setup.*` rows open a draft tab that
    // already holds their prompt. Read once for this scope, then cleared - reopening the tab must
    // not overwrite whatever he typed.
    LaunchedEffect(draftScope) {
        val seed = vm.pendingDraftPrompt.value
        if (seed != null && seed.first == draftScope) {
            text = seed.second
            vm.pendingDraftPrompt.value = null
        }
    }
    // `permissions.autoApprove` - read here so the palette row names the command it will run.
    val autoAccept by com.zapp.net.settings.DisplayPrefs.autoApprove.collectAsState()
    /**
     * `serializePrompt` (`composer/prompt-parts.ts:7`): the editor shows a title, the wire
     * gets `@session:<id>`. The phone's composer is plain text, so the bridge is the exact
     * literal that was inserted — see `SessionMention.serialize`.
     */
    var refs by remember(draftScope) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    // `persistSaveDelay = 100` (`runtime/persistence/persist.ts:25`), per scope - a draft
    // belongs to the session (or the draft tab), never to the whole app.
    LaunchedEffect(text, draftScope) { vm.saveDraft(draftScope, text) }
    // hoisted so the `/` palette can open the same menus the footer pills do
    var footerMenu by remember { mutableStateOf("") }
    // Their composer only offers the deliver-mode switch while a turn is running
    // (`delivery = working ? behavior : "steer"`); on an idle session the queue has no
    // meaning and a prompt sent that way would just sit in the inbox forever.
    var queueMode by remember { mutableStateOf(false) }
    LaunchedEffect(sending) { if (!sending) queueMode = false }
    // `Reply` seeds the box with the quoted message and puts the caret in it
    LaunchedEffect(seed) {
        if (seed != null) {
            text = seed
            onSeedUsed()
        }
    }
    // A refused turn puts its prompt back where it came from, like their `cr.set(er)`:
    // the wall costs him one keypress, not a retype.
    val pendingRestore by vm.restorePrompt.collectAsState()
    LaunchedEffect(pendingRestore, draftScope) {
        val p = pendingRestore ?: return@LaunchedEffect
        if (p.first != draftScope) return@LaunchedEffect
        text = p.second
        vm.restorePrompt.value = null
    }
    val selected by vm.selectedModel.collectAsState()
    var showPicker by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadPickers() }

    // `focus.composer` - their `e.onDone()`. Bumped by the branch machine when a pick closes
    // the popover or a keybind re-toggles it, so the caret comes back to the prompt.
    val promptFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusTick by vm.composerFocusTick.collectAsState()
    LaunchedEffect(focusTick) {
        if (focusTick > 0) runCatching { promptFocus.requestFocus() }
    }

    // `/` and `@` � their two composer menus. The mode is read straight off the text:
    // a leading slash is a command, and the last `@word` is a file mention. Same trigger
    // characters, same filtering rules, the server's own lists.
    val cmds by vm.commands.collectAsState()
    val fRows by vm.fileRows.collectAsState()
    val fDir by vm.fileDir.collectAsState()
    // last `@` that has no space after it
    val atStart = remember(text) {
        val i = text.lastIndexOf('@')
        if (i >= 0 && !text.substring(i + 1).contains(' ')) i else -1
    }
    val sheetMode = when {
        text.startsWith("/") && !text.contains(' ') -> SheetMode.COMMAND
        atStart >= 0 -> SheetMode.FILE
        else -> null
    }
    val sheetQuery = when (sheetMode) {
        SheetMode.COMMAND -> text.removePrefix("/")
        SheetMode.FILE -> text.substring(atStart + 1)
        else -> ""
    }
    LaunchedEffect(sheetMode) {
        if (sheetMode == SheetMode.COMMAND) vm.loadCommands()
        if (sheetMode == SheetMode.FILE) vm.loadFiles(fDir.ifBlank { null })
    }

    // `e.type === "history.open"` - the prompt-history popover (`ui.promptInput.history`).
    // Scoped like the draft, so each tab lists the prompts it was sent.
    var histOpen by remember(draftScope) { mutableStateOf(false) }

    if (showPicker) ModelPickerSheet(
        vm = vm,
        onDismiss = { showPicker = false },
        onManage = {
            // `dialog.model.manage` - "Customize which models appear in the model selector."
            // Their phone drawer routes it to the Manage models dialog; ours is the Models page.
            onManageModels()
        },
    )

    if (histOpen) PromptHistoryDialog(
        entries = vm.promptHistory(draftScope),
        onPick = { text = it },
        onDismiss = { histOpen = false },
    )

    // attachments live here until send: Ctrl+U on the desktop, the system picker here
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<OutAttachment>>(emptyList()) }
    var loadingFiles by remember { mutableStateOf(false) }
    // A refusal used to be silent: `readAttachment` returns null for anything over 24 MB or
    // unreadable, so the chip never appeared and the send button stayed grey with no reason
    // given. He read that as "the + button is broken".
    var attachError by remember { mutableStateOf("") }
    // A file over the inline ceiling is streamed to the server's own disk instead of being
    // refused, so this carries its progress. the owner: "THE 1GB FILES IMAGES ETC NOT FIX".
    var uploadNote by remember { mutableStateOf("") }
    // The `+` menu, and shell mode behind its fourth line (`prompt.mode.shell`).
    var plusMenu by remember { mutableStateOf(false) }
    var shellMode by remember(draftScope) { mutableStateOf(false) }
    // System back closes what is open instead of leaving the app. Nothing in this screen had a
    // `BackHandler`, so a `/` or `@` palette he changed his mind about could only be dismissed
    // by editing the text by hand - the owner: "WALANG PRESS BACK TAPOS WALANG X BUTTON SA FILES".
    androidx.activity.compose.BackHandler(enabled = plusMenu) { plusMenu = false }
    androidx.activity.compose.BackHandler(enabled = sheetMode != null) {
        text = if (atStart >= 0) text.substring(0, atStart).trimEnd() else ""
    }
    /**
     * One path for every picker: the system document picker, the typed mime pickers, and the
     * `Open with…` chooser all hand their uris here, so the inline/stream decision and the
     * error text are written once.
     */
    fun ingest(uris: List<android.net.Uri>?) {
        android.util.Log.e("ZAPP", "plus: picker returned ${uris?.size ?: 0} uri(s)")
        if (uris.isNullOrEmpty()) return
        attachError = ""
        loadingFiles = true
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                uris.map { u -> readAttachment(ctx, u) }
            }
            val added = results.filterIsInstance<AttachResult.Ok>().map { it.a }
            files = files + added
            loadingFiles = false
            // The first real reason, not a guess about size: "over 24 MB or unreadable" told him
            // nothing about which file failed or why (measured 11:32:55, one uri, no message).
            var firstBad = results.filterIsInstance<AttachResult.Bad>()
                .firstOrNull()?.why.orEmpty()
            // Too big to inline is not too big to send. `POST /api/experimental/fs/write` takes a
            // raw body (`binaryBody: true` in their own route table), so the bytes are streamed
            // straight from the picker to the server's disk and the prompt carries only the
            // path. No base64, no heap copy, no ceiling - a 1 GB pick costs a 64 KiB buffer.
            for (b in results.filterIsInstance<AttachResult.Big>()) {
                if (b.size > MAX_PICK_BYTES) {
                    // A provider that reports no size hands us `read` instead of the real length;
                    // this is the second gate, on whatever length we do know.
                    firstBad = "${b.name} is over the 1 GB per-file limit."
                    continue
                }
                val c = vm.client
                if (c == null) {
                    firstBad = "Not connected - ${b.name} could not be sent."
                    break
                }
                val safe = b.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
                val stamp = System.currentTimeMillis()
                // Their route takes `location` + `path`; our `fsList` omits `location` and the
                // server defaults it, so the first try is relative to its own working directory.
                // A termux build with storage access can also take an absolute `/sdcard` path,
                // so that is the second try rather than a hard failure.
                val dests = listOf(
                    "ZAPP-inbox/$stamp-$safe",
                    "/sdcard/Download/ZAPP-inbox/$stamp-$safe",
                )
                loadingFiles = true
                var used = ""
                var lastErr: Throwable? = null
                for (dest in dests) {
                    uploadNote = "sending ${b.name}\u2026"
                    val res = runCatching {
                        val stream = ctx.contentResolver.openInputStream(b.uri)
                            ?: throw java.io.IOException("the picker handed over no bytes")
                        stream.use { input ->
                            c.writeServerFile(dest, input, b.size) { sent, total ->
                                if (total > 0) uploadNote = "${b.name}  ${sent * 100 / total}%"
                            }
                        }
                    }
                    if (res.isSuccess) {
                        used = dest
                        break
                    }
                    lastErr = res.exceptionOrNull()
                    android.util.Log.e("ZAPP", "attach upload to $dest failed: ${lastErr?.message}")
                }
                loadingFiles = false
                uploadNote = ""
                if (used.isNotEmpty()) {
                    android.util.Log.e("ZAPP", "attach uploaded: $used (${b.size} B)")
                    text = (if (text.isBlank()) "" else text.trimEnd() + "\n") + "@$used "
                } else if (firstBad.isEmpty()) {
                    firstBad = "${b.name} could not be sent: ${lastErr?.message?.take(140)}"
                }
            }
            attachError = firstBad
        }
    }

    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> ingest(uris) }

    /**
     * `ACTION_GET_CONTENT`, not `OPEN_DOCUMENT`.
     *
     * MT Manager and ZArchiver do not register as document providers, so the system picker can
     * never list them - the owner, 22 Nov: "WALANG ... MT MANAGER ZARCARVER ETC". They do answer
     * `GET_CONTENT`, and a chooser is the only surface that names them.
     */
    val anyFilePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        val data = res.data
        val uris = ArrayList<android.net.Uri>()
        val clip = data?.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let { uris.add(it) }
        } else {
            data?.data?.let { uris.add(it) }
        }
        android.util.Log.e("ZAPP", "plus: chooser returned ${uris.size} uri(s)")
        ingest(uris)
    }

    // while a turn runs the desktop turns the button into a stop square and Enter queues;
    // here the same slot queues when there is something to send, stops when there is not
    val hasPayload = (text.isNotBlank() || files.isNotEmpty()) && !loadingFiles
    val canSend = hasPayload

    Column(Modifier.fillMaxWidth().background(Surface).imePadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        if (queued.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, top = 5.dp)) {
                queued.forEach { q ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            // a queued prompt is still his text: hold to pull it back into
                            // the composer � the queue entry is cancelled as it is edited
                            .pointerInput(q.id) {
                                detectTapGestures(
                                    onLongPress = {
                                        onEditQueued(q.id, q.payload?.text.orEmpty())
                                    },
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Queued", fontSize = 10.sp, color = TextDim)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            q.payload?.text.orEmpty().lineSequence().joinToString(" ").take(90),
                            fontSize = 11.5.sp,
                            color = TextMid,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // Their `alternate`: the action offered is always the OTHER delivery.
                        // `ui.message.moveToQueue` when it would steer, `ui.promptInput.steer`
                        // when it is sitting in the queue.
                        Text(
                            if (q.delivery == "queue") "Steer" else "Move to queue",
                            fontSize = 10.sp,
                            color = TextDim,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    if (q.delivery == "queue") onMoveToSteer(q.id)
                                    else onMoveToQueue(q.id)
                                }
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                        Box(
                            Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))
                                .clickable { onCancelQueued(q.id) },
                            contentAlignment = Alignment.Center,
                        ) { Text("\u2715", fontSize = 9.sp, color = TextDim) }
                    }
                }
            }
        }
        if (sheetMode != null) {
            CommandSheet(
                mode = sheetMode,
                commands = cmds,
                files = fRows,
                dir = fDir,
                onUp = {
                    val t = fDir.trimEnd('/')
                    val cut = t.lastIndexOf('/')
                    vm.loadFiles(if (cut <= 0) null else t.substring(0, cut))
                },
                query = sheetQuery,
                sessions = vm.sessions.value,
                onSession = { s ->
                    // `serializeSessionMention` (`util/src/session-mention.ts:43`) — the
                    // editor shows the title, the wire gets the stable token. The literal is
                    // remembered so `SessionMention.serialize` can swap it at send time.
                    val label = "@" + com.zapp.net.SessionTitle.display(s.title, !s.parentId.isNullOrBlank())
                    val head = if (atStart >= 0) text.substring(0, atStart) else text
                    text = head + label + " "
                    refs = refs + (label to s.id)
                },
                onCommand = { row ->
                    val arg = text.removePrefix("/" + row.name).trim()
                    text = ""
                    vm.runCommand(row.name, arg)
                },
                onFile = { entry ->
                    if (entry.isDir) {
                        // descend instead of inserting: their @ browser walks directories too
                        vm.loadFiles(entry.path)
                    } else {
                        val head = if (atStart >= 0) text.substring(0, atStart) else text
                        val mention = "@" + entry.path
                        text = head + mention + " "
                        // their client attaches the bytes and records the mention span, so the
                        // model gets the file itself, not just its name
                        val c = vm.client
                        if (c != null) {
                            val start = head.length
                            scope.launch {
                                val body = runCatching { c.readFile(entry.path) }.getOrNull() ?: return@launch
                                val b64 = android.util.Base64.encodeToString(
                                    body.toByteArray(),
                                    android.util.Base64.NO_WRAP,
                                )
                                files = files + com.zapp.net.api.OutAttachment(
                                    name = entry.path.substringAfterLast('/').ifBlank { entry.path },
                                    mime = "text/plain",
                                    base64 = b64,
                                    mentionStart = start,
                                    mentionEnd = start + mention.length,
                                    mentionText = mention,
                                )
                            }
                        }
                    }
                },
                autoAccept = autoAccept,
                onDismiss = {
                    // Take the trigger back out of the draft, which is what closes the sheet:
                    // the mode is derived from the text, so there is no separate flag to clear.
                    text = if (atStart >= 0) text.substring(0, atStart).trimEnd() else ""
                },
                onAction = { a ->
                    when (a) {
                        SheetAction.COMPACT -> { text = ""; vm.compactNow() }
                        SheetAction.FORK -> { text = ""; vm.forkNow() }
                        SheetAction.REVIEW -> { text = ""; onReview() }
                        SheetAction.AGENT -> { text = ""; footerMenu = "agent" }
                        SheetAction.MODEL -> { text = ""; showPicker = true }
                        // `command.permissions.autoaccept.*` - the palette toggles the same flag
                        // the Settings switch does, and says so with their toast strings.
                        SheetAction.AUTOACCEPT -> {
                            text = ""
                            vm.setAutoAccept(!autoAccept)
                        }
                        SheetAction.BACKGROUND -> { text = ""; vm.moveToBackground() }
                        SheetAction.STOP -> { text = ""; vm.stop() }
                    }
                },
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 2.dp)
                .background(Surface2, RoundedCornerShape(12.dp))
                .border(1.dp, Line, RoundedCornerShape(12.dp))
                .padding(start = 12.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 5.dp)) {
                if (files.isNotEmpty() || skills.isNotEmpty() || loadingFiles) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // A skill is a prompt attachment on their side (`composer/schema.ts`,
                        // `SkillPart`, sent as `skills:[{id}]`), not a separate activation call.
                        skills.forEach { s ->
                            SkillChip(s.name.ifBlank { s.id }) { onRemoveSkill(s.id) }
                        }
                        files.forEach { f ->
                            AttachChip(f) { files = files - f }
                        }
                        if (loadingFiles) {
                            Text(
                                uploadNote.ifBlank { "reading\u2026" },
                                fontSize = 10.sp,
                                color = TextDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (attachError.isNotBlank()) {
                    Text(
                        attachError,
                        fontSize = 10.sp,
                        color = Danger,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    // `ui.promptInput.label` = "Prompt" - their accessible name for the box.
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(promptFocus)
                        .onFocusChanged { onFocusChanged(it.isFocused) }
                        .semantics { contentDescription = "Prompt" },
                    textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp, lineHeight = 18.sp),
                    cursorBrush = SolidColor(Accent),
                    maxLines = 6,
                    decorationBox = { inner ->
                        if (text.isEmpty()) {
                            Text(
                                // `ui.promptInput.placeholder.shell` while shell mode is on;
                                // otherwise `.followUp` / `.normal`, verbatim.
                                when {
                                    shellMode -> "Enter shell command…"
                                    sending -> "Add follow-up, / for commands, @ for context…"
                                    else -> "Ask anything, / for commands, @ for context…"
                                },
                                fontSize = 13.sp,
                                color = TextDim,
                            )
                        }
                        inner()
                    },
                )
            }
            // `prompt.mode.shell` chip, and `ui.promptInput.exitShell` = "Exit". Their shell
            // mode is a mode, not a `!` text prefix, so it stays visible and reversible.
            if (shellMode) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(Layer2)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Shell", fontSize = 10.5.sp, color = TextPrimary)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "Exit",
                        fontSize = 10.5.sp,
                        color = TextDim,
                        modifier = Modifier.clickable { shellMode = false },
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
            // Their deliver-mode toggle, only while a turn runs: it always shows the OTHER
            // mode (`ui.promptInput.steer` / `ui.promptInput.queue`), and the hint for
            // queueing is `ui.promptInput.steerHint` � "Send without interrupting".
            // `ui.promptInput.uploading` = "{{percent}}%". The body of a file prompt is written
            // to the server before it answers, so this is the only honest sign that a photo is
            // still going out. It takes the deliver-mode slot because queueing a new turn while
            // the previous body is still on the wire is not a thing.
            val up by vm.upload.collectAsState()
            val upNow = up
            if (upNow != null) {
                Text(
                    "${upNow.percent}%",
                    fontSize = 10.5.sp,
                    color = TextMid,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
                )
                Spacer(Modifier.width(2.dp))
            } else if (sending) {
                Text(
                    if (queueMode) "Steer" else "Queue",
                    fontSize = 10.5.sp,
                    color = if (queueMode) Accent else TextDim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable { queueMode = !queueMode }
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            // `ui.promptInput.history` = "Prompt history". PC opens it with a key (`history.open`
            // after `canStep`: popover closed, editor focused, history non-empty, and the prompt
            // EMPTY). A phone has no such key, so the same gate becomes a control that only
            // appears while the box is empty - the one forced deviation, same as the code-block
            // copy button.
            if (text.isBlank() && !sending) {
                Icon(
                    OcIcons.Clock,
                    contentDescription = "Prompt history",
                    tint = TextDim,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .clickable { histOpen = true },
                )
                Spacer(Modifier.width(6.dp))
            }
            Box(
                Modifier
                    .size(30.dp)
                    .background(if (canSend) Accent else Line, CircleShape)
                    .clickable(enabled = canSend || sending) {
                        when {
                            // `prompt.mode.shell`: the box is a command line, so it goes to
                            // `POST /api/session/{id}/shell` and never to the model.
                            canSend && shellMode -> {
                                if (onShell(text)) text = ""
                            }
                            canSend -> {
                                // The visible text is what he reads; the wire form is
                                // `@session:<id>` (`composer/prompt-parts.ts:7`).
                                // Only clear when the engine took it. A refusal keeps the
                                // text, the files and the queue mode exactly where they were.
                                val ok = onSend(
                                    SessionMention.serialize(text.trim(), refs), files, queueMode,
                                )
                                if (ok) {
                                    // `capture()` - the sent prompt becomes the newest history
                                    // entry, with its filenames as the row's description.
                                    vm.rememberPrompt(
                                        draftScope,
                                        text,
                                        files.joinToString(", ") { it.name },
                                    )
                                    text = ""
                                    refs = emptyList()
                                    files = emptyList()
                                    queueMode = false
                                }
                            }
                            else -> vm.stop()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    OcIcons.ArrowUp,
                    // `ui.promptInput.send` = "Send", `ui.promptInput.stop` = "Stop". The button
                    // changes job when there is nothing to send and a turn is running, so its
                    // name has to change with it.
                    if (canSend) "Send" else "Stop",
                    tint = if (canSend) Bg else TextDim,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        // agent ? � model ? � variant ? � each one opens its own menu, like theirs
        val agents by vm.agentEntries.collectAsState()
        val agentId by vm.agentId.collectAsState()
        val agentLabel = agents.firstOrNull { it.id == agentId }?.name
            ?: agentId.replaceFirstChar { it.uppercase() }
        val modelLabel = (selected?.label ?: "Model").trim()
            .replace(Regex("\\s*\\([^)]*\\)\\s*$"), "")
            .ifBlank { "Model" }
        val variantLabel = (selected?.variant?.takeIf { it.isNotBlank() } ?: "default")
            .replaceFirstChar { it.uppercase() }

        // `wr`'s mount: `target.changed` on enter, `disposed` on leave, and a fresh machine
        // each time (their harness rebuilds rather than poking a disposed one).
        val drafting by vm.drafting.collectAsState()
        val branchState by vm.branchState.collectAsState()
        val draftBranch by vm.draftBranch.collectAsState()
        var branchCheckout by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(branchState) {
            if (BranchMachine.isOpen(branchState)) branchCheckout = vm.branchCheckout()
        }
        if (drafting) {
            LaunchedEffect(Unit) {
                vm.branchReset()
                // `""` is the server's default directory - their `project.root()`.
                vm.branchDispatch(BranchMachine.Event.TargetChanged(""))
            }
            DisposableEffect(Unit) {
                onDispose { vm.branchDispatch(BranchMachine.Event.Disposed) }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The `+` is FIRST, always.
            //
            // It used to sit after the branch picker, and the picker only exists on the
            // new-session surface. So on a session the `+` was at the far left, and on a draft
            // it was pushed ~140dp to the right. the owner taps a fixed place with his thumb:
            //
            //   "I CANT SEND THE IMAGES FILES IS THERES NO WAY INSIDE THE APP?"
            //
            // Measured 22 Nov 12:13 on this phone: `Add images and files` had bounds
            // [48,2412][96,2460] on a session and [468,2412][516,2460] on a draft - a 420 px
            // move. A tap where it used to be hit nothing at all, which reads as "there is no
            // way to attach". Their desktop keeps the add button at the left edge of the
            // prompt row in both states, so that is what this does.
            Box {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        // 40dp: the old 32dp sat hard against the system gesture bar at the
                        // bottom-left corner, so a thumb could miss it entirely.
                        .clickable {
                            android.util.Log.e("ZAPP", "plus: menu opened")
                            plusMenu = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // `ui.promptInput.add` = "Add images and files". The glyph is the tray the owner
                    // pointed at in Android's own picker, not a bare `+` - "CAN YOU REPLACE THOSE
                    // ICON TO THE +".
                    Icon(
                        OcIcons.Files, "Add images and files",
                        tint = TextDim, modifier = Modifier.size(18.dp),
                    )
                }
                // Their `+` IS a four-line menu. Last pass I called that an invention after
                // dumping only the `prompt.*` i18n block - the labels live under
                // `ui.promptInput.*`, and the owner's screenshot of the PC menu settled it:
                //
                //   ui.promptInput.attachments = "Images and files"   Ctrl+U
                //   ui.promptInput.commands    = "Commands"           /
                //   ui.promptInput.context     = "Context"            @
                //   ui.promptInput.shell       = "Shell command"      !
                //
                // Same order, same labels, same shortcut column as the desktop.
                //
                // `/` and `@` are what a keyboard types, so on a phone the line types the same
                // character and the box's own trigger rules (leading `/`, last `@`) open the
                // same palette they open on the PC. `!` is not a text prefix on the desktop -
                // `command.prompt.mode.toggle` = "Toggle shell mode" - so that line toggles the
                // real mode instead, and the mode sends through `POST /api/session/{id}/shell`.
                DropdownMenu(
                    expanded = plusMenu,
                    onDismissRequest = { plusMenu = false },
                    modifier = Modifier.background(Layer1),
                    containerColor = Layer1,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    // Five real sources, each with the glyph and the label the owner asked for -
                    // "I LOVE THE ICON OF THOSE CAN YOU REPLACE THOSE ICON TO THE + AND ADD A
                    // TEXT". Before this there was one line, `Images and files`, and it opened
                    // the system document picker in Downloads: no gallery, no audio, no video,
                    // and nothing that MT Manager or ZArchiver could answer.
                    PlusLine("Files", icon = OcIcons.Files) {
                        plusMenu = false
                        runCatching { picker.launch(arrayOf("*/*")) }.onFailure {
                            android.util.Log.e("ZAPP", "plus: launch FAILED ${it.message}")
                            attachError = "The file picker would not open: ${it.message}"
                        }
                    }
                    PlusLine("Images", icon = OcIcons.Images) {
                        plusMenu = false
                        runCatching { picker.launch(arrayOf("image/*")) }.onFailure {
                            attachError = "The image picker would not open: ${it.message}"
                        }
                    }
                    PlusLine("Audio", icon = OcIcons.Audio) {
                        plusMenu = false
                        runCatching { picker.launch(arrayOf("audio/*")) }.onFailure {
                            attachError = "The audio picker would not open: ${it.message}"
                        }
                    }
                    PlusLine("Videos", icon = OcIcons.Videos) {
                        plusMenu = false
                        runCatching { picker.launch(arrayOf("video/*")) }.onFailure {
                            attachError = "The video picker would not open: ${it.message}"
                        }
                    }
                    PlusLine("Documents", icon = OcIcons.FileDocument) {
                        plusMenu = false
                        runCatching {
                            picker.launch(
                                arrayOf(
                                    "application/pdf",
                                    "text/*",
                                    "application/json",
                                    "application/zip",
                                    "application/msword",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "application/vnd.ms-excel",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                ),
                            )
                        }.onFailure {
                            attachError = "The document picker would not open: ${it.message}"
                        }
                    }
                    // MT Manager and ZArchiver are not document providers, so the system
                    // picker above can never list them. `GET_CONTENT` is the intent they do
                    // answer, and a chooser is what puts them on screen by name.
                    PlusLine("Open with\u2026", icon = OcIcons.Folder) {
                        plusMenu = false
                        val send = Intent(Intent.ACTION_GET_CONTENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*")
                            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        runCatching {
                            anyFilePicker.launch(Intent.createChooser(send, "Open with"))
                        }.onFailure {
                            android.util.Log.e("ZAPP", "plus: chooser FAILED ${it.message}")
                            attachError = "No app offered to pick a file."
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                    PlusLine("Commands", "/") {
                        plusMenu = false
                        shellMode = false
                        text = text + "/"
                    }
                    PlusLine("Context", "@") {
                        plusMenu = false
                        shellMode = false
                        text = text + "@"
                    }
                    PlusLine("Shell command", "!") {
                        plusMenu = false
                        shellMode = !shellMode
                    }
                }
            }
            // `wr` - the base branch picker. It exists on the **new-session** surface only:
            // their `e.onWorktree` gate sits on the prompt row of the new-session page, and
            // once a session exists the worktree is already fixed. It sits AFTER the add
            // button so the add button never moves.
            if (drafting) {
                BranchPicker(
                    state = branchState,
                    branch = draftBranch,
                    dispatch = { vm.branchDispatch(it) },
                    checkout = branchCheckout,
                    search = { q -> vm.branchSearch(q) },
                )
            }
            Box {
                FooterChip(agentLabel, hint = "Choose agent") { footerMenu = "agent" }
                DropdownMenu(
                    expanded = footerMenu == "agent",
                    onDismissRequest = { footerMenu = "" },
                    modifier = Modifier.background(Layer1),
                    containerColor = Layer1,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    agents.forEach { a ->
                        AgentLine(
                            name = a.name.ifBlank { a.id },
                            checked = a.id == agentId,
                        ) {
                            vm.agentId.value = a.id
                            footerMenu = ""
                        }
                    }
                }
            }
            FooterChip(modelLabel, hint = "Choose model") { showPicker = true }
            Box {
                FooterChip(variantLabel, hint = "Choose model variant") { footerMenu = "variant" }
                val allModels by vm.models.collectAsState()
                // the rail is the selected model's own `variants` from the catalog, not a
                // fixed list � same source desktop uses to build its variant map
                val rows = remember(allModels, selected?.providerID, selected?.modelID) {
                    val m = allModels.firstOrNull {
                        it.providerID == selected?.providerID && it.modelID == selected?.modelID
                    }
                    if (m == null) emptyList() else variantLabels(m)
                }
                DropdownMenu(
                    expanded = footerMenu == "variant",
                    onDismissRequest = { footerMenu = "" },
                    modifier = Modifier.background(Layer1),
                    containerColor = Layer1,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    if (rows.isEmpty()) {
                        VariantLine(label = "Default", checked = true, favorite = false) {
                            footerMenu = ""
                        }
                    }
                    rows.forEach { (id, label) ->
                        VariantLine(
                            label = label,
                            checked = (selected?.variant.orEmpty()) == id,
                            favorite = label == "Max",
                        ) {
                            footerMenu = ""
                            selected?.let { sel ->
                                vm.chooseModel(sel.providerID, sel.modelID, id, sel.label)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // no keycap and no second "Queue": the deliver-mode toggle already lives inside
            // the composer row, so repeating it here would give two controls for one setting
        }
    }
}

/** One line of the `+` menu: their label, their shortcut hint, right-aligned like the PC. */
@Composable
private fun PlusLine(
    label: String,
    shortcut: String = "",
    /** One of the five source glyphs (`Files`, `Images`, `Audio`, `Videos`, `FileDocument`). */
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = TextMid, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(label, fontSize = 12.5.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        if (shortcut.isNotBlank()) {
            Spacer(Modifier.width(18.dp))
            Text(shortcut, fontSize = 11.sp, color = TextDim)
        }
    }
}

@Composable
private fun AgentLine(name: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            fontSize = 12.5.sp,
            color = if (checked) TextPrimary else TextMid,
            modifier = Modifier.weight(1f),
        )
        if (checked) Icon(OcIcons.Check, "Selected", tint = TextMid, modifier = Modifier.size(12.dp))
    }
}

@Composable
private fun VariantLine(label: String, checked: Boolean, favorite: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            color = if (checked) TextPrimary else TextMid,
            modifier = Modifier.weight(1f),
        )
        if (checked) Icon(OcIcons.Check, "Selected", tint = TextMid, modifier = Modifier.size(12.dp))
    }
}

@Composable
private fun FooterChip(
    label: String,
    /**
     * The accessible name. PC names each of these chips explicitly -
     * `ui.promptInput.chooseAgent` = "Choose agent", `.chooseModel` = "Choose model",
     * `.chooseVariant` = "Choose model variant" - because the visible label is only the
     * current value, which says nothing about what tapping it does.
     */
    hint: String,
    color: Color = TextMid,
    maxWidth: androidx.compose.ui.unit.Dp = 190.dp,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(5.dp))
            .semantics { contentDescription = hint }
            .clickable { onClick() }
            .padding(horizontal = 5.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 10.5.sp,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            OcIcons.ChevronDown, null,
            tint = color,
            modifier = Modifier.size(11.dp),
        )
    }
}

/** One pending attachment: image thumbnail when we can draw it, name + size otherwise. */
@Composable
private fun AttachChip(f: OutAttachment, onRemove: () -> Unit) {    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, f.base64) {
        if (f.isImage) {
            value = withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = android.util.Base64.decode(f.base64, android.util.Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    // Small chip, tap to see the whole thing: the thumbnail is 18 dp and the label is capped,
    // so a photo no longer eats a third of the composer before it is even sent.
    var open by remember { mutableStateOf(false) }
    val label = if (f.name.length > 16) f.name.take(14) + "\u2026" else f.name
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Surface2)
            .clickable(enabled = bmp != null) { open = true }
            .padding(start = 3.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bmp?.let {
            Image(
                it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(18.dp).clip(RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            label,
            fontSize = 9.5.sp,
            color = TextMid,
            maxLines = 1,
            modifier = Modifier.widthIn(max = 96.dp),
        )
        Spacer(Modifier.width(5.dp))
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color.White.copy(alpha = 0.07f))
                // `ui.promptInput.removeAttachment` = "Remove attachment" - their label for the
                // control that takes a file back out of the prompt. Ours was a bare glyph with
                // no accessible name, so the row read as one undifferentiated blob. It was also
                // 16 dp with an 8.5 sp mark in the dimmest ink, which is why he reported it as
                // missing - the owner: "NO X BUTTON IN THE FILES".
                .semantics { contentDescription = "Remove attachment" }
                .clickable { onRemove() },
            contentAlignment = Alignment.Center,
        ) { Text("\u2715", fontSize = 12.sp, color = TextMid) }
    }
    val shown = bmp
    if (open && shown != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { open = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { open = false },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    shown,
                    contentDescription = f.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(14.dp)
                        .size(36.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.14f))
                        .clickable { open = false },
                    contentAlignment = Alignment.Center,
                ) { Text("\u2715", fontSize = 15.sp, color = Color.White) }
            }
        }
    }
}

/** A skill attached to the prompt (`PromptInput.SkillAttachment`), our chip, their field. */
@Composable
private fun SkillChip(label: String, onRemove: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Surface2)
            .padding(start = 6.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 10.sp,
            color = Accent,
            maxLines = 1,
            modifier = Modifier.widthIn(max = 130.dp),
        )
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .semantics { contentDescription = "Remove attachment" }
                .clickable { onRemove() },
            contentAlignment = Alignment.Center,
        ) { Text("\u2715", fontSize = 8.5.sp, color = TextDim) }
    }
}

/** What a picked uri turned into: the attachment, or the reason it could not be used. */
private sealed interface AttachResult {
    data class Ok(val a: OutAttachment) : AttachResult
    /**
     * Too big to carry inline, but not a refusal. The prompt route base64s every attachment into
     * the request body, so its ceiling is the app's heap; `POST /api/experimental/fs/write` takes
     * a raw body instead, which is the only route on this server that can carry a real file.
     */
    data class Big(
        val name: String,
        val mime: String,
        val uri: android.net.Uri,
        val size: Long,
    ) : AttachResult
    data class Bad(val why: String) : AttachResult
}

/**
 * Read a content:// uri into an inline attachment. Big files are refused, not truncated.
 *
 * Every failure used to be swallowed by `runCatching{}.getOrNull()`, so a refused file and an
 * unreadable one looked identical - a chip that never appeared and no reason on screen, which
 * he read as "the + is broken". Measured 2026-11-21 11:32:55: the picker returned exactly one
 * uri, and nothing at all was said about it afterwards.
 */
private fun readAttachment(ctx: android.content.Context, uri: android.net.Uri): AttachResult {
    return try {
        val cr = ctx.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = 0L
        cr.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (ni >= 0) c.getString(ni)?.takeIf { it.isNotBlank() }?.let { name = it }
                if (si >= 0) size = c.getLong(si)
            }
        }
        if (size > MAX_PICK_BYTES) {
            val gb = String.format(java.util.Locale.US, "%.2f", size / (1024.0 * 1024.0 * 1024.0))
            android.util.Log.e("ZAPP", "attach refused: $name is $gb GB, over the 1 GB pick limit")
            return AttachResult.Bad("$name is $gb GB. The limit is 1 GB per file - there is no limit on how many files you add.")
        }
        if (size > attachLimit()) {
            val mb = size / (1024 * 1024)
            android.util.Log.e("ZAPP", "attach big: $name is $mb MB -> server-side write")
            return AttachResult.Big(name, cr.getType(uri) ?: "application/octet-stream", uri, size)
        }
        // Encode while reading. `readBytes()` + `encodeToString` needed 1.5x the file in RAM
        // twice over; a big pick would have died on the second copy before it ever left.
        // Pre-sized: the buffer used to double as it grew, which is a second full copy at the
        // exact moment the heap is tightest.
        val encoded = if (size > 0) {
            java.io.ByteArrayOutputStream((size / 3 * 4 + 8).toInt())
        } else {
            java.io.ByteArrayOutputStream()
        }
        var read = 0L
        val stream = cr.openInputStream(uri)
            ?: return AttachResult.Bad("$name opened to nothing - the picker handed over no bytes.")
        stream.use { input ->
            android.util.Base64OutputStream(encoded, android.util.Base64.NO_WRAP).use { b64 ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    read += n
                    if (read > attachLimit()) {
                        // The size column lied (a provider that reports 0). Hand it to the
                        // server-side write instead of throwing the work away.
                        android.util.Log.e("ZAPP", "attach big (unreported size): $name")
                        return AttachResult.Big(
                            name, cr.getType(uri) ?: "application/octet-stream", uri, read,
                        )
                    }
                    b64.write(buf, 0, n)
                }
            }
        }
        if (read == 0L) {
            android.util.Log.e("ZAPP", "attach failed: $name opened to nothing")
            return AttachResult.Bad("$name opened to nothing - the picker handed over no bytes.")
        }
        val mime = cr.getType(uri) ?: "application/octet-stream"
        android.util.Log.e("ZAPP", "attach ok: $name $read B $mime")
        AttachResult.Ok(
            OutAttachment(
                name = name,
                mime = mime,
                base64 = String(encoded.toByteArray(), Charsets.US_ASCII),
            ),
        )
    } catch (o: OutOfMemoryError) {
        android.util.Log.e("ZAPP", "attach OOM: $uri")
        AttachResult.Bad("That file is too big to hold in memory on this phone.")
    } catch (t: Throwable) {
        android.util.Log.e("ZAPP", "attach failed: $uri -> $t")
        AttachResult.Bad("Could not read that file: ${t.message ?: t.javaClass.simpleName}")
    }
}

/**
 * The largest attachment this phone can actually carry inline.
 *
 * There is no upload route on this server. `PromptInput.FileAttachment` from this phone's own
 * `/openapi.json` is `{uri, source?, name?, description?, mention?}` with
 * `additionalProperties:false`, and the only `source` their own client ever writes is
 * `{type:"inline"}` - the desktop base64s the whole file into a `data:` URI too. So the file has
 * to fit in this app's Java heap several times over: the base64 buffer, the `toByteArray()` copy,
 * and the `String` the JSON builder keeps.
 *
 * Measured on the phone: `dalvik.vm.heapgrowthlimit` 256 MB, `dalvik.vm.heapsize` 512 MB. A
 * 4-copy peak means roughly `maxMemory / 11` is the honest ceiling - about 23 MB here. 500 MB is
 * not a limit this transport can honour, and claiming it just turned a refusal into an OOM crash
 * (measured 07:29:43.536: `attach OOM: content://.../msf%3A113424`, 292 ms after the picker
 * returned).
 */
/**
 * The largest single file the picker will accept: 1 GB.
 *
 * the owner, 22 Nov: *"I WANT TO EACH USER AND THEY CAN SEND MULTIPLE 1GB FILE IMAGES ETC BUT THE
 * LIMIT OF PICKING THE FILE SIZE IS 1GB AND THEY CAN SEND IT UNLIMITED"*.
 *
 * This is a plain constant, not a setting and not a per-account rule - every install gets the
 * same number, which is the only kind of limit that is allowed to exist here.
 *
 * It is also not a memory limit. Anything above [attachLimit] never touches the heap: it goes to
 * `POST /api/experimental/fs/write` as a raw streamed body (`OpenCodeClient.writeServerFile`,
 * `writeTimeout(0)`), so a 1 GB pick costs a 64 KiB buffer. The number below is a policy ceiling,
 * and the transport can actually carry it.
 */
private const val MAX_PICK_BYTES = 1L * 1024 * 1024 * 1024

private fun attachLimit(): Long {
    val max = Runtime.getRuntime().maxMemory()
    return (max / 11).coerceIn(4L * 1024 * 1024, 48L * 1024 * 1024)
}
