package com.zapp.net.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.ui.theme.Accent
import com.zapp.net.ui.theme.Border
import com.zapp.net.ui.theme.Surface2
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextFaint
import com.zapp.net.ui.theme.TextMid
import com.zapp.net.ui.theme.TextPrimary
import kotlinx.coroutines.delay

/**
 * The new-session **base branch** picker.
 *
 * `wr` in `route-BvCVPSgV.js`: one 28 px button in the prompt row, and a 243 px
 * popover holding a search field, `Use main checkout`, then the branches.
 *
 * Their trigger is:
 *
 *     <button class="flex h-7 items-center gap-1.5 rounded-sm px-1.5
 *                    text-v2-text-text-muted hover:bg-v2-overlay-simple-overlay-hover
 *                    hover:text-v2-text-text-base max-w-[220px] min-w-0">
 *       <icon outline-worktree class="text-v2-icon-icon-muted">
 *       <span>from main | Choose base branch</span>
 *       <icon chevron-down class="-ms-0.5 text-v2-icon-icon-muted">
 *
 * `--v2-icon-icon-muted` resolves to `--v2-grey-600` = `#808080` (`oc-2-COeEKe_J.js`
 * @ 1 568), which is our `TextFaint`; the label is `--v2-text-text-muted` =
 * `--v2-grey-500` = `#AEAEAE`, which is our `TextMid`.
 *
 * The popover's palette (`Gt`) is theirs, not a new design: a search field, a
 * **retry** row when the fetch failed, `No results found` when nothing matched,
 * and the checkout row first. `match` is `e.toLowerCase().includes(t.toLowerCase())`
 * and the checkout row is excluded from matching because it is the default, not a
 * search result.
 *
 * Design is ours where theirs is a desktop panel: this is a `DropdownMenu` of the
 * same width with our row styling, not their Radix popover.
 */
@Composable
fun BranchPicker(
    state: BranchMachine.State,
    /** The draft's selection. `null` = the main checkout. */
    branch: String?,
    dispatch: (BranchMachine.Event) -> Unit,
    /** The current checkout's branch name, for the subtitle on `Use main checkout`. */
    checkout: String?,
    /**
     * The palette's own `source(query, signal)`. `null` means the call failed and the
     * palette shows `Try again`.
     */
    search: suspend (String) -> List<String>?,
) {
    val open = BranchMachine.isOpen(state)
    val initial = BranchMachine.initialItems(state)
    val busy = BranchMachine.isBusy(state)

    var query by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf<List<String>?>(null) }
    var remoteFailed by remember { mutableStateOf(false) }

    // Their palette re-queries the server with the typed text, `delay: 100`. The
    // machine's own fetch is always the empty query, so this is the only place a
    // search term reaches `/api/vcs/branch?search=`.
    LaunchedEffect(open, query) {
        if (!open) return@LaunchedEffect
        if (query.isBlank()) {
            remote = null
            remoteFailed = false
            return@LaunchedEffect
        }
        delay(100)
        val items = search(query)
        if (items == null) {
            remoteFailed = true
        } else {
            remoteFailed = false
            remote = items
        }
    }

    // Closed again -> the next open starts from a clean field, like their unmounted palette.
    LaunchedEffect(open) {
        if (!open) {
            query = ""
            remote = null
            remoteFailed = false
        }
    }

    val source: List<String>? = if (query.isBlank()) initial?.items else remote
    val failed = if (query.isBlank()) initial?.failed == true else remoteFailed
    val rows = source?.let { BranchMachine.visible(it, query) } ?: emptyList()

    Box {
        BranchTrigger(
            label = BranchMachine.label(branch),
            busy = busy,
            onClick = { dispatch(BranchMachine.Event.OpenChange(!open)) },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = {
                // Their Radix fires both: `onInteractOutside` then `onOpenChange(false)`.
                dispatch(BranchMachine.Event.InteractOutside)
                dispatch(BranchMachine.Event.OpenChange(false))
            },
            modifier = Modifier.width(243.dp).background(Surface2),
            containerColor = Surface2,
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                BranchSearch(
                    query = query,
                    onQuery = { query = it },
                )
                Box(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    when {
                        failed -> BranchRow(
                            title = BranchMachine.Copy.RETRY,
                            hint = "",
                            selected = false,
                            muted = true,
                            onClick = {
                                // A failed machine is `Ready`, so re-opening refetches with a
                                // raised attempt - the same path their `retryLabel` takes.
                                dispatch(BranchMachine.Event.OpenChange(false))
                                dispatch(BranchMachine.Event.OpenChange(true))
                            },
                        )
                        rows.isEmpty() -> Text(
                            BranchMachine.Copy.EMPTY,
                            fontSize = 11.sp,
                            color = TextDim,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                        else -> Column {
                            rows.forEach { item ->
                                val key = BranchMachine.rowKey(item)
                                val selected = key == BranchMachine.selectedKey(branch)
                                BranchRow(
                                    title = item ?: BranchMachine.Copy.USE_MAIN,
                                    hint = if (item == null) checkout.orEmpty() else "",
                                    selected = selected,
                                    muted = item == null,
                                    onClick = {
                                        dispatch(
                                            if (item == null) {
                                                BranchMachine.Event.CheckoutSelected
                                            } else {
                                                BranchMachine.Event.BranchSelected(item)
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BranchTrigger(label: String, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .widthIn(max = 220.dp)
            .clip(RoundedCornerShape(6.dp))
            // `aria-description` = `command.session.branch.choose`.
            .semantics { contentDescription = BranchMachine.Copy.CHOOSE }
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            OcIcons.Worktree,
            null,
            tint = if (busy) Accent else TextFaint,
            modifier = Modifier.size(12.dp),
        )
        Text(
            label,
            fontSize = 10.5.sp,
            color = TextMid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(OcIcons.ChevronDown, null, tint = TextFaint, modifier = Modifier.size(11.dp))
    }
}

@Composable
private fun BranchSearch(query: String, onQuery: (String) -> Unit) {
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics { contentDescription = BranchMachine.Copy.SEARCH },
        singleLine = true,
        textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp),
        cursorBrush = SolidColor(Accent),
        decorationBox = { inner ->
            Box {
                if (query.isEmpty()) {
                    Text(BranchMachine.Copy.SEARCH, fontSize = 12.sp, color = TextDim)
                }
                inner()
            }
        },
    )
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(1.dp).background(Border))
}

@Composable
private fun BranchRow(
    title: String,
    hint: String,
    selected: Boolean,
    muted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            fontSize = 12.sp,
            color = when {
                selected -> TextPrimary
                muted -> TextMid
                else -> TextPrimary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (hint.isNotBlank()) {
            Text(
                hint,
                fontSize = 10.5.sp,
                color = TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        if (selected) Icon(OcIcons.Check, "Selected", tint = TextMid, modifier = Modifier.size(12.dp))
    }
}
