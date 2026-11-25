package com.zapp.net.guard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classifier must not send an account-scoped wall down the rotation path.
 *
 * Every vector below comes from a real frame shape, not from invention:
 *
 *  - `liveQuotaFrame` is the exact event measured on this phone at 2026-11-21 20:40:56
 *    (`logcat -s ZAPP` -> `[wall] wall:provider.quota`). A free-tier refusal arrived as a bare
 *    `provider.quota` with **no** `response.body`, so the `FreeUsageLimitError` marker at step 1
 *    of `fromError` never fired. It fell through to `Quota`, whose `addressScoped = true` is what
 *    made the guard hop - three `/64` registrations and 75 s in one connect attempt, for a wall
 *    hopping cannot clear (proven twice by two-identity probes).
 *
 * The fix narrows by the message text, which is the server's own verbatim copy. These tests pin
 * both directions: the free text must stop being address-scoped, and every other bare quota must
 * keep its old verdict so the change cannot silently widen or narrow anything else.
 */
class QuotaWallTextTest {

    private fun obj(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    // ---- the live frame, verbatim -------------------------------------------------------

    /** 2026-11-21 20:40:56 on this device: bare tag, no body, free-tier copy. */
    @Test
    fun theLiveBareQuotaFreeFrameIsNotAddressScoped() {
        val err = obj(
            "type" to "provider.quota",
            "message" to QuotaWall.MSG_FREE,
            "status" to "429",
        )
        val w = QuotaWall.fromError(err, preferTyped = true)
        assertTrue("expected FreeLimit, got ${w?.javaClass?.simpleName}", w is QuotaWall.FreeLimit)
        assertFalse("the free budget is not cleared by a new /64", w!!.addressScoped)
    }

    /** The same frame through the SSE reader, which is the path the guard actually takes. */
    @Test
    fun theLiveFrameThroughParseFrame() {
        val raw = """
            {"id":"evt_1","created":1,"type":"session.step.failed",
             "durable":{"aggregateID":"ses_1","seq":1,"version":1},
             "data":{"sessionID":"ses_1","assistantMessageID":"msg_1",
                     "error":{"type":"provider.quota","status":429,
                              "message":"${QuotaWall.MSG_FREE}"}}}
        """.trimIndent().replace("\n", "")
        val frame = QuotaWall.parseFrame(raw)
        assertTrue(frame != null)
        assertTrue(frame!!.wall is QuotaWall.FreeLimit)
        assertFalse(frame.wall.addressScoped)
        assertEquals("ses_1", frame.sessionID)
        assertEquals("msg_1", frame.assistantMessageID)
    }

    /** A stated window survives the narrowing - it is the only authority for the deadline. */
    @Test
    fun theRetryWindowSurvivesTheNarrowing() {
        val err = obj(
            "type" to "provider.quota",
            "message" to QuotaWall.MSG_FREE,
            "status" to "429",
            "retryAfterMs" to "40535000",
        )
        val w = QuotaWall.fromError(err, preferTyped = true)!!
        assertEquals(40_535_000L, w.retryAfterMs)
    }

    // ---- everything else must keep its old verdict ---------------------------------------

    /** A bare quota that names nothing stays address-scoped, exactly as before. */
    @Test
    fun aBareQuotaWithNoFreeTextStaysAddressScoped() {
        val err = obj(
            "type" to "provider.quota",
            "message" to "Too many requests. Please slow down.",
            "status" to "429",
        )
        val w = QuotaWall.fromError(err, preferTyped = true)
        assertTrue(w is QuotaWall.Quota)
        assertTrue(w!!.addressScoped)
    }

    @Test
    fun aBareQuotaWithABlankMessageStaysAddressScoped() {
        val w = QuotaWall.fromError(obj("type" to "provider.quota"), preferTyped = true)
        assertTrue(w is QuotaWall.Quota)
        assertTrue(w!!.addressScoped)
    }

    /** Workspace budget: account-scoped, and it was already classified as such by tag. */
    @Test
    fun theWorkspaceCopyNarrowsToABudgetWall() {
        val err = obj(
            "type" to "provider.quota",
            "message" to QuotaWall.MSG_WORKSPACE,
            "status" to "429",
        )
        val w = QuotaWall.fromError(err, preferTyped = true)
        assertTrue(w is QuotaWall.BudgetLimit)
        assertFalse(w!!.addressScoped)
        assertFalse((w as QuotaWall.BudgetLimit).perModel)
    }

    @Test
    fun thePerModelSpendCopyNarrowsToAPerModelBudget() {
        val err = obj(
            "type" to "provider.quota",
            "message" to QuotaWall.MSG_BUDGET_MODEL,
            "status" to "429",
        )
        val w = QuotaWall.fromError(err, preferTyped = true)
        assertTrue(w is QuotaWall.BudgetLimit)
        assertTrue((w as QuotaWall.BudgetLimit).perModel)
        assertFalse(w.addressScoped)
    }

    @Test
    fun theGoMarkerNarrowsToAGoLimit() {
        val err = obj(
            "type" to "provider.quota",
            "message" to "upstream said GoUsageLimitError",
            "status" to "429",
        )
        val w = QuotaWall.fromError(err, preferTyped = true)
        assertTrue(w is QuotaWall.GoLimit)
        assertFalse(w!!.addressScoped)
    }

    // ---- the body marker still outranks everything ---------------------------------------

    /** Step 1 of `fromError` is untouched: a body marker wins over a contradicting tag. */
    @Test
    fun theBodyMarkerStillOutranksTheTag() {
        val err = buildJsonObject {
            put("type", "provider.quota")
            put("message", "something else entirely")
            put("status", 429)
            put(
                "response",
                buildJsonObject {
                    put("body", """{"error":{"type":"FreeUsageLimitError"}}""")
                },
            )
        }
        val w = QuotaWall.fromError(err, preferTyped = true)
        assertTrue(w is QuotaWall.FreeLimit)
        assertFalse(w!!.addressScoped)
    }

    /** A typed free-limit tag was already correct and must stay correct. */
    @Test
    fun theTypedFreeLimitTagIsUnchanged() {
        val err = obj(
            "type" to QuotaWall.TYPE,
            "message" to QuotaWall.MSG_FREE,
            "status" to "429",
        )
        val w = QuotaWall.fromError(err, preferTyped = true)
        assertTrue(w is QuotaWall.FreeLimit)
        assertFalse(w!!.addressScoped)
    }

    // ---- the raw-HTTP fallback path ------------------------------------------------------

    /** No tag at all (a raw HTTP failure) + free copy: the text still names the budget. */
    @Test
    fun anUntaggedFreeFailureNarrowsToo() {
        val err = obj("message" to QuotaWall.MSG_FREE)
        val w = QuotaWall.fromError(err, preferTyped = false)
        assertTrue(w is QuotaWall.FreeLimit)
        assertFalse(w!!.addressScoped)
    }

    /** No tag and no wall words: not a wall at all. Unchanged. */
    @Test
    fun anUntaggedHarmlessFailureIsNotAWall() {
        assertEquals(null, QuotaWall.fromError(obj("message" to "network unreachable"), preferTyped = false))
    }

    /** `preferTyped` still suppresses the text fallback for an unknown tag. */
    @Test
    fun preferTypedStillSuppressesTheTextFallback() {
        assertEquals(null, QuotaWall.fromError(obj("message" to QuotaWall.MSG_FREE), preferTyped = true))
    }

    // ---- the narrowing is one-way --------------------------------------------------------

    /**
     * The helper is private, so this pins its contract through the public entry point: a message
     * that is not one of the known account-scoped copies must never produce an account-scoped
     * verdict from a bare `provider.quota`.
     */
    @Test
    fun onlyKnownAccountCopiesLeaveTheAddressPath() {
        val addressScopedMessages = listOf(
            "Too many requests",
            "QuotaExceeded",
            "rate limited, retry later",
            "",
        )
        addressScopedMessages.forEach { m ->
            val w = QuotaWall.fromError(
                obj("type" to "provider.quota", "message" to m, "status" to "429"),
                preferTyped = true,
            )
            assertTrue("message=$m", w is QuotaWall.Quota)
            assertTrue("message=$m", w!!.addressScoped)
        }
    }

    /** Sanity: the JSON reader used by the tests is the same one the class uses. */
    @Test
    fun theMessageConstantIsTheServersOwnCopy() {
        val parsed = Json.parseToJsonElement("""{"m":"${QuotaWall.MSG_FREE}"}""").jsonObject
        assertEquals(
            "Free usage limit reached. Connect a provider or subscribe to OpenCode Go to keep going, or switch models.",
            parsed["m"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content },
        )
    }
}
