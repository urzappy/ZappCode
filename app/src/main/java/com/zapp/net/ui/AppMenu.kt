package com.zapp.net.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.ui.theme.Accent
import com.zapp.net.ui.theme.TextPrimary

/**
 * Their in-app menubar (`Nan`, `out/renderer/assets/main-*.js`) with the six Windows groups
 * (`San` / `Fr`). The strings are their `desktop.menu.*` labels.
 *
 * Phone rules, on purpose:
 * - no shortcut column - there is no Ctrl key on this device, and a dead key cap is noise;
 * - 44 dp rows, 14 sp labels - Android's touch target, not a 28 px desktop menu;
 * - an X on every level and a back arrow inside a group, so no state is a dead end.
 */
data class AppMenuRow(
    val label: String,
    val enabled: Boolean = true,
    val onClick: (() -> Unit)? = null,
)

@Composable
fun AppMenuPanel(
    groups: List<Pair<String, List<AppMenuRow>>>,
    onClose: () -> Unit,
) {
    var open by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(38.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (open != null) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { open = null },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        OcIcons.ArrowLeft,
                        "Back",
                        tint = Accent,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Text(
                open ?: "ZAPP-NET",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (open == null) 6.dp else 2.dp),
            )
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(OcIcons.Close, "Close", tint = Accent, modifier = Modifier.size(14.dp))
            }
        }
        val rows = groups.firstOrNull { it.first == open }?.second
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (rows == null) {
                groups.forEach { (label, _) ->
                    MenuGroupRow(label) { open = label }
                }
            } else {
                rows.forEach { row -> MenuItemRow(row) }
            }
        }
    }
}

/** A group row: label left, chevron right - their submenu trigger, at a phone's size. */
@Composable
private fun MenuGroupRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = TextPrimary.copy(alpha = 0.9f),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Icon(
            OcIcons.ChevronRight,
            null,
            tint = Accent,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** A leaf row: 44 dp, 14 sp, no shortcut column. */
@Composable
private fun MenuItemRow(row: AppMenuRow) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = row.enabled && row.onClick != null) { row.onClick?.invoke() }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.label,
            fontSize = 12.sp,
            color = TextPrimary.copy(alpha = if (row.enabled) 0.9f else 0.3f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
