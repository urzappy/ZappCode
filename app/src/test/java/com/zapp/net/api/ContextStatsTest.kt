package com.zapp.net.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every expectation in this file came out of `theirs-context.mjs`, which is
 * `tab-CbfQLfWC.js`'s `R` / `F` / `O` / `H` / `se` / `sm` pasted verbatim and run in Node.
 * Nothing here was written from reading the code - it was read off the output.
 */
class ContextStatsTest {

    private fun m(
        id: String,
        type: String,
        status: String? = null,
        providerID: String? = null,
        modelID: String? = null,
        created: Long? = null,
        input: Long? = null,
        output: Long = 0,
        reasoning: Long = 0,
        cacheRead: Long = 0,
        cacheWrite: Long = 0,
    ) = MessageInfo(
        id = id,
        role = type,
        type = type,
        status = status,
        providerID = providerID,
        modelID = modelID,
        time = created?.let { MsgTime(created = it) },
        tokens = input?.let {
            TokenUse(
                input = it,
                output = output,
                reasoning = reasoning,
                cache = TokenCacheUse(cacheRead, cacheWrite),
            )
        },
    )

    // The A fixture: 4 messages, the last assistant at m4 with 31,900 total tokens.
    private val A = listOf(
        m("m1", "user"),
        m(
            "m2", "assistant", providerID = "opencode", modelID = "deepseek-v4.1",
            created = 1763736000000, input = 511, output = 494, reasoning = 91,
            cacheRead = 116736, cacheWrite = 2000,
        ),
        m("m3", "user"),
        m(
            "m4", "assistant", providerID = "opencode", modelID = "deepseek-v4.1",
            created = 1763736600000, input = 1200, output = 700, reasoning = 0,
            cacheRead = 30000, cacheWrite = 0,
        ),
    )

    // B: a completed compaction resets the window, so only b3 counts.
    private val B = listOf(
        m("b1", "assistant", providerID = "opencode", modelID = "deepseek-v4.1", created = 1, input = 1, output = 1),
        m("b2", "compaction", status = "completed"),
        m("b3", "assistant", providerID = "opencode", modelID = "deepseek-v4.1", created = 2, input = 9, output = 9),
    )

    // C: reverting to c2 must leave c1 as the answer, not c3.
    private val C = listOf(
        m("c1", "assistant", providerID = "opencode", modelID = "deepseek-v4.1", created = 5, input = 4, output = 4),
        m("c2", "user"),
        m("c3", "assistant", providerID = "opencode", modelID = "deepseek-v4.1", created = 6, input = 7, output = 7),
    )

    // ---- R ----

    @Test
    fun `last assistant with usage is the newest one carrying tokens`() {
        assertEquals("m4", ContextStats.lastAssistantWithUsage(A, null)?.id)
    }

    @Test
    fun `a revert boundary stops the search at that message`() {
        assertEquals("m2", ContextStats.lastAssistantWithUsage(A, "m3")?.id)
        assertEquals("c1", ContextStats.lastAssistantWithUsage(C, "c2")?.id)
    }

    @Test
    fun `a revert id that is not in the list yields nothing at all`() {
        // Their line 3 is `if (t && n === -1) return;` - a bare return, not a fallback.
        assertNull(ContextStats.lastAssistantWithUsage(A, "nope"))
    }

    @Test
    fun `a completed compaction hides everything before it`() {
        assertEquals("b3", ContextStats.lastAssistantWithUsage(B, null)?.id)
    }

    // ---- F ----

    @Test
    fun `counts follow the message types`() {
        val c = ContextStats.counts(A)
        assertEquals(4, c.all)
        assertEquals(2, c.user)
        assertEquals(2, c.assistant)
    }

    // ---- O ----

    @Test
    fun `usage totals input output reasoning and both cache halves`() {
        val u = ContextStats.usage(A, null, "OpenCode Zen", "DeepSeek V4.1", 200000)!!
        assertEquals("OpenCode Zen", u.providerLabel)
        assertEquals("DeepSeek V4.1", u.modelLabel)
        assertEquals(200000L, u.limit)
        assertEquals(31900L, u.total)
        assertEquals(16, u.usagePercent)
        assertEquals(1200L, u.input)
        assertEquals(700L, u.output)
        assertEquals(0L, u.reasoning)
        assertEquals(30000L, u.cacheRead)
        assertEquals(0L, u.cacheWrite)
        assertEquals(1763736600000L, u.createdAt)
    }

    @Test
    fun `no message with tokens means no usage block`() {
        assertNull(ContextStats.usage(listOf(m("x", "user")), null, null, null, null))
    }

    @Test
    fun `an unknown model in the catalog falls back to the message ids`() {
        val u = ContextStats.usage(A, null, null, null, null)!!
        assertEquals("opencode", u.providerLabel)
        assertEquals("deepseek-v4.1", u.modelLabel)
        assertNull(u.limit)
        assertNull(u.usagePercent)
    }

    // ---- H ----

    @Test
    fun `the sixteen rows match theirs on a normal session`() {
        val u = ContextStats.usage(A, null, "OpenCode Zen", "DeepSeek V4.1", 200000)
        val rows = ContextStats.rows(
            sessionTitle = "Fix the build",
            sessionId = "ses_1",
            sessionCreated = 1763730000000,
            messages = A,
            usage = u,
            cost = ContextStats.cost(0.0342, "opencode", A),
        )
        assertEquals(
            listOf(
                "Session" to "Fix the build",
                "Messages" to "4",
                "Provider" to "OpenCode Zen",
                "Model" to "DeepSeek V4.1",
                "Context Limit" to "200,000",
                "Total Tokens" to "31,900",
                "Usage" to "16%",
                "Input Tokens" to "1,200",
                "Output Tokens" to "700",
                "Reasoning Tokens" to "0",
                "Cache Tokens (read/write)" to "30,000 / 0",
                "User Messages" to "2",
                "Assistant Messages" to "2",
                "Total Cost" to "$0.03",
                "Session Created" to "Nov 21, 2025, 9:00 PM",
                "Last Activity" to "Nov 21, 2025, 10:50 PM",
            ),
            rows.map { it.label to it.value },
        )
    }

    @Test
    fun `an empty session shows dashes and a zero dollar cost`() {
        val rows = ContextStats.rows(
            sessionTitle = null,
            sessionId = "ses_2",
            sessionCreated = 1763730000000,
            messages = emptyList(),
            usage = null,
            cost = ContextStats.cost(0.0, null, emptyList()),
        )
        assertEquals("ses_2", rows[0].value)          // `title ?? (id || "-")`
        assertEquals("-", rows[2].value)
        assertEquals("-", rows[4].value)
        assertEquals("-", rows[6].value)
        assertEquals("- / -", rows[10].value)
        assertEquals("0", rows[11].value)
        assertEquals("0", rows[12].value)
        assertEquals("$0.00", rows[13].value)
        assertEquals("-", rows[15].value)
    }

    @Test
    fun `a zero cost on opencode-go renders nothing`() {
        val rows = ContextStats.rows(
            sessionTitle = "t",
            sessionId = "ses_3",
            sessionCreated = 1L,
            messages = A,
            usage = ContextStats.usage(A, null, "OpenCode Zen", "DeepSeek V4.1", 200000),
            cost = ContextStats.cost(0.0, "opencode-go", A),
        )
        assertEquals("", rows[13].value)
        // Their `e ? t.format(e) : '-'` - 1 is truthy, so a 1970 date is real output.
        assertEquals("Jan 1, 1970, 8:00 AM", rows[14].value)
    }

    // ---- se ----

    @Test
    fun `number is toLocaleString with a dash for null`() {
        assertEquals("-", ContextStats.number(null))
        assertEquals("1,234,567", ContextStats.number(1234567))
        assertEquals("0", ContextStats.number(0))
        assertEquals("200,000", ContextStats.number(200000L))
    }

    @Test
    fun `percent appends a percent sign and dashes when null`() {
        assertEquals("-", ContextStats.percent(null))
        assertEquals("38%", ContextStats.percent(38))
    }

    @Test
    fun `time dashes a zero timestamp and formats a real one`() {
        assertEquals("-", ContextStats.time(null))
        assertEquals("-", ContextStats.time(0))
        assertEquals("Nov 21, 2025, 10:40 PM", ContextStats.time(1763736000000))
    }

    // ---- sm ----

    @Test
    fun `export filename lowercases and hyphenates the title`() {
        assertEquals("fix-the-build.json", ContextStats.exportFilename("Fix the Build!! ", "ses_1"))
        assertEquals("ses_abc.json", ContextStats.exportFilename("", "ses_abc"))
        assertEquals("ses_abc.json", ContextStats.exportFilename(null, "ses_abc"))
    }

    @Test
    fun `export filename truncates to 250 characters before the extension`() {
        val name = ContextStats.exportFilename("x".repeat(300), "ses_1")
        assertEquals("x".repeat(250) + ".json", name)
    }

    // ---- currency ----

    @Test
    fun `currency is USD with two decimals`() {
        assertEquals("$0.03", ContextStats.currency(0.0342))
        assertEquals("$0.00", ContextStats.currency(0.0))
        assertEquals("$1,234.50", ContextStats.currency(1234.5))
    }

    // ---- export document ----

    @Test
    fun `export json is two-space pretty printed with info then messages`() {
        val info = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"id":"ses_1","title":"t"}""",
        ) as kotlinx.serialization.json.JsonObject
        val msg = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"id":"m1","type":"user"}""",
        ) as kotlinx.serialization.json.JsonObject
        assertEquals(
            """
            {
              "info": {
                "id": "ses_1",
                "title": "t"
              },
              "messages": [
                {
                  "id": "m1",
                  "type": "user"
                }
              ]
            }
            """.trimIndent(),
            ContextStats.exportJson(info, listOf(msg)),
        )
    }

    // ---- system prompt ----

    @Test
    fun `system prompt is the last system message trimmed`() {
        val a = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"id":"s1","type":"system","text":"  first  "}""",
        ) as kotlinx.serialization.json.JsonObject
        val b = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"id":"s2","type":"system","text":"\n second \n"}""",
        ) as kotlinx.serialization.json.JsonObject
        assertEquals("second", ContextStats.systemPrompt(listOf(a, b)))
        assertEquals("first", ContextStats.systemPrompt(listOf(a)))
        val blank = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"id":"s3","type":"system","text":"   "}""",
        ) as kotlinx.serialization.json.JsonObject
        assertNull(ContextStats.systemPrompt(listOf(blank)))
        assertNull(ContextStats.systemPrompt(emptyList()))
    }

    // ---- labels must not drift from ContextCopy ----

    @Test
    fun `row labels equal the ContextCopy constants`() {
        val rows = ContextStats.rows(null, "x", null, emptyList(), null, null)
        assertEquals(com.zapp.net.ui.ContextCopy.STAT_SESSION, rows[0].label)
        assertEquals(com.zapp.net.ui.ContextCopy.STAT_MESSAGES, rows[1].label)
        assertEquals(com.zapp.net.ui.ContextCopy.STAT_PROVIDER, rows[2].label)
        assertEquals(com.zapp.net.ui.ContextCopy.STAT_MODEL, rows[3].label)
        assertEquals(com.zapp.net.ui.ContextCopy.STAT_LIMIT, rows[4].label)
        assertEquals(com.zapp.net.ui.ContextCopy.STAT_TOTAL_TOKENS, rows[5].label)
        assertEquals(com.zapp.net.ui.ContextCopy.STAT_USAGE, rows[6].label)
        assertEquals(com.zapp.net.ui.ContextCopy.STAT_INPUT_TOKENS, rows[7].label)
        assertEquals(com.zapp.net.ui.ContextCopy.STAT_OUTPUT_TOKENS, rows[8].label)
        assertEquals(com.zapp.net.ui.ContextCopy.STAT_REASONING_TOKENS, rows[9].label)
        assertEquals(com.zapp.net.ui.ContextCopy.STAT_CACHE_TOKENS, rows[10].label)
        assertEquals(com.zapp.net.ui.ContextCopy.STAT_USER_MESSAGES, rows[11].label)
        assertEquals(com.zapp.net.ui.ContextCopy.STAT_ASSISTANT_MESSAGES, rows[12].label)
        assertEquals(com.zapp.net.ui.ContextCopy.STAT_TOTAL_COST, rows[13].label)
        assertEquals(com.zapp.net.ui.ContextCopy.STAT_SESSION_CREATED, rows[14].label)
        assertEquals(com.zapp.net.ui.ContextCopy.STAT_LAST_ACTIVITY, rows[15].label)
    }
}
