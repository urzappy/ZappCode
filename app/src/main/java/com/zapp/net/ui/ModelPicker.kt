package com.zapp.net.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.MainViewModel
import com.zapp.net.api.ModelInfo
import com.zapp.net.ui.theme.Accent
import com.zapp.net.ui.theme.Bg
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.Scrim
import com.zapp.net.ui.theme.Surface

/* Their model dialog, redrawn for a phone. Reversed from the shipped 2.3.2 build
   (`out/renderer/assets/main-CLeW75FU.js` + `runtime/i18n/en.ts`):

   - title/placeholder keys: `dialog.model.select.title` "Select model",
     `dialog.model.search.placeholder` "Search models",
     `dialog.model.empty` "No model results"
   - section keys: `dialog.model.recent` "Recent", `dialog.model.favorites` "Favorites"
   - row menu keys: `dialog.model.favorite` "Favorite", `dialog.model.unfavorite`
   - the effort rail is the model's OWN `variants` array (`GET /api/model`), never a
     hardcoded list: `variants: Object.fromEntries(model.variants.map(v => [v.id, v.settings ?? {}]))`
   - their rail footer is `provider · {{context}} context` over the modality list.

   Colours here are black and white only: the panel is `Surface`, the selected row is our
   purple at 12%, and every label is white at an alpha. No neutral grey fill anywhere. */

/**
 * "" is desktop's "no variant chosen" state: the session keeps the model's own default.
 */
internal fun variantLabels(m: ModelInfo): List<Pair<String, String>> {
    if (m.variants.isEmpty()) return emptyList()
    val rows = m.variants.map { v -> v.id to v.id.replaceFirstChar { it.uppercase() } }
    return listOf("" to "Default") + rows
}

/** `262K` / `1M` - their context figure in the rail footer. */
private fun contextLabel(m: ModelInfo): String? {
    val ctx = m.limit?.context ?: return null
    if (ctx <= 0) return null
    return if (ctx >= 1_000_000) "${ctx / 1_000_000}M" else "${ctx / 1000}K"
}

@Composable
fun ModelPickerSheet(vm: MainViewModel, onDismiss: () -> Unit, onManage: () -> Unit = {}) {
    val models by vm.models.collectAsState()
    val selected by vm.selectedModel.collectAsState()
    var q by remember { mutableStateOf("") }
    var freeOnly by remember { mutableStateOf(false) }
    var variantFor by remember { mutableStateOf<ModelInfo?>(null) }

    val filtered = remember(models, q, freeOnly) {
        models.filter { m ->
            (!freeOnly || m.modelID.contains("free", true) || m.name.contains("free", true)) &&
                (q.isBlank() ||
                    m.name.contains(q, true) ||
                    m.providerID.contains(q, true) ||
                    m.modelID.contains(q, true))
        }
    }
    val recent = remember(filtered, selected) {
        val seed = listOfNotNull(selected?.let { s ->
            filtered.firstOrNull { it.modelID == s.modelID && it.providerID == s.providerID }
        })
        (seed + filtered.take(3)).distinctBy { it.providerID + "/" + it.modelID }.take(4)
    }
    val rest = filtered.filter { m -> recent.none { it.modelID == m.modelID && it.providerID == m.providerID } }
    val grouped = remember(rest) { rest.groupBy { it.providerID } }

    Box(Modifier.fillMaxSize().background(Scrim).clickable { onDismiss() }) {
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .statusBarsPadding()
                .clip(RoundedCornerShape(12.dp))
                .background(Surface)
                .border(1.dp, Line, RoundedCornerShape(12.dp))
                .clickable { },
        ) {
            // Their header: search on the left, two icon actions on the right. `+` manages
            // providers on desktop, which has no phone meaning, so the pair here is the real
            // free-model filter and the X this panel needs to be escapable.
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(OcIcons.Magnifier, null, tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) {
                        Text("Search models", fontSize = 13.sp, color = Color.White.copy(alpha = 0.35f))
                    }
                    BasicTextField(
                        value = q,
                        onValueChange = { q = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                        cursorBrush = SolidColor(Accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // `showClearButton={!!store.search}` / `clearLabel` "Clear" (`:274-277`).
                if (q.isNotEmpty()) {
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .clickable { q = "" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            OcIcons.Close,
                            "Clear",
                            tint = Color.White.copy(alpha = 0.45f),
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (freeOnly) Accent.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { freeOnly = !freeOnly },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        OcIcons.Sliders,
                        "Free models only",
                        tint = if (freeOnly) Accent else Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(15.dp),
                    )
                }
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(OcIcons.Close, "Close", tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(15.dp))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))

            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (recent.isNotEmpty() && q.isBlank() && !freeOnly) {
                    item { SectionLabel("Recent") }
                    items(recent) { m ->
                        ModelRow(m, selected, variantFor,
                            onSelect = { pick(it, vm, onDismiss) },
                            onVariant = { variantFor = it })
                    }
                }
                grouped.forEach { (prov, rows) ->
                    item { SectionLabel(prov.replaceFirstChar { it.uppercase() }) }
                    items(rows) { m ->
                        ModelRow(m, selected, variantFor,
                            onSelect = { pick(it, vm, onDismiss) },
                            onVariant = { variantFor = it })
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "No model results",
                            fontSize = 12.5.sp,
                            color = Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            // `data-slot="model-selector-actions"` (`:433`): their phone drawer closes with a
            // full-width ghost action, `dialog.model.manage` "Manage models", icon `sliders`,
            // h-10. Our equivalent surface is Settings > Models, so it opens that.
            //
            // The lock row above it is the owner's own rule, not theirs: *"WHAT IF I WANT 1 MODEL
            // ONLY KASI YUNG IBANG MODEL ANG HIHINA"*. Their store already hides ids, so this
            // is the same switch thrown for every other model at once, and it is reversible
            // through the same row.
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val sver by com.zapp.net.settings.SettingsPrefs.version.collectAsState()
            val lockedId = remember(sver) { com.zapp.net.settings.SettingsPrefs.lockedModel(ctx) }
            // a local copy: `selected` is a delegated property, so Kotlin cannot smart-cast it
            val lockTarget = selected
            if (lockedId.isNotBlank()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.unlockModels()
                            onDismiss()
                        }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        OcIcons.Models,
                        null,
                        tint = Accent,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Unlock model list", fontSize = 13.sp, color = Color.White.copy(alpha = 0.9f))
                    Spacer(Modifier.weight(1f))
                    Text(
                        "1 model",
                        fontSize = 10.5.sp,
                        color = Color.White.copy(alpha = 0.4f),
                    )
                }
            } else if (lockTarget != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.lockToModel(lockTarget.modelID)
                            onDismiss()
                        }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        OcIcons.Models,
                        null,
                        tint = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Only ${lockTarget.label.ifBlank { lockTarget.modelID }}",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        onDismiss()
                        onManage()
                    }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    OcIcons.Sliders,
                    null,
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Manage models", fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
            }
        }

        // The effort rail: their submenu, opened beside the row.
        val target = variantFor
        AnimatedVisibility(
            visible = target != null,
            enter = fadeIn(tween(120)) + slideInVertically(tween(150)) { it / 14 },
            exit = fadeOut(tween(100)),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
        ) {
            if (target != null) {
                Column(
                    Modifier
                        .width(172.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Bg)
                        .border(1.dp, Line, RoundedCornerShape(10.dp))
                        .clickable { },
                ) {
                    variantLabels(target).forEach { (id, label) ->
                        val checked = (selected?.variant.orEmpty()) == id &&
                            selected?.modelID == target.modelID
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    vm.chooseModel(target.providerID, target.modelID, id, target.name)
                                    variantFor = null
                                    onDismiss()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label,
                                fontSize = 12.5.sp,
                                color = if (checked) Color.White else Color.White.copy(alpha = 0.62f),
                                modifier = Modifier.weight(1f),
                            )
                            if (checked) Text("✓", fontSize = 11.sp, color = Accent)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Favorite", fontSize = 12.5.sp, color = Color.White.copy(alpha = 0.62f), modifier = Modifier.weight(1f))
                        Text("CTRL F", fontSize = 8.5.sp, color = Color.White.copy(alpha = 0.3f))
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            target.providerID.replaceFirstChar { it.uppercase() } +
                                (contextLabel(target)?.let { " · $it context" } ?: ""),
                            fontSize = 9.5.sp,
                            color = Color.White.copy(alpha = 0.38f),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text("Text, Tools", fontSize = 9.5.sp, color = Color.White.copy(alpha = 0.38f))
                    }
                }
            }
        }
    }
}

/** Tapping a row selects it immediately; the `Default ›` chip beside it opens the effort rail. */
private fun pick(m: ModelInfo, vm: MainViewModel, onDismiss: () -> Unit) {
    // no variant until he asks for one: the server then applies the model's own default,
    // which is exactly what desktop does when a model has no chosen variant
    vm.chooseModel(m.providerID, m.modelID, "", m.name)
    onDismiss()
}

/** `dialog.model.recent` / provider section: `text-muted`, small, above its rows. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = Color.White.copy(alpha = 0.42f),
        modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun ModelRow(
    m: ModelInfo,
    selected: com.zapp.net.MainViewModel.SelectedModel?,
    variantFor: ModelInfo?,
    onSelect: (ModelInfo) -> Unit,
    onVariant: (ModelInfo) -> Unit,
) {
    val isSelected = selected?.modelID == m.modelID && selected?.providerID == m.providerID
    val free = m.modelID.contains("free", true) || m.name.contains("free", true)
    val variant = (selected?.variant?.takeIf { isSelected && it.isNotBlank() } ?: "Default")
        .replaceFirstChar { it.uppercase() }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isSelected) Accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable { onSelect(m) }
            .padding(start = 6.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Their leading slot is the checkmark, present on the chosen model only.
        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
            if (isSelected) Text("✓", fontSize = 11.sp, color = Color.White)
        }
        // `model-dialog-name` (`select-dialog.tsx:1010`): name, then the provider, then the
        // badges, all left-packed. The group owns the free width, which is what pushes the
        // variant chip to the hard right - `justify-between` with two children, not a spacer
        // after the name.
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                m.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                m.providerID.replaceFirstChar { it.uppercase() },
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.45f),
                maxLines = 1,
            )
            if (free) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "Free",
                    fontSize = 9.5.sp,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier
                        .border(1.dp, Line, RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        // `model-dialog-variant` (`:1039`): the last child, so `Default ›` sits in the same
        // column on every row, long name or short.
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(5.dp))
                .clickable { onVariant(m) }
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                variant,
                fontSize = 11.5.sp,
                color = Color.White.copy(alpha = if (variantFor?.modelID == m.modelID) 0.9f else 0.45f),
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                OcIcons.ChevronRight,
                "Effort",
                tint = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(11.dp),
            )
        }
    }
}
