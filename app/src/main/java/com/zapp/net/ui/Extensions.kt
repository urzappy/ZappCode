package com.zapp.net.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.zapp.net.MainViewModel
import com.zapp.net.ui.theme.Accent
import com.zapp.net.ui.theme.AccentSoft
import com.zapp.net.ui.theme.Bg
import com.zapp.net.ui.theme.Border
import com.zapp.net.ui.theme.DiffAdd
import com.zapp.net.ui.theme.Layer1
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.Scrim
import com.zapp.net.ui.theme.Surface
import com.zapp.net.ui.theme.Surface2
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextFaint
import com.zapp.net.ui.theme.TextMid
import com.zapp.net.ui.theme.TextPrimary

/* The extensions rail: the same four things OpenCode's panel lists —
   MCP, Plugins, Skills, Configuration file — reading live server data. */

enum class ExtPanel { NONE, MENU, MCP, PLUGINS, SKILLS, PERMS, CONFIG }

/*
 * `details/i18n/en.ts` `setup.*.prompt`, verbatim. Their `ServiceSetup` starts a draft session in
 * the global config's directory and hands it one of these (`server-panel.tsx:1002-1018`); ours
 * starts a draft tab and puts the same words in the composer.
 */
private const val SETUP_MCP_PROMPT =
    "Help me add an MCP server to my global OpenCode config. Ask me which service I want to " +
        "connect, then set it up and check that it connects."

private const val SETUP_PLUGIN_PROMPT =
    "Help me add a plugin to my global OpenCode config. Ask me what I want it to do, suggest a " +
        "plugin, then add it."

private const val SETUP_SKILL_PROMPT =
    "Help me write a new OpenCode skill in my global config. Ask me what it should teach you, " +
        "then write its SKILL.md."

@Composable
fun ExtensionsOverlay(
    vm: MainViewModel,
    panel: ExtPanel,
    onAttachSkill: (com.zapp.net.api.SkillEntry) -> Unit = {},
    onOpenUsage: () -> Unit = {},
    /** `ctx.layout.draft({ prompt })` - a fresh draft tab whose composer already holds the prompt. */
    onDraft: (String) -> Unit = {},
    /** `builtinContext(ctx).layout.command("mcp.toggle")` - their `/mcp` dialog. */
    onManageMcp: () -> Unit = {},
    onChange: (ExtPanel) -> Unit,
) {
    val mcp by vm.mcpEntries.collectAsState()
    val skills by vm.skillEntries.collectAsState()
    val plugins by vm.pluginEntries.collectAsState()
    val config by vm.configJson.collectAsState()
    val perms by vm.savedPerms.collectAsState()
    val clipboard = LocalClipboardManager.current

    // Their machine mounts with the panel and unmounts with it (`Ee` `mounted`/`unmounted`);
    // `je` disposes it once the count reaches zero, and a disposed machine ignores every later
    // event - which is why `mcpMount` rebuilds a disposed state instead of poking it.
    val mounted = panel != ExtPanel.NONE
    DisposableEffect(mounted) {
        if (mounted) vm.mcpMount()
        onDispose { if (mounted) vm.mcpUnmount() }
    }

    // drill-in state, reset whenever the rail closes
    var openSkill by remember { mutableStateOf<com.zapp.net.api.SkillEntry?>(null) }
    var openServer by remember { mutableStateOf<String?>(null) }
    var openPlugin by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(panel) {
        if (panel != ExtPanel.NONE) vm.loadExtensions()
        if (panel == ExtPanel.PERMS) vm.loadSavedPermissions()
        if (panel != ExtPanel.SKILLS) openSkill = null
        if (panel != ExtPanel.MCP) openServer = null
        if (panel != ExtPanel.PLUGINS) openPlugin = null
    }

    AnimatedVisibility(
        visible = panel != ExtPanel.NONE,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(110)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Scrim)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { onChange(ExtPanel.NONE) },
        ) {
            AnimatedVisibility(
                visible = panel != ExtPanel.NONE,
                enter = slideInVertically(tween(160)) { -it / 6 } + fadeIn(tween(140)),
                exit = slideOutVertically(tween(120)) { -it / 8 } + fadeOut(tween(110)),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 68.dp, end = 8.dp),
            ) {
                Box(
                    Modifier
                        .widthIn(max = 320.dp)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Layer1)
                        .border(1.dp, Border, RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) { },
                ) {
                    when (panel) {
                        ExtPanel.NONE -> Unit
                        ExtPanel.MENU -> ExtMenu(
                            mcpCount = mcp.size,
                            pluginCount = plugins.size,
                            skillCount = skills.size,
                            permCount = perms.size,
                            onUsage = onOpenUsage,
                            onGo = onChange,
                        )
                        ExtPanel.PERMS -> PermsPanel(
                            rows = perms,
                            onForget = { vm.forgetPermission(it) },
                            onBack = { onChange(ExtPanel.MENU) },
                        )
                        ExtPanel.MCP -> McpPanel(
                            rows = mcp,
                            config = config,
                            open = openServer,
                            onOpen = { openServer = if (openServer == it) null else it },
                            busy = { vm.mcpBusy(it) },
                            onToggle = { name, on -> vm.toggleMcp(name, on) },
                            onConfig = { onChange(ExtPanel.CONFIG) },
                            onSetup = {
                                onChange(ExtPanel.NONE)
                                onDraft(SETUP_MCP_PROMPT)
                            },
                            onManage = {
                                onChange(ExtPanel.NONE)
                                onManageMcp()
                            },
                            onBack = { onChange(ExtPanel.MENU) },
                        )
                        ExtPanel.PLUGINS -> PluginsPanel(
                            rows = plugins,
                            open = openPlugin,
                            onOpen = { openPlugin = if (openPlugin == it) null else it },
                            checking = vm.pluginState.checking,
                            viewOf = { p ->
                                vm.pluginState.let { s ->
                                    com.zapp.net.api.PluginLifecycle.view(
                                        s,
                                        p.source?.target,
                                        p.source?.outdated,
                                    )
                                }
                            },
                            onCheck = { vm.checkPlugins() },
                            onUpdate = { vm.updatePlugin(it) },
                            onConfig = { onChange(ExtPanel.CONFIG) },
                            onSetup = {
                                onChange(ExtPanel.NONE)
                                onDraft(SETUP_PLUGIN_PROMPT)
                            },
                            onBack = { onChange(ExtPanel.MENU) },
                        )
                        ExtPanel.SKILLS -> {
                            val s = openSkill
                            if (s == null) {
                                SkillsPanel(
                                    rows = skills,
                                    onOpen = { openSkill = it },
                                    onConfig = { onChange(ExtPanel.CONFIG) },
                                    onSetup = {
                                        onChange(ExtPanel.NONE)
                                        onDraft(SETUP_SKILL_PROMPT)
                                    },
                                    onBack = { onChange(ExtPanel.MENU) },
                                )
                            } else {
                                SkillDetail(
                                    skill = s,
                                    onCopy = { clipboard.setText(AnnotatedString(s.content.orEmpty())) },
                                    onAttach = {
                                        onAttachSkill(s)
                                        onChange(ExtPanel.NONE)
                                        openSkill = null
                                    },
                                    onBack = { openSkill = null },
                                )
                            }
                        }
                        ExtPanel.CONFIG -> ConfigPanel(
                            raw = config,
                            onBack = { onChange(ExtPanel.MENU) },
                            onCopy = { clipboard.setText(AnnotatedString(config)) },
                        )
                    }
                    // One way out, on every level: their popover closes on outside press, a phone
                    // needs the visible X too.
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onChange(ExtPanel.NONE) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            OcIcons.Close,
                            "Close",
                            tint = TextPrimary.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtHeader(title: String, onBack: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Text(
                "‹",
                fontSize = 17.sp,
                color = TextMid,
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(title, fontSize = 11.5.sp, color = TextDim, modifier = Modifier.weight(1f))
    }
}

/**
 * `.session-summary-heading` - the card's title. It is a `.session-summary-row` too, so it keeps
 * the row metrics (28px min, 4px/12px padding) and only changes the colour and the weight.
 */
@Composable
private fun ExtHeading(title: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .padding(start = 12.dp, end = 36.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = TextFaint,
            fontWeight = FontWeight(440),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One row of the Extensions card.
 *
 * `details.css` `.session-summary-row`: `display:flex; align-items:center; gap:8px; min-height:28px;
 * padding:4px 12px; border-radius:4px; font-size:13px; font-weight:440`, raised to a 44px touch
 * target under `[data-component="session-summary-panel"]` at 767px and below - which is us.
 * A row carrying `.session-summary-menu-indicator` keeps `padding-inline-end:8px` instead of the
 * 36px a row without one reserves.
 */
@Composable
private fun ExtRow(
    icon: ImageVector?,
    label: String,
    count: Int? = null,
    chevron: Boolean = true,
    labelColor: Color = TextPrimary,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(
                enabled = onClick != null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { onClick?.invoke() }
            .padding(start = 12.dp, end = if (chevron) 8.dp else 36.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = TextFaint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = labelColor,
            fontWeight = FontWeight(440),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // `.session-service-count` - faint, 12px, tabular figures, never shrunk. Their `<Show
        // when={source.count}>` renders nothing for a zero, so a zero count is simply absent.
        if (count != null && count > 0) {
            Text(
                "$count",
                fontSize = 12.sp,
                color = TextFaint,
                fontWeight = FontWeight(440),
                maxLines = 1,
            )
        }
        if (chevron) {
            Spacer(Modifier.width(4.dp))
            Icon(OcIcons.ChevronRight, null, tint = TextFaint, modifier = Modifier.size(16.dp))
        }
    }
}

/** `.session-service-config-separator` - 1px `border-muted`, edge to edge inside the card. */
@Composable
private fun ExtDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

/**
 * `ServiceEmpty` (`server-panel.tsx:1182`): a `<strong>` at `padding:8px 12px`, faint, weight 440.
 * It is the whole body when a list is empty - not a disabled row with a glyph.
 */
@Composable
private fun ExtEmpty(title: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 13.sp, lineHeight = 18.sp, color = TextFaint, fontWeight = FontWeight(440))
    }
}

@Composable
private fun ExtMenu(
    mcpCount: Int,
    pluginCount: Int,
    skillCount: Int,
    permCount: Int,
    onUsage: () -> Unit,
    onGo: (ExtPanel) -> Unit,
) {
    Column(Modifier.widthIn(min = 230.dp, max = 300.dp)) {
        ExtHeading("Extensions")
        Column(
            Modifier.padding(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Their three services, in their order and with their own glyphs
            // (`server-panel.tsx:73-77`: mcp, cube, post-skill).
            ExtRow(OcIcons.Mcp, "MCP", mcpCount) { onGo(ExtPanel.MCP) }
            ExtRow(OcIcons.Cube, "Plugins", pluginCount) { onGo(ExtPanel.PLUGINS) }
            ExtRow(OcIcons.PostSkill, "Skills", skillCount) { onGo(ExtPanel.SKILLS) }
            // Ours, below a rule so the card above stays exactly theirs.
            ExtDivider()
            ExtRow(OcIcons.Status, ContextCopy.MOBILE_TITLE) { onUsage() }
            ExtRow(OcIcons.Gear, "Permissions", permCount) { onGo(ExtPanel.PERMS) }
        }
    }
}

/**
 * `Always` writes one of these onto the server (`/api/permission/saved`). Their settings
 * lets the user read them back and forget one; forgetting is the only way to be asked
 * again, so this panel is the undo for a rule he answered too fast.
 */
@Composable
private fun PermsPanel(
    rows: List<com.zapp.net.api.SavedPermission>,
    onForget: (com.zapp.net.api.SavedPermission) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.widthIn(min = 240.dp, max = 310.dp)) {
        ExtHeader("Permissions", onBack)
        if (rows.isEmpty()) {
            ExtEmpty("No saved rules")
        }
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            rows.forEach { r ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            r.action.ifBlank { "action" },
                            fontSize = 11.5.sp,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "Forget",
                            fontSize = 10.sp,
                            color = AccentSoft,
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .clickable { onForget(r) }
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                    Text(
                        r.resource,
                        fontSize = 10.sp,
                        color = TextDim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun McpPanel(
    rows: List<com.zapp.net.api.McpEntry>,
    config: String,
    open: String?,
    onOpen: (String) -> Unit,
    busy: (String) -> Boolean,
    onToggle: (String, Boolean) -> Unit,
    onConfig: () -> Unit,
    onSetup: () -> Unit,
    onManage: () -> Unit,
    onBack: () -> Unit,
) {
    val defs = remember(config) { mcpServerDefs(config) }
    Column(Modifier.widthIn(min = 250.dp, max = 320.dp)) {
        ExtHeader("MCP", onBack)
        if (rows.isEmpty()) ExtEmpty("No MCP servers configured")
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            rows.forEach { m ->
                val detail = defs[m.name]
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 38.dp)
                            .clickable { onOpen(m.name) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(6.dp).background(if (m.connected) DiffAdd else TextDim, CircleShape),
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(m.name, fontSize = 12.5.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                        // `mcp.status.*` - the same four labels their row carries.
                        Text(m.stateLabel, fontSize = 10.5.sp, color = if (m.connected) DiffAdd else TextDim)
                        Spacer(Modifier.width(8.dp))
                        McpSwitch(
                            on = m.connected,
                            enabled = !busy(m.name) && m.status?.status != "pending",
                        ) { onToggle(m.name, !m.connected) }
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            OcIcons.ChevronDown,
                            if (open == m.name) "Collapse" else "Expand",
                            tint = TextDim,
                            modifier = Modifier
                                .size(11.dp)
                                .rotate(if (open == m.name) 180f else 0f),
                        )
                    }
                    if (open == m.name) {
                        Column(Modifier.fillMaxWidth().padding(start = 27.dp, end = 12.dp, bottom = 8.dp)) {
                            if (detail == null) {
                                Text("No entry in opencode.json", fontSize = 10.5.sp, color = TextDim)
                            } else {
                                val type = detail["type"]?.jsonPrimitive?.contentOrNull ?: "local"
                                val cmd = detail["command"]?.jsonArray
                                    ?.joinToString(" ") { it.jsonPrimitive.content } ?: ""
                                DetailLine("type", type)
                                if (cmd.isNotBlank()) DetailLine("command", cmd)
                                detail["environment"]?.jsonObject?.forEach { (k, v) ->
                                    DetailLine(k, v.jsonPrimitive.content)
                                }
                                if (detail["disabled"]?.jsonPrimitive?.booleanOrNull == true) {
                                    DetailLine("disabled", "true")
                                }
                            }
                        }
                    }
                }
            }
        }
        ExtDivider()
        // `useMcp` footer (`server-panel.tsx:695-713`): the config link, then `mcp.manage`. When the
        // list is empty it is `ServiceEmptyFooter` instead - `setup.mcp` and the config link.
        if (rows.isEmpty()) {
            ExtRow(OcIcons.Plus, "Add a server with OpenCode") { onSetup() }
            ExtRow(OcIcons.Gear, "Configuration file") { onConfig() }
        } else {
            ExtRow(OcIcons.Gear, "Configuration file") { onConfig() }
            ExtRow(OcIcons.Mcp, "Manage MCP servers") { onManage() }
        }
    }
}

/**
 * The MCP row's toggle.
 *
 * Their control is the `settings-extension-trailing` switch. Ours is drawn rather than taken from
 * Material: a 30x17 track with a 13dp knob, `Accent` when on, `Border` when off. The tap is
 * swallowed so it does not also expand the row underneath.
 */
@Composable
private fun McpSwitch(on: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val track = when {
        !enabled -> Line
        on -> Accent.copy(alpha = 0.55f)
        else -> Border
    }
    Box(
        Modifier
            .size(width = 30.dp, height = 17.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(track)
            .clickable(
                enabled = enabled,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { onToggle() },
    ) {
        Box(
            Modifier
                .padding(2.dp)
                .size(13.dp)
                .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                .clip(CircleShape)
                .background(if (enabled) TextPrimary else TextDim),
        )
    }
}

@Composable
private fun DetailLine(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(key, fontSize = 10.sp, color = TextDim, modifier = Modifier.width(64.dp))
        Text(
            value,
            fontSize = 10.sp,
            color = TextMid,
            fontFamily = FontFamily.Monospace,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** mcp.servers out of the live /api/config document. */
private fun mcpServerDefs(raw: String): Map<String, kotlinx.serialization.json.JsonObject> =
    runCatching {
        val docs = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonArray
        val doc = docs.firstOrNull {
            it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "document"
        } ?: return emptyMap()
        val servers = doc.jsonObject["info"]?.jsonObject
            ?.get("mcp")?.jsonObject
            ?.get("servers")?.jsonObject ?: return emptyMap()
        servers.mapValues { it.value.jsonObject }
    }.getOrDefault(emptyMap())

@Composable
private fun PluginsPanel(
    rows: List<com.zapp.net.api.PluginEntry>,
    open: String?,
    onOpen: (String) -> Unit,
    checking: Boolean,
    viewOf: (com.zapp.net.api.PluginEntry) -> com.zapp.net.api.PluginLifecycle.View,
    onCheck: () -> Unit,
    onUpdate: (String) -> Unit,
    onConfig: () -> Unit,
    onSetup: () -> Unit,
    onBack: () -> Unit,
) {
    val user = remember(rows) { rows.filter { it.sourceType != "builtin" } }
    Column(Modifier.widthIn(min = 250.dp, max = 320.dp)) {
        ExtHeader("Plugins", onBack)
        // Their header action. `qs` is `status === 'checking'`, and `check.requested` is dropped
        // while a check is already running, so the label is the only thing that changes.
        ExtRow(
            OcIcons.Cube,
            if (checking) "Checking…" else "Check for updates",
            chevron = false,
        ) { if (!checking) onCheck() }
        if (rows.isEmpty()) ExtEmpty("No plugins configured") else
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                user.forEach { p ->
                    PluginRow(
                        p = p,
                        expanded = open == p.id,
                        view = viewOf(p),
                        onUpdate = onUpdate,
                    ) { onOpen(p.id) }
                }
                if (rows.size > user.size) {
                    Text(
                        "${rows.size - user.size} built-in",
                        fontSize = 10.sp,
                        color = TextDim,
                        modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 2.dp),
                    )
                    rows.filter { it.sourceType == "builtin" }.take(6).forEach { p ->
                        PluginRow(
                            p = p,
                            expanded = open == p.id,
                            view = viewOf(p),
                            onUpdate = onUpdate,
                        ) { onOpen(p.id) }
                    }
                }
            }
        ExtDivider()
        // `useCatalog` footer (`server-panel.tsx:851-861`): `setup.plugins` only while the list is
        // empty, then the config link. A non-empty list drops the setup row.
        if (rows.isEmpty()) {
            ExtRow(OcIcons.Plus, "Add a plugin with OpenCode") { onSetup() }
        }
        ExtRow(OcIcons.Gear, "Configuration file") { onConfig() }
    }
}

@Composable
private fun PluginRow(
    p: com.zapp.net.api.PluginEntry,
    expanded: Boolean,
    view: com.zapp.net.api.PluginLifecycle.View,
    onUpdate: (String) -> Unit,
    onClick: () -> Unit,
) {
    val target = p.source?.target
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 34.dp)
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(OcIcons.Cube, null, tint = TextFaint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                p.id,
                fontSize = 12.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when {
                // `Ks` - `updating` wins over `outdated`, because a target mid-update must not
                // also offer the action that would start a second one.
                view.updating -> Text("updating…", fontSize = 10.sp, color = TextDim)
                view.outdated && target != null -> Text(
                    "Update",
                    fontSize = 10.5.sp,
                    color = Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable { onUpdate(target) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                else -> Text(p.sourceType, fontSize = 10.sp, color = TextDim)
            }
        }
        if (expanded) {
            Row(Modifier.fillMaxWidth().padding(start = 34.dp, end = 12.dp, bottom = 6.dp)) {
                DetailLine("state", p.state?.status ?: "unknown")
            }
        }
    }
}

@Composable
private fun SkillsPanel(
    rows: List<com.zapp.net.api.SkillEntry>,
    onOpen: (com.zapp.net.api.SkillEntry) -> Unit,
    onConfig: () -> Unit,
    onSetup: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.widthIn(min = 250.dp, max = 320.dp)) {
        ExtHeader("Skills", onBack)
        if (rows.isEmpty()) ExtEmpty("No skills configured") else
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(rows, key = { it.id }) { s ->
                    // `session-service-row.session-service-file` (`server-panel.tsx:835-845`):
                    // a `file-document` glyph, then the name. No chevron - the whole row is the link.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clickable { onOpen(s) }
                            .padding(start = 12.dp, end = 36.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(OcIcons.FileDocument, null, tint = TextFaint, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            s.id,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = TextPrimary,
                            fontWeight = FontWeight(440),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        ExtDivider()
        // `useCatalog` footer for skills (`server-panel.tsx:855-858`): `setup.skills` is shown
        // whether the list is empty or not - only plugins drop it.
        ExtRow(OcIcons.Plus, "New skill with OpenCode") { onSetup() }
        ExtRow(OcIcons.Gear, "Configuration file") { onConfig() }
    }
}

/** Their skill page: description, source path, and the real SKILL.md body. */
@Composable
private fun SkillDetail(
    skill: com.zapp.net.api.SkillEntry,
    onCopy: () -> Unit,
    onAttach: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.widthIn(min = 250.dp, max = 320.dp)) {
        ExtHeader(skill.name.ifBlank { skill.id }, onBack)
        skill.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                color = TextMid,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            )
        }
        skill.path?.let {
            Text(
                it,
                fontSize = 9.5.sp,
                color = TextDim,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            )
        }
        ExtDivider()
        val body = skill.content.orEmpty()
        if (body.isBlank()) {
            Text(
                "No content returned for this skill",
                fontSize = 10.5.sp,
                color = TextDim,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            )
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    body.take(12000),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    lineHeight = 12.5.sp,
                    color = TextMid,
                )
            }
        }
        ExtDivider()
        ExtRow(
            OcIcons.FileDocument,
            "Attach to prompt",
            chevron = false,
            onClick = onAttach,
        )
        ExtRow(
            OcIcons.Copy,
            "Copy skill",
            chevron = false,
            onClick = onCopy,
        )
    }
}

@Composable
private fun ConfigPanel(raw: String, onBack: () -> Unit, onCopy: () -> Unit) {
    Column(Modifier.widthIn(min = 260.dp, max = 320.dp)) {
        ExtHeader("Configuration", onBack)
        val pretty = remember(raw) { prettyJson(raw) }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            Text(
                pretty.take(6000),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp,
                lineHeight = 13.sp,
                color = TextMid,
            )
        }
        ExtDivider()
        ExtRow(OcIcons.Copy, "Copy configuration", chevron = false) { onCopy() }
    }
}

private fun prettyJson(raw: String): String =
    runCatching {
        val el = kotlinx.serialization.json.Json.parseToJsonElement(raw)
        kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(
            kotlinx.serialization.json.JsonElement.serializer(), el,
        )
    }.getOrDefault(raw)

