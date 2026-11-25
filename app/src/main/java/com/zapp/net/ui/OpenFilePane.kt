package com.zapp.net.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.api.FsEntry
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.Surface
import com.zapp.net.ui.theme.Surface2
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextMid
import com.zapp.net.ui.theme.TextPrimary

/**
 * `session.panel` order 30: `{id: "open", title: command.open, icon: "folder",
 * keybind: "file.browse"}`.
 *
 * On the desktop that keybind opens the file plugin: a **tree** on the left, the viewer on the
 * right, and a toolbar over both. The plugin's own state is
 * `fileTree: { opened, width, tab: ['changes', 'all'] }` (`current-BpUjPsAD.js` @ 81 395), so the
 * tree has exactly two tabs - the session's changed files, and the whole tree.
 *
 * The tree itself is a plugin **slot** (`RV.FileTree = vi.define('file.tree')`), not code in the
 * shipped bundle, so its markup cannot be read out of these chunks. The functions are theirs; the
 * shape here is ours: one directory level at a time with back/forward over the directories you
 * visited, and the same two tabs.
 *
 * `fs/find` answers 500 on this build, so "All" walks `GET /api/fs/list` one level per tap.
 */
@Composable
fun OpenFilePane(
    rows: List<FsEntry>,
    dir: String,
    changed: List<String>,
    tab: String,
    onTab: (String) -> Unit,
    onDir: (String?) -> Unit,
    onPick: (String) -> Unit,
    canBack: Boolean,
    canForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    val sorted = rows.sortedWith(compareBy({ if (it.isDir) 0 else 1 }, { it.path }))
    Column(Modifier.fillMaxSize().background(Surface)) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ToolGlyph(OcIcons.ArrowLeft, "Back", canBack, onBack)
            ToolGlyph(OcIcons.ArrowRight, "Forward", canForward, onForward)
            ToolGlyph(OcIcons.ArrowUp, "Up", true) {
                onDir(dir.replace('\\', '/').substringBeforeLast('/', "").ifBlank { null })
            }
            Text(
                dir.ifBlank { "." },
                fontSize = 11.sp,
                color = TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            TabChip("Changes", tab == "changes") { onTab("changes") }
            TabChip("All", tab == "all") { onTab("all") }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        if (tab == "changes") {
            LazyColumn(Modifier.fillMaxSize()) {
                items(changed, key = { it }) { path ->
                    FileRow(
                        name = path.replace('\\', '/').substringAfterLast('/'),
                        path = path,
                        onClick = { onPick(path) },
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(sorted, key = { it.path }) { entry ->
                    FileRow(
                        name = entry.path.replace('\\', '/').substringAfterLast('/'),
                        path = entry.path,
                        dir = entry.isDir,
                        onClick = {
                            if (entry.isDir) onDir(entry.path) else onPick(entry.path)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolGlyph(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(5.dp))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            label,
            tint = if (enabled) TextMid else TextDim.copy(alpha = 0.4f),
            modifier = Modifier.size(14.dp),
        )
    }
}

/** Their `fileTree.tab`: two values, `changes` and `all`. */
@Composable
private fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Surface2 else Surface)
            .clickable { onClick() }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight(440),
            color = if (selected) TextPrimary else TextMid,
            maxLines = 1,
            modifier = Modifier.widthIn(max = 120.dp),
        )
    }
}

@Composable
private fun FileRow(
    name: String,
    path: String,
    dir: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(36.dp).clickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // One badge for both, exactly as their tree does it: `FileMarkBadge` with
        // `isDirectory` picks the folder artwork (and its `Open` twin once expanded) and picks
        // the language icon for a file. The old 14dp `Box` wrapped a 17dp glyph, so the mark was
        // squeezed; it is drawn at its own size now and the row keeps its 8dp gap.
        Box(Modifier.size(17.dp), contentAlignment = Alignment.Center) {
            FileMarkBadge(path, isDirectory = dir)
        }
        Text(
            name,
            fontSize = 13.sp,
            color = if (dir) TextPrimary else TextMid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
