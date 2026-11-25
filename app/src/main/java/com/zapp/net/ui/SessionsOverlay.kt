package com.zapp.net.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.MainViewModel
import com.zapp.net.api.Session
import com.zapp.net.ui.theme.Accent
import com.zapp.net.ui.theme.AccentSoft
import com.zapp.net.ui.theme.Bg
import com.zapp.net.ui.theme.Border
import com.zapp.net.ui.theme.DiffDel
import com.zapp.net.ui.theme.Layer1
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.Scrim
import com.zapp.net.ui.theme.Surface
import com.zapp.net.ui.theme.Surface2
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextMid
import com.zapp.net.ui.theme.TextPrimary

/* The two strip buttons that actually do the OpenCode thing:
   the 3x3 grid opens the full sessions board, the glass opens the quick finder. */

@Composable
private fun SessionBadge(title: String?, dir: String?, size: Int = 16) {
    val letter = title?.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    val project = dir.orEmpty().replace('\\', '/').trimEnd('/').substringAfterLast('/')
    val h = if (project.startsWith("hachkerss", true) || project.equals("hachkerss", true)) "H" else "D"
    Box(
        Modifier.size(size.dp).background(Surface2, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (letter == h) {
            Text(h, fontSize = (size * 0.60f).sp, color = TextMid)
        } else {
            Text(letter, fontSize = (size * 0.60f).sp, color = TextMid)
        }
    }
}

@Composable
private fun SessionLine(
    s: Session,
    selected: Boolean,
    showProject: Boolean,
    trailing: String? = null,
    /** `notification.tsx:901-905` — the server's `time.viewed` **or** the local manual flag. */
    unread: Boolean = s.unread,
    onRename: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
    /** `session/session-read-menu-item.tsx` — Mark as read / Mark as unread. */
    onRead: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Surface2 else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SessionBadge(s.title, s.dir)
        Spacer(Modifier.width(9.dp))
        Text(
            com.zapp.net.SessionTitle.display(s.title, !s.parentId.isNullOrBlank()),
            fontSize = 12.5.sp,
            color = if (selected) TextPrimary else TextMid,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showProject) {
            Spacer(Modifier.width(8.dp))
            Text(
                s.project,
                fontSize = 10.5.sp,
                color = TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 132.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, fontSize = 10.5.sp, color = TextDim)
        } else if (unread && !selected) {
            // unread dot, same place their sidebar puts it
            Box(Modifier.size(5.dp).background(Accent, RoundedCornerShape(50)))
        }
        if (onDelete != null || onRename != null || onExport != null || onRead != null) {
            // PORT of `home/sessions/view.tsx:1681-1749`: the row has NO delete glyph. Its
            // `outline-dots` trigger opens Rename / Export / (Read) / separator / Delete.
            // The per-row `X` I had invented put Delete one stray tap from a scroll - the
            // fastest way to lose a conversation by accident.
            Box {
                Icon(
                    OcIcons.Dots,
                    contentDescription = "Session actions",
                    tint = TextDim,
                    modifier = Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { menuOpen = true }
                        .padding(horizontal = 1.dp),
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = Surface,
                ) {
                    if (onRename != null) {
                        DropdownMenuItem(
                            text = { Text("Rename", fontSize = 12.5.sp, color = TextPrimary) },
                            onClick = { menuOpen = false; onRename() },
                        )
                    }
                    if (onExport != null) {
                        DropdownMenuItem(
                            text = { Text("Export\u2026", fontSize = 12.5.sp, color = TextPrimary) },
                            onClick = { menuOpen = false; onExport() },
                        )
                    }
                    // `session/session-read-menu-item.tsx:85-97`: shown when unread, or when the
                    // session has ever run (`time.idle !== undefined`) - an idle session still
                    // has a read state to flip.
                    if (onRead != null && (unread || s.time?.idle != null)) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (unread) "Mark as read" else "Mark as unread",
                                    fontSize = 12.5.sp, color = TextPrimary,
                                )
                            },
                            onClick = { menuOpen = false; onRead() },
                        )
                    }
                    if (onDelete != null) {
                        if (onRename != null || onExport != null || onRead != null) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                        }
                        DropdownMenuItem(
                            text = { Text("Delete\u2026", fontSize = 12.5.sp, color = DiffDel) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FinderField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(Surface2, RoundedCornerShape(9.dp))
            .border(1.dp, Line, RoundedCornerShape(9.dp))
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(OcIcons.Search, null, tint = TextDim, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search sessions", fontSize = 12.5.sp, color = TextDim)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 12.5.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The glass: a floating finder over the chat, newest first, relative times. */
@Composable
fun SearchPopover(vm: MainViewModel, visible: Boolean, onClose: () -> Unit) {
    val sessions by vm.sessions.collectAsState()
    val active by vm.activeSession.collectAsState()
    var q by remember { mutableStateOf("") }
    val shown = remember(sessions, q) {
        sessions.filter {
            q.isBlank() || it.title.orEmpty().contains(q, true) || it.project.contains(q, true)
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(100)),
    ) {
        Box(Modifier.fillMaxSize().background(Scrim).clickable { onClose() }) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(tween(160)) { -it / 10 } + fadeIn(tween(130)),
                exit = slideOutVertically(tween(110)) { -it / 12 } + fadeOut(tween(100)),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Column(
                    Modifier
                        .padding(top = 78.dp, start = 10.dp, end = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Layer1)
                        .border(1.dp, Border, RoundedCornerShape(12.dp))
                        .clickable { },
                ) {
                    FinderField(q, { q = it }, Modifier.padding(8.dp))
                    LazyColumn(Modifier.heightIn(max = 330.dp)) {
                        items(shown) { s ->
                            SessionLine(
                                s = s,
                                selected = s.id == active?.id,
                                showProject = true,
                                trailing = relativeTime(s.updatedAt),
                            ) {
                                vm.open(s)
                                onClose()
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/** The 3x3 grid: projects on the left rail, sessions grouped Today / Older. */
@Composable
fun SessionsOverlay(vm: MainViewModel, visible: Boolean, onClose: () -> Unit) {
    val sessions by vm.sessions.collectAsState()
    val active by vm.activeSession.collectAsState()
    val archived by vm.showArchived.collectAsState()
    val cursor by vm.sessionCursor.collectAsState()
    // `home/sessions/load-machine.ts` phase, ported in MainViewModel.
    val skeleton by vm.listSkeleton.collectAsState()
    val revealing by vm.listRevealing.collectAsState()
    val listError by vm.listError.collectAsState()
    val listLoaded by vm.listLoaded.collectAsState()
    var q by remember { mutableStateOf("") }
    var project by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Session?>(null) }
    var pendingRename by remember { mutableStateOf<Session?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Row `Export session` - same document the header exports, so a session picked from this list
    // is importable elsewhere. Markdown through the share sheet could never be imported back.
    val overlayScope = androidx.compose.runtime.rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }
    val exportPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val doc = pendingExport
        pendingExport = null
        if (uri == null || doc == null) return@rememberLauncherForActivityResult
        overlayScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { o ->
                    o.write(doc.second.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    // `succeeded()` (:565-591) names a reveal only when placeholder rows were up; the list
    // then fades in over them for 200 ms (`HOME_SESSIONS_REVEAL_MS`). A fast load never
    // shows this because the 250 ms placeholder delay has not passed yet.
    val listAlpha = remember { Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(revealing) {
        if (revealing) {
            listAlpha.snapTo(0f)
            listAlpha.animateTo(1f, tween(200))
        } else {
            listAlpha.snapTo(1f)
        }
    }

    // Their search runs on the server (`?search=`), not on the eight rows already in memory:
    // that is the only way a two-month-old session can be found by title. Debounced so a
    // fast typist sends one request, not one per key.
    androidx.compose.runtime.LaunchedEffect(q) {
        kotlinx.coroutines.delay(220)
        // Blank uses the stale window (`queries.ts:43`); typed text always queries.
        if (q.isBlank()) vm.refreshSessions(force = false) else vm.searchSessionsRemote(q)
    }

    val projects = remember(sessions) {
        sessions.map { it.project }.distinct()
    }
    // `session-preferences.ts:107` — `hidden` keeps a session out of the list entirely; the
    // server still has it, so nothing is deleted. `manualUnread` is the local half of unread
    // (`notification.tsx:901-905`). `prefTick` re-reads both after the menu flips one.
    var prefTick by remember { mutableStateOf(0) }
    val hiddenIds = remember(sessions, prefTick) {
        com.zapp.net.settings.SessionPrefs.hidden(context)
    }
    val manualUnread = remember(sessions, prefTick) {
        com.zapp.net.settings.SessionPrefs.manualUnread(context)
    }
    val filtered = remember(sessions, q, project, hiddenIds) {
        sessions.filter { s ->
            s.id !in hiddenIds &&
                (project == null || s.project == project) &&
                (q.isBlank() || searchKey(s).contains(q, true))
        }
    }
    // PORT of home/sessions/controller.tsx::groupSessions (1241): calendar days in the local
    // zone over `time.updated ?? time.created`, three groups - Today, Yesterday, Older. When
    // both newer groups are empty the Older title is "Recent sessions", exactly their string
    // (`sidebar.project.recentSessions`). A plain `updated >= startOfToday` cutoff was mine.
    val dToday = localDayNow()
    val dYesterday = localDayNow(-1)
    fun dayOf(s: Session): Int = localDay(s.updatedAt ?: 0L)
    // `controller.tsx:262`: Home shows at most 64 rows of the 500-row index
    // (`HOME_SESSION_LIMIT = 64`, `home/sessions/index.ts:19`).
    val visibleRows = filtered.take(64)
    val today = visibleRows.filter { dayOf(it) == dToday }
    val yesterday = visibleRows.filter { dayOf(it) == dYesterday }
    val older = visibleRows.filter { dayOf(it) != dToday && dayOf(it) != dYesterday }
    val olderTitle = if (today.isEmpty() && yesterday.isEmpty()) "Recent sessions" else "Older"

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(130)) + slideInVertically(tween(160)) { -it / 14 },
        exit = fadeOut(tween(110)) + slideOutVertically(tween(120)) { -it / 16 },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Bg)
                // A `background` draws pixels; it does not take a hit. This board is drawn over
                // the chat, and without a pointer handler of its own every tap or drag that
                // landed on one of its empty areas went straight through to the message list
                // behind it - the chat scrolled under the board, and a tap opened whatever row
                // was under his finger. the owner, 2026-11-24: *"I CANT SCROLL TRU IT THE BACKGROUND
                // YUNG MA TAP KO"*.
                //
                // This node registers the whole panel as a hit target and consumes nothing, so
                // every child below still gets its events in full; it only stops the events from
                // reaching the surface this board is floating over.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
                }
                .statusBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sessions", fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (archived) "Archived" else "Active",
                    fontSize = 10.sp,
                    color = if (archived) AccentSoft else TextDim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (archived) Surface2 else Color.Transparent)
                        .clickable { vm.toggleArchived() }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "✕",
                    fontSize = 13.sp,
                    color = TextDim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable { onClose() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            FinderField(q, { q = it }, Modifier.padding(horizontal = 12.dp))

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                // projects rail
                // `verticalScroll` so a long project list can be dragged instead of running off
                // the bottom of the screen with no way to reach the end. The rail is as tall as
                // the board, so it needs its own scroll rather than the list's.
                Column(
                    Modifier
                        .width(112.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("Projects", fontSize = 11.sp, color = TextDim, modifier = Modifier.padding(bottom = 6.dp))
                    ProjectRow("All projects", project == null) { project = null }
                    projects.forEach { p ->
                        ProjectRow(p, project == p) { project = if (project == p) null else p }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        Row(
                            Modifier.clip(RoundedCornerShape(5.dp)).clickable {
                                vm.newSession()
                                onClose()
                            }.padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(OcIcons.Plus, "New session", tint = TextMid, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("New session", fontSize = 10.5.sp, color = TextMid)
                        }
                    }
                    // PORT of `home/sessions/view.tsx:507-587`. Placeholder rows sit behind the
                    // list and mount only after the 250 ms delay; error and empty are separate
                    // from "not loaded yet", exactly as their Switch is ordered.
                    Box(Modifier.weight(1f)) {
                        if (skeleton || revealing) {
                            Column(Modifier.fillMaxWidth().alpha(1f - listAlpha.value)) {
                                Spacer(Modifier.height(2.dp))
                                // `view.tsx:111 HOME_SESSION_PLACEHOLDER_WIDTHS`, rows at `:2069-2115`.
                                listOf(56, 38, 66, 46, 30, 52).forEach { w ->
                                    Row(
                                        Modifier.fillMaxWidth().height(48.dp)
                                            .padding(start = 6.dp, end = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            Modifier.size(16.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Surface2),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Box(Modifier.weight(1f)) {
                                            Box(
                                                Modifier.fillMaxWidth(w / 100f).height(8.dp)
                                                    .clip(CircleShape).background(Surface2),
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Box(
                                            Modifier.width(64.dp).height(8.dp)
                                                .clip(CircleShape).background(Line),
                                        )
                                    }
                                }
                            }
                        }
                        when {
                            // `view.tsx:535-553`: the load failed. Never the empty state here.
                            listError != null -> Column(
                                Modifier.fillMaxWidth().padding(top = 52.dp, start = 24.dp, end = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    "Couldn't load sessions",
                                    fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    listError.orEmpty(),
                                    fontSize = 13.sp, color = TextMid, textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "Try again",
                                    fontSize = 12.sp, color = Accent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { vm.refreshSessions(force = true) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                            // `view.tsx:577-585`, i18n `home.sessions.empty*`: ready, nothing to list.
                            listLoaded && today.isEmpty() && yesterday.isEmpty() && older.isEmpty() && q.isBlank() -> Column(
                                Modifier.fillMaxWidth().padding(top = 52.dp, start = 24.dp, end = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    "Nothing here yet",
                                    fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text("Create a session to get started", fontSize = 13.sp, color = TextMid)
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Surface2)
                                        .clickable { vm.newSession(); onClose() }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        OcIcons.Edit, "New session",
                                        tint = TextPrimary, modifier = Modifier.size(13.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("New session", fontSize = 12.sp, color = TextPrimary)
                                }
                            }
                            else -> LazyColumn(Modifier.fillMaxSize().alpha(listAlpha.value)) {
                                fun group(title: String, rows: List<Session>) {
                                    if (rows.isEmpty()) return
                                    item(key = "h:$title") {
                                        Text(
                                            title,
                                            fontSize = 11.sp,
                                            color = TextDim,
                                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                        )
                                    }
                                    items(rows, key = { it.id }) { s ->
                                        val isUnread = s.unread || s.id in manualUnread
                                        SessionLine(
                                            s, s.id == active?.id, showProject = true,
                                            unread = isUnread,
                                            onRename = { pendingRename = s },
                                            onExport = {
                                                vm.exportSessionToDownloads(s.id) { name, json ->
                                                    pendingExport = name to json
                                                    exportPicker.launch(name)
                                                }
                                            },
                                            onRead = {
                                                if (isUnread) vm.markRead(s.id) else vm.markUnread(s.id)
                                                prefTick++
                                            },
                                            onDelete = { pendingDelete = s },
                                        ) {
                                            vm.open(s); onClose()
                                        }
                                    }
                                }
                                // their order: today, yesterday, older
                                group("Today", today)
                                group("Yesterday", yesterday)
                                group(olderTitle, older)
                                if (cursor != null) {
                                    item {
                                        Text(
                                            "Load more",
                                            fontSize = 10.5.sp,
                                            color = TextMid,
                                            modifier = Modifier
                                                .padding(top = 10.dp, bottom = 6.dp)
                                                .clip(RoundedCornerShape(5.dp))
                                                .clickable { vm.nextSessionPage() }
                                                .padding(horizontal = 4.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingRename?.let { s ->
        RenameDialog(
            initial = s.title.orEmpty(),
            onDismiss = { pendingRename = null },
            onConfirm = { t ->
                pendingRename = null
                vm.renameSession(s.id, t)
            },
        )
    }

    pendingDelete?.let { s ->
        ConfirmDialog(
            title = "Delete session?",
            body = "\"${s.title ?: "(untitled)"}\" will be removed from the server.",
            confirm = "Delete",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                vm.deleteSession(s.id)
            },
        )
    }
}

@Composable
private fun ProjectRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 30.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) Surface2 else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(13.dp).background(Surface, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "D",
                fontSize = 8.sp,
                color = TextDim,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = if (selected) TextPrimary else TextMid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Row `Export…` hands markdown to the same chooser the header's Export uses. */
private fun shareMarkdown(ctx: android.content.Context, text: String) {
    val i = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("text/markdown")
        .putExtra(android.content.Intent.EXTRA_TEXT, text)
    ctx.startActivity(
        android.content.Intent.createChooser(i, "Export session").addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
        ),
    )
}
/** `localDay` from home/sessions/controller.tsx:1233 - `y*10000 + month*100 + day`, local zone. */
private fun localDay(ms: Long): Int {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = if (ms in 1..9_999_999_999L) ms * 1000 else ms
    return cal.get(java.util.Calendar.YEAR) * 10_000 +
        cal.get(java.util.Calendar.MONTH) * 100 +
        cal.get(java.util.Calendar.DAY_OF_MONTH)
}

/** Today at offset 0, yesterday at -1 - their `new Date(y, m, d - 1)`. */
private fun localDayNow(offset: Int = 0): Int {
    val cal = java.util.Calendar.getInstance()
    cal.add(java.util.Calendar.DAY_OF_MONTH, offset)
    return cal.get(java.util.Calendar.YEAR) * 10_000 +
        cal.get(java.util.Calendar.MONTH) * 100 +
        cal.get(java.util.Calendar.DAY_OF_MONTH)
}

/** "24m ago", "3h ago", "2d ago" — their relative stamp. */
fun relativeTime(ms: Long?): String? {
    if (ms == null) return null
    var epoch = ms
    if (epoch in 1..9_999_999_999L) epoch *= 1000 // seconds -> millis
    val diff = System.currentTimeMillis() - epoch
    if (diff < 0) return "now"
    return when {
        diff < 60_000 -> "now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        diff < 2_592_000_000L -> "${diff / 86_400_000}d ago"
        else -> "${diff / 2_592_000_000L}mo ago"
    }
}

/**
 * `home/sessions/search.ts:159` — the searched text is
 * `` `${sessionLabel(record.session)} ${record.projectName}` ``: the **display label** plus the
 * project folder, never the raw stored title.
 *
 * Searching `s.title` was wrong twice: a session whose stored title is the timestamped
 * fallback (`New session - 2026-11-20T…Z`) is shown as `New session`, so typing that never
 * matched it; and a project name was never searched at all.
 */
private fun searchKey(s: com.zapp.net.api.Session): String =
    com.zapp.net.SessionTitle.display(s.title, !s.parentId.isNullOrBlank()) + " " + s.project
