package com.zapp.net.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.ui.theme.DiffAdd
import com.zapp.net.ui.theme.DiffDel

/*
 * `ui/src/data-display/diff-changes` - the `+N -N` pair.
 *
 * Their built stylesheet, `out/renderer/assets/diff-changes-kktMd8h_.css`, is the whole spec:
 *
 *     [data-component=diff-changes] {
 *       justify-content: flex-end; align-items: center; gap: 8px; display: flex;
 *       & [data-slot=diff-changes-additions], & [data-slot=diff-changes-deletions] {
 *         letter-spacing: .05px; text-align: right;
 *         font-size: 11px; font-style: normal; font-weight: 440; line-height: 1 }
 *       & [data-slot=diff-changes-additions] { color: var(--v2-state-fg-success) }
 *       & [data-slot=diff-changes-deletions] { color: var(--v2-state-fg-danger) }
 *     }
 *
 * There is no background, no padding and no border on either number - they are bare text on
 * whatever surface the row already has. `data-appearance=standard` swaps them to 14px mono.
 */
@Composable
internal fun DiffChanges(
    additions: Int,
    deletions: Int,
    fontSize: androidx.compose.ui.unit.TextUnit = 11.sp,
    modifier: Modifier = Modifier,
) {
    // desktop treats a 0/0 pair as "not a change at all" (`gui-plugins/src/review/kinds.ts:41`)
    if (additions + deletions == 0) return
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `diff-changes-B1h4MugF.js`: both spans are always written, so a pure addition prints
        // `+8 -0`. Only the `when: d() > 0` guard can drop the pair.
        Text(
            "+$additions",
            fontSize = fontSize,
            lineHeight = fontSize,
            fontWeight = FontWeight(440),
            color = DiffAdd,
        )
        Text(
            "-$deletions",
            fontSize = fontSize,
            lineHeight = fontSize,
            fontWeight = FontWeight(440),
            color = DiffDel,
        )
    }
}
