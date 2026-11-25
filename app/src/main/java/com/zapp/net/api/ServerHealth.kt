package com.zapp.net.api

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Server verification, ported from the desktop client's `runtime/server/health.ts`.
 *
 * This is NOT API-key validation, NOT quota verification and NOT token refresh. Desktop's
 * verification answers exactly one question, three ways:
 *
 *     GET /api/info
 *       healthy       -> { version, hostname }
 *       unauthorized  -> 401
 *       unreachable   -> anything else, after retries
 *
 * Read verbatim from `checkServerHealth()` (`health.ts:97-143`):
 *
 *     timeoutMs     30 000     one overall deadline, shared by every attempt
 *     retryCount     2         attempted only when the failure is transport-shaped
 *     retryDelayMs   100       multiplied by (count + 1) -> 100 ms, then 200 ms
 *     cacheMs        750       per (url + password + fetcher), re-dated when a probe settles
 *
 * The cache is what stops duplicate verification: an in-flight probe is shared rather
 * than repeated, and a settled answer stays warm for 750 ms. Their code does the same
 * thing with a `Map` of promises and re-dates the entry in `.finally`.
 *
 * Measured on this phone 2026-11-20 06:38:
 *
 *     with Basic auth      -> 200 {"version":"2.3.2","pid":6656,"hostname":"localhost",
 *                                  "urls":["http://127.0.0.1:4096"],...}
 *     without Basic auth   -> 401 {"_tag":"UnauthorizedError","message":"Authentication required"}
 *
 * so both arms of the classifier are real here, not assumed.
 *
 * WHAT DOES NOT EXIST IN THE SOURCE: there is no refresh token, no expiry and no rotation.
 * The credential is the long-lived server password sent as `Basic opencode:<password>`.
 * A successful verify can therefore never "refresh a token" - see `RotationEngine.hop`,
 * where it gates the one thing that IS genuinely reset: the WARP registration identity.
 */
object ServerHealth {

    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val DEFAULT_RETRY_COUNT = 2
    const val DEFAULT_RETRY_DELAY_MS = 100L
    const val CACHE_MS = 750L

    /** Desktop's three outcomes, same names. */
    sealed interface Result {
        data class Healthy(val version: String, val hostname: String) : Result
        data object Unauthorized : Result
        data object Unreachable : Result
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * One client, so the cache key can include its identity the way desktop keys on
     * `fetcher`. Probes are loopback calls; the timeouts exist for a Termux server that is
     * starting up and accepting but not answering yet.
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // retries are ours, on their schedule
            .build()
    }

    /** Settled and in-flight probes, keyed exactly like theirs. */
    private class Entry(val at: Long, val done: CompletableDeferred<Result>)

    private val gate = Mutex()
    private val cache = HashMap<String, Entry>()

    private fun key(baseUrl: String, password: String): String =
        "${baseUrl.trimEnd('/')}|$password|${System.identityHashCode(client)}"

    /**
     * Verify the server, sharing an in-flight probe and honouring the 750 ms window.
     *
     * Never throws. A probe that dies for any reason is `Unreachable`, because a
     * verification that fails open is worse than one that reports the truth.
     */
    suspend fun verify(
        baseUrl: String,
        password: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        retryCount: Int = DEFAULT_RETRY_COUNT,
        retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    ): Result {
        if (baseUrl.isBlank()) return Result.Unreachable
        val k = key(baseUrl, password)
        val now = System.currentTimeMillis()

        var owner = false
        val entry = gate.withLock {
            val hit = cache[k]
            if (hit != null && now - hit.at < CACHE_MS) return hit.done.await()
            Entry(now, CompletableDeferred<Result>()).also { cache[k] = it; owner = true }
        }
        if (!owner) return entry.done.await()

        val result = runCatching {
            check(baseUrl, password, timeoutMs, retryCount, retryDelayMs)
        }.getOrElse { e ->
            android.util.Log.w("ZAPP", "verify: ${e.javaClass.simpleName}: ${e.message}")
            Result.Unreachable
        }
        entry.done.complete(result)
        // Their `.finally`: re-date the entry when the probe settles.
        gate.withLock { cache[k] = Entry(System.currentTimeMillis(), entry.done) }
        return result
    }

    /** Drop the cached verdict. Called when the saved base URL or password changes. */
    suspend fun invalidate() = gate.withLock { cache.clear() }

    /**
     * The retry loop, in their order.
     *
     * `timeoutMs` is one deadline across every attempt, exactly as their single `signal`
     * is created once before `attempt(0)` - not per attempt.
     */
    private suspend fun check(
        baseUrl: String,
        password: String,
        timeoutMs: Long,
        retryCount: Int,
        retryDelayMs: Long,
    ): Result {
        val started = SystemClock.elapsedRealtime()
        var attempt = 0
        while (true) {
            val remaining = timeoutMs - (SystemClock.elapsedRealtime() - started)
            if (remaining <= 0L) return Result.Unreachable

            val (result, retryable) = once(baseUrl, password, remaining)
            if (result != null) return result

            // `if (count >= retryCount || !retry) return unreachable`
            if (attempt >= retryCount || !retryable) return Result.Unreachable

            val wait = retryDelayMs * (attempt + 1)
            if (SystemClock.elapsedRealtime() - started + wait >= timeoutMs) {
                return Result.Unreachable
            }
            delay(wait)
            attempt += 1
        }
    }

    /**
     * One `/api/info` probe.
     *
     * Returns `(result, retryable)`. A null result means the attempt failed; `retryable`
     * mirrors their `retryable()`: transport failures retry, our own deadline and any
     * classified answer do not.
     */
    private suspend fun once(
        baseUrl: String,
        password: String,
        budgetMs: Long,
    ): Pair<Result?, Boolean> {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/info")
            .apply {
                // No password any more (`serve.sh` unsets OPENCODE_SERVER_PASSWORD). Sending
                // `Basic opencode:` to a server with auth disabled is harmless, but an empty
                // header is noise in every capture and one more thing to explain.
                if (password.isNotBlank()) {
                    header("Authorization", Credentials.basic("opencode", password))
                }
            }
            .build()
        val call = client.newCall(request)
        return try {
            val response = withTimeoutOrNull(budgetMs) {
                withContext(Dispatchers.IO) { call.execute() }
            }
            if (response == null) {
                // Our deadline fired. Desktop's `retryable()` returns false for an aborted
                // signal: a timeout is an answer, not a reason to hammer the server.
                runCatching { call.cancel() }
                null to false
            } else {
                response.use { r ->
                    when {
                        r.code == 401 || r.code == 403 -> Result.Unauthorized to false
                        !r.isSuccessful -> null to false
                        else -> {
                            val body = withContext(Dispatchers.IO) { r.body?.string().orEmpty() }
                            val obj = runCatching { json.parseToJsonElement(body).jsonObject }
                                .getOrNull()
                            if (obj == null) null to false
                            else Result.Healthy(
                                version = obj.str("version"),
                                hostname = obj.str("hostname"),
                            ) to false
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // Transport-shaped: connection refused, reset, DNS. Their `ClientError`
            // reason `Transport`. These are the only failures worth a retry.
            null to true
        } catch (e: Exception) {
            null to false
        }
    }

    private fun JsonObject.str(name: String): String =
        runCatching { this[name]?.jsonPrimitive?.content }.getOrNull().orEmpty()
}
