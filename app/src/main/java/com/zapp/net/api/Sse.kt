package com.zapp.net.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The event stream, read as bytes instead of through OkHttp's `EventSource`.
 *
 * Why: the server keeps the stream alive with a `: heartbeat` comment every ~15 s (measured
 * on the phone at 03:13:53, 03:14:07, 03:14:22). `EventSourceListener` only surfaces `data:`
 * frames, so a socket that is half-open - no RST, no FIN, packets simply disappear - is
 * indistinguishable from an idle stream. That is exactly the state a TUN rebuild leaves
 * behind: the tunnel swapped under the socket at 03:10:29 and the transcript stopped moving
 * while the server kept working.
 *
 * Reading the lines ourselves makes the heartbeat a liveness signal, so "no bytes for 45 s"
 * is a real verdict and can drive a resubscribe.
 *
 * Framing is the SSE spec as far as this server uses it: `data:` lines accumulate until a
 * blank line dispatches one event; `:` lines are comments (the heartbeat); `event:`, `id:`
 * and `retry:` are ignored because the payload already carries its own `type`.
 */
class SseStream(private val baseUrl: String, private val http: OkHttpClient) {

    /** Open connection. [cancel] is safe to call from any thread and more than once. */
    inner class Handle internal constructor(
        private val call: okhttp3.Call,
        private val reader: Job,
    ) {
        fun cancel() {
            runCatching { call.cancel() }
            reader.cancel()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * @param onEvent one dispatched SSE frame (`data:` payload, newlines joined).
     * @param onBeat  a heartbeat comment arrived; the socket carried bytes.
     * @param onDown  the stream ended: EOF, transport error, or HTTP failure. Called once.
     */
    fun open(
        path: String,
        onEvent: (String) -> Unit,
        onBeat: () -> Unit,
        onDown: (String) -> Unit,
    ): Handle {
        val call = http.newCall(
            Request.Builder()
                .url(baseUrl + path)
                // Never let OkHttp negotiate gzip for this one. A compressed event stream is
                // held inside the decoder until its buffer fills or the connection closes, so
                // `: heartbeat` lines that the server wrote every 15 s arrived in bursts of
                // dozens - or not at all until the turn ended. Measured 23 Nov: the app logged
                // `stream silent for 49403ms` while a plain `curl` on the same endpoint was
                // printing a heartbeat every 15 s. Identity encoding is the contract for SSE.
                .header("Accept-Encoding", "identity")
                .build(),
        )
        val done = AtomicBoolean(false)
        val finish = { why: String ->
            if (done.compareAndSet(false, true)) runCatching { onDown(why) }
        }
        val job = scope.launch {
            try {
                call.execute().use { res ->
                    if (!res.isSuccessful) {
                        finish("http ${res.code}")
                        return@use
                    }
                    val src = res.body?.source() ?: run {
                        finish("empty body")
                        return@use
                    }
                    val payload = StringBuilder()
                    while (true) {
                        val line = src.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> {
                                if (payload.isNotEmpty()) {
                                    val frame = payload.toString()
                                    payload.setLength(0)
                                    runCatching { onEvent(frame) }
                                }
                            }
                            line.startsWith(":") -> onBeat()
                            line.startsWith("data:") -> {
                                if (payload.isNotEmpty()) payload.append('\n')
                                payload.append(line.removePrefix("data:").trimStart())
                            }
                            // `event:` / `id:` / `retry:` carry nothing we act on; the JSON
                            // payload's own `type` field is the discriminator.
                        }
                    }
                    finish("eof")
                }
            } catch (t: Throwable) {
                finish("${t.javaClass.simpleName}: ${t.message ?: "no message"}")
            }
        }
        return Handle(call, job)
    }

    fun cancel() {
        scope.coroutineContext[Job]?.cancel()
    }
}
