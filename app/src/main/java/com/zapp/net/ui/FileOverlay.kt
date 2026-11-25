package com.zapp.net.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.MainViewModel
import com.zapp.net.ui.theme.Bg
import com.zapp.net.ui.theme.DiffDel
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.Surface
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextMid
import com.zapp.net.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Their file tab, opened from a timeline file card (`ui.sessionReview.openFile`).
 *  Body is `GET /api/fs/read/<path>` verbatim: gutter numbers, monospace, no reflow. */
@Composable
fun FileOverlay(state: MainViewModel.FileView?, onClose: () -> Unit) {
    AnimatedVisibility(
        visible = state != null,
        enter = fadeIn(tween(130)) + slideInVertically(tween(160)) { -it / 14 },
        exit = fadeOut(tween(110)) + slideOutVertically(tween(120)) { -it / 16 },
    ) {
        val st = state ?: return@AnimatedVisibility
        Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FileGlyph(st.path)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        st.path.replace('\\', '/').substringAfterLast('/'),
                        fontSize = 12.5.sp,
                        color = TextPrimary,
                        maxLines = 1,
                    )
                    Text(
                        st.path.replace('\\', '/').substringBeforeLast('/', ""),
                        fontSize = 9.5.sp,
                        color = TextDim,
                        maxLines = 1,
                    )
                }
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
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            // `weight(1f)`: the body is a `LazyColumn` that fills its parent, so without this it
            // takes the *whole* overlay height and the last rows sit under the header, out of
            // reach.
            Box(Modifier.fillMaxWidth().weight(1f)) { FileBody(st) }
        }
    }
}

/**
 * The file tab's body with no chrome: the pane's tab strip already carries the name and the `×`.
 * Loading, the read error and both previews are the same code the standalone viewer used.
 */
@Composable
fun FileBody(st: MainViewModel.FileView) {
    when {
        st.busy -> Text(
            "Loading",
            fontSize = 11.5.sp,
            color = TextDim,
            modifier = Modifier.padding(14.dp),
        )
        !st.error.isNullOrBlank() -> Text(
            st.error,
            fontSize = 11.sp,
            color = DiffDel,
            modifier = Modifier.padding(14.dp),
        )
        else -> {
            val raw = st.bytes
            if (raw != null) BinaryPreview(st.path, raw)
            else CodeBody(st.path, st.text.orEmpty())
        }
    }
}

/**
 * One `LazyColumn` row per source line, painted off the main thread.
 *
 * The old body put the whole file into a single `Text`: 6 000 lines became one `SpannableString`
 * with tens of thousands of spans, built and measured on the UI thread. Android killed the app
 * with `Input dispatching timed out ... Waited 5001ms for MotionEvent` while the main thread sat
 * inside `SpannableStringInternal.setSpan` (dropbox `data_app_anr`, 2026-11-21 17:47:59). A list
 * only measures the rows that are on screen, so the cost is now proportional to the viewport.
 *
 * The gutter is fixed and the code scrolls under it through one shared [ScrollState] - the shape
 * every code viewer uses, and the one their file tab has.
 */
@Composable
private fun CodeBody(path: String, text: String) {
    val lang = path.substringAfterLast('.', "").lowercase()
    val painted by produceState<List<AnnotatedString>?>(null, text, lang) {
        value = withContext(Dispatchers.Default) {
            highlightLines(text.lineSequence().toList(), lang)
        }
    }
    val lines = painted
    if (lines == null) {
        Text(
            "Loading",
            fontSize = 11.5.sp,
            color = TextDim,
            modifier = Modifier.padding(14.dp),
        )
        return
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val gutter = remember(lines.size) {
        val digits = lines.size.toString().length.coerceAtLeast(2)
        val w = measurer.measure(
            "0".repeat(digits),
            TextStyle(fontSize = 9.5.sp, fontFamily = FontFamily.Monospace),
        ).size.width
        with(density) { w.toDp() } + 10.dp
    }
    val codeStyle = remember {
        TextStyle(fontSize = 10.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace)
    }
    // One horizontal owner for the whole body: see [rememberCodeScroll]. Measuring only the
    // longest line keeps this exact and costs a single `TextMeasurer` pass, because the body is
    // monospace - the widest string is the longest one.
    // `file-search-DNzGSpLZ.js` - their find-in-file overlay: 32dp bar, border, `px-3`,
    // magnifier, a 160dp input with placeholder `ui.fileSearch.placeholder` = "Find", a
    // tabular `{{index}}/{{count}}` counter (10ch, `0/0` when empty), `Previous match` /
    // `Next match` chevrons and `Close search`. Ported whole; the only addition is the
    // magnifier that opens it, because a phone has no Ctrl+F.
    var findOpen by remember(path) { mutableStateOf(false) }
    var query by remember(path) { mutableStateOf("") }
    var hit by remember(path) { mutableStateOf(0) }
    val matches = remember(lines, query) {
        if (query.isBlank()) emptyList()
        else lines.indices.filter { lines[it].text.contains(query, ignoreCase = true) }
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(matches) { hit = 0 }
    LaunchedEffect(matches, hit) {
        if (matches.isNotEmpty() && hit in matches.indices) {
            listState.animateScrollToItem(matches[hit])
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportPx = with(density) { maxWidth.toPx() }
        val gutterPx = with(density) { gutter.toPx() }
        val contentPx = remember(lines) {
            val longest = lines.maxByOrNull { it.text.length } ?: AnnotatedString("")
            measurer.measure(longest, codeStyle).size.width.toFloat() +
                with(density) { 14.dp.toPx() }
        }
        val scroll = rememberCodeScroll(contentPx, viewportPx - gutterPx)
        Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().codeHorizontalDrag(scroll),
        ) {
            items(lines.size) { index ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "${index + 1}",
                        fontSize = 9.5.sp,
                        lineHeight = 15.sp,
                        color = TextDim,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.width(gutter).padding(end = 10.dp),
                    )
                    // The line is measured unbounded so a long one keeps its width, then shifted
                    // by the shared offset. No scroller of its own, so a vertical drag on a line
                    // always belongs to the list.
                    Box(Modifier.weight(1f).clipToBounds()) {
                        val line = lines[index]
                        Text(
                            if (line.isEmpty()) AnnotatedString(" ") else line,
                            style = codeStyle,
                            color = TextMid,
                            softWrap = false,
                            modifier = Modifier
                                .wrapContentWidth(Alignment.Start, unbounded = true)
                                .padding(end = 14.dp)
                                .then(scroll.row),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        // The bar itself (`m` in `file-search-DNzGSpLZ.js`), pinned top-end over the body.
        if (findOpen) {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Surface)
                    .border(1.dp, Line, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(OcIcons.Search, null, tint = TextDim, modifier = Modifier.size(14.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 12.sp, color = TextPrimary),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(TextPrimary),
                    // `placeholder:text-v2-text-text-faint` + `ui.fileSearch.placeholder`
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                Text("Find", fontSize = 12.sp, color = TextDim)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.width(160.dp),
                )
                Text(
                    if (matches.isEmpty()) "0/0" else "${hit + 1}/${matches.size}",
                    fontSize = 11.sp,
                    color = TextDim,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(44.dp),
                )
                Icon(
                    OcIcons.ChevronDown,
                    contentDescription = "Previous match",
                    tint = if (matches.isEmpty()) Line else TextDim,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { rotationZ = 180f }
                        .clickable(enabled = matches.isNotEmpty()) {
                            hit = (hit - 1 + matches.size) % matches.size
                        },
                )
                Icon(
                    OcIcons.ChevronDown,
                    contentDescription = "Next match",
                    tint = if (matches.isEmpty()) Line else TextDim,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(enabled = matches.isNotEmpty()) {
                            hit = (hit + 1) % matches.size
                        },
                )
                Icon(
                    OcIcons.Close,
                    contentDescription = "Close search",
                    tint = TextDim,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { findOpen = false },
                )
            }
        } else {
            // The key that opens theirs is Ctrl+F; a phone has none, so the same control is a
            // magnifier pinned where the bar opens.
            Icon(
                OcIcons.Search,
                contentDescription = "Find",
                tint = TextDim,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .clickable { findOpen = true },
            )
        }
        }
    }
}
