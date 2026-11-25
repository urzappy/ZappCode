package com.zapp.net.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Context tab's arithmetic, kept out of the UI so it can be proven against their own code.
 *
 * Every rule here is transcribed from `tab-CbfQLfWC.js`:
 *
 *  - `R(e,t)` (`lastAssistantWithUsage`) picks the message the whole panel is about:
 *    ```js
 *    function R(e,t){
 *      let n = t ? e.findIndex(e=>e.id===t) : -1;
 *      if (t && n === -1) return;
 *      let r = n === -1 ? e.length : n;
 *      let i = e.findLastIndex((e,t)=> e.type===`compaction` && e.status===`completed` && t<r);
 *      return e.findLast((e,t)=> e.type===`assistant` && e.tokens!==undefined && t>i && t<r);
 *    }
 *    ```
 *    A revert that is not in the list yields nothing at all - that is the `return` on line 3,
 *    not a fallback.
 *  - `O` builds the usage block from that message, `F` counts messages by type, and `H` is the
 *    sixteen-row table.
 *  - `se(locale)` is the formatter: `number` is `toLocaleString` with `-` for null, `percent`
 *    appends `%`, `time` is `Intl.DateTimeFormat` with
 *    `{year:numeric, month:short, day:numeric, hour:numeric, minute:numeric}`.
 *  - `A` is the cost: a zero cost is **hidden** only when the provider is `opencode-go`.
 */
object ContextStats {

    /** One row of their `H` table: a `stats.*` key and the string it resolved to. */
    data class Row(val label: String, val value: String)

    /** The `O` object. Every field is nullable exactly where theirs can be `undefined`. */
    data class Usage(
        val messageId: String,
        val createdAt: Long?,
        val providerLabel: String,
        val modelLabel: String,
        val limit: Long?,
        val input: Long,
        val output: Long,
        val reasoning: Long,
        val cacheRead: Long,
        val cacheWrite: Long,
        val total: Long,
        val usagePercent: Int?,
    )

    /** `F()` - message counts by type. */
    data class Counts(val all: Int, val user: Int, val assistant: Int)

    /**
     * `R(e, t)`: the last assistant message that carries `tokens`, ignoring anything at or after
     * the revert point and anything before the last completed compaction.
     */
    fun lastAssistantWithUsage(messages: List<MessageInfo>, revertMessageId: String?): MessageInfo? {
        val n = if (revertMessageId != null) {
            messages.indexOfFirst { it.id == revertMessageId }
        } else -1
        // Their `if (t && n === -1) return` - an unknown revert id means "no usage", not "all".
        if (revertMessageId != null && n == -1) return null
        val boundary = if (n == -1) messages.size else n
        // `findLastIndex` over the slice before the boundary.
        var compaction = -1
        for (k in 0 until boundary) {
            val m = messages[k]
            if (m.type == "compaction" && m.status == "completed") compaction = k
        }
        // `findLast` over (compaction, boundary), strictly after the compaction.
        for (k in boundary - 1 downTo compaction + 1) {
            val m = messages[k]
            if (m.type == "assistant" && m.tokens != null) return m
        }
        return null
    }

    /** `F()` - `all`, `user` and `assistant` counts. Their `all` is the raw list length. */
    fun counts(messages: List<MessageInfo>): Counts = Counts(
        all = messages.size,
        user = messages.count { it.type == "user" },
        assistant = messages.count { it.type == "assistant" },
    )

    /**
     * `O` - the usage block, or null when there is no message with tokens.
     *
     * `ie(session, model)` is their catalog lookup; [modelName] and [limitContext] are what it
     * yields, so the caller passes them in rather than this object reaching for a registry.
     */
    fun usage(
        messages: List<MessageInfo>,
        revertMessageId: String?,
        providerName: String?,
        modelName: String?,
        limitContext: Long?,
    ): Usage? {
        val m = lastAssistantWithUsage(messages, revertMessageId) ?: return null
        val t = m.tokens ?: return null
        val cacheRead = t.cache?.read ?: 0
        val cacheWrite = t.cache?.write ?: 0
        val total = t.input + t.output + t.reasoning + cacheRead + cacheWrite
        return Usage(
            messageId = m.id,
            createdAt = m.time?.created,
            // `t?.provider.name ?? e.model.providerID`
            providerLabel = providerName ?: m.providerID.orEmpty(),
            // `t?.model?.name ?? e.model.id`
            modelLabel = modelName ?: m.modelID.orEmpty(),
            limit = limitContext,
            input = t.input,
            output = t.output,
            reasoning = t.reasoning,
            cacheRead = cacheRead,
            cacheWrite = cacheWrite,
            total = total,
            // `t?.model?.limit.context ? Math.round(n / limit * 100) : null`
            usagePercent = if (limitContext != null && limitContext != 0L) {
                Math.round(total.toDouble() / limitContext.toDouble() * 100.0).toInt()
            } else null,
        )
    }

    /**
     * `A()` - the total-cost cell.
     *
     * ```js
     * if (e !== 0 || (T()?.model ?? E().findLast(e=>e.type===`assistant`)?.model)?.providerID
     *     !== `opencode-go`) return D().format(e)
     * ```
     *
     * Returns null for the one case that shows nothing: a zero cost on an `opencode-go` model.
     */
    fun cost(
        cost: Double,
        sessionProviderId: String?,
        messages: List<MessageInfo>,
    ): String? {
        val provider = sessionProviderId
            ?: messages.lastOrNull { it.type == "assistant" }?.providerID
        if (cost != 0.0 || provider != "opencode-go") return currency(cost)
        return null
    }

    /** `new Intl.NumberFormat(locale,{style:"currency",currency:"USD"}).format(e)` */
    fun currency(v: Double): String = String.format(java.util.Locale.US, "$%,.2f", v)

    /**
     * The sixteen rows of `H`, in their order, with their labels from `stats.*`.
     *
     * [sessionTitle] and [sessionId] feed `stats.session`: `T()?.title ?? (id || "-")`.
     * [sessionCreated] is `T()?.time.created`.
     */
    fun rows(
        sessionTitle: String?,
        sessionId: String?,
        sessionCreated: Long?,
        messages: List<MessageInfo>,
        usage: Usage?,
        cost: String?,
    ): List<Row> {
        val c = counts(messages)
        return listOf(
            Row("Session", sessionTitle ?: (sessionId ?: "-")),
            Row("Messages", number(c.all)),
            Row("Provider", usage?.providerLabel ?: "-"),
            Row("Model", usage?.modelLabel ?: "-"),
            Row("Context Limit", number(usage?.limit)),
            Row("Total Tokens", number(usage?.total)),
            Row("Usage", percent(usage?.usagePercent)),
            Row("Input Tokens", number(usage?.input)),
            Row("Output Tokens", number(usage?.output)),
            Row("Reasoning Tokens", number(usage?.reasoning)),
            Row("Cache Tokens (read/write)", number(usage?.cacheRead) + " / " + number(usage?.cacheWrite)),
            Row("User Messages", number(c.user)),
            Row("Assistant Messages", number(c.assistant)),
            Row("Total Cost", cost ?: ""),
            Row("Session Created", time(sessionCreated)),
            Row("Last Activity", time(usage?.createdAt)),
        )
    }

    /**
     * `number(t){ return t == null ? "-" : t.toLocaleString(e) }` - and `toLocaleString` groups
     * thousands with commas in every locale this app ships.
     */
    fun number(v: Number?): String {
        if (v == null) return "-"
        return when (v) {
            is Long -> String.format(java.util.Locale.US, "%,d", v)
            is Int -> String.format(java.util.Locale.US, "%,d", v)
            is Double -> if (v == Math.floor(v) && !v.isInfinite()) {
                String.format(java.util.Locale.US, "%,d", v.toLong())
            } else v.toString()
            else -> v.toString()
        }
    }

    /** `percent(t){ return t == null ? "-" : t.toLocaleString(e)+"%" }` */
    fun percent(v: Int?): String = if (v == null) "-" else number(v) + "%"

    /**
     * `time(e){ return e ? t.format(e) : "-" }` with
     * `{year:numeric,month:short,day:numeric,hour:numeric,minute:numeric}`.
     *
     * That is en-US `Nov 21, 2026, 6:47 PM`. A zero or negative timestamp is falsy to their `e ?`
     * test, so it also prints `-`.
     */
    fun time(ms: Long?): String {
        if (ms == null || ms <= 0L) return "-"
        val fmt = java.text.SimpleDateFormat("MMM d, yyyy, h:mm a", java.util.Locale.US)
        return fmt.format(java.util.Date(ms))
    }

    /**
     * `sm(info)` - the export filename.
     *
     * ```js
     * `${(e.title||e.slug||e.id).toLowerCase().replace(/[^a-z0-9_-]+/gi,'-')
     *   .slice(0,250).replace(/^-+|-+$/g,'') || e.id}.json`
     * ```
     */
    fun exportFilename(title: String?, id: String): String {
        val base = (title?.takeIf { it.isNotEmpty() } ?: id)
            .lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .take(250)
            .trim('-')
        return (base.ifEmpty { id }) + ".json"
    }

    /**
     * The saved document. Their `ae()` builds `{info, messages}` from `session.get` plus every
     * page of `message.list`, and `lm()` writes `JSON.stringify(data, null, 2)`.
     */
    fun exportJson(info: JsonObject, messages: List<JsonObject>): String {
        val doc = buildJsonObject {
            put("info", info)
            put("messages", JsonArray(messages))
        }
        return PRETTY.encodeToString(JsonObject.serializer(), doc)
    }

    /** `JSON.stringify(x, null, 2)` - two-space indent, no trailing newline. */
    val PRETTY = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }

    /**
     * The system prompt: their `E().findLast(e=>e.type==='system')?.text`, trimmed.
     *
     * Their message union puts `text` on the message itself for a `system` row - not inside
     * `content` - so this reads the raw object rather than the decoded one.
     */
    fun systemPrompt(raw: List<JsonObject>): String? {
        val last = raw.lastOrNull {
            (it["type"] as? JsonPrimitive)?.content == "system"
        } ?: return null
        val text = (last["text"] as? JsonPrimitive)?.content.orEmpty().trim()
        return text.ifEmpty { null }
    }

    /** `JSON.stringify(e.message,null,2)` for one raw row. */
    fun rawMessageJson(o: JsonObject): String = PRETTY.encodeToString(JsonObject.serializer(), o)

    /** Their `I(e)` trigger shows `message.type`, `message.id` and `time(created)`. */
    fun rawMessageLabel(o: JsonObject): Triple<String, String, Long?> {
        fun s(k: String) = (o[k] as? JsonPrimitive)?.content
        val time = ((o["time"] as? JsonObject)?.get("created") as? JsonPrimitive)
            ?.content?.toLongOrNull()
        return Triple(s("type") ?: "", s("id") ?: "", time)
    }
}
