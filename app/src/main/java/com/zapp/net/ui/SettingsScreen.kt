package com.zapp.net.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.MainViewModel
import com.zapp.net.settings.DisplayPrefs
import com.zapp.net.settings.SessionPrefs
import com.zapp.net.settings.SettingsPrefs
import com.zapp.net.ui.theme.Accent
import com.zapp.net.ui.theme.Bg
import com.zapp.net.ui.theme.Border
import com.zapp.net.ui.theme.Danger
import com.zapp.net.ui.theme.Ink
import com.zapp.net.ui.theme.Layer1
import com.zapp.net.ui.theme.Layer2
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.Scrim
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextFaint
import com.zapp.net.ui.theme.TextMid
import com.zapp.net.ui.theme.TextPrimary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * PORT of the whole OpenCode PC Settings surface: thirteen pages, their routes, their words.
 *
 * ## What is theirs
 *
 * `settings/route.ts` gives the tab sets; `settings/surface.tsx` gives the navigation model
 * (`select`, `subtab`, `back`, `openProject`, `selectServer`); `settings/search-machine.ts`
 * gives the search; `runtime/i18n/en.ts` gives every string. The page *bodies* are not in the
 * extracted map - `src/settings` holds only the models - so each page's row set was read off
 * the shipped window and cross-checked against the `settings.*` keys that exist. A row with no
 * key behind it is either quoted from the window in `SettingsCopy` with that noted, or it is
 * not here.
 *
 * ## Why the layout is not a 200px rail
 *
 * Their settings is a two-pane surface: a rail on the left, the page on the right. A 412dp
 * phone cannot hold both without making the page unreadable, so the phone keeps their *routes*
 * and their *rows* and swaps the pane split for a push navigation: root list -> page ->
 * `Back to settings`. That is the same `back()` their router performs, with one screen of
 * history instead of two columns. Their `settings.backToSettings` string is used verbatim.
 *
 * ## What is real and what is stored
 *
 * the owner's rule is that a control which does nothing must say so rather than pretend. Every row
 * that the phone does not act on carries [SettingsCopy.NOT_USED] under its description. As of
 * this pass the live rows are: timeline detail (5 presets + advanced), changed files, collapse
 * long messages, auto-accept permissions, the four notification banners, the four chimes,
 * interface sounds, bottom navigation, the model enable/disable list, the MCP toggles, the
 * plugin check/update, the project list and the server state.
 */
@Composable
fun SettingsScreen(vm: MainViewModel, startPage: Page? = null, onClose: () -> Unit) {
    val ctx = LocalContext.current
    // Bumped by every SettingsPrefs write so the whole surface recomposes from one flow.
    SettingsPrefs.version.collectAsState()

    // Their search is a session, not a string: it remembers the query, the row it came from, the
    // scroll position, and the activation counter that fences one reveal off from the next.
    var search by remember { mutableStateOf(SettingsSearchMachine.initialState()) }
    var page by remember { mutableStateOf<Page?>(startPage) }
    var activation by remember { mutableStateOf<Int?>(null) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    // The row a reveal is looking for, the row currently holding the highlight, and the wait
    // that gives up if the row never mounts.
    var revealTarget by remember { mutableStateOf<String?>(null) }
    var flashTarget by remember { mutableStateOf<String?>(null) }
    var revealJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    // The rail is the origin a search starts from, so it is what Back returns to.
    val railView = SettingsView(type = ViewType.RAIL, tab = page ?: Page.PREFERENCES)

    fun view(): SettingsView = SettingsView(
        type = if (page == null) ViewType.RAIL else ViewType.PAGE,
        tab = page ?: Page.PREFERENCES,
        searchActivation = activation,
    )

    fun apply(t: SettingsSearchMachine.Transition) {
        search = t.state
        for (effect in t.effects) {
            when (effect) {
                is SettingsSearchMachine.Effect.Navigate -> {
                    // The origin is the rail, which on this phone is the results list itself.
                    page = if (effect.view.type == ViewType.RAIL) null else effect.view.tab
                    activation = effect.view.searchActivation
                    // A new navigation owns the reveal; whatever the last one was doing is dead.
                    revealJob?.cancel()
                    revealJob = null
                    revealTarget = null
                    flashTarget = null
                }
                SettingsSearchMachine.Effect.Exit -> onClose()
                // Their reveal arms a wait, then gives up if no row ever answers it.
                is SettingsSearchMachine.Effect.RevealStart -> {
                    revealJob?.cancel()
                    revealTarget = effect.view.target
                    flashTarget = null
                    revealJob = scope.launch {
                        delay(effect.timeoutMs)
                        apply(
                            SettingsSearchMachine.transition(
                                search,
                                SettingsSearchMachine.Event.RevealTimedOut(effect.activation),
                            ),
                        )
                    }
                }
                // Their `settings-search-reveal` animation: the row holds the highlight for the
                // animation's own length, and its `animationend` is what settles the reveal.
                is SettingsSearchMachine.Effect.RevealHighlight -> {
                    flashTarget = revealTarget
                    scope.launch {
                        delay(effect.timeoutMs)
                        apply(
                            SettingsSearchMachine.transition(
                                search,
                                SettingsSearchMachine.Event.RevealCompleted(effect.activation),
                            ),
                        )
                    }
                }
                SettingsSearchMachine.Effect.RevealCancel -> {
                    revealJob?.cancel()
                    revealJob = null
                    revealTarget = null
                    flashTarget = null
                }
            }
        }
    }

    fun send(event: SettingsSearchMachine.Event) = apply(SettingsSearchMachine.transition(search, event))

    val query = SettingsSearchMachine.selectSearchQuery(search)
    val searching = SettingsSearchMachine.isSearchActive(search)
    // Their `canIndexServerSettings`: search lists a server's settings and projects only while
    // its pages can open, so an unreachable server contributes nothing to the index.
    val server by vm.serverBase.collectAsState()
    val connected by vm.connected.collectAsState()
    val connecting by vm.connecting.collectAsState()
    val booting by vm.booting.collectAsState()
    val gate by vm.gate.collectAsState()
    val availability = ServerAvailabilityMachine.resolve(
        connection = server.ifBlank { null },
        health = ServerAvailabilityMachine.healthOf(connected, connecting || booting, gate),
    )
    val indexedServer = server.takeIf { ServerAvailabilityMachine.canIndex(availability) }
    val index = remember(indexedServer) { SettingsSearch.index(server = indexedServer) }
    val entries = remember(query, searching, index) {
        if (searching) SettingsSearch.grouped(query, index) else emptyList()
    }

    if (page == null) {
        SettingsRoot(
            query = query,
            searching = searching,
            entries = entries,
            onQuery = { send(SettingsSearchMachine.Event.InputChanged(it, railView)) },
            onBack = { send(SettingsSearchMachine.Event.EscapePressed(railView)) },
            onOpenPage = { page = it; activation = null },
            onOpenEntry = { entry ->
                send(SettingsSearchMachine.Event.ResultOpened(entry.id, entry.view))
            },
            index = index,
        )
    } else {
        SettingsPageView(
            page = page!!,
            vm = vm,
            reveal = revealTarget,
            flash = flashTarget,
            activation = activation,
            onMounted = { send(SettingsSearchMachine.Event.ViewSynced(view())) },
            onRevealed = { a ->
                // The row answered: the wait is over and the highlight can start.
                revealJob?.cancel()
                revealJob = null
                send(SettingsSearchMachine.Event.RevealHighlighted(a))
            },
            onBack = {
                val t = SettingsSearchMachine.transition(
                    search,
                    SettingsSearchMachine.Event.BackRequested(view()),
                )
                val returning = t.effects.any { it is SettingsSearchMachine.Effect.Navigate }
                apply(t)
                // No search to return to: Back leaves the page for the rail, as it always did.
                if (!returning) {
                    page = null
                    activation = null
                    send(SettingsSearchMachine.Event.SettingsClosed)
                }
            },
            onOpen = { page = it },
            onPick = { picker = it },
        )
    }

    picker?.let { p ->
        ChoiceSheet(
            title = p.title,
            options = p.options,
            selected = p.selected,
            onPick = { p.onPick(it); picker = null },
            onDismiss = { picker = null },
            onHighlight = p.onHighlight,
        )
    }
}

// =========================================================================================
// Routes - `settings/route.ts:48-61`, in their order
// =========================================================================================

enum class Page(
    /** The `tab` value in their URL. */
    val key: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
) {
    PREFERENCES(
        "general", SettingsCopy.PREFERENCES_TITLE, SettingsCopy.PREFERENCES_DESC,
        OcIcons.Sliders,
    ),
    APPEARANCE(
        "appearance", SettingsCopy.APPEARANCE_TITLE, SettingsCopy.APPEARANCE_DESC,
        OcIcons.Appearance,
    ),
    NOTIFICATIONS(
        "notifications", SettingsCopy.NOTIFICATIONS_TITLE, SettingsCopy.NOTIFICATIONS_DESCRIPTION,
        OcIcons.Notifications,
    ),
    SHORTCUTS(
        "shortcuts", SettingsCopy.SHORTCUTS_TITLE, SettingsCopy.SHORTCUTS_DESC,
        OcIcons.Keyboard,
    ),
    PROJECTS(
        "projects", SettingsCopy.PROJECTS_TITLE, SettingsCopy.PROJECTS_DESC,
        OcIcons.Folder,
    ),
    WORKTREES(
        "workspaces", SettingsCopy.WORKTREES_TITLE, SettingsCopy.WORKTREES_DESC,
        OcIcons.Worktree,
    ),
    PROVIDERS(
        "providers", SettingsCopy.PROVIDERS_TITLE, SettingsCopy.PROVIDERS_DESC,
        OcIcons.Providers,
    ),
    MODELS(
        "models", SettingsCopy.MODELS_TITLE, SettingsCopy.MODELS_DESC,
        OcIcons.Models,
    ),
    EXTENSIONS(
        "extensions", SettingsCopy.EXTENSIONS_TITLE, SettingsCopy.EXTENSIONS_DESC,
        OcIcons.Extensions,
    ),
    SERVERS(
        "servers", SettingsCopy.TAB_SERVERS, SettingsCopy.SERVER_DESC,
        OcIcons.Server,
    ),
    PAIRING(
        "pairing", SettingsCopy.PAIRING_TITLE, SettingsCopy.PAIRING_DESC,
        OcIcons.Pair,
    ),
    EXPERIMENTAL(
        "experimental", SettingsCopy.EXPERIMENTAL_TITLE, SettingsCopy.EXPERIMENTAL_DESC,
        OcIcons.Flask,
    ),
    // Ours, not theirs. The first-run tutorial, reachable after onboarding so a user who
    // gets stuck (or reinstalls Termux) can walk the same four steps again.
    SETUP(
        "setup", "Setup tutorial", "Install Termux and start the engine",
        OcIcons.Terminal,
    ),
    ABOUT(
        "about", SettingsCopy.ABOUT_TITLE, SettingsCopy.ABOUT_DESCRIPTION,
        OcIcons.Info,
    ),
    ;

    companion object {
        /** Their rail: the server-scoped pages first, then the two tail pages. */
        val rail: List<Page> = listOf(
            PREFERENCES, APPEARANCE, NOTIFICATIONS, SHORTCUTS,
            PROJECTS, WORKTREES, PROVIDERS, MODELS, EXTENSIONS, SERVERS, PAIRING,
        )

        val tail: List<Page> = listOf(SETUP, EXPERIMENTAL, ABOUT)
    }
}

// =========================================================================================
// Root
// =========================================================================================

@Composable
private fun SettingsRoot(
    query: String,
    searching: Boolean,
    entries: List<SettingsSearch.Entry>,
    onQuery: (String) -> Unit,
    onBack: () -> Unit,
    onOpenPage: (Page) -> Unit,
    onOpenEntry: (SettingsSearch.Entry) -> Unit,
    index: List<SettingsSearch.Entry>,
) {
    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
        TopBar(title = SettingsCopy.TITLE, back = SettingsCopy.BACK_TO_APP, onBack = onBack)
        SearchField(query = query, onQuery = onQuery)
        Spacer(Modifier.height(8.dp))

        if (!searching) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = rememberLazyListState(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
            ) {
                item { RailSection(Page.rail, onOpenPage) }
                item { Spacer(Modifier.height(18.dp)) }
                item { RailSection(Page.tail, onOpenPage) }
            }
        } else {
            SearchResults(query = query, entries = entries, index = index, onOpen = onOpenEntry)
        }
    }
}
@Composable
private fun RailSection(pages: List<Page>, onOpen: (Page) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        pages.forEach { p ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpen(p) }
                    .padding(horizontal = 8.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(p.icon, null, tint = TextMid, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        p.title,
                        fontSize = 14.sp,
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.1).sp,
                    )
                    Text(
                        p.description,
                        fontSize = 11.5.sp,
                        color = TextFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
                Icon(
                    OcIcons.ChevronRight,
                    null,
                    tint = TextDim,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
@Composable
private fun SearchResults(
    query: String,
    entries: List<SettingsSearch.Entry>,
    index: List<SettingsSearch.Entry>,
    onOpen: (SettingsSearch.Entry) -> Unit,
) {
    if (entries.isEmpty()) {
        // Their `J` measures the message, then shortens the query on grapheme boundaries until
        // the quoted form fits. A long paste must not push the card wider than the screen.
        val measurer = rememberTextMeasurer()
        val style = TextStyle(fontSize = 13.sp, color = TextMid)
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp)) {
            val prefix = SettingsCopy.searchEmpty("")
            val shown = SettingsSearch.truncateToWidth(
                query = query,
                available = constraints.maxWidth.toFloat(),
                measure = { q ->
                    measurer.measure(prefix + SettingsCopy.quoted(q), style, maxLines = 1)
                        .size.width.toFloat()
                },
            )
            Column {
                Text(prefix + SettingsCopy.quoted(shown), style = style)
                Text(
                    SettingsCopy.SEARCH_REFINE,
                    fontSize = 11.5.sp,
                    color = TextFaint,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        return
    }

    // Their `V`: a group only earns a header when it holds a page or a project row.
    val headed = remember(entries) { SettingsSearch.groupsWithHeader(entries) }
    // Their `footer`: the cap hid something, so tell the reader to narrow the query.
    val truncated = remember(query, index) { SettingsSearch.isTruncated(query, index) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
    ) {
        itemsIndexed(entries, key = { _, e -> e.id }) { index, entry ->
            val group = SettingsSearch.groupKey(entry)
            val isFirstOfGroup = index == 0 || SettingsSearch.groupKey(entries[index - 1]) != group
            if (isFirstOfGroup && group in headed) SectionLabel(group)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(entry) }
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(entry.title, fontSize = 13.sp, color = TextPrimary)
                    Text(
                        SettingsSearch.pageLabel(entry),
                        fontSize = 11.5.sp,
                        color = TextFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
                Icon(
                    OcIcons.ChevronRight,
                    null,
                    tint = TextDim,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (truncated) {
            item {
                Text(
                    SettingsSearch.REFINE,
                    fontSize = 11.5.sp,
                    color = TextFaint,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                )
            }
        }
    }
}
// =========================================================================================
// Reveal - their `search-reveal.ts`, in Compose terms
// =========================================================================================

/** Where each tagged row currently sits, in window coordinates. */
private val LocalRowBounds = staticCompositionLocalOf<MutableMap<String, Rect>?> { null }

/**
 * Their `[data-search-target]` tag: a row a result can land on reports its own bounds.
 *
 * Their reveal finds the element in the DOM, so it can scroll and highlight it directly. A Compose
 * list has no DOM, and the rows here live inside shared cards rather than one item each, so the row
 * reports where it is and the page scrolls by the difference. A row inside a card is still composed
 * when it is off-screen, so the report arrives before the scroll.
 */
@Composable
private fun Modifier.revealRow(id: String?): Modifier {
    val bounds = LocalRowBounds.current ?: return this
    if (id == null) return this
    return onGloballyPositioned { bounds[id] = it.boundsInWindow() }
}

// =========================================================================================
// Page shell
// =========================================================================================

@Composable
private fun SettingsPageView(
    page: Page,
    vm: MainViewModel,
    reveal: String?,
    flash: String?,
    activation: Int?,
    onMounted: () -> Unit,
    onRevealed: (Int) -> Unit,
    onBack: () -> Unit,
    onOpen: (Page) -> Unit,
    onPick: (Picker) -> Unit,
) {
    val listState = rememberLazyListState()
    val rowBounds = remember(page) { mutableStateMapOf<String, Rect>() }
    var listOrigin by remember { mutableStateOf(Offset.Zero) }
    var flashRect by remember { mutableStateOf<Rect?>(null) }
    val glow = remember { Animatable(0f) }
    val density = LocalDensity.current

    // Our own page, not one of theirs: it hosts the first-run tutorial full-screen, so the
    // tutorial's scroll is the only scroll (nesting two vertical scrollers throws). Back
    // returns to the rail exactly like every other page.
    if (page == Page.SETUP) {
        com.zapp.net.onboarding.SetupTutorial(onDone = onBack)
        return
    }

    // Their `sync(view)`: the page says which view it now shows, and the machine answers a
    // matching activation with `RevealStart`.
    LaunchedEffect(page, activation) { onMounted() }

    // Their `scrollIntoView({block: "center"})` + `settings-search-reveal` animation, walked in
    // the same order: find the row, centre it, then let it hold the highlight. A virtualised list
    // only composes what is near the viewport, so a row that is not mounted yet is looked for by
    // walking the list - inside their own 5 000 ms budget, which is what settles a miss.
    LaunchedEffect(page, reveal, activation) {
        if (reveal == null || activation == null) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        var box = rowBounds[reveal]
        if (box == null) {
            val deadline = System.currentTimeMillis() + SettingsSearchMachine.SEARCH_REVEAL_WAIT_MS
            listState.scrollToItem(0)
            withFrameNanos { }
            while (box == null && System.currentTimeMillis() < deadline) {
                val viewport = listState.layoutInfo.viewportSize.height
                if (viewport <= 0) break
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (last >= listState.layoutInfo.totalItemsCount - 1) break
                listState.scrollBy(viewport * 0.7f)
                withFrameNanos { }
                withFrameNanos { }
                box = rowBounds[reveal]
            }
        }
        box ?: return@LaunchedEffect
        val viewport = listState.layoutInfo.viewportSize.height.toFloat()
        if (viewport > 0f) {
            listState.scrollBy((box.top - listOrigin.y) - viewport / 2f + box.height / 2f)
            withFrameNanos { }
        }
        onRevealed(activation)
    }

    // The highlight itself: their animation runs for `SEARCH_REVEAL_ANIMATION_MS`, so the band
    // comes in fast, holds, and leaves before the machine settles the reveal.
    LaunchedEffect(flash, activation) {
        if (flash == null) {
            flashRect = null
            glow.snapTo(0f)
            return@LaunchedEffect
        }
        flashRect = rowBounds[flash] ?: return@LaunchedEffect
        glow.snapTo(0f)
        glow.animateTo(1f, tween(140))
        delay((SettingsSearchMachine.SEARCH_REVEAL_ANIMATION_MS - 560L).coerceAtLeast(0L))
        glow.animateTo(0f, tween(420))
        flashRect = null
    }

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
        TopBar(title = page.title, back = SettingsCopy.BACK_TO_SETTINGS, onBack = onBack)
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalRowBounds provides rowBounds) {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { listOrigin = it.boundsInWindow().topLeft },
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            page.description,
                            fontSize = 12.sp,
                            color = TextFaint,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                    }
                    when (page) {
                        Page.PREFERENCES -> preferencesPage(onPick)
                        Page.APPEARANCE -> appearancePage(onPick)
                        Page.NOTIFICATIONS -> notificationsPage(onPick)
                        Page.SHORTCUTS -> shortcutsPage()
                        Page.PROJECTS -> projectsPage(vm)
                        Page.WORKTREES -> worktreesPage(vm)
                        Page.PROVIDERS -> providersPage(vm)
                        Page.MODELS -> modelsPage(vm)
                        Page.EXTENSIONS -> extensionsPage(vm)
                        Page.SERVERS -> serversPage(vm)
                        Page.PAIRING -> pairingPage(vm)
                        Page.EXPERIMENTAL -> experimentalPage()
                        Page.ABOUT -> aboutPage(vm)
                        // Unreachable: SETUP returns above with the tutorial. Listed so the
                        // `when` stays exhaustive when the enum grows.
                        Page.SETUP -> Unit
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
            flashRect?.let { r ->
                val alpha = glow.value
                if (alpha > 0.001f) {
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    (r.left - listOrigin.x).roundToInt(),
                                    (r.top - listOrigin.y).roundToInt(),
                                )
                            }
                            .size(
                                with(density) { r.width.toDp() },
                                with(density) { r.height.toDp() },
                            )
                            .clip(RoundedCornerShape(10.dp))
                            .background(Accent.copy(alpha = 0.13f * alpha))
                            .border(
                                1.dp,
                                Accent.copy(alpha = 0.32f * alpha),
                                RoundedCornerShape(10.dp),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(title: String, back: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 8.dp)) {
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onBack() }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(OcIcons.ArrowLeft, back, tint = TextMid, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(back, fontSize = 12.5.sp, color = TextMid)
        }
        Text(
            title,
            fontSize = 13.sp,
            color = TextPrimary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Layer1)
            .border(0.5.dp, Border, RoundedCornerShape(9.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(OcIcons.Magnifier, null, tint = TextFaint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(SettingsCopy.SEARCH, fontSize = 13.sp, color = TextFaint)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                textStyle = TextStyle(fontSize = 13.sp, color = TextPrimary),
                singleLine = true,
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                OcIcons.Close,
                SettingsCopy.CLEAR,
                tint = TextFaint,
                modifier = Modifier
                    .size(15.dp)
                    .clip(CircleShape)
                    .clickable { onQuery("") },
            )
        }
    }
}

// =========================================================================================
// Row primitives - their card shape, phone widths
// =========================================================================================

// =========================================================================================
// Row primitives - their card shape, phone widths
// =========================================================================================

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = TextFaint,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Layer1)
            .border(0.5.dp, Border, RoundedCornerShape(12.dp)),
    ) { content() }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().padding(start = 14.dp).height(0.5.dp).background(Line))
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    id: String? = null,
    note: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().revealRow(id).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = TextPrimary)
            if (description.isNotBlank()) {
                Text(
                    description,
                    fontSize = 11.5.sp,
                    color = TextMid,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (note != null) Note(note)
        }
        Spacer(Modifier.width(12.dp))
        ZappSwitch(checked, onChange)
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    description: String,
    options: List<String>,
    selected: String,
    id: String? = null,
    note: String? = null,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().revealRow(id).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(title, fontSize = 13.sp, color = TextPrimary)
        if (description.isNotBlank()) {
            Text(
                description,
                fontSize = 11.5.sp,
                color = TextMid,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (note != null) Note(note)
        Spacer(Modifier.height(9.dp))
        Segmented(options = options, selected = selected, onSelect = onSelect)
    }
}

@Composable
private fun ValueRow(
    title: String,
    description: String,
    value: String,
    id: String? = null,
    note: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().revealRow(id).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = TextPrimary)
            if (description.isNotBlank()) {
                Text(
                    description,
                    fontSize = 11.5.sp,
                    color = TextMid,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (note != null) Note(note)
        }
        Spacer(Modifier.width(10.dp))
        Text(value, fontSize = 12.sp, color = TextMid, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Icon(OcIcons.ChevronRight, null, tint = TextDim, modifier = Modifier.size(13.dp))
    }
}

@Composable
private fun NavRow(
    title: String,
    description: String,
    id: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .revealRow(id)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = if (enabled) TextPrimary else TextDim)
            if (description.isNotBlank()) {
                Text(
                    description,
                    fontSize = 11.5.sp,
                    color = if (enabled) TextMid else TextDim,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Icon(OcIcons.ChevronRight, null, tint = TextDim, modifier = Modifier.size(13.dp))
    }
}

@Composable
private fun TextRow(title: String, description: String, id: String? = null) {
    Column(Modifier.fillMaxWidth().revealRow(id).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(title, fontSize = 13.sp, color = TextPrimary)
        if (description.isNotBlank()) {
            Text(
                description,
                fontSize = 11.5.sp,
                color = TextMid,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        fontSize = 10.5.sp,
        color = TextFaint,
        modifier = Modifier.padding(top = 3.dp),
    )
}

@Composable
private fun EmptyCard(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 18.dp)) {
        Text(title, fontSize = 13.sp, color = TextPrimary)
        Text(
            description,
            fontSize = 11.5.sp,
            color = TextFaint,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun ZappSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .width(40.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) Accent else Layer2)
            .clickable { onChange(!checked) }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (checked) Ink else TextFaint),
        )
    }
}

@Composable
private fun Segmented(
    options: List<String>,
    selected: String,
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Bg)
            .border(0.5.dp, Border, RoundedCornerShape(8.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { label ->
            val on = label == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) Layer2 else Color.Transparent)
                    .then(if (enabled) Modifier.clickable { onSelect(label) } else Modifier)
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            ) {
                Text(
                    label,
                    fontSize = 11.5.sp,
                    color = when {
                        !enabled -> TextDim
                        on -> TextPrimary
                        else -> TextMid
                    },
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

// =========================================================================================
// Choice sheet - the phone's stand-in for their popover list
// =========================================================================================

data class Picker(
    val title: String,
    val options: List<Pair<String, String>>,   // id to label
    val selected: String,
    /**
     * Their `onHighlight`: the option the pointer settles on is **auditioned** before it is
     * chosen. Only the sound pickers pass it (`dr()`'s `highlight`); every other picker leaves it
     * null, which is why the parameter is optional rather than a no-op lambda.
     */
    val onHighlight: ((String) -> Unit)? = null,
    val onPick: (String) -> Unit,
)

/**
 * Their `onHighlight`, on glass.
 *
 * Their popover highlights the row under a mouse pointer as it moves. A phone has no hover, so the
 * pointer is the finger that is already on the list: every move while pressed re-hit-tests the row
 * under it. Dragging down the list auditions each sound in turn - the same gesture as scrolling,
 * which is why this only observes and never consumes. Their 100 ms debounce is what keeps a fast
 * drag from firing all 45.
 */
private fun Modifier.auditionHighlight(
    state: androidx.compose.foundation.lazy.LazyListState,
    idOf: (Int) -> String?,
    onHighlight: (String) -> Unit,
): Modifier = pointerInput(state) {
    awaitPointerEventScope {
        var last = Int.MIN_VALUE
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Move && event.type != PointerEventType.Press) continue
            val y = event.changes.firstOrNull()?.position?.y ?: continue
            val row = state.layoutInfo.visibleItemsInfo
                .firstOrNull { y >= it.offset && y < it.offset + it.size }
            val index = row?.index ?: -1
            if (index == last) continue
            last = index
            if (index >= 0) idOf(index)?.let(onHighlight)
        }
    }
}

@Composable
private fun ChoiceSheet(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    onHighlight: ((String) -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize().background(Scrim).clickable { onDismiss() }) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Layer1)
                .border(0.5.dp, Border, RoundedCornerShape(14.dp))
                .clickable { }
                .padding(vertical = 8.dp),
        ) {
            Text(
                title,
                fontSize = 12.sp,
                color = TextFaint,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
            )
            val listState = rememberLazyListState()
            LazyColumn(
                Modifier
                    .height(360.dp)
                    .then(
                        if (onHighlight == null) Modifier
                        else Modifier.auditionHighlight(
                            listState,
                            { options.getOrNull(it)?.first },
                            onHighlight,
                        ),
                    ),
                state = listState,
            ) {
                items(options) { (id, label) ->
                    val on = id == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(id) }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label,
                            fontSize = 13.sp,
                            color = if (on) TextPrimary else TextMid,
                            modifier = Modifier.weight(1f),
                        )
                        if (on) Icon(OcIcons.Check, null, tint = Accent, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

// =========================================================================================
// Preferences - `settings.tab.preferences`, sections General / Timeline / Permissions
// =========================================================================================

private fun androidx.compose.foundation.lazy.LazyListScope.preferencesPage(
    onPick: (Picker) -> Unit,
) {
    item { PreferencesBody(onPick) }
}

@Composable
private fun PreferencesBody(onPick: (Picker) -> Unit) {
    val ctx = LocalContext.current
    val detail by DisplayPrefs.detail.collectAsState()
    val detailId by DisplayPrefs.detailId.collectAsState()
    val turnDiffs by DisplayPrefs.turnDiffs.collectAsState()
    val foldUser by DisplayPrefs.foldUser.collectAsState()
    val autoApprove by DisplayPrefs.autoApprove.collectAsState()
    var advanced by remember { mutableStateOf(false) }

    if (advanced) {
        // `settings.timeline.advanced` - their Advanced view, ported whole: one chip row per
        // category, the preset the value still equals as the summary, and the reset.
        Text(
            SettingsCopy.ADVANCED_DESCRIPTION,
            fontSize = 11.5.sp,
            color = TextFaint,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
        )
        Text(
            SettingsCopy.summary(detailId),
            fontSize = 11.5.sp,
            color = Accent,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 8.dp),
        )
        SettingsModel.categoryNames.forEach { (category, name) ->
            val rule = detail.of(category)
            val shown = rule.placement != TimelineDetail.Placement.Hidden
            val grouped = rule.placement == TimelineDetail.Placement.Grouped
            val hasDetails = TimelineDetail.hasDetails(category)

            Card {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(name, fontSize = 13.sp, color = TextPrimary)
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Chip(SettingsCopy.SHOW, shown, SettingsCopy.visibilityLabel(name)) {
                            DisplayPrefs.setCustom(
                                ctx,
                                TimelineDetail.with(
                                    detail,
                                    category,
                                    TimelineDetail.Rule(
                                        if (grouped) TimelineDetail.Placement.Grouped
                                        else TimelineDetail.Placement.Separate,
                                        rule.details,
                                    ),
                                ),
                            )
                        }
                        Chip(SettingsCopy.HIDE, !shown, SettingsCopy.visibilityLabel(name)) {
                            DisplayPrefs.setCustom(
                                ctx,
                                TimelineDetail.with(
                                    detail,
                                    category,
                                    TimelineDetail.Rule(
                                        TimelineDetail.Placement.Hidden,
                                        rule.details,
                                    ),
                                ),
                            )
                        }
                        if (shown) {
                            Chip(SettingsCopy.GROUP, grouped, SettingsCopy.groupedLabel(name)) {
                                DisplayPrefs.setCustom(
                                    ctx,
                                    TimelineDetail.with(
                                    detail,
                                        category,
                                        TimelineDetail.Rule(
                                            if (grouped) TimelineDetail.Placement.Separate
                                            else TimelineDetail.Placement.Grouped,
                                            rule.details,
                                        ),
                                    ),
                                )
                            }
                        }
                        if (shown && hasDetails) {
                            val collapsed = rule.details == TimelineDetail.Expansion.Collapsed
                            Chip(SettingsCopy.COLLAPSE, collapsed, SettingsCopy.collapsedLabel(name)) {
                                DisplayPrefs.setCustom(
                                    ctx,
                                    TimelineDetail.with(
                                    detail,
                                        category,
                                        TimelineDetail.Rule(
                                            rule.placement,
                                            if (collapsed) TimelineDetail.Expansion.Expanded
                                            else TimelineDetail.Expansion.Collapsed,
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Layer2)
                .clickable { DisplayPrefs.resetDetail(ctx) }
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text(SettingsCopy.RESET_TO_DEFAULTS, fontSize = 12.sp, color = TextPrimary)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { advanced = false }
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text(SettingsCopy.BACK_TO_SETTINGS, fontSize = 12.sp, color = TextMid)
        }
        return
    }

    SectionLabel(SettingsCopy.SECTION_GENERAL)
    Card {
        ChoiceRow(
            title = SettingsCopy.INTERFACE_LAYOUT,
            description = SettingsCopy.INTERFACE_LAYOUT_DESC,
            options = listOf(SettingsCopy.TOP_TABS, SettingsCopy.SIDE_TABS, SettingsCopy.INBOX),
            selected = interfaceLabel(
                SettingsPrefs.string(
                    ctx, SettingsPrefs.KEY_TAB_LAYOUT, SettingsPrefs.DEFAULT_TAB_LAYOUT,
                ),
            ),
            note = SettingsCopy.NOT_USED,
            onSelect = { SettingsPrefs.set(ctx, SettingsPrefs.KEY_TAB_LAYOUT, interfaceId(it)) },
        )
        Divider()
        ValueRow(
            title = SettingsCopy.LANGUAGE,
            id = "settings-language",
            description = SettingsCopy.LANGUAGE_DESC,
            value = "English",
            onClick = {
                onPick(
                    Picker(
                        title = SettingsCopy.LANGUAGE,
                        options = listOf("en" to "English"),
                        selected = "en",
                        onPick = { SettingsPrefs.set(ctx, SettingsPrefs.KEY_LANGUAGE, it) },
                    ),
                )
            },
        )
        Divider()
        ChoiceRow(
            title = SettingsCopy.DEFAULT_ENV,
            id = "settings-workspace-destination",
            description = SettingsCopy.DEFAULT_ENV_DESC,
            options = listOf(
                SettingsCopy.LAST_USED, SettingsCopy.LOCAL_DIR, SettingsCopy.NEW_WORKTREE,
            ),
            selected = destinationLabel(
                SettingsPrefs.string(
                    ctx, SettingsPrefs.KEY_DEFAULT_DESTINATION,
                    SettingsPrefs.DEFAULT_DESTINATION,
                ),
            ),
            note = SettingsCopy.NOT_USED,
            onSelect = {
                SettingsPrefs.set(ctx, SettingsPrefs.KEY_DEFAULT_DESTINATION, destinationId(it))
            },
        )
        Divider()
        SwitchRow(
            title = SettingsCopy.SHOW_AGENT,
            id = "settings-show-custom-agents",
            description = SettingsCopy.SHOW_AGENT_DESC,
            checked = SettingsPrefs.bool(ctx, SettingsPrefs.KEY_SHOW_CUSTOM_AGENTS, true),
            note = SettingsCopy.NOT_USED,
            onChange = { SettingsPrefs.set(ctx, SettingsPrefs.KEY_SHOW_CUSTOM_AGENTS, it) },
        )
        Divider()
        ChoiceRow(
            title = SettingsCopy.FOLLOW_UP,
            id = "settings-follow-up-behavior",
            description = SettingsCopy.FOLLOW_UP_DESC,
            options = listOf(SettingsCopy.QUEUE, SettingsCopy.STEER),
            selected = if (
                SettingsPrefs.string(
                    ctx, SettingsPrefs.KEY_FOLLOW_UP, SettingsPrefs.DEFAULT_FOLLOW_UP,
                ) == "queue"
            ) SettingsCopy.QUEUE else SettingsCopy.STEER,
            note = SettingsCopy.NOT_USED,
            onSelect = {
                SettingsPrefs.set(
                    ctx, SettingsPrefs.KEY_FOLLOW_UP,
                    if (it == SettingsCopy.QUEUE) "queue" else "steer",
                )
            },
        )
        Divider()
        SwitchRow(
            title = SettingsCopy.PINCH_ZOOM,
            id = "settings-pinch-zoom",
            description = SettingsCopy.PINCH_ZOOM_DESC,
            checked = false,
            note = SettingsCopy.NOT_USED,
            onChange = { },
        )
        Divider()
        SwitchRow(
            title = SettingsCopy.SPELLCHECK,
            id = "settings-spellcheck",
            description = SettingsCopy.SPELLCHECK_DESC,
            checked = false,
            note = SettingsCopy.NOT_USED,
            onChange = { },
        )
        Divider()
        // `titlebar.tsx:231` - `bottom = mobile() && mobileTitlebarPosition() === "bottom"`, and
        // `:403` `order-last` moves the row to the end of the shell column. The chat header reads
        // the same key, so this switch is live on the phone.
        val titlebarBottom = SettingsPrefs.string(
            ctx,
            SettingsPrefs.KEY_TITLEBAR_POSITION,
            SettingsPrefs.DEFAULT_TITLEBAR_POSITION,
        ) == "bottom"
        SwitchRow(
            title = SettingsCopy.BOTTOM_NAV,
            id = "settings-mobile-titlebar-bottom",
            description = SettingsCopy.BOTTOM_NAV_DESC,
            checked = titlebarBottom,
            onChange = {
                SettingsPrefs.set(
                    ctx,
                    SettingsPrefs.KEY_TITLEBAR_POSITION,
                    if (it) "bottom" else "top",
                )
            },
        )
    }

    SectionLabel(SettingsCopy.TIMELINE_TITLE)
    Card {
        ValueRow(
            title = SettingsCopy.TIMELINE_DETAIL,
            id = "settings-timeline-detail",
            description = SettingsCopy.presetDescription(detailId),
            value = SettingsCopy.presetTitle(detailId),
            onClick = {
                onPick(
                    Picker(
                        title = SettingsCopy.TIMELINE_DETAIL,
                        options = SettingsCopy.presets,
                        selected = detailId,
                        onPick = { DisplayPrefs.setDetail(ctx, it) },
                    ),
                )
            },
        )
        Divider()
        NavRow(
            title = SettingsCopy.ADVANCED_TITLE,
            description = SettingsCopy.ADVANCED_DESCRIPTION,
            onClick = { advanced = true },
        )
        Divider()
        SwitchRow(
            title = SettingsCopy.TURN_DIFFS_TITLE,
            id = "settings-show-turn-diffs",
            description = SettingsCopy.TURN_DIFFS_DESCRIPTION,
            checked = turnDiffs,
            onChange = { DisplayPrefs.setTurnDiffs(ctx, it) },
        )
        Divider()
        SwitchRow(
            title = SettingsCopy.FOLD_TITLE,
            id = "settings-fold-user-messages",
            description = SettingsCopy.FOLD_DESCRIPTION,
            checked = foldUser,
            onChange = { DisplayPrefs.setFoldUser(ctx, it) },
        )
    }

    SectionLabel(SettingsCopy.AUTO_APPROVE_TITLE)
    Card {
        SwitchRow(
            title = SettingsCopy.AUTO_APPROVE_TITLE,
            id = "settings-auto-accept-permissions",
            description = SettingsCopy.AUTO_APPROVE_DESCRIPTION,
            checked = autoApprove,
            onChange = { DisplayPrefs.setAutoApprove(ctx, it) },
        )
    }
}

@Composable
private fun Chip(label: String, on: Boolean, desc: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.sp,
        color = if (on) Ink else TextMid,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) Accent else Layer2)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .semantics { contentDescription = desc },
    )
}

internal fun interfaceLabel(id: String): String = when (id) {
    "vertical" -> SettingsCopy.SIDE_TABS
    "inbox" -> SettingsCopy.INBOX
    else -> SettingsCopy.TOP_TABS
}

internal fun interfaceId(label: String): String = when (label) {
    SettingsCopy.SIDE_TABS -> "vertical"
    SettingsCopy.INBOX -> "inbox"
    else -> "horizontal"
}

internal fun destinationLabel(id: String): String = when (id) {
    "local" -> SettingsCopy.LOCAL_DIR
    "new" -> SettingsCopy.NEW_WORKTREE
    else -> SettingsCopy.LAST_USED
}

internal fun destinationId(label: String): String = when (label) {
    SettingsCopy.LOCAL_DIR -> "local"
    SettingsCopy.NEW_WORKTREE -> "new"
    else -> "last-used"
}

// =========================================================================================
// Appearance - `settings.tab.appearance`
// =========================================================================================

private fun androidx.compose.foundation.lazy.LazyListScope.appearancePage(onPick: (Picker) -> Unit) {
    item { AppearanceBody(onPick) }
}

@Composable
private fun AppearanceBody(onPick: (Picker) -> Unit) {
    val ctx = LocalContext.current
    val zoom = SettingsPrefs.fontSize(ctx)

    SectionLabel(SettingsCopy.SECTION_APPEARANCE)
    Card {
        ChoiceRow(
            title = SettingsCopy.COLOR_SCHEME,
            id = "settings-color-scheme",
            description = SettingsCopy.COLOR_SCHEME_DESC,
            options = listOf(SettingsCopy.SYSTEM, SettingsCopy.LIGHT, SettingsCopy.DARK),
            selected = SettingsCopy.DARK,
            note = SettingsCopy.DARK_ONLY,
            onSelect = { SettingsPrefs.set(ctx, SettingsPrefs.KEY_COLOR_SCHEME, it.lowercase()) },
        )
        Divider()
        ValueRow(
            title = SettingsCopy.THEME,
            id = "settings-theme",
            description = SettingsCopy.THEME_DESC,
            value = SettingsCopy.THEME_NAME,
            note = SettingsCopy.NOT_USED,
            onClick = { },
        )
        Divider()
        ValueRow(
            title = SettingsCopy.UI_FONT,
            id = "settings-ui-font",
            description = SettingsCopy.UI_FONT_DESC,
            value = SettingsCopy.SYSTEM,
            note = SettingsCopy.NOT_USED,
            onClick = { },
        )
        Divider()
        ValueRow(
            title = SettingsCopy.CODE_FONT,
            id = "settings-code-font",
            description = SettingsCopy.CODE_FONT_DESC,
            value = SettingsCopy.SYSTEM,
            note = SettingsCopy.NOT_USED,
            onClick = { },
        )
        Divider()
        ValueRow(
            title = SettingsCopy.TERMINAL_FONT,
            id = "settings-terminal-font",
            description = SettingsCopy.TERMINAL_FONT_DESC,
            value = SettingsCopy.SYSTEM,
            note = SettingsCopy.NOT_USED,
            onClick = { },
        )
        Divider()
        ValueRow(
            title = SettingsCopy.ZOOM,
            id = "settings-zoom",
            description = SettingsCopy.ZOOM_DESC,
            value = "$zoom pt",
            note = SettingsCopy.NOT_USED,
            onClick = {
                onPick(
                    Picker(
                        title = SettingsCopy.ZOOM,
                        options = (11..18).map { it.toString() to "$it pt" },
                        selected = zoom.toString(),
                        onPick = { SettingsPrefs.set(ctx, SettingsPrefs.KEY_FONT_SIZE, it.toInt()) },
                    ),
                )
            },
        )
        Divider()
        ChoiceRow(
            title = SettingsCopy.REDUCE_MOTION,
            id = "settings-reduce-motion",
            description = SettingsCopy.REDUCE_MOTION_DESC,
            options = listOf(SettingsCopy.SYSTEM, SettingsCopy.ON, SettingsCopy.OFF),
            selected = reduceMotionLabel(
                SettingsPrefs.string(ctx, SettingsPrefs.KEY_REDUCE_MOTION, SettingsPrefs.DEFAULT_REDUCE_MOTION),
            ),
            note = SettingsCopy.NOT_USED,
            onSelect = { SettingsPrefs.set(ctx, SettingsPrefs.KEY_REDUCE_MOTION, it.lowercase()) },
        )
        Divider()
        ChoiceRow(
            title = SettingsCopy.TABS,
            id = "settings-tab-layout",
            description = SettingsCopy.TABS_DESC,
            options = listOf(SettingsCopy.HORIZONTAL, SettingsCopy.VERTICAL),
            selected = if (SettingsPrefs.string(ctx, SettingsPrefs.KEY_TAB_LAYOUT, SettingsPrefs.DEFAULT_TAB_LAYOUT) == "vertical") SettingsCopy.VERTICAL else SettingsCopy.HORIZONTAL,
            note = SettingsCopy.NOT_USED,
            onSelect = {
                SettingsPrefs.set(
                    ctx, SettingsPrefs.KEY_TAB_LAYOUT,
                    if (it == SettingsCopy.VERTICAL) "vertical" else "horizontal",
                )
            },
        )
    }
}

internal fun reduceMotionLabel(id: String): String = when (id) {
    "on" -> SettingsCopy.ON
    "off" -> SettingsCopy.OFF
    else -> SettingsCopy.SYSTEM
}

// =========================================================================================
// Notifications - banners, chimes, interface. All real: `SessionPrefs.notify`.
// =========================================================================================

private fun androidx.compose.foundation.lazy.LazyListScope.notificationsPage(onPick: (Picker) -> Unit) {
    item { NotificationsBody(onPick) }
}

@Composable
private fun NotificationsBody(onPick: (Picker) -> Unit) {
    val ctx = LocalContext.current
    val n by SessionPrefs.notify.collectAsState()
    val soundAgent = SessionPrefs.string(ctx, SessionPrefs.KEY_ID_AGENT, SessionPrefs.DEFAULT_ID_AGENT)
    val soundPerms = SessionPrefs.string(ctx, SessionPrefs.KEY_ID_PERMISSIONS, SessionPrefs.DEFAULT_ID_PERMISSIONS)
    val soundQuestions = SessionPrefs.string(ctx, SessionPrefs.KEY_ID_QUESTIONS, SessionPrefs.DEFAULT_ID_QUESTIONS)
    val soundErrors = SessionPrefs.string(ctx, SessionPrefs.KEY_ID_ERRORS, SessionPrefs.DEFAULT_ID_ERRORS)

    // Their `Ke(Le)` - one preview controller for the whole page, so moving from one channel's
    // picker to another's cancels the first rather than stacking two sounds.
    val scope = rememberCoroutineScope()
    val preview = remember {
        SoundPreviewController(scope) { id ->
            com.zapp.net.notify.SoundPlayer.play(ctx, id)?.let { playback ->
                SoundPreviewMachine.Stop { playback.stop() }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { preview.dispose() } }

    SectionLabel(SettingsCopy.SECTION_NOTIFICATIONS)
    Card {
        SwitchRow(SettingsCopy.NOTIFY_AGENT, SettingsCopy.NOTIFY_AGENT_DESC, n.agent, id = "settings-notifications-agent") {
            SessionPrefs.setNotify(ctx, SessionPrefs.KEY_AGENT, it)
        }
        Divider()
        SwitchRow(SettingsCopy.NOTIFY_PERMISSIONS, SettingsCopy.NOTIFY_PERMISSIONS_DESC, n.permissions, id = "settings-notifications-permissions") {
            SessionPrefs.setNotify(ctx, SessionPrefs.KEY_PERMISSIONS, it)
        }
        Divider()
        SwitchRow(SettingsCopy.NOTIFY_QUESTIONS, SettingsCopy.NOTIFY_QUESTIONS_DESC, n.questions, id = "settings-notifications-questions") {
            SessionPrefs.setNotify(ctx, SessionPrefs.KEY_QUESTIONS, it)
        }
        Divider()
        SwitchRow(SettingsCopy.NOTIFY_ERRORS, SettingsCopy.NOTIFY_ERRORS_DESC, n.errors, id = "settings-notifications-errors") {
            SessionPrefs.setNotify(ctx, SessionPrefs.KEY_ERRORS, it)
        }
    }

    SectionLabel(SettingsCopy.SECTION_SOUNDS)
    // Their `dr()` in `shell-DLa9XmkV.js` @ 13 494: **one select per channel**, and `None` *is*
    // the off state. There is no separate switch - picking None calls `setEnabled(false)` and
    // `stop()`, picking a file calls `setEnabled(true)`, stores the id, and plays it.
    Card {
        SoundChannelRow(
            title = SettingsCopy.SOUND_AGENT,
            description = SettingsCopy.SOUND_AGENT_DESC,
            enabled = n.soundAgent,
            soundId = soundAgent,
            id = "settings-sounds-agent",
            onEnabled = { SessionPrefs.setNotify(ctx, SessionPrefs.KEY_SOUND_AGENT, it) },
            onId = { SessionPrefs.set(ctx, SessionPrefs.KEY_ID_AGENT, it) },
            preview = preview,
            onPick = onPick,
        )
        Divider()
        SoundChannelRow(
            title = SettingsCopy.SOUND_PERMISSIONS,
            description = SettingsCopy.SOUND_PERMISSIONS_DESC,
            enabled = n.soundPermissions,
            soundId = soundPerms,
            id = "settings-sounds-permissions",
            onEnabled = { SessionPrefs.setNotify(ctx, SessionPrefs.KEY_SOUND_PERMISSIONS, it) },
            onId = { SessionPrefs.set(ctx, SessionPrefs.KEY_ID_PERMISSIONS, it) },
            preview = preview,
            onPick = onPick,
        )
        Divider()
        SoundChannelRow(
            title = SettingsCopy.SOUND_QUESTIONS,
            description = SettingsCopy.SOUND_QUESTIONS_DESC,
            enabled = n.soundQuestions,
            soundId = soundQuestions,
            id = "settings-sounds-questions",
            onEnabled = { SessionPrefs.setNotify(ctx, SessionPrefs.KEY_SOUND_QUESTIONS, it) },
            onId = { SessionPrefs.set(ctx, SessionPrefs.KEY_ID_QUESTIONS, it) },
            preview = preview,
            onPick = onPick,
        )
        Divider()
        SoundChannelRow(
            title = SettingsCopy.SOUND_ERRORS,
            description = SettingsCopy.SOUND_ERRORS_DESC,
            enabled = n.soundErrors,
            soundId = soundErrors,
            id = "settings-sounds-errors",
            onEnabled = { SessionPrefs.setNotify(ctx, SessionPrefs.KEY_SOUND_ERRORS, it) },
            onId = { SessionPrefs.set(ctx, SessionPrefs.KEY_ID_ERRORS, it) },
            preview = preview,
            onPick = onPick,
        )
        Divider()
        // `Interface` has no id in their model - it is a plain toggle
        // (`settings/model.tsx:655`), so it keeps its switch. Their toggle gates the sounds the
        // wordmark makes; the phone draws no wordmark, so the switch is honest about it.
        SwitchRow(
            SettingsCopy.SOUND_INTERFACE,
            SettingsCopy.SOUND_INTERFACE_DESC,
            n.soundInterface,
            id = "settings-sounds-interface",
            note = SettingsCopy.NOT_USED,
        ) {
            SessionPrefs.setNotify(ctx, SessionPrefs.KEY_SOUND_INTERFACE, it)
        }
    }
}

/**
 * One sound channel - their `n(enabled, id, setEnabled, setId)` closure.
 *
 * `current` is `enabled ? find(id) ?? none : none`: a channel that is off shows **None** even
 * though it still remembers which file it would play. That is their behaviour, and it is why the
 * stored id is never cleared by picking None.
 */
@Composable
private fun SoundChannelRow(
    title: String,
    description: String,
    enabled: Boolean,
    soundId: String,
    id: String,
    onEnabled: (Boolean) -> Unit,
    onId: (String) -> Unit,
    preview: SoundPreviewController,
    onPick: (Picker) -> Unit,
) {
    val current = if (enabled) soundId else "none"
    ValueRow(
        title = title,
        description = description,
        value = SettingsCopy.soundLabel(current),
        id = id,
        onClick = {
            onPick(
                Picker(
                    title = title,
                    options = SettingsCopy.soundOptions,
                    selected = current,
                    // Their `highlight`: `t.play(e.id === "none" ? void 0 : e.id)`. `None` is not
                    // silent - it stops whatever was auditioned, so sliding onto it is how you
                    // quiet the list without choosing anything.
                    onHighlight = { picked -> preview.play(if (picked == "none") null else picked) },
                    onPick = { picked ->
                        if (picked == "none") {
                            // Their `select` for None: disable, then stop. The stored id stays.
                            onEnabled(false)
                            preview.stop()
                        } else {
                            onEnabled(true)
                            onId(picked)
                            preview.play(picked)
                        }
                    },
                ),
            )
        },
    )
}

// =========================================================================================
// Shortcuts - their groups and action titles, the phone's real gestures
// =========================================================================================

private fun androidx.compose.foundation.lazy.LazyListScope.shortcutsPage() {
    item { Note(SettingsCopy.GESTURES_DESCRIPTION) }
    SettingsModel.gestureGroups.forEach { group ->
        item { SectionLabel(group) }
        item {
            Card {
                val rows = SettingsModel.gesturesIn(group)
                rows.forEachIndexed { i, g ->
                    if (i > 0) Divider()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            g.action,
                            fontSize = 12.5.sp,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            g.gesture ?: SettingsCopy.UNASSIGNED,
                            fontSize = 11.5.sp,
                            color = if (g.gesture == null) TextDim else TextMid,
                        )
                    }
                }
            }
        }
    }
}

// =========================================================================================
// Projects
// =========================================================================================

private fun androidx.compose.foundation.lazy.LazyListScope.projectsPage(vm: MainViewModel) {
    item { ProjectsBody(vm) }
}

@Composable
private fun ProjectsBody(vm: MainViewModel) {
    val projects by vm.projectEntries.collectAsState()
    val loading by vm.settingsLoading.collectAsState()
    val error by vm.settingsError.collectAsState()

    androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadSettingsData() }

    SectionLabel(SettingsCopy.PROJECTS_TITLE)
    Card {
        when {
            error.isNotBlank() -> TextRow(SettingsCopy.PROJECTS_TITLE, error)
            loading && projects.isEmpty() -> TextRow(SettingsCopy.PROJECTS_TITLE, SettingsCopy.LOADING)
            projects.isEmpty() -> EmptyCard(SettingsCopy.NO_PROJECTS_YET, SettingsCopy.NO_PROJECTS_DESC)
            else -> projects.forEachIndexed { i, p ->
                if (i > 0) Divider()
                TextRow(
                    title = p.name ?: p.directory ?: p.id,
                    description = p.directory ?: p.id,
                )
            }
        }
    }
    if (projects.isEmpty() && !loading) {
        Spacer(Modifier.height(2.dp))
        Note(SettingsCopy.PROJECTS_NOTE)
    }
}

// =========================================================================================
// Worktrees - `settings.tab.workspaces`
// =========================================================================================

private fun androidx.compose.foundation.lazy.LazyListScope.worktreesPage(vm: MainViewModel) {
    item { WorktreesBody(vm) }
}

@Composable
private fun WorktreesBody(vm: MainViewModel) {
    val raw by vm.worktreeRaw.collectAsState()
    val entries = remember(raw) { WorktreeList.parse(raw) }

    SectionLabel(SettingsCopy.WORKTREES_TITLE)
    Card {
        if (entries.isEmpty()) {
            EmptyCard(SettingsCopy.NO_WORKTREES, SettingsCopy.NO_WORKTREES_DESC)
        } else {
            entries.forEachIndexed { i, w ->
                if (i > 0) Divider()
                TextRow(title = w.name, description = w.directory)
            }
        }
    }
    Spacer(Modifier.height(2.dp))
    Note(SettingsCopy.WORKTREES_NOTE)
}

/** One row of `GET /api/worktree`, parsed leniently - a build that omits a field must not throw. */
data class WorktreeRow(val name: String, val directory: String)

object WorktreeList {
    fun parse(raw: String): List<WorktreeRow> {
        if (raw.isBlank()) return emptyList()
        val arr = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw)
        }.getOrNull() as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val dir = s("directory") ?: s("path") ?: return@mapNotNull null
            WorktreeRow(name = s("name") ?: dir.substringAfterLast('/').ifBlank { dir }, directory = dir)
        }
    }
}

// =========================================================================================
// Providers
// =========================================================================================

private fun androidx.compose.foundation.lazy.LazyListScope.providersPage(vm: MainViewModel) {
    item { ProvidersBody(vm) }
}

@Composable
private fun ProvidersBody(vm: MainViewModel) {
    val providers by vm.providers.collectAsState()
    val models by vm.models.collectAsState()

    SectionLabel(SettingsCopy.PROVIDERS_CONNECTED)
    Card {
        if (providers.isEmpty()) {
            EmptyCard(SettingsCopy.PROVIDERS_EMPTY, SettingsCopy.PROVIDERS_EMPTY_DESC)
        } else {
            providers.forEachIndexed { i, p ->
                if (i > 0) Divider()
                val count = models.count { it.providerID == p.id }
                TextRow(
                    title = p.name,
                    description = if (count == 1) "1 model" else "$count models",
                )
            }
        }
    }
    Spacer(Modifier.height(2.dp))
    Note(SettingsCopy.PROVIDERS_NOTE)
}

// =========================================================================================
// Models - enable / disable, stored and honoured by the picker filter
// =========================================================================================

private fun androidx.compose.foundation.lazy.LazyListScope.modelsPage(vm: MainViewModel) {
    item { ModelsBody(vm) }
}

@Composable
private fun ModelsBody(vm: MainViewModel) {
    val ctx = LocalContext.current
    val version by SettingsPrefs.version.collectAsState()
    val all by vm.catalogModels.collectAsState()
    val providers by vm.providers.collectAsState()
    val disabled = remember(version) { SettingsPrefs.disabledModels(ctx) }
    val locked = remember(version) { SettingsPrefs.lockedModel(ctx) }
    val grouped = remember(all, providers) {
        all.groupBy { it.providerID }.toList().sortedBy { (id, _) ->
            providers.firstOrNull { it.id == id }?.name ?: id
        }
    }

    if (all.isEmpty()) {
        SectionLabel(SettingsCopy.MODELS_TITLE)
        Card { EmptyCard(SettingsCopy.MODELS_TITLE, SettingsCopy.MODELS_EMPTY) }
        return
    }

    SectionLabel(SettingsCopy.MODELS_TITLE)
    // the owner, 2026-11-24: one model only. The lock is enforced by the same store these
    // switches write, so the page must say it is on - otherwise every row reads "off" and
    // the list looks broken instead of deliberate.
    if (locked.isNotBlank()) {
        Card {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Locked to one model", fontSize = 12.5.sp, color = TextPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(locked, fontSize = 10.5.sp, color = TextMid)
                }
                SmallButton("Unlock") {
                    SettingsPrefs.unlockModel(ctx)
                    vm.loadPickers()
                }
            }
        }
    }
    Card {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallButton(SettingsCopy.ENABLE_ALL) {
                SettingsPrefs.setAllModelsEnabled(ctx, all.map { it.modelID }, true)
            }
            SmallButton(SettingsCopy.DISABLE_ALL) {
                SettingsPrefs.setAllModelsEnabled(ctx, all.map { it.modelID }, false)
            }
        }
    }

    grouped.forEach { (pid, list) ->
        SectionLabel(providers.firstOrNull { it.id == pid }?.name ?: pid)
        Card {
            list.forEachIndexed { i, m ->
                if (i > 0) Divider()
                SwitchRow(
                    title = m.name.ifBlank { m.modelID },
                    description = m.modelID,
                    checked = m.modelID !in disabled,
                    onChange = { SettingsPrefs.setModelEnabled(ctx, m.modelID, it) },
                )
            }
        }
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Layer2)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 11.5.sp, color = TextPrimary)
    }
}

// =========================================================================================
// Extensions - MCPs / Plugins / Skills subtabs (`settings.extensions.tab.*`)
// =========================================================================================

private fun androidx.compose.foundation.lazy.LazyListScope.extensionsPage(vm: MainViewModel) {
    item { ExtensionsBody(vm) }
}

private enum class ExtTab(val title: String) {
    MCPS(SettingsCopy.MCPS),
    PLUGINS(SettingsCopy.PLUGINS),
    SKILLS(SettingsCopy.SKILLS),
}

@Composable
private fun ExtensionsBody(vm: MainViewModel) {
    var tab by remember { mutableStateOf(ExtTab.MCPS) }
    val mcps by vm.mcpEntries.collectAsState()
    val plugins by vm.pluginEntries.collectAsState()
    val skills by vm.skillEntries.collectAsState()

    // Their extensions page reads the lists on mount; ours must ask for them or the subtabs
    // would show the empty state for a server that does have MCPs and plugins.
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadExtensions() }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ExtTab.entries.forEach { t ->
            val on = t == tab
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) Layer2 else Color.Transparent)
                    .border(0.5.dp, if (on) Color.Transparent else Border, RoundedCornerShape(8.dp))
                    .clickable { tab = t }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    t.title,
                    fontSize = 12.sp,
                    color = if (on) TextPrimary else TextMid,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }

    when (tab) {
        ExtTab.MCPS -> {
            SectionLabel(SettingsCopy.MCPS)
            Card {
                if (mcps.isEmpty()) {
                    EmptyCard(SettingsCopy.MCPS, SettingsCopy.NO_MCPS)
                } else {
                    mcps.forEachIndexed { i, m ->
                        if (i > 0) Divider()
                        SwitchRow(
                            title = m.name,
                            description = m.stateLabel,
                            checked = m.connected,
                            onChange = { on -> vm.toggleMcp(m.name, on) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Note(SettingsCopy.MANAGE_CONFIG)
        }

        ExtTab.PLUGINS -> {
            SectionLabel(SettingsCopy.PLUGINS)
            Card {
                if (plugins.isEmpty()) {
                    EmptyCard(SettingsCopy.PLUGINS, SettingsCopy.NO_PLUGINS)
                } else {
                    plugins.forEachIndexed { i, p ->
                        if (i > 0) Divider()
                        val outdated = p.source?.outdated == true
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.id, fontSize = 13.sp, color = TextPrimary)
                                Text(
                                    if (p.sourceType == "package") p.source?.target ?: p.sourceType else p.sourceType,
                                    fontSize = 11.5.sp,
                                    color = TextMid,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            if (outdated) {
                                SmallButton(SettingsCopy.UPDATE) {
                                    vm.updatePlugin(p.source?.target ?: p.id)
                                }
                            } else if (!p.active) {
                                Text(SettingsCopy.BUILT_IN, fontSize = 11.sp, color = TextFaint)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(SettingsCopy.CHECK_UPDATES) { vm.checkPlugins() }
                SmallButton(SettingsCopy.RELOAD) { vm.loadExtensions() }
            }
        }

        ExtTab.SKILLS -> {
            SectionLabel(SettingsCopy.AVAILABLE_ALL)
            Card {
                if (skills.isEmpty()) {
                    EmptyCard(SettingsCopy.SKILLS, SettingsCopy.NO_SKILLS)
                } else {
                    skills.forEachIndexed { i, s ->
                        if (i > 0) Divider()
                        TextRow(title = s.name.ifBlank { s.id }, description = s.description ?: "")
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Note(SettingsCopy.MANAGE_CONFIG)
        }
    }
}

// =========================================================================================
// Servers - `settings.tab.servers`
// =========================================================================================

private fun androidx.compose.foundation.lazy.LazyListScope.serversPage(vm: MainViewModel) {
    item { ServersBody(vm) }
}

@Composable
private fun ServersBody(vm: MainViewModel) {
    val connected by vm.connected.collectAsState()
    val connecting by vm.connecting.collectAsState()
    val booting by vm.booting.collectAsState()
    val gate by vm.gate.collectAsState()
    val base by vm.serverBase.collectAsState()

    // Their `resolveServerAvailability`: one value every row reads, so the dot, the word and the
    // Retry button cannot disagree about the same server.
    val availability = ServerAvailabilityMachine.resolve(
        connection = base.ifBlank { null },
        health = ServerAvailabilityMachine.healthOf(connected, connecting || booting, gate),
    )
    val health = ServerAvailabilityMachine.selectHealth(availability)
    val ready = availability is ServerAvailability.Ready
    val unreachable = ServerAvailabilityMachine.isUnreachable(availability)

    // Their Retry fence: one attempt at a time, fenced to the server it was issued for, 35 s long.
    var retry by remember { mutableStateOf(ServerAvailabilityMachine.initialRetryState(base)) }
    val scope = rememberCoroutineScope()
    fun dispatch(event: ServerAvailabilityMachine.RetryEvent) {
        retry = ServerAvailabilityMachine.transition(retry, event).state
    }
    LaunchedEffect(base) {
        dispatch(ServerAvailabilityMachine.RetryEvent.ServerSynced(base))
    }
    val retrying = ServerAvailabilityMachine.isRetrying(retry)

    SectionLabel(SettingsCopy.CONNECTION)
    Card {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(
                        when (health) {
                            ServerHealth.Healthy, ServerHealth.Checking -> Accent
                            ServerHealth.Starting, null -> TextFaint
                            else -> Danger
                        },
                    ),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    SettingsCopy.SERVER_LABEL,
                    fontSize = 13.sp,
                    color = TextPrimary,
                )
                Text(
                    base,
                    fontSize = 11.5.sp,
                    color = TextMid,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // Their bundle has no word for the healthy state; ours is the one this page already
            // showed. `starting` has no key either, so it gets the dot and no word rather than an
            // invented one.
            if (ready || unreachable) {
                Text(
                    if (ready) SettingsCopy.CONNECTED else SettingsCopy.UNREACHABLE,
                    fontSize = 11.5.sp,
                    color = if (ready) TextMid else Danger,
                )
            }
        }
        Divider()
        NavRow(
            title = SettingsCopy.RETRY,
            description = SettingsCopy.SERVER_RETRY_DESC,
            enabled = !retrying,
            onClick = {
                val t = ServerAvailabilityMachine.transition(
                    retry, ServerAvailabilityMachine.RetryEvent.RetryClicked,
                )
                retry = t.state
                val run = t.effects.filterIsInstance<ServerAvailabilityMachine.RetryEffect.RetryRun>()
                    .firstOrNull() ?: return@NavRow
                scope.launch {
                    val job = vm.retryConnection()
                    val timeout = launch {
                        delay(run.timeoutMs)
                        dispatch(ServerAvailabilityMachine.RetryEvent.RetryTimedOut(run.attempt))
                    }
                    job.join()
                    timeout.cancel()
                    dispatch(ServerAvailabilityMachine.RetryEvent.RetrySettled(run.attempt))
                }
            },
        )
    }

    // Their `settings.server.unreachable` block, only while the server really is unavailable.
    if (unreachable) {
        Spacer(Modifier.height(2.dp))
        Card {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    SettingsCopy.serverUnreachableTitle(base),
                    fontSize = 13.sp,
                    color = TextPrimary,
                )
                Text(
                    SettingsCopy.serverUnreachableDesc(base),
                    fontSize = 11.5.sp,
                    color = TextMid,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }

    Spacer(Modifier.height(2.dp))
    Note(SettingsCopy.SERVERS_NOTE)
}

// =========================================================================================
// Pairing - this phone is the paired device; the token is the credential
// =========================================================================================

private fun androidx.compose.foundation.lazy.LazyListScope.pairingPage(vm: MainViewModel) {
    item { PairingBody(vm) }
}

@Composable
private fun PairingBody(vm: MainViewModel) {
    val base by vm.serverBase.collectAsState()
    val connected by vm.connected.collectAsState()

    SectionLabel(SettingsCopy.PAIRING_TITLE)
    Card {
        TextRow(
            title = SettingsCopy.PAIRED_DEVICE,
            description = SettingsCopy.PAIRED_DEVICE_DESC,
        )
        Divider()
        TextRow(
            title = SettingsCopy.PAIRING_SERVER,
            description = base,
        )
        Divider()
        TextRow(
            title = SettingsCopy.PAIRING_STATE,
            description = if (connected) SettingsCopy.CONNECTED else SettingsCopy.UNREACHABLE,
        )
    }
    Spacer(Modifier.height(2.dp))
    Note(SettingsCopy.PAIRING_NOTE)
}

// =========================================================================================
// Experimental - `settings.tab.experimental`
// =========================================================================================

private fun androidx.compose.foundation.lazy.LazyListScope.experimentalPage() {
    item { ExperimentalBody() }
}

@Composable
private fun ExperimentalBody() {
    val ctx = LocalContext.current

    SectionLabel(SettingsCopy.EXPERIMENTAL_TITLE)
    Card {
        SwitchRow(
            title = SettingsCopy.NEW_TIMELINE,
            id = "settings-new-timeline",
            description = SettingsCopy.NEW_TIMELINE_DESC,
            checked = SettingsPrefs.bool(ctx, SettingsPrefs.KEY_NEW_TIMELINE, false),
            note = SettingsCopy.NOT_USED,
            onChange = { SettingsPrefs.set(ctx, SettingsPrefs.KEY_NEW_TIMELINE, it) },
        )
        Divider()
        SwitchRow(
            title = SettingsCopy.NEW_QUESTION_UI,
            id = "settings-new-question-ui",
            description = SettingsCopy.NEW_QUESTION_UI_DESC,
            checked = SettingsPrefs.bool(ctx, SettingsPrefs.KEY_NEW_QUESTION_UI, false),
            note = SettingsCopy.NOT_USED,
            onChange = { SettingsPrefs.set(ctx, SettingsPrefs.KEY_NEW_QUESTION_UI, it) },
        )
        Divider()
        ChoiceRow(
            title = SettingsCopy.SIDEBAR_ORGANIZE,
            id = "settings-sidebar-organize",
            description = SettingsCopy.SIDEBAR_ORGANIZE_DESC,
            options = listOf(SettingsCopy.ONE_LIST, SettingsCopy.RECENT, SettingsCopy.CUSTOMIZE),
            selected = sidebarLabel(
                SettingsPrefs.string(ctx, SettingsPrefs.KEY_SIDEBAR_ORGANIZE, SettingsPrefs.DEFAULT_SIDEBAR_ORGANIZE),
            ),
            note = SettingsCopy.NOT_USED,
            onSelect = { SettingsPrefs.set(ctx, SettingsPrefs.KEY_SIDEBAR_ORGANIZE, it.lowercase()) },
        )
    }
}

internal fun sidebarLabel(id: String): String = when (id) {
    "recent" -> SettingsCopy.RECENT
    "customize" -> SettingsCopy.CUSTOMIZE
    else -> SettingsCopy.ONE_LIST
}

// =========================================================================================
// About - `settings.tab.about`
// =========================================================================================

private fun androidx.compose.foundation.lazy.LazyListScope.aboutPage(vm: MainViewModel) {
    item { AboutBody() }
}

@Composable
private fun AboutBody() {
    val ctx = LocalContext.current
    val version = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "0.1"
    }

    SectionLabel(SettingsCopy.ABOUT_TITLE)
    Card {
        TextRow(SettingsCopy.APP_NAME, SettingsCopy.version(version))
        Divider()
        TextRow(SettingsCopy.ABOUT_DESCRIPTION, SettingsCopy.ABOUT_TAGLINE)
        Divider()
        TextRow(SettingsCopy.LICENSE, SettingsCopy.ATTRIBUTION)
        Divider()
        TextRow(SettingsCopy.ABOUT_TYPESET, SettingsCopy.ABOUT_PUBLISHED)
    }

    SectionLabel(SettingsCopy.ABOUT_WEBSITE)
    Card {
        TextRow(SettingsCopy.ABOUT_WEBSITE, SettingsCopy.ABOUT_TRADEMARK)
        Divider()
        TextRow(SettingsCopy.ABOUT_COPYRIGHT, "")
    }
}

