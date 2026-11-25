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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.api.CommandEntry
import com.zapp.net.api.FsEntry
import com.zapp.net.ui.theme.AccentSoft
import com.zapp.net.ui.theme.Border
import com.zapp.net.ui.theme.Surface2
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextPrimary

/**
 * The composer's `/` and `@` palette.
 *
 * Their client opens both menus from those two characters: `/` lists what
 * `GET /api/command` returns (`init`, `review`, `goal` on 2.3.2) plus the session
 * actions that have no command name of their own; `@` lists files. `GET /api/fs/find`
 * answers 500 UnknownError on this build, so the file half walks `GET /api/fs/list`
 * one directory at a time instead — same menu, different route.
 *
 * Design is ours: a dark card above the composer, purple only on the marker glyph.
 */
@Composable
fun CommandSheet(
    mode: SheetMode,
    commands: List<CommandEntry>,
    files: List<FsEntry>,
    query: String,
    onCommand: (CommandEntry) -> Unit,
    onFile: (FsEntry) -> Unit,
    onAction: (SheetAction) -> Unit,
    /**
     * `composer/state.ts` has a `session` prompt part and `util/src/session-mention.ts`
     * defines its wire form, so their `@` palette offers sessions beside files. Ours did
     * files only.
     */
    sessions: List<com.zapp.net.api.Session> = emptyList(),
    onSession: ((com.zapp.net.api.Session) -> Unit)? = null,
    /** `command.permissions.autoaccept.enable` / `.disable` - the row flips its own label. */
    autoAccept: Boolean = false,
    /**
     * Their palette closes on Escape or a click outside. A phone has neither, and this sheet
     * had no way out at all - the owner: "WALANG PRESS BACK TAPOS WALANG X BUTTON SA FILES".
     */
    onDismiss: () -> Unit = {},
    /**
     * The directory the `@` browser is showing, and how to walk back out of it. Their palette
     * is a desktop popover that closes on Escape; on a phone he descended into a folder with no
     * way back up - the owner: "INSIDE THE FILES NO EXIST NO BACK BUTTON".
     */
    dir: String = "",
    onUp: (() -> Unit)? = null,
) {
    val q = query.trim().lowercase()

    // Their palette carries two commands, not one switch: enable when it is off, disable when it
    // is on. The label is the command's own title, so the row always names what the tap will do.
    fun labelOf(a: SheetAction): String = when (a) {
        SheetAction.AUTOACCEPT ->
            if (autoAccept) PermissionCopy.COMMAND_DISABLE else PermissionCopy.COMMAND_ENABLE
        else -> a.label
    }

    // Prefix matches first, then anything containing the text — their palette order.
    val cmdRows = commands
        .filter {
            q.isEmpty() || it.name.lowercase().contains(q) || it.description.lowercase().contains(q)
        }
        .sortedWith(
            compareBy(
                { if (q.isNotEmpty() && it.name.lowercase().startsWith(q)) 0 else 1 },
                { it.name },
            ),
        )

    val actionRows = SheetAction.values().filter {
        q.isEmpty() || labelOf(it).lowercase().contains(q) || it.hint.lowercase().contains(q)
    }

    val fileRows = files.sortedWith(compareBy({ if (it.isDir) 0 else 1 }, { it.path }))

    // Their palette matches on the title the user reads, not the id they never see.
    val sessionRows = if (onSession == null) emptyList() else sessions.filter {
        q.isEmpty() || com.zapp.net.SessionTitle.display(it.title, !it.parentId.isNullOrBlank())
            .lowercase().contains(q)
    }

    val empty = when (mode) {
        SheetMode.COMMAND -> cmdRows.isEmpty() && actionRows.isEmpty()
        // an empty folder still has its `..` row, so it is not "no matching items"
        SheetMode.FILE -> fileRows.isEmpty() && sessionRows.isEmpty() &&
            !(dir.isNotBlank() && onUp != null)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .border(1.dp, Border, RoundedCornerShape(10.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (mode == SheetMode.COMMAND) "/" else "@",
                fontSize = 11.sp,
                color = AccentSoft,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (mode == SheetMode.COMMAND) "Commands" else "Files",
                fontSize = 10.5.sp,
                color = TextDim,
            )
            Spacer(Modifier.weight(1f))
            // Up one level, inside the browser. Only exists while he is inside a folder, so the
            // header stays quiet at the server root.
            if (mode == SheetMode.FILE && dir.isNotBlank() && onUp != null) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .semantics { contentDescription = "Up one folder" }
                        .clickable { onUp() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("\u2191", fontSize = 11.sp, color = TextDim)
                }
                Spacer(Modifier.width(4.dp))
            }
            if (query.isNotBlank()) {
                Text(
                    query,
                    fontSize = 10.5.sp,
                    color = TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            // The way out. Their palette is a desktop popover that closes on Escape or a click
            // outside; this one is anchored above the composer and had neither, so a picker he
            // changed his mind about trapped him until he edited the text by hand.
            Box(
                Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .semantics { contentDescription = "Close" }
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center,
            ) {
                Text("\u2715", fontSize = 10.sp, color = TextDim)
            }
        }
        Box(Modifier.fillMaxWidth().heightIn(max = 236.dp)) {
            if (empty) {
                // `ui.promptInput.noMatchingItems` = "No matching items" - one string for every
                // palette the prompt input opens, commands and context alike. Ours said
                // "No matching command" / "No matching file", which is not a key in the bundle.
                Text(
                    "No matching items",
                    fontSize = 11.sp,
                    color = TextDim,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            } else {
                LazyColumn {
                    if (mode == SheetMode.COMMAND) {
                        items(cmdRows, key = { "c:" + it.name }) { row ->
                            SheetRow(
                                title = "/" + row.name,
                                hint = row.description,
                                onClick = { onCommand(row) },
                            )
                        }
                        items(actionRows, key = { "a:" + it.key }) { a ->
                            SheetRow(title = labelOf(a), hint = a.hint, onClick = { onAction(a) })
                        }
                    } else {
                        // A way back out of a folder. The server lists one level at a time, so
                        // descending used to be a one-way door on a phone - no Escape key, no
                        // `..` entry in its own listing.
                        if (dir.isNotBlank() && onUp != null) {
                            item(key = "up") {
                                SheetRow(
                                    title = "\u2191  ..",
                                    hint = "back",
                                    onClick = { onUp() },
                                )
                            }
                        }
                        items(sessionRows, key = { "s:" + it.id }) { s ->
                            SheetRow(
                                title = "@" + com.zapp.net.SessionTitle.display(
                                    s.title, !s.parentId.isNullOrBlank(),
                                ),
                                hint = "session",
                                onClick = { onSession?.invoke(s) },
                            )
                        }
                        items(fileRows, key = { "f:" + it.path }) { row ->
                            // the server's entries carry a trailing slash on directories and a
                            // leading run of `../` because it lists from its own working dir
                            val clean = row.path.trimEnd('/')
                            SheetRow(
                                title = (if (row.isDir) "\u25B8 " else "") +
                                    clean.substringAfterLast('/').ifBlank { clean },
                                hint = clean,
                                onClick = { onFile(row) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetRow(title: String, hint: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            fontSize = 11.5.sp,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
        Spacer(Modifier.weight(1f))
        if (hint.isNotBlank()) {
            Text(
                hint.replace('\n', ' '),
                fontSize = 10.sp,
                color = TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 190.dp),
            )
        }
    }
}

enum class SheetMode { COMMAND, FILE }

/**
 * Session actions their client exposes, none of which is a server "command" name.
 * Each maps to exactly one route, the same ones tracked in ZAPP-FEATURES.md.
 */
enum class SheetAction(val key: String, val label: String, val hint: String) {
    COMPACT("compact", "Compact conversation", "Summarise history now"),
    FORK("fork", "Fork session", "Branch from here"),
    REVIEW("review", "Review changes", "Files this session touched"),
    AGENT("agent", "Switch agent", "build / plan"),
    MODEL("model", "Switch model", "Provider and model"),
    AUTOACCEPT("autoaccept", "Auto-accept permissions", "Approve every request without asking"),
    BACKGROUND("background", "Move to background", "Keep the run going"),
    STOP("stop", "Stop", "Interrupt the turn"),
}
