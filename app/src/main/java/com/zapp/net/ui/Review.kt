package com.zapp.net.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.api.MessageWithParts
import com.zapp.net.api.PartFile
import com.zapp.net.settings.ReviewScrollStore
import com.zapp.net.ui.theme.Accent
import com.zapp.net.ui.theme.Bg
import com.zapp.net.ui.theme.AccentSoft
import com.zapp.net.ui.theme.DiffAdd
import com.zapp.net.ui.theme.DiffDel
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.Surface2
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextMid
import com.zapp.net.ui.theme.Surface
import com.zapp.net.ui.theme.TextPrimary

/*
 * Their session review (`gui-plugins/src/review`, strings from `ui/src/i18n/en.ts`).
 *
 * The panel is not a view over tool messages: it reads the location's VCS diff through
 * `/api/vcs/diff` for the mode the session offers (`review/model.ts:365-403`), and only the
 * `turn` mode reads `/api/session/:id/diff`. That is why an untouched working tree shows the
 * "No file changes yet" state even when the conversation changed files in an earlier turn.
 */

/** Latest diff per path, in the order the files were first touched. */
internal fun collectChangedFiles(list: List<MessageWithParts>): List<PartFile> {
    val order = LinkedHashMap<String, PartFile>()
    for (m in list) {
        for (p in m.parts) {
            for (f in p.state?.metadata?.files.orEmpty()) {
                val k = f.file ?: continue
                order[k] = f
            }
        }
    }
    return order.values.toList()
}

/** `ui.sessionReview.title.git|branch|lastTurn` — the header names each mode exactly. */
internal fun reviewTitle(mode: String): String = when (mode) {
    "branch" -> "Branch changes"
    "turn" -> "Last turn changes"
    else -> "Git changes"
}

/** The rail label for a mode. Desktop's titles are the long form; the chips stay short. */
private fun reviewModeLabel(mode: String): String = when (mode) {
    "branch" -> "Branch"
    "turn" -> "Last turn"
    else -> "Git"
}

/** `ui.sessionReview.change.added|removed|modified`. */
private fun reviewStatusLabel(status: String?): String = when (status) {
    "added" -> "Added"
    "deleted" -> "Removed"
    "modified" -> "Modified"
    else -> ""
}

/** `session-review-machine.ts:1` - a diff over this many changed lines is not rendered until asked. */
private const val MAX_DIFF_CHANGED_LINES = 500

/** `session-diff.ts` `diffCanRender`: binary entries and untracked folders list 0/0 and have no body. */
private fun canRender(f: PartFile): Boolean = f.additions != 0 || f.deletions != 0

/** `pierre/media.ts:44-52` - the extensions the preview reads as media instead of a diff. */
private fun mediaKind(path: String): String? {
    val ext = path.substringAfterLast('.', "").lowercase()
    if (ext == "svg") return "svg"
    if (ext in setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "bmp", "ico", "tif", "tiff", "heic")) return "image"
    if (ext in setOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "opus")) return "audio"
    return null
}

private fun isFolder(path: String): Boolean = path.endsWith("/")

/**
 * `review/parts.tsx:13-43` `ReviewTitle`. One offered mode is a plain 13/530 label with
 * `padding-inline: 8px 4px`; several become the inline `Select` - a 24px, radius-4, no-fill
 * trigger whose value text is 13/530 and whose chevron is a 16px `chevron-down`, flipped while
 * the menu is open (`select.css` `[data-appearance=inline]`).
 */
@Composable
private fun ReviewTitle(modes: List<String>, mode: String, onMode: (String) -> Unit) {
    if (modes.isEmpty()) return
    if (modes.size == 1) {
        Box(
            Modifier.height(24.dp).padding(start = 8.dp, end = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                reviewTitle(modes[0]),
                fontSize = 13.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight(530),
                letterSpacing = (-0.04).sp,
                color = TextPrimary,
                maxLines = 1,
            )
        }
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable { open = true }
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                reviewTitle(mode),
                fontSize = 13.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight(530),
                letterSpacing = (-0.04).sp,
                color = TextPrimary,
                maxLines = 1,
            )
            Icon(
                OcIcons.ChevronDown,
                null,
                tint = TextDim,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(if (open) 180f else 0f),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Surface2) {
            modes.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Text(
                            reviewTitle(m),
                            fontSize = 13.sp,
                            color = if (m == mode) TextPrimary else TextMid,
                        )
                    },
                    onClick = {
                        open = false
                        onMode(m)
                    },
                )
            }
        }
    }
}

/**
 * `session-review-empty-no-git-v2.tsx` + its CSS: a centred column, 16px gap, `24px 24px 160px`
 * padding, the `file-document` glyph at 20px in `icon-muted`, the title at 13/530, the
 * description at 13/440 muted capped to 360px, then a neutral 28px button.
 */
@Composable
private fun EmptyState(
    title: String,
    description: String,
    action: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 184.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(OcIcons.FileDocument, null, tint = TextMid, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight(530),
            letterSpacing = (-0.04).sp,
            color = TextPrimary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            description,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight(440),
            letterSpacing = (-0.04).sp,
            color = TextMid,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier.height(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Surface2)
                .then(if (actionEnabled) Modifier.clickable { onAction() } else Modifier)
                .padding(horizontal = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                action,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight(530),
                color = if (actionEnabled) TextPrimary else TextDim,
            )
        }
    }
}

@Composable
fun ReviewPanel(
    sessionKey: String,
    visible: Boolean,
    modes: List<String>,
    mode: String,
    onMode: (String) -> Unit,
    files: List<PartFile>,
    noVcs: Boolean,
    loading: Boolean,
    error: String,
    openFiles: Set<String>,
    activeFile: String?,
    onToggleFile: (String) -> Unit,
    onExpandAll: () -> Unit,
    onFocusFile: (String) -> Unit,
    onRetry: () -> Unit,
    onInitRepo: () -> Unit,
    onOpenFile: (String) -> Unit,
    onClose: () -> Unit,
    stagedRevert: Boolean = false,
    onClearRevert: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
) {
    // `session-review-machine.ts:19-20,129-140` `forced`: files whose large-diff gate he bypassed
    // with "Render anyway". The machine keeps it in memory while the review is mounted.
    var forced by remember(sessionKey) { mutableStateOf(setOf<String>()) }

    // ---- `review/scroll-machine.ts` ----
    // The list reloads: diffs arrive late, a row re-measures when a diff opens, and a file can be
    // focused before its row exists. The machine decides when a position may be stored and when
    // it must be restored, so neither fights the other.
    val appCtx = LocalContext.current
    val listState = rememberLazyListState()
    var scrollState by remember(sessionKey) { mutableStateOf(ReviewScroll.initial) }
    fun dispatchScroll(event: ReviewScroll.Event) {
        scrollState = ReviewScroll.transition(scrollState, event).state
    }

    val visitID = remember(sessionKey) { sessionKey.hashCode() }
    val scrollCtx = ReviewScroll.Context(
        visitID = visitID,
        // Only the narrow review scrolls its list. The phone has no wide panel.
        narrow = true,
        scroller = true,
        ready = !loading && files.isNotEmpty(),
        layoutReady = true,
        commentFocused = false,
    )
    LaunchedEffect(scrollCtx) { dispatchScroll(ReviewScroll.Event.ContextSynced(scrollCtx)) }

    // `frame.restore`: the stored position is re-applied on the frame after the list measures,
    // and the step is reported back so a stale attempt cannot move a list it no longer owns.
    val restoreAttempt = (scrollState as? ReviewScroll.State.Restoring)
        ?.takeIf { it.restoreQueued }?.attempt
    LaunchedEffect(restoreAttempt, files.size) {
        if (restoreAttempt == null) return@LaunchedEffect
        withFrameNanos { }
        if (files.isNotEmpty()) {
            listState.scrollToItem(
                ReviewScrollStore.get(appCtx, sessionKey)
                    .coerceIn(0, files.lastIndex),
            )
        }
        dispatchScroll(ReviewScroll.Event.RestoreStepped(restoreAttempt))
    }

    // `frame.schedule`: a focused file is scrolled one frame at a time, because the first frame
    // cannot know its row height and a fixed jump would land wrong once the diff renders.
    val scrolling = scrollState as? ReviewScroll.State.Scrolling
    LaunchedEffect(scrolling?.attempt, scrolling?.path, files.size) {
        val s = scrolling ?: return@LaunchedEffect
        withFrameNanos { }
        val index = files.indexOfFirst { (it.file ?: "").replace('\\', '/') == s.path }
        if (index >= 0) listState.scrollToItem(index)
        val settled = index >= 0 &&
            listState.firstVisibleItemIndex == index &&
            listState.firstVisibleItemScrollOffset == 0
        dispatchScroll(ReviewScroll.Event.FrameStepped(s.attempt, settled))
    }

    // `file.focused` - their `focusFile`. Only the stored selection focuses a file; the derived
    // fallback that picks the first row is a display default, not a jump.
    LaunchedEffect(activeFile, files.size) {
        val path = activeFile ?: return@LaunchedEffect
        if (files.any { (it.file ?: "").replace('\\', '/') == path }) {
            dispatchScroll(ReviewScroll.Event.FileFocused(path))
        }
    }

    // `interacted` - his finger takes the list. A drag settles it, which is what makes the
    // position his to store; the restore's own scroll never does.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect {
            if (it) dispatchScroll(ReviewScroll.Event.Interacted)
        }
    }
    // `canSaveReviewScroll` - only a settled list writes, so restoring never overwrites itself.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect {
            if (ReviewScroll.canSave(scrollState)) {
                ReviewScrollStore.put(appCtx, sessionKey, it)
            }
        }
    }
    DisposableEffect(sessionKey) {
        onDispose { dispatchScroll(ReviewScroll.Event.Disposed) }
    }

    // `review/model.ts:578-585` `activeFile`: the stored file while it is still listed, else the
    // first entry that is not an untracked folder.
    val active = activeFile?.takeIf { path -> files.any { it.file == path } }
        ?: files.firstOrNull { !isFolder(it.file ?: "") }?.file

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) + slideInVertically(tween(170)) { it / 12 },
        exit = fadeOut(tween(100)) + slideOutVertically(tween(120)) { it / 14 },
    ) {
        Column(Modifier.fillMaxSize().background(Bg)) {
            // The tab strip lives on the pane now (`SidePane`), because the pane holds Review,
            // Browser, Terminal and file tabs side by side. Their `#review-panel` draws one
            // strip for the whole region; this surface used to draw its own, which is why the
            // `+` only ever offered Terminal.
            // `session-review.tsx:386-429` `[data-slot=session-review-header]`: 40px,
            // `grid-template-columns: minmax(0,1fr) auto minmax(0,1fr)` - title, changes, actions.
            // `review/mobile.tsx:69` narrows it to `!px-2 !h-10 !pb-0` with a hairline underneath.
            Row(
                Modifier.fillMaxWidth().height(40.dp)
                    .background(Bg)
                    .padding(start = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    ReviewTitle(modes, mode, onMode)
                }
                if (files.isNotEmpty()) {
                    // `DiffChanges appearance="standard"`
                    DiffTotals(files.sumOf { it.additions }, files.sumOf { it.deletions })
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (stagedRevert) {
                            Text(
                                "Clear revert",
                                fontSize = 11.sp,
                                color = AccentSoft,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .clickable { onClearRevert() }
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }
                        if (files.isNotEmpty()) {
                            // `session-review.tsx:414-426`: a `Button size="small"` (24px, radius 4,
                            // 9px inline padding, 13/530/20) with `chevron-grabber-vertical`, its
                            // label "Collapse all" whenever anything is open, else "Expand all".
                            Spacer(Modifier.width(4.dp))
                            Row(
                                Modifier.height(24.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Surface2)
                                    .clickable { onExpandAll() }
                                    .padding(horizontal = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    OcIcons.ChevronGrabber,
                                    null,
                                    tint = TextMid,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (openFiles.isEmpty()) "Expand all" else "Collapse all",
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                    fontWeight = FontWeight(530),
                                    letterSpacing = (-0.04).sp,
                                    color = TextPrimary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
            // `review/mobile.tsx:70` `before:` rule - a hairline under the header
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))

            // `review/parts.tsx:47-80` `ReviewEmpty`, in its own order: loading, no-vcs,
            // offline/error, then the one-line empty notice.
            when {
                loading && files.isEmpty() -> LoadingChanges()
                noVcs -> EmptyState(
                    // `ui.sessionReviewV2.empty.noGit.*`
                    title = "No tracked changes",
                    description = "Track, review, and undo changes in this project",
                    action = if (loading) "Creating Git repository." else "Create Git repository",
                    actionEnabled = !loading,
                    onAction = onInitRepo,
                )
                error.isNotBlank() -> ReviewFailureBlock(error, onRetry)
                files.isEmpty() -> ReviewNoChanges(mode)
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    itemsIndexed(files, key = { _, f -> f.file ?: f.hashCode().toString() }) { index, f ->
                        val full = (f.file ?: "").replace('\\', '/')
                        ReviewFileRow(
                            f = f,
                            first = index == 0,
                            last = index == files.lastIndex,
                            expanded = openFiles.contains(full) && canRender(f),
                            selected = active == full,
                            forced = forced.contains(full),
                            onToggle = {
                                onToggleFile(full)
                                onFocusFile(full)
                            },
                            onForce = { forced = forced + full },
                            onOpenFile = onOpenFile,
                        )
                    }
                }
            }
        }
    }
}

/** `session-review.tsx:550-579` `changes()` at 13px/440; the counts come from `DiffChanges`. */
@Composable
private fun ChangeLabel(f: PartFile, withCounts: Boolean) {
    val path = (f.file ?: "").replace('\\', '/')
    val before = f.patch.orEmpty().lineSequence().filter { it.startsWith("-") && !it.startsWith("---") }.count()
    val after = f.patch.orEmpty().lineSequence().filter { it.startsWith("+") && !it.startsWith("+++") }.count()
    val added = f.status == "added" || (before == 0 && after > 0)
    val deleted = f.status == "deleted" || (after == 0 && before > 0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            added -> Text("Added", fontSize = 13.sp, fontWeight = FontWeight(440), color = DiffAdd)
            deleted -> Text("Removed", fontSize = 13.sp, fontWeight = FontWeight(440), color = DiffDel)
            mediaKind(path) != null ->
                Text("Modified", fontSize = 13.sp, fontWeight = FontWeight(440), color = AccentSoft)
        }
        if (withCounts || added) {
            if (added || deleted || mediaKind(path) == null) {
                if (f.additions + f.deletions > 0) Spacer(Modifier.width(12.dp))
            }
            DiffTotals(f.additions, f.deletions)
        }
    }
}

/** `ui/src/data-display/diff-changes` - `+N -N`, hidden when both sides are zero. */
@Composable
private fun DiffTotals(additions: Int, deletions: Int) {
    DiffChanges(additions, deletions)
}

/** `review/parts.tsx:60-62` `loadingChanges` at `px-2 py-2 text-text-weak`. */
@Composable
private fun LoadingChanges() {
    Box(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.TopStart) {
        Text("Loading changes.", fontSize = 12.sp, color = TextDim)
    }
}

/** `review/parts.tsx:73-77`: one `text-14-regular text-text-weak max-w-56` line, `pb-64 -mt-4`. */
@Composable
private fun ReviewNoChanges(mode: String) {
    Column(
        Modifier.fillMaxSize().padding(bottom = 256.dp).offset(y = (-16).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            when (mode) {
                "git" -> "No uncommitted changes yet"
                "branch" -> "No branch changes yet"
                else -> "No changes"
            },
            fontSize = 14.sp,
            color = TextDim,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(max = 224.dp),
        )
    }
}

/** `review/parts.tsx:117-139` `ReviewFailure`: title, why, and an outline Retry button. */
@Composable
private fun ReviewFailureBlock(detail: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(bottom = 256.dp).offset(y = (-16).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Couldn't load changes", fontSize = 13.sp, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            detail,
            fontSize = 12.sp,
            color = TextDim,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        Spacer(Modifier.height(12.dp))
        // `Button size="small" variant="outline"` - 24px, radius 4, inset 1px border.
        Box(
            Modifier.height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, Line, RoundedCornerShape(4.dp))
                .clickable { onRetry() }
                .padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Retry", fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight(530), color = TextPrimary)
        }
    }
}

/*
 * `session-ui/src/components/session-review.tsx:581-718` - one accordion item.
 *
 * `[data-slot=accordion-trigger]` is 32px tall, `padding: 8px 12px`, `border: 1px` on every
 * side, `border-radius: 0`; `review/mobile.tsx:68` then strips the inline borders and every
 * radius, strips the first trigger's top border, and strips the last collapsed trigger's
 * bottom one. The trigger is disabled when the entry has nothing to render - an untracked
 * folder or a binary file lists 0/0 - and only a renderable entry gets the chevron.
 *
 * `changeSummary` is on, so a collapsed renderable row shows only the chevron; the label,
 * `DiffChanges` and "Open file" move into the summary row above the diff. A row that cannot
 * render keeps its label in the trigger, because it has no summary row.
 */
@Composable
private fun ReviewFileRow(
    f: PartFile,
    first: Boolean,
    last: Boolean,
    expanded: Boolean,
    selected: Boolean,
    forced: Boolean,
    onToggle: () -> Unit,
    onForce: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val full = (f.file ?: "").replace('\\', '/')
    val dir = full.substringBeforeLast('/', "")
    val name = full.substringAfterLast('/')
    val renderable = canRender(f)
    val changedLines = f.additions + f.deletions
    val media = mediaKind(full) != null

    Column(Modifier.fillMaxWidth()) {
        // `[data-slot=session-review-accordion-item][data-selected]` -> `--surface-base-active`.
        Column(
            Modifier.fillMaxWidth()
                .background(if (selected) Color.Black.copy(alpha = 0.05f) else Bg)
                .drawBehind {
                    val h = 1.dp.toPx()
                    val stroke = Line
                    if (!first) drawRect(stroke, topLeft = androidx.compose.ui.geometry.Offset.Zero, size = androidx.compose.ui.geometry.Size(size.width, h))
                    if (!(last && !expanded)) drawRect(stroke, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width, h))
                }
                .then(if (renderable) Modifier.clickable { onToggle() } else Modifier)
                .padding(start = 12.dp, end = 12.dp)
                .height(32.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    FileGlyph(full)
                    Spacer(Modifier.width(12.dp))
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        if (dir.isNotBlank()) {
                            Text(
                                "$dir/",
                                fontSize = 13.sp,
                                lineHeight = 19.5.sp,
                                color = TextMid,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        Text(
                            name,
                            fontSize = 13.sp,
                            lineHeight = 19.5.sp,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // `Show when={!changeSummary || !diffCanRender()}` - only an entry with no
                    // summary row keeps its label in the trigger.
                    if (!renderable) ChangeLabel(f, withCounts = true)
                    if (renderable) {
                        Spacer(Modifier.width(16.dp))
                        Icon(
                            OcIcons.ChevronDown,
                            if (expanded) "Collapse" else "Expand",
                            tint = TextDim,
                            modifier = Modifier.size(16.dp).rotate(if (expanded) 0f else -90f),
                        )
                    }
                }
            }
        }
        if (expanded) {
            // `session-review-change-summary`: 32px min, `padding: 8px 12px`, 12px gap,
            // `.5px` bottom border, sticky under the 32px trigger.
            Row(
                Modifier.fillMaxWidth().heightIn(min = 32.dp)
                    .background(Bg)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChangeLabel(f, withCounts = true)
                Spacer(Modifier.weight(1f))
                // `Button size="small" variant="ghost" icon="open-file"` -> "Open file"
                Row(
                    Modifier.clip(RoundedCornerShape(4.dp))
                        .clickable { onOpenFile(full) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(OcIcons.OpenFile, null, tint = TextMid, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open file", fontSize = 13.sp, color = TextMid)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            when {
                // `session-review-machine.ts:163-165` `too-large`
                !media && !forced && changedLines > MAX_DIFF_CHANGED_LINES -> Column(
                    Modifier.fillMaxWidth().background(Bg).padding(12.dp),
                ) {
                    Text(
                        "Diff too large to render",
                        fontSize = 13.sp,
                        fontWeight = FontWeight(530),
                        color = TextPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Limit: $MAX_DIFF_CHANGED_LINES changed lines. Current: $changedLines changed lines.",
                        fontSize = 13.sp,
                        color = TextDim,
                    )
                    Spacer(Modifier.height(10.dp))
                    // `Button size="normal" variant="neutral"` - 28px, radius 6, 11px inline.
                    Box(
                        Modifier.height(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Surface2)
                            .clickable { onForce() }
                            .padding(horizontal = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Render anyway",
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight(530),
                            color = TextPrimary,
                        )
                    }
                }
                !f.patch.isNullOrBlank() -> DiffView(f.patch!!, full)
            }
        }
    }
}

/*
 * `runtime/plugin/side-region.tsx:263` `.session-review-v2-tabs-bar`.
 *
 * The last `:root body ...` block in `main-BDySOyg3.css` wins the cascade over the compact
 * pill block, so the shipped shape is:
 *
 *   bar      `.5px` bottom border, `--v2-background-bg-base`, `gap: 8px`, 100% wide
 *   list     `height: var(--tabs-bar-height)`, i.e. 48px, `gap: 8px`, no end padding
 *   tab      `height: 28px`, `padding-inline: 10px`, radius 6, no border, transparent;
 *            selected takes `--v2-background-bg-layer-02`
 *   label    `13px / 440 / line-height 16px / tracking -0.04px`, muted; selected base
 *            `flex items-center gap-1.5` around `<Icon name="review"/>` + the title
 *   close    inside the wrapper, `padding-inline-end: 4px` when present
 *   add      `IconButton{icon: plus, variant: ghost-muted, size: large}` pinned at the end
 *
 * The `+` lists the `session.panel` launchers in their declared order (review 10, browser 15,
 * terminal 20, files 30); only ones this build can actually open are offered.
 */
/*
 * `runtime/plugin/mobile.tsx:50-126` `MobileViewTabs`.
 *
 * The strip is `flex shrink-0 items-center` with a `before:` hairline along the bottom (that is
 * `before:bottom-0` for the default `"top"` titlebar). `Tabs variant="line"` overrides the list to
 * `!h-9 !gap-0 !px-0 before:!hidden` - 36px tall, no gaps, no inset line of its own - and every
 * trigger to `min-w-0 flex-1` with a full-width, centred button.
 *
 * The `line` variant's own rules (`tabs-CyGMveLx.css`) give the trigger `height:100%; padding:6px`
 * at `13px/440/-.04px`; the selected wrapper gets `color: --v2-text-text-base` and a 1px
 * `border-bottom` in `--v2-text-text-faint`, the rest stay `--v2-text-text-muted`.
 *
 * Labels are `session.tab.session` -> "Session" and the review plugin's own
 * `mobile.title` -> "Changes" (`gui-plugins/src/review/i18n/en.ts`). ZAPP has no other mobile
 * panel, so there is no "More..." trigger: `mobile.tsx:110` only renders it when
 * `props.views.menu().length` is non-zero.
 */
@Composable
internal fun MobileViewTabs(
    changes: Boolean,
    onSession: () -> Unit,
    onChanges: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MobileViewTab("Session", !changes, Modifier.weight(1f), onSession)
            MobileViewTab("Changes", changes, Modifier.weight(1f), onChanges)
        }
        // `before:h-px before:bg-v2-border-border-base`, `before:bottom-0` when the setting is "top"
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }
}

@Composable
private fun MobileViewTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .fillMaxHeight()
            .clickable { onClick() }
            .then(
                if (selected) {
                    Modifier.drawBehind {
                        val h = 1.dp.toPx()
                        drawRect(
                            color = TextDim,
                            topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h),
                            size = androidx.compose.ui.geometry.Size(size.width, h),
                        )
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight(440),
            letterSpacing = (-0.04).sp,
            color = if (selected) TextPrimary else TextMid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

@Composable
private fun SideTabStrip(title: String, onClose: () -> Unit, onOpenTerminal: () -> Unit) {
    var add by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(Bg)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.height(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Surface2)
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(OcIcons.Review, null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    title,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    color = TextPrimary,
                    fontWeight = FontWeight(440),
                    letterSpacing = (-0.04).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) { Icon(OcIcons.Close, "Close tab", tint = TextDim, modifier = Modifier.size(14.dp)) }
        }
        Box(Modifier.weight(1f))
        Box {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).clickable { add = true },
                contentAlignment = Alignment.Center,
            ) { Icon(OcIcons.Plus, "Add tab", tint = TextMid, modifier = Modifier.size(16.dp)) }
            DropdownMenu(expanded = add, onDismissRequest = { add = false }, containerColor = Surface2) {
                // `terminal/renderer.tsx:159` order 20, `session.panel` launcher
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(OcIcons.Terminal, null, tint = TextDim, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Terminal", fontSize = 13.sp, color = TextPrimary)
                        }
                    },
                    onClick = {
                        add = false
                        onOpenTerminal()
                    },
                )
            }
        }
        Spacer(Modifier.width(12.dp))
    }
    // `.5px` bottom border of the bar
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}
