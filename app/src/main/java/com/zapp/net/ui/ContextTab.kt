package com.zapp.net.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.MainViewModel
import com.zapp.net.api.ContextStats
import com.zapp.net.api.MessageInfo
import com.zapp.net.api.ModelInfo
import com.zapp.net.api.Provider
import com.zapp.net.api.Session
import com.zapp.net.ui.theme.Bg
import com.zapp.net.ui.theme.Border
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.Surface
import com.zapp.net.ui.theme.Surface2
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextFaint
import com.zapp.net.ui.theme.TextMid
import com.zapp.net.ui.theme.TextPrimary

/**
 * The Context panel, ported from `gui-plugins/src/context/tab.tsx`, recovered from the desktop
 * bundle's lazy chunk `out/renderer/assets/tab-CqEgFar3.js.map`.
 *
 * Their panel is a `BuiltinPanel` (`context/renderer.tsx`):
 *
 * ```
 * { id: "main", region: "side", legacy: { context: "main" },
 *   mobile: { title: "Usage", order: 40, kind: "menu", icon: "status" },
 *   list: (input) => input.open.includes("main") ? [tab] : [],
 *   closed: () => ({ icon: "status" }) }
 * ```
 *
 * and its body is, verbatim:
 *
 * ```
 * <div class="@container h-full overflow-y-auto" data-scrollable>
 *   <div data-slot="session-usage-content"
 *        class="px-4 pt-4 pb-6 flex flex-col gap-6 md:px-6 md:pb-10 md:gap-10">
 *     <div class="grid grid-cols-1 @[32rem]:grid-cols-2 gap-4">  16 x Stat
 *     <Show when={systemPrompt()}>  label + bordered Markdown box
 *     <div class="flex flex-col gap-2">  Raw messages header + Accordion
 * ```
 *
 * `Stat` is `flex flex-col gap-1`: a `text-12-regular text-text-weak` label over a
 * `text-12-medium text-text-strong` value, and a stat whose value is falsy is **not rendered**
 * (`<Show when={stat.value()}>`) - which is how `Total Cost` disappears on a zero-cost
 * `opencode-go` session.
 *
 * All sixteen rows and their arithmetic live in [ContextStats], a transcription of their `H`
 * table; this file only draws them.
 */

/** The full-screen destination their phone build calls "Usage" (`mobile.title`). */
@Composable
fun ContextTab(vm: MainViewModel, onClose: () -> Unit) {
    val totals by vm.sessionTotals.collectAsState()
    val messages by vm.messages.collectAsState()
    val models by vm.models.collectAsState()
    val providers by vm.providers.collectAsState()
    val sessions by vm.sessions.collectAsState()

    // `Modifier.statusBarsPadding()`: Android 15 draws every window edge to edge, so a
    // full-screen destination has to inset itself - otherwise the clock lands on the header.
    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
        // Their phone opens this from the `More` menu as a full route; ours is an overlay, so it
        // needs the one control their route header would have supplied.
        Row(
            Modifier
                .fillMaxWidth()
                .background(Surface)
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Usage", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f))
            Box(
                Modifier.size(28.dp).clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Text("\u2715", fontSize = 13.sp, color = TextFaint)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        ContextBody(totals, messages.map { m -> m.info }, models, providers, sessions)
    }
}

/** The side-region tab body - what the header's usage pill toggles. */
@Composable
fun SessionContextPanel(vm: MainViewModel) {
    val totals = vm.sessionTotals.collectAsState().value
    val messages = vm.messages.collectAsState().value
    val models = vm.models.collectAsState().value
    val providers = vm.providers.collectAsState().value
    val sessions = vm.sessions.collectAsState().value
    ContextBody(totals, messages.map { m -> m.info }, models, providers, sessions)
}

@Composable
private fun ContextBody(
    session: Session?,
    infos: List<MessageInfo>,
    models: List<ModelInfo>,
    providers: List<Provider>,
    sessions: List<Session>,
) {
    // `R(e, t)` - the message the whole panel is about. Their `t` is
    // `info()?.revert?.messageID`, read off the session, not guessed.
    val revertId = remember(session) {
        (session?.revert as? kotlinx.serialization.json.JsonObject)
            ?.let { it["messageID"] as? kotlinx.serialization.json.JsonPrimitive }
            ?.content
    }
    val usageMessage = remember(infos, revertId) {
        ContextStats.lastAssistantWithUsage(infos, revertId)
    }
    // `ie(session, model)` - their catalog lookup. The message carries the model it ran on.
    val modelMeta = remember(usageMessage, models) {
        models.firstOrNull {
            it.modelID == (usageMessage?.modelID ?: session?.model?.id) &&
                it.providerID == (usageMessage?.providerID ?: session?.model?.providerID)
        }
    }
    val providerName = remember(modelMeta, providers) {
        providers.firstOrNull { it.id == modelMeta?.providerID }?.name
    }
    val usage = remember(infos, revertId, modelMeta, providerName) {
        ContextStats.usage(
            messages = infos,
            revertMessageId = revertId,
            providerName = providerName,
            modelName = modelMeta?.name,
            limitContext = modelMeta?.limit?.context,
        )
    }
    // `A()` - null means the row is not drawn at all.
    val cost = remember(session, infos, sessions) {
        ContextStats.cost(
            cost = familyCost(session, sessions),
            sessionProviderId = session?.model?.providerID,
            messages = infos,
        )
    }
    val rows = remember(session, infos, usage, cost) {
        ContextStats.rows(
            sessionTitle = session?.title,
            sessionId = session?.id,
            sessionCreated = session?.time?.created,
            messages = infos,
            usage = usage,
            cost = cost,
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // `grid-cols-1 @[32rem]:grid-cols-2` is a container query: the column count follows the
        // pane's own width, not the window's. 32rem = 512dp.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val twoUp = maxWidth >= 512.dp
            val visible = rows.filter { it.value.isNotEmpty() }
            if (twoUp) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    visible.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth()) {
                            Box(Modifier.weight(1f)) { Stat(pair[0].label, pair[0].value) }
                            Spacer(Modifier.width(16.dp))
                            // A lone last row keeps the left column's width, like `grid` does.
                            Box(Modifier.weight(1f)) {
                                pair.getOrNull(1)?.let { Stat(it.label, it.value) }
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    visible.forEach { Stat(it.label, it.value) }
                }
            }
        }

        // Their system-prompt block is `<Show when={systemPrompt()}>`; we cannot reach it yet -
        // `MessageInfo` has no `text`, so the block stays hidden rather than printing a guess.
        // Recorded as a gap in ZAPP-PARITY-MATRIX.md.

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Raw messages", fontSize = 12.sp, color = TextDim)
            infos.forEach { message -> RawMessageRow(message) }
        }
    }
}

/**
 * `session.cost(id)` - the root session's own cost plus every descendant's, walked through
 * `parentID`. A child session reports only its own cost, exactly as their `A()` does.
 */
private fun familyCost(session: Session?, sessions: List<Session>): Double {
    val root = session ?: return 0.0
    if (root.parentId != null) return root.cost ?: 0.0
    val byParent = sessions.groupBy { it.parentId }
    var sum = 0.0
    val seen = mutableSetOf<String>()
    val queue = ArrayDeque<String>().apply { add(root.id) }
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (!seen.add(id)) continue
        val s = if (id == root.id) root else sessions.firstOrNull { it.id == id }
        sum += s?.cost ?: 0.0
        byParent[id]?.forEach { queue.add(it.id) }
    }
    return sum
}

/** `Stat`: `flex flex-col gap-1`, weak 12px label over strong 12px medium value. */
@Composable
private fun Stat(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
    }
}

/**
 * One `RawMessage`: their `Accordion.Item value={message.id}`.
 *
 * The trigger is `{type} — {id}` truncated with the created time on the right; the content is
 * `JSON.stringify(message, null, 2)` in a `File mode="text" overflow="wrap"` block.
 *
 * Their raw object is the server message, which still carries `content`; ours is the decoded
 * [MessageInfo] re-serialized, so the row's shape is theirs and the payload is what this client
 * holds. Recorded as a gap rather than passed off as identical.
 */
@Composable
private fun RawMessageRow(message: MessageInfo) {
    var open by remember(message.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = (message.type ?: message.role).orEmpty(),
                fontSize = 12.sp,
                color = TextMid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                " \u2014 ${message.id}",
                fontSize = 12.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                ContextStats.time(message.time?.created),
                fontSize = 12.sp,
                color = TextDim,
                maxLines = 1,
            )
            Spacer(Modifier.width(12.dp))
            // `chevron-grabber-vertical` is two stacked chevrons; a rotating caret is the same
            // affordance without inventing a glyph their catalog does not pair with this row.
            Text(if (open) "\u2303" else "\u2304", fontSize = 11.sp, color = TextDim)
        }
        if (open) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Surface2, RoundedCornerShape(6.dp))
                    .border(1.dp, Border, RoundedCornerShape(6.dp))
                    .padding(12.dp),
            ) {
                Text(
                    remember(message.id) {
                        ContextStats.PRETTY.encodeToString(MessageInfo.serializer(), message)
                    },
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextMid,
                )
            }
        }
    }
}
