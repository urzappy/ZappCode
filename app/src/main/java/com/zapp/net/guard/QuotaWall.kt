package com.zapp.net.guard

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The real quota wall.
 *
 * Not a string hunt through an HTTP error: the server classifies a provider refusal and
 * emits a typed event. Everything here is copied from the server's own classifier
 * (`tk()` in the 2.3.2 bundle, which maps its internal reason tags onto the public
 * `provider.*` error types) and from the wire schema `session.step.failed` ->
 * `Session.StructuredError`.
 *
 * The distinction that matters for rotation is [addressScoped]:
 *
 *  - a limit attached to the *egress address* is what a new WARP /64 can clear;
 *  - a limit attached to the *account* (Go plan, workspace budget, the free tier's daily
 *    budget, per-model spend) is not, and hopping on one burns identities for nothing.
 *
 * The free-tier budget was classified as address-scoped until 2026-11-20. It is not - see
 * [FreeLimit] for the three-model measurement that settled it.
 */
sealed class QuotaWall(
    val type: String,
    val message: String,
    /** a fresh /64 can plausibly clear it */
    val addressScoped: Boolean,
    val retryAfterMs: Long? = null,
    val status: Int? = null,
) {
    /**
     * The free tier's budget. **Not address-scoped** - corrected 2026-11-20.
     *
     * This shipped as `addressScoped = true` and that single flag was the whole defect: it
     * sent every free limit down the rotation path, so the guard paid a WARP registration
     * plus a full tunnel rebuild per refusal and the limit was waiting anyway.
     *
     * MEASURED 2026-11-20 06:33:16 / 06:33:24 / 06:33:33 (Asia/Manila, UTC+8) - three
     * consecutive probes, three DIFFERENT models, one shared clock:
     *
     *     deepseek-v4.1-flash-free  retryAfterMs 5 210 000  ->  same instant
     *     big-pickle                retryAfterMs 5 202 000  ->  same instant
     *     tidepool-alpha-free       retryAfterMs 5 194 000  ->  same instant
     *
     * Each value is exactly the wall-clock gap shorter than the one before it, and all
     * three resolve to the same instant. One budget, every free model, independent of
     * egress. The counter-evidence had been there for a day: at 06:11:58 a never-used
     * `/64` (`2a09:bac5:4ff3:15f`) was refused one second after the IP change while
     * `warp=on`, and the reference tool's own panel logged
     * "free limit is not following the IP".
     *
     * WHAT IS **NOT** PROVEN: that the instant is fixed at 00:00 UTC. The first three
     * probes all landed on 00:00:06 UTC, which looked like a daily boundary, but a live
     * session refusal at 06:40:21 reported 00:10:39 UTC instead - the deadline had moved
     * forward 10.5 min in 7 min of wall clock. So the deadline is real and shared, but its
     * anchor is not a constant, and this class does not pretend to know it. The only
     * authority is `retryAfterMs` on each refusal; see [freeLimitDeadline].
     *
     * `retryAfterMs` is still carried, because it is the ONLY authoritative statement of
     * when the budget reopens - see [freeLimitDeadline].
     */
    class FreeLimit(message: String, status: Int?, retryAfterMs: Long? = null) :
        QuotaWall(TYPE, message, addressScoped = false, retryAfterMs = retryAfterMs, status = status)

    /** Bare `QuotaExceeded` the server could not narrow down: still address-shaped. */
    class Quota(message: String, status: Int?, retryAfterMs: Long? = null) :
        QuotaWall("provider.quota", message, addressScoped = true, retryAfterMs = retryAfterMs, status = status)

    /** Metered, but the fix is waiting, not moving. */
    class RateLimit(message: String, retryAfterMs: Long?, status: Int?) :
        QuotaWall("provider.rate-limit", message, addressScoped = false, retryAfterMs = retryAfterMs, status = status)

    class GoLimit(message: String, status: Int?) :
        QuotaWall("provider.go-limit", message, addressScoped = false, status = status)

    class BudgetLimit(message: String, status: Int?, val perModel: Boolean) :
        QuotaWall(
            if (perModel) "provider.model-budget-limit" else "provider.budget-limit",
            message,
            addressScoped = false,
            status = status,
        )

    class ChatGptLimit(message: String) :
        QuotaWall("provider.chatgpt-limit", message, addressScoped = false)

    /** Anything else that still names itself a quota. Kept so nothing is silently lost. */
    class Other(type: String, message: String, addressScoped: Boolean, status: Int?) :
        QuotaWall(type, message, addressScoped, status = status)

    val isWall: Boolean
        get() = this !is RateLimit

    data class Frame(
        val wall: QuotaWall,
        val sessionID: String?,
        val assistantMessageID: String?,
        /** the frame's own `created`, so a replayed wall can be told from a fresh one */
        val created: Long? = null,
    )

    companion object {
        const val TYPE = "provider.free-limit"

        private val json = Json { ignoreUnknownKeys = true }

        // their exact user-facing copy (server bundle constants `cb`, `lb`, `ub`)
        const val MSG_FREE =
            "Free usage limit reached. Connect a provider or subscribe to OpenCode Go to keep going, or switch models."
        const val MSG_WORKSPACE =
            "Workspace usage limit reached. Model use is paused until the limit resets."
        const val MSG_BUDGET_MODEL =
            "You've reached the spend limit for this model. Switch models to keep going."

        /**
         * When the free budget reopens.
         *
         * `retryAfterMs` on the wire is the ONLY authority: measured 2026-11-20, it is a
         * real countdown (three probes eight seconds apart read 5 210 000 / 5 202 000 /
         * 5 194 000) whose anchor moves between refusals - 00:00 UTC at 06:33, 00:10 UTC
         * at 06:40 - so no constant here can be trusted.
         *
         * The fallback exists only for a refusal that states no window. It is the
         * product's own published reset boundary, 00:00 UTC (the reference panel's own
         * tooltip: "daily quota resets at midnight UTC"), and it is labelled UNPROVEN:
         * it matched the first three readings and did not match the fourth.
         */
        fun freeLimitDeadline(wall: QuotaWall, now: Long = System.currentTimeMillis()): Long {
            val stated = wall.retryAfterMs?.takeIf { it > 0L }
            val wait = stated ?: msUntilUtcMidnight(now)
            return now + wait.coerceIn(30_000L, 25 * 60 * 60_000L)
        }

        /** Milliseconds until the next 00:00 UTC, strictly in the future. */
        fun msUntilUtcMidnight(now: Long): Long {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.timeInMillis = now
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return (cal.timeInMillis - now).coerceAtLeast(60_000L)
        }

        /** `HH:mm:ss` in the phone's zone - what he reads on the wall card. */
        fun localTime(ms: Long): String =
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(ms))

        /** `HH:mm:ss` in UTC - what the server's deadline actually is. */
        fun utcTime(ms: Long): String =
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .also { it.timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date(ms))

        /** Provider error tags, in the order the server checks them. */
        private val ACCOUNT_TYPES = setOf(
            "provider.go-limit",
            "provider.budget-limit",
            "provider.model-budget-limit",
            "provider.chatgpt-limit",
        )

        /**
         * Read a whole SSE frame and keep what the UI/hop needs from it.
         * Returns null unless the frame carries an error object that classifies as a wall.
         *
         * Live shape (`/api/event`):
         * `{"type":"session.step.failed","data":{"sessionID":...,"assistantMessageID":...,
         *   "error":{"type":"provider.free-limit","message":...}}}`
         */
        fun parseFrame(raw: String): Frame? {
            if (!raw.contains("error")) return null
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
            val eventType = root["type"]?.primStr().orEmpty()
            // Only the failure frames carry a verdict. A stray `"error"` key inside a tool
            // result or a diff must never be mistaken for a wall.
            val failureFrame = eventType == "session.step.failed" ||
                eventType == "session.error" ||
                eventType == "message.error" ||
                eventType == "session.step.ended"
            if (!failureFrame && eventType.isNotBlank()) return null
            // the error can sit on the frame itself (`message.error`) or under `data`
            val data = root["data"]?.let { runCatching { it.jsonObject }.getOrNull() }
            val holder = data ?: root
            val err = holder["error"]?.let { runCatching { it.jsonObject }.getOrNull() }
                ?: root["error"]?.let { runCatching { it.jsonObject }.getOrNull() }
                ?: return null
            // an idle/step event with no error object is normal traffic
            if (err["type"] == null && err["message"] == null) return null
            val wall = fromError(err, preferTyped = true) ?: return null
            return Frame(
                wall = wall,
                sessionID = data?.get("sessionID")?.primStr(),
                assistantMessageID = data?.get("assistantMessageID")?.primStr(),
                created = root["created"]?.primStr()?.toLongOrNull(),
            )
        }

        fun parse(raw: String): QuotaWall? = parseFrame(raw)?.wall

        /**
         * Classify a `Session.StructuredError`. `body` is the raw provider response and is
         * what the server itself keys on (`FreeUsageLimitError`, `BudgetLimitError`,
         * `GoUsageLimitError`), so it outranks the message text.
         */
        fun fromError(err: JsonObject, preferTyped: Boolean = false): QuotaWall? {
            val type = err["type"]?.primStr().orEmpty()
            val body = runCatching {
                err["response"]?.jsonObject?.get("body")?.primStr()
            }.getOrNull().orEmpty()
            val message = err["message"]?.primStr()
                ?: if (body.isNotBlank()) body.take(300) else ""
            val status = err["status"]?.primStr()?.toIntOrNull()
            val retry = err["retryAfterMs"]?.primStr()?.toLongOrNull()

            // 1. body markers first: the server does exactly this
            if (body.contains("FreeUsageLimitError")) return FreeLimit(message.ifBlank { MSG_FREE }, status, retry)
            if (body.contains("BudgetLimitError")) {
                val perModel = Regex("\"coverage\"\\s*:\\s*\"models\"").containsMatchIn(body)
                return BudgetLimit(message.ifBlank { if (perModel) MSG_BUDGET_MODEL else MSG_WORKSPACE }, status, perModel)
            }
            if (body.contains("GoUsageLimitError")) return GoLimit(message, status)

            // 2. then the public tag
            return when (type) {
                TYPE -> FreeLimit(message.ifBlank { MSG_FREE }, status, retry)
                // MEASURED 2026-11-21 20:40:56 on this phone: the live refusal arrived as a
                // bare `provider.quota` with NO `response.body`, and the message was the free
                // tier's own copy verbatim. Mapping that straight to [Quota] (addressScoped =
                // true) sent the guard down the hop path for a wall hopping cannot clear -
                // three `/64` registrations and 75 s inside one connect attempt. The tag is
                // generic; the text is not, so the text narrows it.
                "provider.quota" -> narrowByText(message, status, retry)
                    ?: Quota(message, status, retry)
                "provider.rate-limit" -> RateLimit(message, retry, status)
                "provider.go-limit" -> GoLimit(message, status)
                "provider.budget-limit" -> BudgetLimit(message.ifBlank { MSG_WORKSPACE }, status, false)
                "provider.model-budget-limit" -> BudgetLimit(message.ifBlank { MSG_BUDGET_MODEL }, status, true)
                "provider.chatgpt-limit" -> ChatGptLimit(message)
                else -> {
                    // No tag we know: only claim a wall when the words say so. This is the
                    // fallback for a raw HTTP failure, which never carries a tag.
                    if (!preferTyped && looksLikeWallText(message)) {
                        if (type in ACCOUNT_TYPES) Other(type, message, false, status)
                        // same narrowing as the `provider.quota` branch above: a raw failure
                        // whose text names the free tier is the budget, not the address.
                        else narrowByText(message, status, retry) ?: Quota(message, status, retry)
                    } else null
                }
            }
        }

        /**
         * Narrow a generic quota tag by the message the server attached.
         *
         * MEASURED 2026-11-21 20:40:56 (`logcat -s ZAPP`, this phone): a real free-tier
         * refusal arrived as
         *
         *     {"type":"provider.quota","status":429,
         *      "message":"Free usage limit reached. Connect a provider or subscribe to ..."}
         *
         * with **no** `response.body`, so the `FreeUsageLimitError` marker at step 1 never
         * fired. The bare tag then became [Quota], whose `addressScoped = true` is what sends
         * a wall down the rotation path. The text named the wall exactly, and the free budget
         * is provably not address-scoped, so the guard hopped for nothing: three `/64`
         * registrations and 75 s inside one connect attempt, and the wall was still there.
         *
         * This only ever **narrows**: it returns a wall only for text that names an
         * account-scoped limit, and returns null for everything else so the caller keeps its
         * existing `Quota` verdict. It cannot turn a non-wall into a wall, and it cannot turn
         * an address-scoped wall into a non-address-scoped one by accident - every branch
         * below is a wall the server itself scopes to the account.
         */
        private fun narrowByText(message: String, status: Int?, retry: Long?): QuotaWall? {
            if (message.isBlank()) return null
            val t = message.lowercase()
            return when {
                // the free tier's own copy, and the marker the server keys on
                t.contains("free usage limit reached") || t.contains("freeusagelimiterror") ->
                    FreeLimit(message, status, retry)
                t.contains("workspace usage limit reached") || t.contains("budgetlimiterror") ->
                    BudgetLimit(message, status, perModel = false)
                t.contains("you've reached the spend limit for this model") ->
                    BudgetLimit(message, status, perModel = true)
                t.contains("gousagelimiterror") -> GoLimit(message, status)
                else -> null
            }
        }

        /**
         * Text fallback, for the one path that has no typed error: a failed `POST
         * /session/:id/prompt`. Only strings the product actually emits are listed -
         * every entry below appears verbatim in the 2.3.2 server bundle.
         */
        fun looksLikeWallText(text: String): Boolean {
            if (text.isBlank()) return false
            val t = text.lowercase()
            return TEXT_SIGNATURES.any { t.contains(it) }
        }

        private val TEXT_SIGNATURES = listOf(
            "free usage limit reached",
            "workspace usage limit reached",
            "you've reached the spend limit for this model",
            "usage limit reached",
            "freeusagelimiterror",
            "budgetlimiterror",
            "gousagelimiterror",
            "quota exceeded",
            "insufficient_quota",
        )

        /**
         * Debug-only proof that the classifier reads the wire the way the server writes it.
         * Each frame below is the shape from the live `session.step.failed` schema; the
         * body markers are the exact tokens the server keys on (`tk()` in 2.3.2).
         * Returns an empty string when every case passes.
         */
        fun selfTest(): String {
            val fails = ArrayList<String>()
            fun js(s: String) = kotlinx.serialization.json.JsonPrimitive(s)

            // 1. the live frame shape, exactly as `session.step.failed` is defined
            val freeFrame = """
                {"id":"evt_1","created":1,"type":"session.step.failed",
                 "durable":{"aggregateID":"ses_1","seq":1,"version":1},
                 "data":{"sessionID":"ses_1","assistantMessageID":"msg_1",
                         "error":{"type":"provider.free-limit","message":"$MSG_FREE","status":429}}}
            """.trimIndent().replace("\n", "")
            parseFrame(freeFrame)?.let {
                if (it.wall !is FreeLimit) fails.add("free-limit typed wrong")
                // CORRECTED 2026-11-20: it is the budget, not the address. Measured - a
                // never-used /64 was refused 1 s after the IP change, and three different
                // models reported the same deadline instant.
                if (it.wall.addressScoped) fails.add("free-limit must NOT be address-scoped")
                if (it.sessionID != "ses_1") fails.add("free-limit lost sessionID")
            } ?: fails.add("free-limit frame not parsed")

            // 1b. the deadline is one instant, and a stated window always outranks the
            //     00:00 UTC fallback
            run {
                val now = 1_800_000_000_000L
                val stated = FreeLimit("x", 429, 5_210_000L)
                if (freeLimitDeadline(stated, now) != now + 5_210_000L) {
                    fails.add("stated retry window not honoured")
                }
                val silent = FreeLimit("x", 429, null)
                val fallback = freeLimitDeadline(silent, now)
                if (fallback <= now || fallback > now + 25 * 60 * 60_000L) {
                    fails.add("midnight fallback out of range")
                }
                // 00:00 UTC really is the boundary the fallback lands on
                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                cal.timeInMillis = fallback
                if (cal.get(java.util.Calendar.HOUR_OF_DAY) != 0 ||
                    cal.get(java.util.Calendar.MINUTE) != 0
                ) {
                    fails.add("fallback is not 00:00 UTC")
                }
            }

            // 2. the body marker outranks a generic tag: parsed straight by the classifier
            val freeBody = kotlinx.serialization.json.buildJsonObject {
                put("type", js("provider.quota"))
                put("message", js("x"))
                put(
                    "response",
                    kotlinx.serialization.json.buildJsonObject {
                        put("body", js("""{"error":{"type":"FreeUsageLimitError"}}"""))
                    },
                )
            }
            val fromBody = fromError(freeBody, preferTyped = true)
            if (fromBody !is FreeLimit) fails.add("FreeUsageLimitError body not honoured")

            // 3. a Go plan is account-scoped: hopping cannot clear it
            val go = fromError(
                kotlinx.serialization.json.buildJsonObject {
                    put("type", js("provider.go-limit"))
                    put("message", js("OpenCode Go usage limit reached."))
                },
                preferTyped = true,
            )
            if (go !is GoLimit || go.addressScoped) fails.add("go-limit classified wrong")

            // 4. per-model budget, recognised by the coverage marker
            val budgetBody = kotlinx.serialization.json.buildJsonObject {
                put("type", js("provider.quota"))
                put("message", js("x"))
                put(
                    "response",
                    kotlinx.serialization.json.buildJsonObject {
                        put("body", js("""{"coverage":"models","type":"BudgetLimitError"}"""))
                    },
                )
            }
            val budget = fromError(budgetBody, preferTyped = true)
            if (budget !is BudgetLimit || !budget.perModel) fails.add("model budget not detected")
            if (budget != null && budget.addressScoped) fails.add("budget must NOT be address-scoped")

            // 5. a rate limit is metered, not walled
            val rate = fromError(
                kotlinx.serialization.json.buildJsonObject {
                    put("type", js("provider.rate-limit"))
                    put("message", js("slow down"))
                    put("retryAfterMs", js("3000"))
                },
                preferTyped = true,
            )
            if (rate == null || rate.isWall) fails.add("rate-limit is not a wall")

            // 6. ordinary traffic is not a wall
            if (parseFrame("""{"type":"session.text.delta","data":{"sessionID":"s","text":"hi"}}""") != null)
                fails.add("plain delta treated as wall")

            // 7. the old guessed strings stay dead: a generic 429 is not a quota wall
            if (looksLikeWallText("429 Too Many Requests")) fails.add("generic 429 still matches")
            if (!looksLikeWallText("Free usage limit reached. Connect a provider"))
                fails.add("real copy does not match")

            return fails.joinToString("; ")
        }
    }
}

private fun kotlinx.serialization.json.JsonElement.primStr(): String =
    runCatching { (this as JsonPrimitive).content }.getOrDefault("")

/**
 * One rotation at a time, for the whole process.
 *
 * Both the guard service and the chat screen see the same failure frame. Without a gate
 * they each hop, so one wall costs two identities and the second hop races the first
 * tunnel's verify.
 */
object WallGate {
    private val mutex = Mutex()
    private var lastAt = 0L
    private var lastReg: WarpReg? = null
    private const val COOLDOWN_MS = 75_000L

    /** How long a hop may take to prove itself before we call it failed. */
    private const val EGRESS_DEADLINE_MS = 40_000L

    /**
     * A reset window at least this long is the free tier's shared budget, not a burst limit.
     *
     * Measured windows on the shared bucket: 5 210 000 ms (87 min) and 40 535 000 ms
     * (11.3 h). The transient case that a hop CAN clear was "same prompt, same /64,
     * answered 4 minutes later" - so the line sits well above four minutes and well below
     * the smallest budget window ever measured.
     */
    private const val BUDGET_WINDOW_MS = 10 * 60_000L

    /** True when the refusal states a window long enough to be the shared free budget. */
    fun isSharedBudget(wall: QuotaWall): Boolean =
        wall is QuotaWall.FreeLimit && (wall.retryAfterMs ?: 0L) >= BUDGET_WINDOW_MS

    suspend fun rotateOnce(
        ctx: android.content.Context,
        wall: QuotaWall,
        reason: String,
    ): WarpReg? {
        // Their guard never classifies scope: `_note_wall` decides how many hops a wall may
        // have, and `_rotate` runs until that budget is spent. The free tier therefore DOES
        // get up to two confirmed-fresh-address attempts before the pause - that is exactly
        // what `FREE_WALL_HOPS_MAX` exists for. Plan/workspace budgets still do not move.
        if (!wall.addressScoped && wall !is QuotaWall.FreeLimit) return null
        // A free limit that STATES its own reset window is the shared daily bucket.
        //
        // the owner, 2026-11-24 02:49: "IM VERIFIED I HAVE 2 SOLID ADDRESS BUT STILL" - and he
        // was right that two addresses did not help, because the bucket is not per address.
        // Two independent measurements settled that on 2026-11-20/21 (ZAPP-SESSION-LOG.md
        // 16835-16850):
        //
        //   2026-11-20 08:26  proxy vs direct, one second apart, countdown differing by 1 s
        //   2026-11-21 12:44  HIS PC, home link, no WARP, different public IPv4 from the
        //                     phone's /64 -> 429, Retry-After 40535 -> the same 00:00 UTC
        //                     instant the phone was counting down to
        //
        // `WallCore` already reaches this verdict by itself - "free limit is not following
        // the IP: 2 confirmed new IPv6 addresses hit it again" - but only AFTER paying for
        // those two addresses: two registrations, two tunnel rebuilds and two verify loops,
        // 25-60 s each, with the working tunnel torn down in between. That is the wait he
        // calls "SUPER TAGAL MA VERIFIED AND REPAIR".
        //
        // So: when the server states a window long enough to be a budget rather than a burst
        // limit, do not move the identity at all. A short window (the 20 Nov 04:42 -> 04:46
        // case, answered four minutes later on the same /64) keeps the old path, and so does
        // a refusal that states nothing - `FREE_WALL_HOPS_MAX` still governs those.
        if (wall is QuotaWall.FreeLimit) {
            val stated = wall.retryAfterMs ?: 0L
            if (isSharedBudget(wall)) {
                GuardState.log(
                    "wall",
                    "free limit states a ${stated / 60_000} min window - that is the shared " +
                        "budget, not the address -> not moving the identity",
                )
                return null
            }
        }
        return mutex.withLock {
            val now = System.currentTimeMillis()
            lastReg?.let { if (now - lastAt < COOLDOWN_MS) return@withLock it }
            val fresh = RotationEngine.hopNow(ctx, reason, wall.retryAfterMs)
            if (fresh == null) return@withLock null
            // `hopNow` returns as soon as the handshake is configured. The tunnel is not
            // usable until the egress probe has been through it, and a registration whose
            // probe fails is torn down by the service. Reporting success here before that
            // would tell him "send again" into a dead tunnel.
            val proven = awaitEgress(ctx, fresh)
            if (proven == null) {
                GuardState.log("wall", "hop ${fresh.v6.take(20)} never proven -> not advertised")
                return@withLock null
            }
            lastAt = System.currentTimeMillis()
            lastReg = proven
            proven
        }
    }

    /**
     * Wait for `WarpPool.setEgress` to land on this registration, i.e. for the service's own
     * verify to have reached Cloudflare through it. Returns null if it is retired or times out.
     */
    private suspend fun awaitEgress(
        ctx: android.content.Context,
        reg: WarpReg,
    ): WarpReg? {
        val pool = WarpPool(ctx)
        val deadline = System.currentTimeMillis() + EGRESS_DEADLINE_MS
        while (System.currentTimeMillis() < deadline) {
            val row = pool.all().firstOrNull { it.id == reg.id }
            // gone means the service discarded the identity: no egress to advertise
            if (row == null) return null
            if (!row.egress64.isNullOrBlank()) return row
            kotlinx.coroutines.delay(700)
        }
        return null
    }
}
