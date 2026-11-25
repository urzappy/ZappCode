package com.zapp.net.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.ui.theme.Border
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.Surface
import com.zapp.net.ui.theme.Surface2
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextMid
import com.zapp.net.ui.theme.TextPrimary
import kotlinx.coroutines.delay

/**
 * A real tab in the side pane.
 *
 * Their model keeps two lists. `SidePanel.State.panes[].ids` holds **placeholder** tabs
 * (`new:<serial>`) that a `+` makes and the four-item list fills; the **registry** holds the real
 * ones (`review`, `browser:<n>`, `terminal:<n>`, `file:open:<path>`), which never enter `ids`.
 * This is the registry.
 */
data class PaneTab(
    /** Their tab key. A file tab is `file:open:<path>`, so reopening restores the same tab. */
    val key: String,
    val kind: SidePanel.Kind,
    val title: String,
    /** The truncating second half of a `Recently closed` row. */
    val detail: String? = null,
    val icon: ImageVector,
    val pinned: Boolean = false,
)

/**
 * `#review-panel`'s tab strip and content slot.
 *
 * Metrics are theirs, from `index-CbZCBGB7.css`. The selector that actually applies is the
 * **`.session-review-v2-tabs-bar`** one, which outranks the plain rule:
 *
 * ```
 * :root body :is(#review-panel,#bottom-panel) [data-component=tabs][data-variant=normal]
 *            [data-orientation=horizontal] [data-slot=tabs-list] {
 *   --tabs-review-fade: 8px; gap: 8px; height: 52px; padding-inline-end: 12px
 * }
 * ... .session-review-v2-tabs-bar [data-slot=tabs-list] {
 *   height: var(--tabs-bar-height)   // 48px
 * }
 * ... > :not(.sticky) { flex: 1 1 0; min-width: 72px; max-width: max-content }
 * ```
 *
 * So the real bar is **48px tall, gap 8px, padding-inline 12px / 12px**, and every tab flexes
 * equally but never wider than its own content and never narrower than 72px.
 *
 * The pill, from `[data-slot=tabs-trigger-wrapper]`:
 *
 * ```
 * height: var(--tabs-compact-pill-height)   // 24px
 * max-width: 320px; padding-inline: 4px; box-sizing: border-box
 * border-radius: 6px; gap: 8px; color: --text-weak
 * background: #0000; border: 1px solid #0000
 * ```
 * and the selected look is a filled pill with **no** underline:
 *
 * ```
 * :root body :is(#review-panel,#bottom-panel) ... :has([data-selected]) {
 *   background-color: var(--v2-background-bg-layer-02)   // #2e2e2e
 * }
 * ... [data-slot=tabs-trigger-wrapper]:after { display: none }
 * ```
 *
 * Their surfaces are `#151515` / `#101010`; ours are darker by the owner's order, so the bar takes
 * `Surface` and the selected pill `Surface2`. The text steps are theirs: unselected
 * `--v2-text-text-muted` (#aeaeae, our `TextMid`), selected `--v2-text-text-base` (#fafafa, our
 * `TextPrimary`) while the panel holds focus, and back to muted when it does not.
 */
@Composable
fun SidePane(
    tabs: List<PaneTab>,
    activeKey: String?,
    /** A `new:<serial>` placeholder is chosen, so the launcher is drawn over the registry. */
    placeholder: Boolean,
    /** `data-focused` - the panel holds focus, so the selected tab's title brightens. */
    focused: Boolean,
    recent: List<SidePanel.RecentEntry>,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onOpen: (SidePanel.Kind) -> Unit,
    onReopen: (SidePanel.RecentEntry) -> Unit,
    /** `open.new-tab` - a `+` makes an empty tab, which is drawn as the four-item launcher. */
    onEmptyTab: () -> Unit,
    onClosePane: () -> Unit,
    content: @Composable (PaneTab) -> Unit,
) {
    // The pane is drawn OVER the chat, not beside it (320dp of a 408dp-wide phone cannot split).
    // A `background` does not take a hit, so every tap or drag on an empty part of the pane -
    // the launcher's margins, the gaps between the tab strip's chips - reached the message list
    // underneath: the chat scrolled behind the pane and taps opened chat rows.
    //
    // the owner, 2026-11-24: *"I CANT SCROLL TRU IT THE BACKGROUND YUNG MA TAP KO"*, on the panel
    // the right-hand header button opens.
    //
    // The old shape put a consume-everything `pointerInput` on the pane's ROOT Column. That
    // stopped the chat behind it, but it also sat above every child the pane owns, so it ate the
    // events the children needed: the owner, 2026-11-24 - *"YUNG BROWSER HINDI AKO MAKA SCROLL HINDI
    // DIN MAKA PINDOT"*, and the tab strip would not drag. A parent that consumes in the main
    // pass kills `AndroidView` interop outright, which is exactly what a WebView is.
    //
    // The blocker is now a SIBLING that lies *behind* the pane's content instead of above it:
    // the content is hit first and keeps every gesture, and only a touch that lands on nothing
    // falls through to the blocker, which swallows it so it cannot reach the transcript.
    Box(Modifier.fillMaxSize().background(Surface)) {
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            for (i in e.changes.indices) e.changes[i].consume()
                        }
                    }
                },
        )
        Column(Modifier.fillMaxSize()) {
            TabStrip(
                tabs = tabs,
                activeKey = activeKey,
                placeholder = placeholder,
                focused = focused,
                recent = recent,
                onSelect = onSelect,
                onClose = onClose,
                onOpen = onOpen,
                onReopen = onReopen,
                onEmptyTab = onEmptyTab,
                onClosePane = onClosePane,
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val active = tabs.firstOrNull { it.key == activeKey }
                // `Wa.Content value={shownNewTab ?? pane.active() ?? "empty"}`: the placeholder
                // wins, then the active registry tab, then the "select a file" state.
                when {
                    placeholder -> EmptyLauncher(recent = recent, onOpen = onOpen, onReopen = onReopen)
                    active != null -> content(active)
                    else -> EmptyPane()
                }
            }
        }
    }
}

/**
 * `PT` - `route-CMUQLRzL.js` @ 416 000:
 *
 * ```
 * <div class="relative pt-2 flex-1 min-h-0 overflow-hidden">
 *   <div class="h-full px-6 pb-42 -mt-4 flex flex-col items-center justify-center
 *               text-center gap-6">
 *     <div class="text-14-regular text-text-weak max-w-56">
 * ```
 *
 * `pb-42` is 168px of bottom padding, which lifts the block above the centre line; `-mt-4` pulls
 * it back up another 16px.
 */
@Composable
private fun EmptyPane() {
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 168.dp)
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Icon(
                OcIcons.FileDocument,
                null,
                tint = TextPrimary,
                modifier = Modifier.size(56.dp).alpha(0.10f),
            )
            Text(
                "Select a file to open",
                fontSize = 14.sp,
                color = TextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 224.dp),
            )
        }
    }
}

@Composable
private fun TabStrip(
    tabs: List<PaneTab>,
    activeKey: String?,
    placeholder: Boolean,
    focused: Boolean,
    recent: List<SidePanel.RecentEntry>,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onOpen: (SidePanel.Kind) -> Unit,
    onReopen: (SidePanel.RecentEntry) -> Unit,
    onEmptyTab: () -> Unit,
    onClosePane: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().background(Surface)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(start = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                // `fill = false`: with one or two tabs the row is only as wide as its pills, so
                // the `+` sits immediately after the last tab - the shape in their own panel.
                Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tabs.forEach { tab ->
                    TabPill(
                        title = tab.title,
                        icon = tab.icon,
                        selected = !placeholder && tab.key == activeKey,
                        focused = focused,
                        onSelect = { onSelect(tab.key) },
                        onClose = { onClose(tab.key) },
                    )
                }
                // Their `ids` holds `new:<serial>` placeholders beside the registry, so a chosen
                // placeholder draws its own pill - titled `session.tab.new` = "New tab" - even when
                // the registry is empty.
                if (placeholder) {
                    TabPill(
                        title = "New tab",
                        icon = OcIcons.Plus,
                        selected = true,
                        focused = focused,
                        onSelect = {},
                        onClose = onClosePane,
                    )
                }
            }
            // Their `[tabs-list]` has `gap: 8px` across every child, the `+` included.
            Spacer(Modifier.width(8.dp))
            // `jT` - `<div class="h-full shrink-0 sticky end-0 z-10 flex items-center
            // justify-center bg-v2-background-bg-base">`, a sticky end cap **inside** the strip.
            PlusButton(recent = recent, onReopen = onReopen, onEmptyTab = onEmptyTab)
        }
        // `.session-review-v2-tabs-bar` carries `border-bottom: .5px` of `--v2-border-border-base`.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(Line),
        )
    }
}

/**
 * One pill. Their `[data-slot=tabs-trigger-wrapper]`: 24px tall, 6px radius, 4px inline padding
 * (5px at the end once a close button is present), 8px gap, 320px cap.
 *
 * The selected look is **only** a filled pill. Their rules, in the order that wins:
 *
 * ```
 * :root body :is(#review-panel,#bottom-panel) ... :has([data-selected]) {
 *   background-color: var(--v2-background-bg-layer-02);   // #2e2e2e
 *   box-shadow: none;
 * }
 * ... [data-slot=tabs-trigger-wrapper]:after { display: none; }   // no underline
 * ... [data-slot=tabs-trigger] {
 *   padding: 0 !important; font-size: 13px; font-weight: 440; line-height: 16px;
 *   letter-spacing: -.04px; color: var(--v2-text-text-muted);     // #aeaeae
 * }
 * ... [data-slot=tabs-trigger][data-selected] {
 *   color: var(--v2-text-text-base);                             // #fafafa
 * }
 * #review-panel:not([data-focused]) ... [data-slot=tabs-trigger][data-selected] {
 *   color: var(--v2-text-text-muted);                            // #aeaeae
 * }
 * ```
 *
 * So there is no underline at all in the side region, and an unfocused selected tab is the same
 * text colour as an unselected one - the pill fill is the whole signal. `focused` is the phone
 * stand-in for their `data-focused` (DOM focus inside the panel): true while he is working in the
 * pane, false once he is typing in the composer.
 */
@Composable
private fun TabPill(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    focused: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val text = when {
        selected && focused -> TextPrimary
        else -> TextMid
    }
    Box(Modifier.height(48.dp)) {
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .height(24.dp)
                .widthIn(min = 72.dp, max = 320.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) Surface2 else Color.Transparent)
                .then(
                    if (selected) {
                        Modifier.border(1.dp, Border, RoundedCornerShape(6.dp))
                    } else {
                        Modifier
                    }
                )
                .padding(start = 4.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onSelect() }
                    .padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(icon, null, tint = text, modifier = Modifier.size(14.dp))
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight(440),
                    letterSpacing = (-0.04).sp,
                    color = text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(OcIcons.Close, "Close tab", tint = TextDim, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/**
 * `ET` - `route-CMUQLRzL.js` @ 413 315, the `+`.
 *
 * ```
 * data-slot=side-new-tab, variant ghost-muted, size large, aria-haspopup=menu
 * placement: bottom-start, gutter: 4
 * onOpenChange  -> {type:"open.change", open, available: entries.length > 0, defaultIntent:"new"}
 * onPointerDown -> {type:"arm", intent: c() || modifierHeld ? "menu" : "new"}
 * onPointerEnter/Leave -> {type:"hover"}
 * onContextMenu -> {type:"context.menu", available: entries.length > 0}
 * Content.onCloseAutoFocus -> {type:"close.autofocus"}
 * ```
 *
 * `c = plusOpen(state) || (hasRecent && modifierHeld && (global || hover))`, and it decides both
 * the icon (`chevron-down` vs `plus`) and the label (`Recently closed` vs `Add tab`).
 *
 * A phone has no modifier key, so `c` is `plusOpen` alone and the long-press stands in for
 * `context.menu` - the same event their right-click dispatches.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlusButton(
    recent: List<SidePanel.RecentEntry>,
    onReopen: (SidePanel.RecentEntry) -> Unit,
    onEmptyTab: () -> Unit,
) {
    var state by remember { mutableStateOf(SidePanel.PlusState()) }
    // `timer.start` -> `timer.fired`. `close.autofocus` restarts it at 0.
    var pending by remember { mutableStateOf<Pair<Int, Long>?>(null) }

    val available = recent.isNotEmpty()
    val open = SidePanel.plusOpen(state)

    fun dispatch(event: SidePanel.PlusEvent) {
        val (next, effects) = SidePanel.plusPopover(state, event)
        state = next
        for (fx in effects) {
            when (fx) {
                is SidePanel.PlusEffect.OpenNewTab -> onEmptyTab()
                is SidePanel.PlusEffect.TimerStart -> pending = fx.attempt to fx.delayMs
                is SidePanel.PlusEffect.TimerStop -> pending = null
                is SidePanel.PlusEffect.RunChoice -> {
                    val key = fx.choice
                    if (key != null) recent.firstOrNull { it.key == key }?.let(onReopen)
                }
            }
        }
    }

    LaunchedEffect(pending) {
        val (attempt, wait) = pending ?: return@LaunchedEffect
        if (wait > 0) delay(wait)
        pending = null
        dispatch(SidePanel.PlusEvent.TimerFired(attempt))
    }

    // Their popover machine has a terminal `disposed` state: once the `+` leaves the tree, every
    // later event is inert. `sT` also stops a close timer that was still running.
    DisposableEffect(Unit) {
        onDispose { dispatch(SidePanel.PlusEvent.Disposed) }
    }

    Box {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                // One control, two gestures: a tap arms `new`, a long press arms `menu`.
                .combinedClickable(
                    onClick = {
                        dispatch(SidePanel.PlusEvent.Arm(SidePanel.Intent.NEW))
                        dispatch(
                            SidePanel.PlusEvent.OpenChange(
                                open = true,
                                available = available,
                                defaultIntent = SidePanel.Intent.NEW,
                            )
                        )
                    },
                    onLongClick = {
                        dispatch(SidePanel.PlusEvent.Arm(SidePanel.Intent.MENU))
                        dispatch(SidePanel.PlusEvent.ContextMenu(available = available))
                    },
                )
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (open) OcIcons.ChevronDown else OcIcons.Plus,
                if (open) "Recently closed" else "Add tab",
                tint = TextMid,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = {
                dispatch(SidePanel.PlusEvent.OpenChange(open = false, available = available))
            },
            containerColor = Surface2,
        ) {
            // `CT` - the popover renders the **palette** group, not the launcher's section:
            // `Recently closed` label, up to `vT = 10` rows, `No results found` when empty.
            RecentlyClosedPalette(
                recent = recent,
                // A chosen row does not close the menu: it goes to `closing` for 50 ms, then
                // `timer.fired` runs `run.choice`.
                onReopen = { entry -> dispatch(SidePanel.PlusEvent.Select(entry.key)) },
            )
        }
    }
}

/**
 * `ST` - the `Recently closed` **section** under the four launcher entries, `route-CMUQLRzL.js`
 * @ 410 800:
 *
 * ```
 * <section data-slot=side-recently-closed class="flex flex-col gap-1 pt-6">
 *   <h2 id=... class="px-2 pb-1 text-[13px] font-[440] text-v2-text-text-muted">Recently closed</h2>
 *   <button data-slot=side-recently-closed-row
 *           class="flex h-8 w-full shrink-0 items-center justify-between gap-4 rounded-[6px] px-2
 *                  text-start text-[13px] font-[440] hover:bg-v2-background-bg-layer-02">
 *     <span class="flex min-w-0 items-center gap-1.5">
 *       <icon class="shrink-0 text-v2-icon-icon-muted">
 *       <span class="min-w-0 shrink truncate text-v2-text-text-base">      // title
 *       <span class="min-w-0 truncate text-v2-text-text-muted">            // detail
 *     <keybind chip>                                                       // first row only
 *   <button data-slot=side-recently-closed-more
 *           class="flex h-8 w-fit items-center gap-1 rounded-[6px] px-2 text-[13px] font-[440]
 *                  text-v2-text-text-muted hover:bg-v2-background-bg-layer-02
 *                  hover:text-v2-text-text-base">Show more <chevron-down>
 * ```
 *
 * `yT = 5` rows before `Show more`. The whole section is wrapped in a `Show when entries > 0`,
 * so an empty list draws **nothing** here - `No results found` is the palette's fallback, not
 * this one. The keybind chip on the first row has no phone equivalent.
 */
@Composable
private fun RecentlyClosedSection(
    recent: List<SidePanel.RecentEntry>,
    expanded: Boolean,
    onShowAll: () -> Unit,
    onReopen: (SidePanel.RecentEntry) -> Unit,
) {
    if (recent.isEmpty()) return
    val shown = if (expanded) recent else recent.take(SidePanel.RECENT_SHOWN)
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Recently closed",
            fontSize = 13.sp,
            fontWeight = FontWeight(440),
            color = TextMid,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
        )
        shown.forEach { entry ->
            ClosedRow(entry = entry, onClick = { onReopen(entry) })
        }
        if (!expanded && recent.size > SidePanel.RECENT_SHOWN) {
            Row(
                Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onShowAll() }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Show more", fontSize = 13.sp, fontWeight = FontWeight(440), color = TextMid)
                Icon(
                    OcIcons.ChevronDown,
                    null,
                    tint = TextMid,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * `CT` - the same list as a **palette group**, which is what the `+` popover actually renders:
 *
 * ```
 * Q.Group {
 *   Q.GroupLabel -> t('home.recentlyClosed')
 *   Show when entries.length > 0, fallback: <div class="px-2 py-1.5 text-[13px] text-v2-text-text-muted">
 *     t('palette.empty')                                    // "No results found"
 *   For entries.slice(0, vT) {                              // 10, no Show more
 *     Q.Item class="group/closed-tab !gap-6 max-w-80" onSelect -> onReopen(key)
 *   }
 * }
 * ```
 */
@Composable
private fun RecentlyClosedPalette(
    recent: List<SidePanel.RecentEntry>,
    onReopen: (SidePanel.RecentEntry) -> Unit,
) {
    Column(Modifier.widthIn(min = 256.dp)) {
        Text(
            "Recently closed",
            fontSize = 13.sp,
            fontWeight = FontWeight(440),
            color = TextMid,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        )
        if (recent.isEmpty()) {
            Text(
                // `palette.empty`
                "No results found",
                fontSize = 13.sp,
                color = TextMid,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        recent.take(SidePanel.RECENT_IN_PALETTE).forEach { entry ->
            ClosedRow(entry = entry, onClick = { onReopen(entry) })
        }
    }
}

/** One `side-recently-closed-row`: icon, title, detail, in that order, all truncating. */
@Composable
private fun ClosedRow(entry: SidePanel.RecentEntry, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(OcIcons.FileDocument, null, tint = TextDim, modifier = Modifier.size(14.dp))
            Text(
                entry.title,
                fontSize = 13.sp,
                fontWeight = FontWeight(440),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            entry.detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    fontWeight = FontWeight(440),
                    color = TextMid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

/**
 * The new-tab content. Their `LT` / `GT` / `KT`:
 *
 * ```
 * <div class="flex-1 min-h-0 overflow-y-auto px-6">
 *   <div class="mx-auto flex max-w-[560px] flex-col gap-3 pt-12 pb-10">
 *     <button class="flex h-[41px] w-full items-center justify-between gap-4 rounded-[8px]
 *                    border-[0.5px] border-v2-border-border-base bg-v2-background-bg-layer-01
 *                    px-3.5 text-start hover:bg-v2-background-bg-layer-02">
 *       <span class="flex min-w-0 items-center gap-2 text-[13px] font-[440]">
 *         <icon class="shrink-0 text-v2-text-text-base">
 *         <span class="truncate text-v2-text-text-muted">
 * ```
 *
 * The four entries are `session.panel`'s registrations, in their order: Review 10, Browser 15,
 * Terminal 20, Open file 30.
 */
@Composable
private fun EmptyLauncher(
    recent: List<SidePanel.RecentEntry>,
    onOpen: (SidePanel.Kind) -> Unit,
    onReopen: (SidePanel.RecentEntry) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .padding(top = 48.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SidePanel.MENU.forEach { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(41.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surface2)
                        .border(0.5.dp, Border, RoundedCornerShape(8.dp))
                        .clickable { onOpen(entry.kind) }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        menuIcon(entry.kind),
                        null,
                        tint = TextPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        menuLabel(entry.kind),
                        fontSize = 13.sp,
                        fontWeight = FontWeight(440),
                        color = TextMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // `M(i, y(ST, ...))` - the section is appended beside the column, not inside it.
        RecentlyClosedSection(
            recent = recent,
            expanded = expanded,
            onShowAll = { expanded = true },
            onReopen = onReopen,
        )
    }
}

/** `command.open` = "Open file"; the other three are `tab.title` of their provider. */
fun menuLabel(kind: SidePanel.Kind): String = when (kind) {
    SidePanel.Kind.REVIEW -> "Review"
    SidePanel.Kind.BROWSER -> "Browser"
    SidePanel.Kind.TERMINAL -> "Terminal"
    SidePanel.Kind.FILE -> "Open file"
    // `context/i18n/en.ts` `tab.title`
    SidePanel.Kind.CONTEXT -> "Context"
}

fun menuIcon(kind: SidePanel.Kind): ImageVector = when (kind) {
    SidePanel.Kind.REVIEW -> OcIcons.Review
    SidePanel.Kind.BROWSER -> OcIcons.Globe
    SidePanel.Kind.TERMINAL -> OcIcons.Terminal
    SidePanel.Kind.FILE -> OcIcons.Folder
    // `closed: () => ({ icon: "status" })` on their BuiltinPanel
    SidePanel.Kind.CONTEXT -> OcIcons.Status
}
