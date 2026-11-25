package com.zapp.net.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zapp.net.api.Part
import com.zapp.net.ui.theme.Bg
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.Surface
import com.zapp.net.ui.theme.Surface2
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextMid
import com.zapp.net.ui.theme.TextPrimary

/**
 * The turn's sources chip (`sources/experiment.ts:115` — "a \"N sources\" chip under a reply that
 * searched the web") and the panel it opens.
 *
 * Their panel is a side-panel tab keyed by the user message that opened the turn
 * (`gui-plugins/src/sources/renderer.tsx:83`). A phone has no side panel, so the same content
 * opens as a sheet. The list itself is [Sources.turn], unchanged.
 *
 * Their default icon mode is `letters`, and the reason is written down at `experiment.ts:31`:
 * "A site's own icon is fetched from that site, which tells it the page was looked at; letters
 * stay on this device." We keep letters, for that reason.
 */
@Composable
fun SourcesChip(parts: List<Part>) {
    // `sources.ts` is pure, so the derivation is keyed on the parts it read.
    val turn = remember(parts) { Sources.turn(parts) }
    if (turn.sources.isEmpty()) return

    var open by remember { mutableStateOf(false) }
    val count = turn.sources.size

    Row(
        Modifier
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Surface2)
            .border(0.5.dp, Line, RoundedCornerShape(999.dp))
            .clickable { open = true }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `sources-title.tsx:21` — `<Icon name="globe" />`. This was a text glyph; the
        // hand-built vector set already has the real one.
        Icon(OcIcons.Globe, null, tint = TextDim, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (count == 1) "1 source" else "$count sources",
            fontSize = 11.sp,
            color = TextMid,
            fontWeight = FontWeight.Medium,
        )
    }

    if (open) SourcesPanel(turn, onDismiss = { open = false })
}

/**
 * The turn's searches and fetches, in the order they ran (`sources.ts:59-63`), each search with
 * the results it found.
 */
@Composable
private fun SourcesPanel(turn: Sources.Turn, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Bg)
                .border(0.5.dp, Line, RoundedCornerShape(14.dp)),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(OcIcons.Globe, null, tint = TextDim, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sources", fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                // `sources-title.tsx:25-34` — the count in faint tabular figures after a separator.
                Text("·", fontSize = 13.sp, color = TextDim)
                Spacer(Modifier.width(6.dp))
                Text("${turn.sources.size}", fontSize = 13.sp, color = TextDim)
                Spacer(Modifier.weight(1f))
                Text(
                    "Close",
                    fontSize = 12.sp,
                    color = TextMid,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Box(Modifier.fillMaxWidth().heightIn(max = 0.5.dp).background(Line))

            LazyColumn(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Flattened first: a search's results belong to that search's header, and
                // `LazyListScope` cannot nest. Order is `sources.ts:59-63` — the order they ran.
                items(panelRows(turn)) { row ->
                    when (row) {
                        is PanelRow.Header -> StepHeader(row.label, row.detail, row.status)
                        is PanelRow.Page -> SourceRow(
                            row.result,
                            onClick = { runCatching { uri.openUri(row.result.url) } },
                        )
                        is PanelRow.Empty -> EmptyRow(row.text)
                    }
                }
            }
        }
    }
}

/**
 * The panel's rows, flattened in the order the turn met them: a search header, its results, a
 * page header, the page. `sources.ts` gives the order; this only interleaves it.
 */
private sealed interface PanelRow {
    data class Header(val label: String, val detail: String, val status: String) : PanelRow
    data class Page(val result: Sources.Result) : PanelRow
    data class Empty(val text: String) : PanelRow
}

private fun panelRows(turn: Sources.Turn): List<PanelRow> = buildList {
    turn.steps.forEach { step ->
        when (step) {
            is Sources.Step.Search -> {
                add(PanelRow.Header("Search", step.query.ifBlank { "search" }, step.status))
                if (step.results.isEmpty() && step.status == Sources.COMPLETED) add(PanelRow.Empty("No results"))
                step.results.forEach { add(PanelRow.Page(it)) }
            }
            is Sources.Step.Fetch -> {
                add(PanelRow.Header("Page", step.path.ifBlank { step.host }, step.status))
                add(PanelRow.Page(Sources.Result(step.url, step.title, step.site, step.host)))
            }
        }
    }
}

@Composable
private fun StepHeader(label: String, detail: String, status: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(),
            fontSize = 9.sp,
            color = TextDim,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.6.sp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            detail,
            fontSize = 11.sp,
            color = TextMid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (status == Sources.ERROR) {
            Spacer(Modifier.width(8.dp))
            Text("failed", fontSize = 10.sp, color = TextDim)
        } else if (status == Sources.RUNNING) {
            Spacer(Modifier.width(8.dp))
            Text("…", fontSize = 11.sp, color = TextDim)
        }
    }
}

/**
 * One page. The letter tile is their default `letters` mode: the site's first character.
 *
 * The primary line is the title, or — when `sources.ts:231` dropped it because it repeated the
 * URL — the page's own `path()`, so two pages on one host are still told apart. Falling back to
 * `site` printed the host twice, once as the row and once as its subtitle.
 */
@Composable
private fun SourceRow(result: Sources.Result, onClick: () -> Unit) {
    val label = result.title
        ?: Sources.path(result.url).ifBlank { result.site }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Surface)
                .border(0.5.dp, Line, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                result.site.take(1).uppercase(),
                fontSize = 10.sp,
                color = TextMid,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 12.sp,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                result.site,
                fontSize = 10.sp,
                color = TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = TextDim,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
    )
}
