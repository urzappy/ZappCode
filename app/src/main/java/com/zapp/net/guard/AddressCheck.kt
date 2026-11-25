package com.zapp.net.guard

import android.content.Context
import com.zapp.net.api.OpenCodeClient
import com.zapp.net.settings.observe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Does this address actually reset the free-token bucket?
 *
 * A tunnel verify only proves the packets are leaving through WARP. It says nothing about the
 * thing he actually cares about: whether the server will answer. Measured tonight, both states
 * exist on perfectly healthy tunnels -
 *
 *   `tunnel VERIFY ok: /64=2a09:bac5:4ff4:18be::/64`  ->  `finish=stop  TEXT: READY  in=71`
 *   `tunnel VERIFY ok: /64=2a09:bac5:4ff4:2e1e::/64`  ->  `finish=error provider.free-limit`
 *
 * so "verified" is not "working". The only honest test of a working address is a turn, which
 * is what this does: one throwaway session, a two-word prompt, and the server's own verdict.
 * A walled address is retired and the engine hops again; an answering one is kept.
 *
 * Cost is deliberately tiny - a session with one short turn. Against a walled address it costs
 * nothing at all, because the refusal is the response.
 */
object AddressCheck {

    /** Two words: enough for the server to run a turn, small enough to cost almost nothing. */
    private const val PROBE_TEXT = "say ok"

    /**
     * A refusal lands in ~5 s and a real answer starts in 2-4 s, so 15 s is already generous.
     * It was 30 s, and every candidate paid it in full before being accepted - which is most
     * of why "Rotating the tunnel" sat on screen for 27 s (the owner, 05:40).
     */
    private const val ANSWER_WINDOW_MS = 15_000L
    private const val POLL_MS = 800L

    /**
     * /64s that already answered in this run, with the time. A prefix that served a turn two
     * minutes ago does not need to be re-interrogated on the next hop.
     */
    private val qualified = java.util.Collections.synchronizedMap(HashMap<String, Long>())
    private const val QUALIFIED_TTL_MS = 10 * 60_000L

    /** Last verdict, for the panel and the log. Never used as a decision by itself. */
    @Volatile
    var lastVerdict: String = "unmeasured"
        private set

    /**
     * True only when the server answered a real prompt through the current tunnel.
     *
     * Returns false for every other outcome - wall, timeout, transport error, no model - because
     * "could not prove it works" must never be treated as "it works".
     */
    suspend fun qualifies(ctx: Context, prefix64: String = ""): Boolean = withContext(Dispatchers.IO) {
        // A prefix that answered two minutes ago does not need to be interrogated again.
        // Re-proving it costs a session plus a turn for no new information.
        if (prefix64.isNotBlank()) {
            val at = qualified[prefix64]
            if (at != null && System.currentTimeMillis() - at < QUALIFIED_TTL_MS) {
                lastVerdict = "ok (cached)"
                GuardState.log("address", "$prefix64 answered ${(System.currentTimeMillis() - at) / 1000}s ago - still good")
                return@withContext true
            }
        }
        val settings = runCatching { observe(ctx).first() }.getOrNull()
            ?: run { lastVerdict = "no-settings"; return@withContext false }
        if (settings.serverBase.isBlank()) {
            lastVerdict = "no-server"
            return@withContext false
        }
        val client = runCatching {
            OpenCodeClient(
                settings.serverBase,
                initialToken = settings.authToken.ifBlank { null },
            )
        }.getOrNull() ?: run { lastVerdict = "no-client"; return@withContext false }

        val session = runCatching { client.newSession("address check") }.getOrNull()
            ?: run { lastVerdict = "no-session"; return@withContext false }

        var verdict = "timeout"
        var ok = false
        try {
            runCatching {
                client.prompt(
                    session.id,
                    PROBE_TEXT,
                    settings.providerID.ifBlank { null },
                    settings.modelID.ifBlank { null },
                )
            }.onFailure {
                lastVerdict = "prompt-failed:${it.javaClass.simpleName}"
                return@withContext false
            }

            val deadline = System.currentTimeMillis() + ANSWER_WINDOW_MS
            while (System.currentTimeMillis() < deadline) {
                delay(POLL_MS)
                val messages = runCatching { client.messages(session.id) }.getOrNull() ?: continue
                val assistant = messages.lastOrNull { it.info.role == "assistant" } ?: continue
                // The mapped parts, not the raw wire, are what carry the verdict: a refused
                // turn arrives as a `Part(type = "error")` plus an `idle` row whose `status`
                // is the error message, and a real answer arrives as `Part(type = "text")`.
                val errText = (
                    assistant.parts.filter { it.type == "error" || it.type == "idle" }
                        .joinToString(" ") { (it.text.orEmpty() + " " + it.status.orEmpty()) }
                    ).trim()
                val refused = errText.isNotEmpty() && !errText.equals("succeeded", ignoreCase = true)
                val answer = assistant.parts
                    .filter { it.type == "text" }
                    .joinToString("") { it.text.orEmpty() }
                    .trim()
                if (refused) {
                    // Only a limit is a spent address. A transport error or a dropped socket
                    // says nothing about the bucket, and banning a prefix for it would throw
                    // away a good address on the strength of a bad second.
                    val isLimit = errText.contains("limit", ignoreCase = true) ||
                        errText.contains("free-limit", ignoreCase = true)
                    verdict = (if (isLimit) "wall:" else "error:") + errText.take(120)
                    ok = false
                    break
                }
                if (answer.isNotEmpty()) {
                    verdict = "ok"
                    ok = true
                    break
                }
            }
        } finally {
            lastVerdict = verdict
            if (ok && prefix64.isNotBlank()) qualified[prefix64] = System.currentTimeMillis()
            if (!ok && prefix64.isNotBlank()) qualified.remove(prefix64)
            GuardState.log(
                "address",
                if (ok) "qualified: this /64 answers" else "not qualified ($verdict) - hopping on",
            )
            runCatching { client.deleteSession(session.id) }
        }
        ok
    }
}
