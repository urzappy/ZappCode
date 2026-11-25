package com.zapp.net.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.buffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Native OpenCode server client. Talks the exact API the official web UI talks:
 *
 *   GET  /api/session?limit=&order=desc            -> session list (sidebar)
 *   GET  /api/session/{id}/message?limit=&order=   -> conversation
 *   POST /api/session/{id}/prompt                  -> send message
 *   POST /api/session/{id}/abort                   -> stop the agent
 *   GET  /api/config/providers (via /api/rpc layer) -> model picker data
 *   GET  /event                                     -> SSE live stream
 *
 * Auth = cookie obtained by consuming a one-time pair link once, valid ~30 days.
 */
class OpenCodeClient(
    private val baseUrl: String,
    initialCookies: List<Cookie> = emptyList(),
    initialToken: String? = null,
    private val onCookies: (List<Cookie>) -> Unit = {},
    private val onToken: (String) -> Unit = {},
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Pair token = the real credential: sent as `Basic opencode:<token>` on every request
     *  (verified from the official client: btoa(`opencode:${password}`)). */
    @Volatile private var token: String? = initialToken

    private val store = ConcurrentHashMap<String, List<Cookie>>().apply {
        initialCookies.filter { it.expiresAt > System.currentTimeMillis() }
            .forEach { c -> merge(c.domain, listOf(c)) { old, _ -> old + c } }
    }

    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isNotEmpty()) {
                store[url.host] = cookies
                onCookies(cookies) // caller persists -> survives app restarts
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store[url.host].orEmpty().filter { it.expiresAt > System.currentTimeMillis() }
    }

    private val authInterceptor = okhttp3.Interceptor { chain ->
        val t = token
        val req = if (t.isNullOrBlank()) chain.request()
        else chain.request().newBuilder()
            .header("Authorization", okhttp3.Credentials.basic("opencode", t))
            .build()
        chain.proceed(req)
    }

    // Desktop's query defaults are 15 s connect / 30 s fetch (`home/sessions/load-machine.ts`).
    // `readTimeout(0)` on the shared client meant every hung request lived forever: after the
    // server stalled at 14:39 the app held ~60 ESTABLISHED sockets and kept piling on. Only the
    // event stream may hang, and it gets its own client below.
    private val http = OkHttpClient.Builder()
        .cookieJar(jar)
        .addInterceptor(authInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** `/api/event` is the one request that must stay open indefinitely. */
    /**
     * The SSE transport: fast failure, like a PC.
     *
     * `readTimeout(0)` also covers *waiting for response headers*, so a resubscribe into a dead
     * tunnel blocked forever with no exception, no `onDown`, nothing. Measured 23 Nov on this
     * phone, five stream generations and a two-minute dead window:
     *
     *   19:02:57  stream open (gen 2): first frame received
     *   19:03:59  stream silent for 47173ms (gen 2) -> resubscribing
     *   19:04:49  stream silent for 50028ms (gen 3) -> resubscribing
     *   19:05:39  stream silent for 50010ms (gen 4) -> resubscribing
     *   19:06:59  stream open (gen 6): first frame received
     *
     * Generations 3, 4 and 5 produced no `stream open` line at all - not one frame - and each
     * cost 50 s of the app's own watchdog before it gave up. The official desktop client has no
     * such watchdog; it does not need one, because a blocked socket on a PC throws. This is the
     * same contract on a phone: a finite read timeout turns "hung forever" into
     * `SocketTimeoutException` -> `onDown` -> immediate resubscribe.
     *
     * 35 s WAS WRONG, and it was my own number. Measured 2026-11-23 20:51:39 on a clean
     * install with the frame-gap instrumentation:
     *
     *     stream beat gap 28957ms (gen 1)
     *
     * The server's `: heartbeat` is ~29 s apart, not the ~15 s the old comment claimed from a
     * 03:13 sample. A 35 s deadline sat 6 s above a NORMAL interval, so ordinary jitter - and
     * every moment the engine is busy enough to answer /api/info in ~2.5 s instead of 4 ms -
     * manufactured a `SocketTimeoutException` and threw away a healthy stream. That is a
     * self-inflicted disconnect, not a dead server.
     *
     * The official desktop client has NO read deadline at all: it decides liveness from frame
     * activity (see `shared-events`: `onActivity()` fires on every transport frame) and never
     * kills a stream for being quiet. 70 s is the phone's bounded version of that - 2.4 missed
     * beats, still finite so a truly hung socket becomes an exception instead of a hang.
     */
    private val httpSse = http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .build()

    private val ctype = "application/json; charset=utf-8".toMediaType()

    /**
     * Ceilings for the two file reads.
     *
     * The viewer is a phone pane, not a download manager: a source file of 1 MB is already 3 000
     * lines and a bitmap over 24 MB cannot be decoded inside a 256 MB heap without risking the
     * same crash. Both numbers are refused with the file's real size, never silently truncated.
     */
    private val MAX_TEXT_BYTES = 1L * 1024 * 1024
    private val MAX_BYTES = 24L * 1024 * 1024

    // ---- low level (internal: Providers.kt uses the same transport) ----
    internal suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(baseUrl + path).build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            check(r.isSuccessful) { "GET $path -> ${r.code}: ${body.take(240)}" }
            body
        }
    }

    /** `GET /api/fs/read/<path>` — the server's own file read (verified: 200 with the raw body).
     *  Segments are encoded one by one so Windows drive paths and spaces survive the URL.
     *
     *  BOUNDED. It used to be `get(...)`, which is `ResponseBody.string()` — the whole file into
     *  one String. the owner tapped the link on the 113 MB apk he had just uploaded and the process
     *  died:
     *
     *  ```
     *  java.lang.OutOfMemoryError: Failed to allocate a 213980264 byte allocation
     *      at okhttp3.ResponseBody.string(ResponseBody.kt:187)
     *      at com.zapp.net.api.OpenCodeClient$get$2.invokeSuspend(OpenCodeClient.kt:97)
     *  ```
     *
     *  213 980 264 bytes is the UTF-8 string of a 113 MB binary. A preview never needs that, and
     *  the heap here is 256 MB. Anything over [MAX_TEXT_BYTES] is refused with its real size
     *  instead of being loaded.
     */
    suspend fun readFile(path: String): String {
        val clean = path.replace('\\', '/')
        val enc = clean.split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(baseUrl + "/api/fs/read/$enc").build())
                .execute().use { r ->
                    if (!r.isSuccessful) {
                        throw java.io.IOException("GET /api/fs/read -> ${r.code}")
                    }
                    val body = r.body ?: throw java.io.IOException("empty body")
                    val len = body.contentLength()
                    if (len > MAX_TEXT_BYTES) throw tooLarge(path, len)
                    val src = body.source()
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = okio.Buffer()
                    var total = 0L
                    while (true) {
                        val n = src.read(chunk, 8192L)
                        if (n == -1L) break
                        total += n
                        if (total > MAX_TEXT_BYTES) throw tooLarge(path, total)
                        out.write(chunk.readByteArray())
                    }
                    out.toByteArray().toString(Charsets.UTF_8)
                }
        }
    }

    private fun tooLarge(path: String, bytes: Long): java.io.IOException {
        val mb = String.format(java.util.Locale.US, "%.1f", bytes / 1048576.0)
        return java.io.IOException(
            "${path.substringAfterLast('/').substringAfterLast('\\')} is $mb MB - too large to " +
                "preview here. The agent can still read it by path.",
        )
    }

    /**
     * The same route as raw bytes. `review/model.ts:784-798` reads a previewed file's bytes for
     * images and audio and hands them on base64-encoded with their MIME type; `String` would
     * decode them as text and destroy the file, so the media path gets its own read.
     */
    suspend fun readBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        val clean = path.replace('\\', '/')
        val enc = clean.split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        http.newCall(Request.Builder().url(baseUrl + "/api/fs/read/$enc").build()).execute().use { r ->
            check(r.isSuccessful) { "GET /api/fs/read -> ${r.code}" }
            val body = r.body ?: throw java.io.IOException("empty body")
            // Same ceiling as the text read: `bytes()` on a 113 MB apk is the other way into
            // the same OOM, and an image that size has no business being decoded on a phone.
            val len = body.contentLength()
            if (len > MAX_BYTES) throw tooLarge(path, len)
            val src = body.source()
            val out = java.io.ByteArrayOutputStream()
            val chunk = okio.Buffer()
            var total = 0L
            while (true) {
                val n = src.read(chunk, 8192L)
                if (n == -1L) break
                total += n
                if (total > MAX_BYTES) throw tooLarge(path, total)
                out.write(chunk.readByteArray())
            }
            out.toByteArray()
        }
    }

    /**
     * `POST /api/experimental/fs/write?path=` with a raw byte body.
     *
     * Read from their own client (`renderer-main.js`, the `file.write` entry in the route table):
     *
     * ```js
     * file: { write: (e, t) => a({ method: `POST`, path: `/api/experimental/fs/write`,
     *   query: { location: e.location, path: e.path }, body: e.payload,
     *   successStatus: 200, declaredStatuses: [400, 401, 404], empty: !1, binaryBody: !0 }, t) }
     * ```
     *
     * `binaryBody: true` is the whole point: the bytes go up raw, not base64'd into JSON. That
     * is the only route on this server that can carry a file of any real size, because the
     * prompt route carries every attachment inline and has to fit the app's heap.
     *
     * The body is streamed straight from the picker's `InputStream`, so a 1 GB pick costs a
     * 64 KiB buffer instead of 1.4 GB of base64.
     */
    suspend fun writeServerFile(
        path: String,
        stream: java.io.InputStream,
        length: Long,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        val enc = path.split('/').joinToString("/") { enc(it) }
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = length
            override fun writeTo(sink: okio.BufferedSink) {
                val buf = ByteArray(64 * 1024)
                var sent = 0L
                var lastPct = -1
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    sink.write(buf, 0, n)
                    sent += n
                    val pct = if (length > 0) ((sent * 100) / length).toInt() else -1
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress?.invoke(sent, length)
                    }
                }
                sink.flush()
            }
        }
        // A gigabyte is not a 30-second request.
        httpLong.newBuilder().writeTimeout(0, TimeUnit.MILLISECONDS).build()
            .newCall(
                Request.Builder()
                    .url(baseUrl + "/api/experimental/fs/write?path=" + enc)
                    .post(body).build()
            ).execute().use { r ->
                val text = r.body?.string().orEmpty()
                check(r.isSuccessful) { "fs/write $path -> ${r.code}: ${text.take(240)}" }
                text
            }
    }

    /**
     * A prompt that carries a file needs a longer read budget than a prompt that does not.
     *
     * The server writes the attachment bytes out before it answers the POST. With the shared
     * 30 s budget a 13 MB photo was still being written when the client gave up, and the send
     * was reported as failed even though the server had it (measured 2026-11-21 11:33:10).
     */
    private val httpLong = http.newBuilder().readTimeout(180, TimeUnit.SECONDS).build()

    /**
     * Bytes of the current upload, as they leave the phone.
     *
     * `ui.promptInput.uploading` = `"{{percent}}%"` - the PC shows a percentage while a file
     * body is being written, and this app showed **nothing** for up to 180 s (the long read
     * budget). That silence is a large part of why a file send felt broken even when it
     * worked. The callback is null when no upload is in flight.
     */
    @Volatile var onUploadProgress: ((sent: Long, total: Long) -> Unit)? = null

    /**
     * A request body that reports how much of it has been written.
     *
     * OkHttp writes a body through a sink, so counting there measures the real wire progress
     * rather than a guess. `contentLength` is passed straight through: the server needs it, and
     * it is also the denominator of the percentage.
     */
    private class ProgressBody(
        private val delegate: okhttp3.RequestBody,
        private val onProgress: (Long, Long) -> Unit,
    ) : okhttp3.RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun writeTo(sink: okio.BufferedSink) {
            val total = contentLength()
            var written = 0L
            var lastPct = -1
            val counting = object : okio.ForwardingSink(sink) {
                override fun write(source: okio.Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    written += byteCount
                    // 100 ticks would be 100 callbacks for a 13 MB photo; 1% steps are enough
                    // for a label and keep the main thread quiet.
                    val pct = if (total > 0) ((written * 100) / total).toInt() else -1
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress(written, total)
                    }
                }
            }
            val buffered = counting.buffer()
            delegate.writeTo(buffered)
            buffered.flush()
        }
    }

    internal suspend fun post(
        path: String,
        body: String,
        longRead: Boolean = false,
        reportProgress: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val client = if (longRead) httpLong else http
        val raw = body.toRequestBody(ctype)
        val progress = onUploadProgress
        val wire: okhttp3.RequestBody =
            if (reportProgress && progress != null) ProgressBody(raw, progress) else raw
        client.newCall(
            Request.Builder().url(baseUrl + path)
                .post(wire).build()
        ).execute().use { r ->
            val text = r.body?.string().orEmpty()
            check(r.isSuccessful) { "POST $path -> ${r.code}: ${text.take(240)}" }
            text
        }
    }

    internal suspend fun patch(path: String, body: String): String = withContext(Dispatchers.IO) {
        http.newCall(
            Request.Builder().url(baseUrl + path)
                .patch(body.toRequestBody(ctype)).build()
        ).execute().use { r ->
            val text = r.body?.string().orEmpty()
            check(r.isSuccessful) { "PATCH $path -> ${r.code}: ${text.take(240)}" }
            text
        }
    }

    internal suspend fun delete(path: String): String = withContext(Dispatchers.IO) {
        http.newCall(
            Request.Builder().url(baseUrl + path).delete().build()
        ).execute().use { r ->
            val text = r.body?.string().orEmpty()
            check(r.isSuccessful) { "DELETE $path -> ${r.code}: ${text.take(240)}" }
            text
        }
    }

    /** `PUT` on this server is used for whole-object writes (env vars, pty size). */
    internal suspend fun put(path: String, body: String): String = withContext(Dispatchers.IO) {
        http.newCall(
            Request.Builder().url(baseUrl + path)
                .put(body.toRequestBody(ctype)).build()
        ).execute().use { r ->
            val text = r.body?.string().orEmpty()
            check(r.isSuccessful) { "PUT $path -> ${r.code}: ${text.take(240)}" }
            text
        }
    }

    /**
     * Consume a one-time pair link like http://host:port/auth/connect/XXXX.
     * Server answers JSON {"token":"..."} (Basic password, long-lived) when the
     * request accepts JSON; browsers instead get a Set-Cookie + redirect.
     * Returns the token, or throws.
     */
    suspend fun pair(pairUrl: String): String = withContext(Dispatchers.IO) {
        // the link embeds the server's own address; if the app reaches the server
        // through a different host (e.g. 10.0.2.2 from an emulator), rewrite to baseUrl
        val path = runCatching { java.net.URI(pairUrl.trim()).rawPath }.getOrDefault(pairUrl.trim())
        val target = if (path.startsWith("/auth/")) baseUrl.trimEnd('/') + path else pairUrl.trim()
        http.newCall(
            Request.Builder().url(target).header("Accept", "application/json").build()
        ).execute().use { r ->
            val body = r.body?.string().orEmpty()
            check(r.isSuccessful) { "pair -> ${r.code}: ${body.take(160)}" }
            val t = runCatching { org.json.JSONObject(body).optString("token") }.getOrDefault("")
                .ifBlank { Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty() }
            check(t.isNotBlank()) { "pair: no token in body: ${body.take(160)}" }
            token = t
            onToken(t) // caller persists -> survives app restarts
            t
        }
    }

    // ---- sessions ----
    /** Server wraps lists in {"data":[...]} (verified live); older builds sent bare arrays.
     *  Accept both so one server change can't brick the app. */
    private fun <T> decodeList(raw: String, deserializer: kotlinx.serialization.KSerializer<T>): List<T> {
        val el = json.parseToJsonElement(raw)
        val arr = (el as? kotlinx.serialization.json.JsonArray)
            ?: (el as? kotlinx.serialization.json.JsonObject)?.get("data") as? kotlinx.serialization.json.JsonArray
            ?: return emptyList()
        return arr.map { json.decodeFromJsonElement(deserializer, it) }
    }

    private fun decodeObject(raw: String): String {
        val el = json.parseToJsonElement(raw)
        return if (el is kotlinx.serialization.json.JsonObject && el.containsKey("data"))
            json.encodeToString(el.getValue("data")) else raw
    }

    /**
     * One cheap authenticated call, reported as a status code instead of an exception.
     *
     * The rest of this class throws `IllegalStateException("GET /path -> 401: ...")`, which is
     * the right shape for a request whose result you want. A gate needs the opposite: it must
     * tell "your access was revoked" (401) apart from "your phone has no route" (no response
     * at all), and both of those currently arrive as the same thrown exception.
     *
     * `-1` means the request never produced a response - DNS, socket, timeout. Anything else
     * is the server's own verdict.
     */
    data class Probe(val ok: Boolean, val status: Int, val detail: String)

    suspend fun probe(): Probe = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(
                Request.Builder().url(baseUrl + "/api/session?limit=1&order=desc").build(),
            ).execute().use { r ->
                val body = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
                Probe(r.isSuccessful, r.code, body.take(200))
            }
        }.getOrElse { Probe(false, -1, it.message ?: it.toString()) }
    }

    suspend fun sessions(limit: Int = 200): List<Session> =
        decodeList(get("/api/session?limit=$limit&order=desc"), Session.serializer())

    /**
     * Every session the server holds, walked page by page.
     *
     * Their card counts `SELECT count(*) FROM session_v2` (`avatar_ui.py:311`), which has
     * no cap. `sessions(limit = 200).size` did, so a user past 200 chats would have seen a
     * number that stopped moving. Returns -1 when the walk does not terminate inside
     * [maxPages] - unknown beats a truncated count.
     */
    suspend fun sessionCount(limit: Int = 200, maxPages: Int = 25): Int = runCatching {
        var cursor: String? = null
        var total = 0
        repeat(maxPages) {
            val page = sessionsPage(limit = limit, cursor = cursor)
            total += page.sessions.size
            val next = page.next
            if (next.isNullOrBlank() || page.sessions.isEmpty() || next == cursor) return total
            cursor = next
        }
        -1
    }.getOrDefault(-1)

    /**
     * `GET /api/project` — the same table their dashboard counts (`avatar_ui.py:303-319`
     * reads `project`, `session_v2`, `session_message` out of `opencode.db`). The app cannot
     * open Termux's database, so it asks the server that owns it. -1 means the call failed,
     * which the card shows as unknown rather than as zero.
     */
    suspend fun projectCount(): Int = runCatching {
        val el = json.parseToJsonElement(get("/api/project"))
        val arr = (el as? kotlinx.serialization.json.JsonArray)
            ?: (el as? kotlinx.serialization.json.JsonObject)?.get("data") as? kotlinx.serialization.json.JsonArray
        arr?.size ?: -1
    }.getOrDefault(-1)

    /** Decoded off the main thread: this session carries megabytes of JSON, and parsing it
        on the UI dispatcher is what made taps and scrolling stall. */
    suspend fun messages(sessionId: String, limit: Int = 80): List<MessageWithParts> {
        val raw = get("/api/session/$sessionId/message?limit=$limit&order=desc")
        return withContext(Dispatchers.Default) { parseMessages(raw) }
    }

    /**
     * `GET /api/session/:id/message?limit&order&cursor&type` — the paged form their timeline
     * uses. The cursor block is the same shape as the session list, so `previous` walks back
     * into older history without ever reloading what is already on screen.
     */
    suspend fun messagesPage(
        sessionId: String,
        limit: Int = 40,
        cursor: String? = null,
        type: String? = null,
    ): MessagePage {
        val q = buildList {
            add("limit=$limit")
            add("order=desc")
            if (!cursor.isNullOrBlank()) add("cursor=" + enc(cursor))
            if (!type.isNullOrBlank()) add("type=" + enc(type))
        }.joinToString("&")
        val raw = get("/api/session/$sessionId/message?$q")
        val el = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            as? kotlinx.serialization.json.JsonObject
        // A session he can see in the list gets deleted (another client, another tab): the
        // server answers 200 with `{"_tag":"SessionNotFoundError",...}`. Decoding that as an
        // empty array silently produced a blank transcript and looked like the chat vanished.
        val tag = (el?.get("_tag") as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (tag != null) {
            val msg = (el?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
            throw IllegalStateException("$tag: ${msg ?: sessionId}")
        }
        val list = withContext(Dispatchers.Default) { parseMessages(raw) }
        android.util.Log.e(
            "ZAPP",
            "messages: sid=$sessionId rows=${list.size} bytes=${raw.length}",
        )
        val cur = el?.get("cursor") as? kotlinx.serialization.json.JsonObject
        fun s(k: String) = (cur?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content
        return MessagePage(list, s("next"), s("previous"))
    }

    /**
     * Their store decodes message rows one by one; a single row the shape does not cover
     * must never blank a transcript. `decodeList` maps the whole array strictly, so one
     * unknown type or one null-in-the-wrong-place threw and the entire page became empty —
     * which is what made a session look erased right after tapping it.
     */
    private fun decodeMessages(raw: String): List<WireMessage> {
        val el = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        val arr = (el as? kotlinx.serialization.json.JsonArray)
            ?: (el as? kotlinx.serialization.json.JsonObject)
                ?.get("data") as? kotlinx.serialization.json.JsonArray
            ?: return emptyList()
        val out = ArrayList<WireMessage>(arr.size)
        var skipped = 0
        var first = ""
        for (node in arr) {
            val m = runCatching { json.decodeFromJsonElement(WireMessage.serializer(), node) }
                .getOrElse { e ->
                    skipped++
                    if (first.isEmpty()) {
                        val t = (node as? kotlinx.serialization.json.JsonObject)
                            ?.get("type")?.toString().orEmpty()
                        first = "$t -> ${e.message?.take(120)}"
                    }
                    null
                }
            if (m != null) out.add(m)
        }
        if (skipped > 0) android.util.Log.e("ZAPP", "messages: kept=${out.size} skipped=$skipped first=$first")
        return out
    }

    private fun parseMessages(raw: String): List<MessageWithParts> =
        decodeMessages(raw)
            // Every member of their `SessionMessage.Info` union is kept now. Filtering to
            // user|assistant is what made `system`, `synthetic`, `skill`, `shell` and the
            // `idle` outcome invisible in the transcript.
            .map { m ->
                MessageWithParts(
                    info = MessageInfo(
                        id = m.id,
                        role = m.type,
                        tokens = m.tokens,
                        cost = m.cost,
                        modelID = m.model?.id,
                        providerID = m.model?.providerID,
                        variant = m.model?.variant,
                        agent = m.agent,
                        type = m.type,
                        time = m.time,
                    ),
                    // user msgs carry text directly plus attached files; assistant msgs
                    // carry parts in content
                    parts = buildList {
                        when (m.type) {
                            "user" -> {
                                add(Part(type = "text", text = m.text))
                                m.files.forEach { f ->
                                    val b64 = f.data ?: f.url?.substringAfter("base64,", "")
                                    add(
                                        Part(
                                            type = "image",
                                            mime = f.mime,
                                            url = if (b64 != null) "data:${f.mime ?: "application/octet-stream"};base64,$b64" else f.url,
                                            text = f.name,
                                        ),
                                    )
                                }
                            }
                            "assistant" -> {
                                addAll(m.content)
                                // A refused turn carries no content at all, so without this the
                                // reply renders as nothing and a wall looks like a dead app.
                                if (m.error != null || m.finish == "error") {
                                    val raw = m.error?.message.orEmpty()
                                    val shown = raw.substringBefore(": {").ifBlank {
                                        m.error?.type?.takeIf { it != "unknown" } ?: "The provider rejected this request."
                                    }
                                    add(Part(type = "error", text = shown.trim()))
                                }
                                // their retry countdown, shown where it happened
                                m.retry?.let { r ->
                                    add(
                                        Part(
                                            type = "retry",
                                            text = r.error?.message
                                                ?: r.error?.type
                                                ?: "Retrying",
                                            name = r.attempt.toString(),
                                            time = r.at.takeIf { it > 0 }?.let { PartTime(created = it) },
                                        ),
                                    )
                                }
                                // the files this step touched
                                m.snapshot?.files?.takeIf { it.isNotEmpty() }?.let { fs ->
                                    add(Part(type = "touched", text = fs.joinToString(" ")))
                                }
                            }
                            "compaction" -> add(
                                Part(
                                    id = m.id,
                                    type = "compacted",
                                    text = m.summary,
                                    status = m.status,
                                    name = m.reason,
                                ),
                            )
                            "synthetic", "system" -> add(
                                Part(
                                    id = m.id,
                                    type = "system",
                                    text = m.description ?: m.text,
                                    status = m.outcome,
                                ),
                            )
                            "skill" -> add(
                                Part(
                                    id = m.id,
                                    type = "system",
                                    text = m.name?.let { "Skill: $it" } ?: "Skill loaded",
                                ),
                            )
                            "shell" -> add(
                                Part(
                                    id = m.id,
                                    type = "shellrow",
                                    text = m.command,
                                    status = m.status,
                                    name = m.exit?.toString(),
                                ),
                            )
                            "idle" -> add(
                                Part(
                                    id = m.id,
                                    type = "idle",
                                    text = m.outcome,
                                    status = m.error?.message ?: m.error?.type,
                                ),
                            )
                            "agent-switched" -> add(
                                Part(id = m.id, type = "switch", text = "Agent", name = m.agent),
                            )
                            "model-switched" -> add(
                                Part(id = m.id, type = "switch", text = "Model", name = m.model?.id),
                            )
                            "location-switched" -> add(
                                Part(id = m.id, type = "switch", text = "Directory", name = m.name),
                            )
                            else -> addAll(m.content)
                        }
                    },
                )
            }
            .reversed() // server sends newest-first; chat renders oldest->newest


    /** Session detail — carries cost + token totals (verified live shape: {data:{...}}). */
    suspend fun sessionDetail(sessionId: String): Session =
        json.decodeFromString<DetailEnvelope>(get("/api/session/$sessionId")).data

    @kotlinx.serialization.Serializable
    private data class DetailEnvelope(val data: Session)

    // ---- the Context tab (`tab-CbfQLfWC.js`) ----
    //
    // Their panel composes its document client-side: `session.get` plus every page of
    // `message.list({limit:200, order:"asc"})`, following `cursor.next`. It does **not** call
    // `/api/experimental/session/{id}/export` even though that route exists - so neither do we.

    data class ContextPayload(
        val info: kotlinx.serialization.json.JsonObject,
        val messages: List<kotlinx.serialization.json.JsonObject>,
    ) {
        /** Each message decoded for the arithmetic; unknown members are ignored. */
        val typed: List<MessageInfo> by lazy {
            messages.mapNotNull { o ->
                runCatching { WIRE.decodeFromJsonElement(MessageInfo.serializer(), o) }.getOrNull()
            }
        }

        val revertMessageId: String?
            get() = (info["revert"] as? kotlinx.serialization.json.JsonObject)
                ?.let { it["messageID"] as? kotlinx.serialization.json.JsonPrimitive }
                ?.content

        val title: String? get() = (info["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val sessionId: String? get() = (info["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val sessionProviderId: String?
            get() = ((info["model"] as? kotlinx.serialization.json.JsonObject)
                ?.get("providerID") as? kotlinx.serialization.json.JsonPrimitive)?.content
        val createdAt: Long?
            get() = ((info["time"] as? kotlinx.serialization.json.JsonObject)
                ?.get("created") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
        val cost: Double
            get() = (info["cost"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
    }

    /**
     * `ae(e)` - the whole session as their export sees it, with the 200-row pages followed.
     * A page that repeats its own cursor would loop forever, so the walk is bounded.
     */
    suspend fun contextPayload(sessionId: String): ContextPayload = withContext(Dispatchers.IO) {
        val infoRaw = get("/api/session/$sessionId")
        val infoEl = json.parseToJsonElement(infoRaw)
        val info = ((infoEl as? kotlinx.serialization.json.JsonObject)
            ?.get("data") as? kotlinx.serialization.json.JsonObject) ?: infoEl
            as? kotlinx.serialization.json.JsonObject
            ?: throw IllegalStateException("session $sessionId: not an object")
        val out = mutableListOf<kotlinx.serialization.json.JsonObject>()
        var cursor: String? = null
        var pages = 0
        while (pages < 500) {
            val q = buildString {
                append("/api/session/").append(sessionId).append("/message?limit=200&order=asc")
                if (!cursor.isNullOrBlank()) append("&cursor=").append(enc(cursor))
            }
            val el = json.parseToJsonElement(get(q)) as? kotlinx.serialization.json.JsonObject
                ?: break
            (el["data"] as? kotlinx.serialization.json.JsonArray)?.forEach { row ->
                (row as? kotlinx.serialization.json.JsonObject)?.let { out += it }
            }
            val next = ((el["cursor"] as? kotlinx.serialization.json.JsonObject)
                ?.get("next") as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (next.isNullOrBlank() || next == cursor) break
            cursor = next
            pages++
        }
        ContextPayload(info = info, messages = out)
    }

    /** The `JSON.stringify(data, null, 2)` their save writes. */
    suspend fun exportSessionJson(sessionId: String): Pair<String, String> {
        val p = contextPayload(sessionId)
        return ContextStats.exportFilename(p.title, p.sessionId ?: sessionId) to
            ContextStats.exportJson(p.info, p.messages)
    }

    /**
     * `GET /api/session/active` - the server's live-run registry.
     *
     * Their client calls this once per `server.connected` and reads it as a **map**, not a list:
     *
     * ```js
     * t().session.active().then(t => {
     *   let n = new Map(Object.keys(t).map(e => [e, `running`]));
     *   ...
     * })
     * ```
     *
     * So the body is `{"<sessionID>": "<status>"}`. An empty object means nothing is running.
     */
    suspend fun activeSessions(): Map<String, String> = withContext(Dispatchers.IO) {
        val raw = get("/api/session/active")
        val el = json.parseToJsonElement(raw)
        val obj = ((el as? kotlinx.serialization.json.JsonObject)
            ?.get("data") as? kotlinx.serialization.json.JsonObject)
            ?: el as? kotlinx.serialization.json.JsonObject
            ?: return@withContext emptyMap()
        obj.mapValues { (_, v) ->
            (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "running"
        }
    }

    /**
     * `POST /api/experimental/fs/watch?location=&path=` -> 204.
     *
     * Contract verbatim from `promise-D9LzW5Qq.js` @ 15 896:
     * ```
     * watch:(e,t)=>f({method:"POST", path:"/api/experimental/fs/watch",
     *                query:{location:e.location}, body:{path:e.path}, successStatus:204, ...})
     * unwatch:(e,t)=>f({method:"DELETE", path:"/api/experimental/fs/watch",
     *                  query:{location:e.location}, body:{path:e.path}, successStatus:204, ...})
     * ```
     *
     * The `path` is in the **body**, not the query - a watch on a directory is the same call.
     * `503` is declared: the server refuses to watch when it has no watcher available, and that
     * refusal is not an error worth surfacing.
     */
    suspend fun fsWatch(path: String, location: String? = null): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val q = location?.takeIf { it.isNotBlank() }?.let { "?location=" + enc(it) } ?: ""
            post("/api/experimental/fs/watch$q", "{\"path\":" + json.encodeToString(path) + "}")
            true
        }.getOrDefault(false)
    }

    /** `DELETE /api/experimental/fs/watch` - the same body, so it closes the same watch. */
    suspend fun fsUnwatch(path: String, location: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val q = location?.takeIf { it.isNotBlank() }?.let { "?location=" + enc(it) } ?: ""
                http.newCall(
                    Request.Builder().url(baseUrl + "/api/experimental/fs/watch" + q)
                        .delete(("{\"path\":" + json.encodeToString(path) + "}").toRequestBody(ctype))
                        .build(),
                ).execute().use { it.isSuccessful || it.code == 204 }
            }.getOrDefault(false)
        }

    companion object {
        /** A lenient decoder for individual message objects. */
        private val WIRE = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            coerceInputValues = true
        }
    }

    /** Send a user message. VERIFIED LIVE: body is {text} (+ optional files/agents/...).
     *  The old {"parts":[...]} shape returns 400 Missing key ["text"] on this server. */
    suspend fun prompt(
        sessionId: String,
        text: String,
        providerID: String? = null,
        modelID: String? = null,
        files: List<OutAttachment> = emptyList(),
        delivery: String? = null,
        agents: List<String> = emptyList(),
        skills: List<SkillPick> = emptyList(),
    ): String {
        // Live contract, read straight from this server's `/openapi.json`:
        //   POST /api/session/{sessionID}/prompt
        //   { text: string (REQUIRED), files[], agents[], skills[], metadata,
        //     delivery, resume }   additionalProperties:false
        // It is FLAT. The wrapped `{"prompt":{...}}` form is what this server answers with
        // 400 — and because the old code tried the wrapper first and only retried on
        // "Missing key", every send on 2.3.x died before it was ever stored. That is why
        // his own message vanished and the transcript looked like it was not saved.
        // Order now: flat (current contract) -> wrapped (1.19.6) -> legacy `uri` files.
        fun filesJson(legacyUri: Boolean): String = if (files.isEmpty()) "" else buildString {
            append(",\"files\":[")
            files.forEachIndexed { i, f ->
                if (i > 0) append(',')
                if (legacyUri) {
                    append("{\"uri\":").append(json.encodeToString(f.dataUrl))
                    append(",\"name\":").append(json.encodeToString(f.name))
                    append(",\"source\":{\"type\":\"inline\"}}")
                } else {
                    append("{\"data\":").append(json.encodeToString(f.base64))
                    append(",\"mime\":").append(json.encodeToString(f.mime))
                    append(",\"name\":").append(json.encodeToString(f.name))
                    append(",\"source\":{\"type\":\"inline\"}")
                    if (f.mentionStart != null && f.mentionEnd != null && f.mentionText != null) {
                        append(",\"mention\":{\"start\":").append(f.mentionStart)
                        append(",\"end\":").append(f.mentionEnd)
                        append(",\"text\":").append(json.encodeToString(f.mentionText))
                        append('}')
                    }
                    append('}')
                }
            }
            append(']')
        }

        // `Prompt.AgentAttachment {name, mention?}` and `Prompt.SkillAttachment {id, name,
        // text?, mention?}` ride alongside files in the same PromptInput.
        fun agentsJson(): String = if (agents.isEmpty()) "" else buildString {
            append(",\"agents\":[")
            agents.forEachIndexed { i, a ->
                if (i > 0) append(',')
                append("{\"name\":").append(json.encodeToString(a)).append('}')
            }
            append(']')
        }

        // Live contract, `PromptInput.SkillAttachment`: `{id}` only, `additionalProperties:false`.
        // The old body also sent `name`, which the schema rejects.
        fun skillsJson(): String = if (skills.isEmpty()) "" else buildString {
            append(",\"skills\":[")
            skills.forEachIndexed { i, s ->
                if (i > 0) append(',')
                append("{\"id\":").append(json.encodeToString(s.id)).append('}')
            }
            append(']')
        }

        fun body(uriShape: Boolean): String {
            val fields = "\"text\":" + json.encodeToString(text) + filesJson(legacyUri = uriShape) +
                agentsJson() + skillsJson()
            val deliveryField =
                if (delivery != null) ",\"delivery\":${json.encodeToString(delivery)}" else ""
            return "{$fields$deliveryField}"
        }

        val url = "/api/session/$sessionId/prompt"
        // The live server's own schema decides the order, not memory. Read from this server's
        // `/openapi.json` (2026-11-21 11:41):
        //
        //   components.schemas["PromptInput.FileAttachment"] = {
        //     uri (REQUIRED), source?, name?, description?, mention?
        //   }, additionalProperties:false
        //
        // There is no `data` and no `mime` in it, so the `uri` carrier is this build's contract
        // shape and it goes first. The `data`+`mime` pair stays as the fallback for the older
        // server that used it.
        //
        // The wrapped `{"prompt":{...}}` form is gone. It was only ever reached after a failure
        // and it was built as `"{\"prompt\":{$fields$deliveryField}"` - the outer brace was never
        // closed - so it could not parse anywhere. Measured 2026-11-21 11:33:10: his file send
        // died with `400 Expected a valid JSON body`, which was that broken fallback speaking,
        // not the server rejecting his file.
        var lastError: Throwable? = null
        val shapes = listOf("uri" to body(uriShape = true), "data" to body(uriShape = false))
        for ((label, payload) in shapes) {
            try {
                // `ui.promptInput.uploading`: a body carrying files gets a percentage, a text
                // prompt does not - there is nothing to watch on a 200-byte body.
                return post(url, payload, longRead = true, reportProgress = files.isNotEmpty())
            } catch (e: Exception) {
                lastError = e
                android.util.Log.e("ZAPP", "prompt $label shape failed: ${e.message}")
                // Only a 4xx is a verdict about the shape. A timeout or a dropped socket means
                // the server may already hold the message, and posting a second shape could
                // deliver it twice - nothing in this app may send what he did not send.
                if (!e.message.orEmpty().contains("-> 4")) break
            }
        }
        throw lastError ?: IllegalStateException("prompt: no shape attempted")
    }

    /**
     * `POST /api/session/{sessionID}/shell` - PC's shell mode (`prompt.mode.shell`).
     *
     * Read from this phone's own `/openapi.json` (2026-11-21): the body is `{command}` and
     * `id` is optional, and the only success response is **204 No Content**. So there is
     * nothing to render from the reply - the command and its output arrive on `/api/event`
     * and in the ordinary message refresh, exactly like a prompt's parts do.
     */
    suspend fun shell(sessionId: String, command: String): String =
        post(
            "/api/session/$sessionId/shell",
            "{\"command\":${json.encodeToString(command)}}",
            longRead = true,
        )

    suspend fun newSession(title: String? = null): Session {
        val body = if (title != null) """{"title":${json.encodeToString(title)}}""" else "{}"
        return json.decodeFromString(decodeObject(post("/api/session", body)))
    }

    /** VERIFIED LIVE: PATCH /api/session/:id {"title":"..."} persists the new title. */
    suspend fun renameSession(sessionId: String, title: String): Session {
        val body = """{"title":${json.encodeToString(title)}}"""
        return json.decodeFromString(decodeObject(patch("/api/session/$sessionId", body)))
    }

    /** VERIFIED LIVE: DELETE /api/session/:id returns 200 and removes the session. */
    suspend fun deleteSession(sessionId: String) {
        delete("/api/session/$sessionId")
    }

    /**
     * Stop the running turn. Copying their shape exactly:
     *   POST /api/session/:id/interrupt?resume=<bool>   (no body)
     * The old `/abort` route does not exist on 2.3.2 — it answers **404 with an empty body**,
     * so every press of Stop was silently doing nothing. A 404 falls through to `/abort`
     * only so an older server (1.19.6) keeps its own route working.
     */
    suspend fun interrupt(sessionId: String, resume: Boolean = false): Boolean =
        try {
            post("/api/session/$sessionId/interrupt?resume=$resume", "")
            true
        } catch (e: Exception) {
            runCatching { post("/api/session/$sessionId/abort", "{}") }.isSuccess
        }

    /** Kept for callers that still spell it `abort`; it is the same interrupt now. */
    suspend fun abort(sessionId: String): String {
        interrupt(sessionId)
        return ""
    }

    // ---- session commands (their composer's `/` menu) ----
    /** GET /api/command -> [{name, description}] — measured on 2.3.2: init, review, goal. */
    suspend fun commands(): List<CommandEntry> =
        decodeList(get("/api/command"), CommandEntry.serializer())

    /** POST /api/session/:id/command {name,text,files,agents,skills,delivery} -> 204.
     *  This is how `/init`, `/review`, `/goal` are sent: the server resolves the template. */
    suspend fun runCommand(
        sessionId: String,
        name: String,
        text: String = "",
        delivery: String? = null,
    ) {
        post("/api/session/$sessionId/command", buildString {
            append("{\"name\":").append(json.encodeToString(name))
            append(",\"text\":").append(json.encodeToString(text))
            if (delivery != null) append(",\"delivery\":").append(json.encodeToString(delivery))
            append('}')
        })
    }

    // ---- the real usage/quota surface ----
    /**
     * GET /api/experimental/usage/limits  (`location` is a deepObject: ?location[directory]=…)
     * -> `{location, data}`. `data: null` is a real answer, not a failure: no workspace is
     * connected, so there is no budget to meter. Their desktop renders the signed-out copy
     * for exactly that case (`usage.signedOut.*`).
     */
    suspend fun usageLimits(directory: String? = null): UsageEnvelope? = runCatching {
        val q = directory?.let {
            "?location%5Bdirectory%5D=" + java.net.URLEncoder.encode(it, "UTF-8")
        }.orEmpty()
        json.decodeFromString(UsageEnvelope.serializer(), get("/api/experimental/usage/limits$q"))
    }.getOrNull()

    /** POST /api/session/:id/agent {agent} -> 204 — switches build/plan/… for this session. */
    suspend fun setAgent(sessionId: String, agent: String) {
        post("/api/session/$sessionId/agent", "{\"agent\":" + json.encodeToString(agent) + "}")
    }

    /** POST /api/session/:id/compact {id?,delivery?} -> 200 — manual compaction. Their
     *  server also compacts on its own; this is the `/compact` action. */
    suspend fun compact(sessionId: String, delivery: String? = "steer"): String =
        post("/api/session/$sessionId/compact", buildString {
            append('{')
            if (delivery != null) append("\"delivery\":").append(json.encodeToString(delivery))
            append('}')
        })

    /** POST /api/session/:id/synthetic {text} — a system notice that enters the transcript
     *  without asking the model anything. */
    suspend fun synthetic(sessionId: String, text: String, description: String? = null) {
        post("/api/session/$sessionId/synthetic", buildString {
            append("{\"text\":").append(json.encodeToString(text))
            if (description != null) append(",\"description\":").append(json.encodeToString(description))
            append('}')
        })
    }

    /** POST /api/session/:id/generate {prompt:String} -> generated text. The prompt is a
     *  plain string on this route, not a PromptInput object. */
    suspend fun generate(sessionId: String, prompt: String): String =
        decodeObject(
            post("/api/session/$sessionId/generate", "{\"prompt\":${json.encodeToString(prompt)}}"),
        )

    // ---- permissions ----
    /** GET /api/session/:id/permission -> outstanding requests for one session. */
    suspend fun permissions(sessionId: String): List<PermissionRequest> =
        decodeList(get("/api/session/$sessionId/permission"), PermissionRequest.serializer())

    /** GET /api/session/:id/permission/:requestID — one outstanding request. */
    suspend fun permission(sessionId: String, requestId: String): PermissionRequest? =
        runCatching {
            json.decodeFromString<PermissionEnvelope>(
                get("/api/session/$sessionId/permission/$requestId"),
            ).data
        }.getOrNull()

    /** GET /api/permission/request -> every outstanding request on the server. */
    suspend fun pendingPermissions(): List<PermissionRequest> =
        decodeList(get("/api/permission/request"), PermissionRequest.serializer())

    /** POST /api/session/:id/permission/:requestID/reply {decision,message} -> 204.
     *  decision is exactly one of `once`, `always`, `reject` (their `Permission.Reply`). */
    suspend fun replyPermission(
        sessionId: String,
        requestId: String,
        decision: String,
        message: String? = null,
    ) {
        post(
            "/api/session/$sessionId/permission/$requestId/reply",
            buildString {
                append("{\"decision\":").append(json.encodeToString(decision))
                if (!message.isNullOrBlank()) append(",\"message\":").append(json.encodeToString(message))
                append('}')
            },
        )
    }

    // ---- single message / context / diff ----
    /** GET /api/session/:id/message/:mid — one message with its parts. Used by the live
     *  streaming path so a first delta does not need a whole-transcript refetch. */
    suspend fun message(sessionId: String, messageId: String): MessageWithParts? =
        runCatching {
            val raw = decodeObject(get("/api/session/$sessionId/message/$messageId"))
            parseMessages("{\"data\":[" + raw + "]}").firstOrNull()
        }.getOrNull()

    /** GET /api/session/:id/context — the context window the model is actually holding. */
    suspend fun context(sessionId: String): String =
        runCatching { decodeObject(get("/api/session/$sessionId/context")) }.getOrDefault("")

    /** GET /api/session/:id/diff — file changes produced by this session. */
    suspend fun sessionDiff(sessionId: String): String =
        runCatching { decodeObject(get("/api/session/$sessionId/diff")) }.getOrDefault("")

    /**
     * The same route decoded: `{data:[FileDiff.Info]}`. This is the "Last turn changes" mode
     * (`review/model.ts:663`), which is why it is a list of files, not a raw patch string.
     */
    suspend fun sessionDiffFiles(sessionId: String, context: Int? = null): List<PartFile> {
        val q = if (context != null) "?context=$context" else ""
        return decodeList(get("/api/session/$sessionId/diff$q"), PartFile.serializer())
    }

    /** DELETE /api/session/:id/revert — `unrevert`: drop a staged revert. */
    suspend fun unrevert(sessionId: String) {
        delete("/api/session/$sessionId/revert")
    }

    /** POST /api/session/:id/view {idle?} -> 204 — marks the session read. */
    /** POST /api/session/:id/view {idle} -> 204 - marks the session read.
        The body key is **required** (`openapi.json` -> `Session.ViewInput`, `idle: number`);
        our old `{}` was answered with `400 ... "Missing key at [\"idle\"]"` on every open. */
    suspend fun markViewed(sessionId: String, idle: Long) {
        runCatching { post("/api/session/$sessionId/view", """{"idle":$idle}""") }
    }

    // ---- session list: search / archive / rename ----
    /** PATCH /api/session/:id {title?, archived?} -> 204. */
    suspend fun patchSession(sessionId: String, title: String? = null, archived: Boolean? = null) {
        patch("/api/session/$sessionId", buildString {
            append('{')
            var first = true
            if (title != null) {
                append("\"title\":").append(json.encodeToString(title)); first = false
            }
            if (archived != null) {
                if (!first) append(',')
                append("\"archived\":").append(archived)
            }
            append('}')
        })
    }

    /** GET /api/session?search=&order=desc — server-side session search. */
    suspend fun searchSessions(query: String, limit: Int = 60): List<Session> =
        decodeList(
            get("/api/session?limit=$limit&order=desc&search=" + enc(query)),
            Session.serializer(),
        )

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ---- files / models ----
    /** GET /api/fs/list?path= -> [{path,type}] — the @file browser. `/api/fs/find` answers
     *  500 UnknownError on the phone's 2.3.2, so listing is the only working path. */
    suspend fun fsList(path: String? = null): List<FsEntry> =
        decodeList(
            get("/api/fs/list" + (path?.let { "?path=" + enc(it) } ?: "")),
            FsEntry.serializer(),
        )

    /** GET /api/model/default — the model their own client starts a session with. */
    suspend fun defaultModel(): ModelInfo? =
        runCatching {
            json.decodeFromString<ModelEnvelope>(get("/api/model/default")).data
        }.getOrNull()


    /** Their `session.background` command — the button above the composer posts this.
        Verified live against the PC server: 204 with an empty body. */
    suspend fun backgroundSession(sessionId: String) {
        post("/api/session/$sessionId/background", "{}")
    }

    /** `Fork from here` — verified live: POST /fork {before:<messageID>} returns the child
        session; `before:null`/omitted copies the whole history. */
    suspend fun forkSession(sessionId: String, before: String?): Session =
        json.decodeFromString<DetailEnvelope>(
            post(
                "/api/session/$sessionId/fork",
                if (before == null) "{}" else "{\"before\":" + json.encodeToString(before) + "}",
            ),
        ).data

    /** `Undo` — verified live on a scratch session: stage {messageID} answers
        `{"data":{"messageID":"…","files":[]}}`, commit drops that message and everything after
        (the timeline came back empty when the only user message was undone). */
    suspend fun undoMessage(sessionId: String, messageId: String) {
        rewind(sessionId, messageId)
        post("/api/session/$sessionId/revert/commit", "{}")
    }

    /**
     * Their rewind, in their order (`route-CMUQLRzL.js` @ 512 669):
     *
     * ```js
     * await n.api.session.interrupt({sessionID: e});
     * await n.api.experimental.session.wait({sessionID: e});
     * await n.api.session.revert.stage({sessionID: e, messageID: r.message.id});
     * ```
     *
     * The `wait` is not decoration: staging a revert while a turn is still streaming would let the
     * running turn append messages past the cut. Their client stops the turn, waits for the
     * server to report idle, and only then stages. `redo` is the other branch of the same machine
     * and is just `session.revert.clear`.
     */
    suspend fun rewind(sessionId: String, messageId: String, files: Boolean = true) {
        interrupt(sessionId)
        runCatching { waitSession(sessionId) }
        stageRevert(sessionId, messageId, files)
    }

    /** Prompts waiting for the running turn — the desktop prints `Pending` under them. */
    suspend fun inbox(sessionId: String): List<InboxItem> =
        decodeList(get("/api/session/$sessionId/inbox"), InboxItem.serializer())

    suspend fun cancelInbox(sessionId: String, id: String) {
        delete("/api/session/$sessionId/inbox/$id")
    }

    /** Their `inbox.update`: PATCH .../inbox/:id with `{delivery}` — 204 on success,
     *  409 when the item already left the queue (`ui.message.moveToQueue`). */
    suspend fun setInboxDelivery(sessionId: String, id: String, delivery: String) {
        patch("/api/session/$sessionId/inbox/$id", "{\"delivery\":\"$delivery\"}")
    }

    suspend fun info(): String = get("/api/info")

    // ---- extensions (all endpoints VERIFIED LIVE on the PC server) ----
    /**
     * `GET /api/mcp?location[directory]=` -> `[{name, status:{status}, integrationID?}]`.
     *
     * Their client always sends the location (`mcp.list({location})`); the bare route answers for
     * the server's own default directory, which on a phone is not the session's directory.
     */
    suspend fun mcpServers(directory: String? = null): List<McpEntry> =
        decodeList(get("/api/mcp" + locOnly(directory)), McpEntry.serializer())

    /**
     * `POST /api/experimental/mcp/{server}/connect?location[directory]=` -> 204.
     *
     * Only correct for a server that is **not** `needs_auth`: their toggle skips this call in that
     * case, because a server waiting for OAuth cannot be connected by asking.
     */
    suspend fun mcpConnect(server: String, directory: String? = null) {
        post("/api/experimental/mcp/${enc(server)}/connect" + locOnly(directory), "")
    }

    /** `POST /api/experimental/mcp/{server}/disconnect?location[directory]=` -> 204. */
    suspend fun mcpDisconnect(server: String, directory: String? = null) {
        post("/api/experimental/mcp/${enc(server)}/disconnect" + locOnly(directory), "")
    }

    /** `PUT /api/experimental/mcp/{server}?location[directory]=` body `{config}` -> 204. */
    suspend fun mcpAdd(server: String, config: kotlinx.serialization.json.JsonObject, directory: String? = null) {
        put("/api/experimental/mcp/${enc(server)}" + locOnly(directory), config.toString())
    }

    /** `DELETE /api/experimental/mcp/{server}?location[directory]=` -> 204. */
    suspend fun mcpRemove(server: String, directory: String? = null) {
        delete("/api/experimental/mcp/${enc(server)}" + locOnly(directory))
    }

    /**
     * `GET /api/mcp/resource?location[directory]=` -> `{location, data:{resources:[...]}}`.
     *
     * Their client syncs this into `g.location[i].mcpResource` on every location sync
     * (`solid-B85sHMHe.js` @ 37 252, `resource: X('mcpResource', async e => ({location: n.location,
     * data: n.data.resources}))`) and refreshes it on the `mcp.resources.changed` event. **No
     * shipped surface renders it** - the catalog is cached for a resources browser their build does
     * not include, so ZAPP fetches it and keeps it, without inventing a screen for it.
     */
    suspend fun mcpResources(directory: String? = null): List<McpResource> = runCatching {
        json.decodeFromString(
            McpResourceEnvelope.serializer(),
            get("/api/mcp/resource" + locOnly(directory)),
        ).data?.resources ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * `POST /api/plugin/check?location[directory]=` body `{}` -> the plugin list again, each
     * package entry carrying `source.outdated`.
     *
     * Their client calls it with no argument, so `target` is omitted entirely and the server
     * checks every package. A single-target check sends `{"target": "..."}`.
     */
    suspend fun pluginCheck(directory: String? = null, target: String? = null): List<PluginEntry> =
        decodeList(
            post(
                "/api/plugin/check" + locOnly(directory),
                if (target == null) "{}" else "{\"target\":" + json.encodeToString(target) + "}",
            ),
            PluginEntry.serializer(),
        )

    /** `POST /api/plugin/update?location[directory]=` body `{targets:[...]}` -> 204. */
    suspend fun pluginUpdate(targets: List<String>, directory: String? = null) {
        post(
            "/api/plugin/update" + locOnly(directory),
            "{\"targets\":" + json.encodeToString(targets) + "}",
        )
    }

    /**
     * `GET /api/integration?location[directory]=` -> `[{id, name, methods, connections}]`.
     *
     * This is the real Discord/MCP-OAuth surface. Their Settings -> Extensions reads it to find an
     * `oauth` method with no form when an MCP server answers `needs_auth`.
     */
    suspend fun integrationList(directory: String? = null): List<IntegrationEntry> =
        decodeList(get("/api/integration" + locOnly(directory)), IntegrationEntry.serializer())

    /** `GET /api/integration/{id}?location[directory]=` -> one integration with its methods. */
    suspend fun integrationGet(id: String, directory: String? = null): IntegrationEntry? =
        runCatching {
            json.decodeFromString(
                IntegrationEnvelope.serializer(),
                get("/api/integration/${enc(id)}" + locOnly(directory)),
            ).data
        }.getOrNull()

    /**
     * `POST /api/integration/{id}/connect/oauth?location[directory]=` body `{methodID,answer}`
     * -> `{location, data:{attemptID,url,instructions,mode,time{created,expires}}}`.
     *
     * Read from the live server on 2026-11-24 for integration `opencode`, method `device`:
     *   `{"attemptID":"con_...","url":"https://opencode.ai/console/device?user_code=QHJM-BZVG",
     *     "instructions":"Enter code: QHJM-BZVG","mode":"auto", ...}`
     * Their desktop reads exactly these fields (`provider.connect.console.deviceCode.label` is
     * `Device code: {{code}}`, and the code shown is this one). Nothing is guessed here.
     */
    suspend fun connectOAuth(
        integrationID: String,
        methodID: String,
        answer: Map<String, String> = emptyMap(),
        directory: String? = null,
    ): ConnectStart? = runCatching {
        val body = buildString {
            append("{\"methodID\":").append(json.encodeToString(methodID))
            append(",\"answer\":{")
            append(
                answer.entries.joinToString(",") { (k, v) ->
                    json.encodeToString(k) + ":" + json.encodeToString(v)
                },
            )
            append("}}")
        }
        json.decodeFromString(
            ConnectEnvelope.serializer(),
            post("/api/integration/${enc(integrationID)}/connect/oauth" + locOnly(directory), body),
        ).data
    }.getOrNull()

    /** `GET .../connect/oauth/{attemptID}` -> `{location, data:{status,time}}`. `pending` until the browser confirms. */
    suspend fun connectStatus(
        integrationID: String,
        attemptID: String,
        directory: String? = null,
    ): ConnectStatus? = runCatching {
        json.decodeFromString(
            ConnectStatusEnvelope.serializer(),
            get("/api/integration/${enc(integrationID)}/connect/oauth/${enc(attemptID)}" + locOnly(directory)),
        ).data
    }.getOrNull()

    /** `DELETE .../connect/oauth/{attemptID}` -> 204. Their "Try again" throws the old code away first. */
    suspend fun connectCancel(integrationID: String, attemptID: String, directory: String? = null) {
        runCatching {
            delete("/api/integration/${enc(integrationID)}/connect/oauth/${enc(attemptID)}" + locOnly(directory))
        }
    }

    /** /api/skill -> [{id,name,description,path}] */
    suspend fun skills(): List<SkillEntry> =
        decodeList(get("/api/skill"), SkillEntry.serializer())

    /** /api/plugin -> [{id,source:{type},state:{status}}] */
    suspend fun plugins(): List<PluginEntry> =
        decodeList(get("/api/plugin"), PluginEntry.serializer())

    /** /api/config -> [{type:"document",path,info:{...}}] raw text, shown in the config panel */
    suspend fun configDocs(): String = get("/api/config")

    /** /api/agent -> [{id,name}] — composer's Build label */
    suspend fun agentNames(): List<AgentEntry> =
        decodeList(get("/api/agent"), AgentEntry.serializer())

    // ---- background shells (shape copied from the server's own /openapi.json) ----
    /** GET /api/shell -> [{id,status,command,cwd,shell,file,pid,exit,signal,metadata,time}] */
    suspend fun shells(): List<ShellEntry> =
        decodeList(get("/api/shell"), ShellEntry.serializer())

    /** GET /api/shell/:id -> one shell, with its exit code once it is done. */
    suspend fun shell(id: String): ShellEntry? =
        runCatching {
            json.decodeFromString(ShellEnvelope.serializer(), get("/api/shell/$id")).data
        }.getOrNull()

    /**
     * GET /api/shell/:id/output?cursor=&limit= — the same incremental tail their client
     * reads: hand back the cursor you last saw, get only the new bytes, keep the new cursor.
     */
    suspend fun shellOutput(id: String, cursor: Long? = null, limit: Int? = null): ShellOutput? =
        runCatching {
            val q = buildString {
                val c = cursor?.let { "cursor=$it" }
                val l = limit?.let { "limit=$it" }
                val joined = listOfNotNull(c, l).joinToString("&")
                if (joined.isNotEmpty()) append('?').append(joined)
            }
            json.decodeFromString(ShellOutEnvelope.serializer(), get("/api/shell/$id/output$q")).data
        }.getOrNull()

    /** POST /api/shell {command,cwd?,timeout?} — start a background command ourselves. */
    suspend fun runShell(command: String, cwd: String? = null, timeout: Long? = null): ShellEntry =
        json.decodeFromString(
            ShellEnvelope.serializer(),
            post("/api/shell", buildString {
                append("{\"command\":").append(json.encodeToString(command))
                if (!cwd.isNullOrBlank()) append(",\"cwd\":").append(json.encodeToString(cwd))
                if (timeout != null) append(",\"timeout\":").append(timeout)
                append('}')
            }),
        ).data ?: error("POST /api/shell returned no data")

    /**
     * POST /api/session/:id/shell {id?,command} — their "move to background": the command
     * leaves the message's inline tool card and becomes a real shell in /api/shell, still
     * attached to that message id.
     */
    suspend fun detachShell(sessionId: String, messageId: String?, command: String): ShellEntry =
        json.decodeFromString(
            ShellEnvelope.serializer(),
            post("/api/session/$sessionId/shell", buildString {
                append("{\"command\":").append(json.encodeToString(command))
                if (!messageId.isNullOrBlank()) append(",\"id\":").append(json.encodeToString(messageId))
                append('}')
            }),
        ).data ?: error("POST /api/session/:id/shell returned no data")

    /** DELETE /api/shell/:id -> 204 */
    suspend fun killShell(id: String): Unit = delete("/api/shell/$id").let { }

    // ---- session move / revert with file selection / paged list ----

    /** POST /api/session/:id/move {directory, delivery?} -> 204 — their "move session". */
    suspend fun moveSession(sessionId: String, directory: String, delivery: String? = null) {
        post("/api/session/$sessionId/move", buildString {
            append("{\"directory\":").append(json.encodeToString(directory))
            if (delivery != null) append(",\"delivery\":").append(json.encodeToString(delivery))
            append('}')
        })
    }

    /** POST /api/session/:id/revert/stage {messageID, files?} -> 200.
     *  `files` is a BOOLEAN on the wire (`SessionRevertStageInput.files?: boolean`), not a
     *  list: it says whether the files on disk go back too, or only the transcript does.
     *  Sending an array fails validation with 400, so the flag is explicit here. */
    suspend fun stageRevert(sessionId: String, messageId: String, files: Boolean = true) {
        post("/api/session/$sessionId/revert/stage", buildString {
            append("{\"messageID\":").append(json.encodeToString(messageId))
            append(",\"files\":").append(files)
            append('}')
        })
    }

    /** POST /api/session/:id/revert/commit -> 204 — apply the staged revert. */
    suspend fun commitRevert(sessionId: String) {
        post("/api/session/$sessionId/revert/commit", "{}")
    }

    /** GET /api/session?search=&archived=&parentID=&cursor= — the same call their list makes. */
    suspend fun sessionsPage(
        limit: Int = 60,
        search: String? = null,
        archived: Boolean? = null,
        parentID: String? = null,
        cursor: String? = null,
    ): SessionPage {
        val q = buildList {
            add("limit=$limit")
            add("order=desc")
            if (!search.isNullOrBlank()) add("search=" + enc(search))
            if (archived != null) add("archived=$archived")
            if (!parentID.isNullOrBlank()) add("parentID=" + enc(parentID))
            if (!cursor.isNullOrBlank()) add("cursor=" + enc(cursor))
        }.joinToString("&")
        val raw = get("/api/session?$q")
        val list = decodeList(raw, Session.serializer())
        val el = json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
        val cur = el?.get("cursor") as? kotlinx.serialization.json.JsonObject
        fun s(k: String) = (cur?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content
        return SessionPage(list, s("next"), s("previous"))
    }

    // ---- permission rules ----

    /** GET /api/permission/saved?projectID= -> [{id, projectID, action, resource, time}] */
    suspend fun savedPermissions(projectID: String? = null): List<SavedPermission> =
        decodeList(
            get("/api/permission/saved" + (projectID?.let { "?projectID=" + enc(it) } ?: "")),
            SavedPermission.serializer(),
        )

    /** PATCH /api/permission/saved/:id {action, resource} -> 200 */
    suspend fun patchSavedPermission(id: String, action: String, resource: String) {
        patch("/api/permission/saved/$id", buildString {
            append("{\"action\":").append(json.encodeToString(action))
            append(",\"resource\":").append(json.encodeToString(resource))
            append('}')
        })
    }

    /** DELETE /api/permission/saved/:id -> 204 */
    suspend fun deleteSavedPermission(id: String) {
        delete("/api/permission/saved/$id")
    }

    // ---- forms: their server-driven question cards ----

    /** GET /api/session/:id/form -> [Form.Info] */
    suspend fun forms(sessionId: String): List<FormInfo> =
        decodeList(get("/api/session/$sessionId/form"), FormInfo.serializer())

    /** GET /api/form -> every global form (MCP elicitation uses the "global" owner). */
    suspend fun globalForms(): List<FormInfo> =
        decodeList(get("/api/form"), FormInfo.serializer())

    /** GET /api/session/:id/form/:formID -> Form.Detail (info + state) */
    suspend fun form(sessionId: String, formId: String): String =
        decodeObject(get("/api/session/$sessionId/form/$formId"))

    /** POST /api/session/:id/form/:formID/reply {answer} -> 204 */
    suspend fun replyForm(sessionId: String, formId: String, answerJson: String) {
        post("/api/session/$sessionId/form/$formId/reply", "{\"answer\":$answerJson}")
    }

    /** DELETE /api/session/:id/form/:formID?message= -> 204 */
    suspend fun cancelForm(sessionId: String, formId: String, message: String? = null) {
        delete(
            "/api/session/$sessionId/form/$formId" +
                (message?.let { "?message=" + enc(it) } ?: ""),
        )
    }

    // ---- questions: the `question` tool's blocking request ----
    //
    // Their route table, proven from the running server binary
    // (`/data/data/com.termux/files/home/.local/bin/opencode`, offset ~119 648 654):
    //
    //   GET  /api/session/:sessionID/question                        -> {data: QuestionV2.Request[]}
    //   POST /api/session/:sessionID/question/:requestID/reply       {answers: string[][]}
    //   POST /api/session/:sessionID/question/:requestID/reject      -> 204
    //   GET  /api/question/request                                   -> every pending request
    //
    // The payload description is theirs, verbatim: "User answers in order of questions
    // (each answer is an array of selected labels)". So the array index IS the question
    // index, and an unanswered question is an empty array - not a missing entry.

    /** GET /api/session/:id/question -> pending requests for one session. */
    suspend fun questions(sessionId: String): List<QuestionRequest> =
        decodeList(get("/api/session/$sessionId/question"), QuestionRequest.serializer())

    /** GET /api/question/request -> every pending request on the server. */
    suspend fun pendingQuestions(): List<QuestionRequest> =
        decodeList(get("/api/question/request"), QuestionRequest.serializer())

    /** POST /api/session/:id/question/:requestID/reply {answers} -> 204. */
    suspend fun replyQuestion(sessionId: String, requestId: String, answers: List<List<String>>) {
        post(
            "/api/session/$sessionId/question/$requestId/reply",
            buildString {
                append("{\"answers\":[")
                answers.forEachIndexed { i, group ->
                    if (i > 0) append(',')
                    append('[')
                    group.forEachIndexed { j, label ->
                        if (j > 0) append(',')
                        append(json.encodeToString(label))
                    }
                    append(']')
                }
                append("]}")
            },
        )
    }

    /** POST /api/session/:id/question/:requestID/reject -> 204. The model gets
     *  `QuestionV2.RejectedError` = "The user dismissed this question". */
    suspend fun rejectQuestion(sessionId: String, requestId: String) {
        post("/api/session/$sessionId/question/$requestId/reject", "{}")
    }

    // ---- skills ----

    /** POST /api/experimental/session/:id/skill {id, resume?} -> 204 — activate a skill. */
    suspend fun activateSkill(sessionId: String, skillId: String, resume: Boolean = false) {
        post("/api/experimental/session/$sessionId/skill", buildString {
            append("{\"id\":").append(json.encodeToString(skillId))
            append(",\"resume\":").append(resume)
            append('}')
        })
    }

    // ---- version control (their Review / branches surface) ----

    /**
     * `GET /api/vcs?location[directory]=` -> `Vcs.Info` (`{provider?, branch{current,default}}`),
     * the value desktop reads as `location.vcs.info(directory)` and feeds every review mode
     * decision (`gui-plugins/src/review/model.ts:361`). A missing repository answers 404
     * `LocationNotFoundError`, which is the "no-vcs" branch there, so it decodes to null.
     */
    suspend fun vcsInfo(directory: String? = null): VcsInfo? {
        // Cancellation (a timeout, a session switch) must never read as "no repository":
        // desktop shows a failed load with Retry, never a clean working tree.
        val raw = try {
            get("/api/vcs" + locOnly(directory))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return runCatching {
            json.decodeFromString(VcsInfo.serializer(), decodeObject(raw))
        }.getOrNull()
    }

    /**
     * `GET /api/vcs/diff` -> `{location, data:[FileDiff.Info]}`.
     *
     * Desktop always sends `untracked: "folders"` (`review/model.ts:677`) and maps its own
     * `git` mode onto the wire mode `working` (`model.ts:675`): the panel's Git tab is the
     * working tree, Branch is the merge-base comparison, and `committed` is the third mode
     * the route accepts but the panel never offers.
     */
    suspend fun vcsDiffFiles(
        directory: String? = null,
        mode: String,
        untracked: String = "folders",
        context: Int? = null,
    ): List<PartFile> {
        val q = buildList {
            if (!directory.isNullOrBlank()) add("location%5Bdirectory%5D=" + enc(directory))
            add("mode=" + enc(mode))
            add("untracked=" + enc(untracked))
            // `review/model.ts:769` sends `context: 3` for the bounded stage of a full patch.
            if (context != null) add("context=$context")
        }.joinToString("&")
        return decodeList(get("/api/vcs/diff?$q"), PartFile.serializer())
    }

    /** `GET /api/vcs/status` -> the working tree, same shape their Review panel reads. */
    suspend fun vcsStatus(directory: String? = null): String =
        get("/api/vcs/status" + locOnly(directory))

    /** `GET /api/vcs/base` -> `{name, ref, source}`: the comparison base the branch mode diffs against. */
    suspend fun vcsBase(directory: String? = null): String =
        decodeObject(get("/api/vcs/base" + locOnly(directory)))

    /** `GET /api/vcs/diff?mode=&base=&context=&untracked=` */
    suspend fun vcsDiff(mode: String = "working", base: String? = null, context: Int? = null): String {
        val q = buildList {
            add("mode=" + enc(mode))
            if (base != null) add("base=" + enc(base))
            if (context != null) add("context=$context")
        }.joinToString("&")
        return get("/api/vcs/diff?$q")
    }

    /** `GET /api/vcs/branch?search=&limit=` */
    suspend fun vcsBranches(
        search: String? = null,
        limit: Int = 40,
        directory: String? = null,
    ): List<BranchInfo> {
        val q = buildList {
            add("limit=$limit")
            search?.takeIf { it.isNotBlank() }?.let { add("search=" + enc(it)) }
        }
        return decodeList(
            get("/api/vcs/branch?" + q.joinToString("&") + locAnd(directory)),
            BranchInfo.serializer(),
        )
    }

    /** `POST /api/vcs/init?location[directory]&provider=` -> 204. Their "Create Git repository". */
    suspend fun vcsInit(provider: String? = null, directory: String? = null) {
        val q = buildList {
            provider?.takeIf { it.isNotBlank() }?.let { add("provider=" + enc(it)) }
        }
        val sep = if (q.isEmpty()) "?" else "&"
        post(
            "/api/vcs/init" + (if (q.isEmpty()) "" else "?" + q.joinToString("&")) +
                (if (directory.isNullOrBlank()) "" else sep + "location%5Bdirectory%5D=" + enc(directory)),
            "",
        )
    }

    /** `?location[directory]=…`, or nothing when the caller relies on the server default. */
    private fun locOnly(directory: String?): String =
        if (directory.isNullOrBlank()) "" else "?location%5Bdirectory%5D=" + enc(directory)

    private fun locAnd(directory: String?): String =
        if (directory.isNullOrBlank()) "" else "&location%5Bdirectory%5D=" + enc(directory)

    // ---- project + worktrees ----

    /** GET /api/project?archived= -> the projects their sidebar groups sessions under. */
    suspend fun projects(archived: Boolean? = null): List<ProjectInfo> =
        decodeList(
            get("/api/project" + (archived?.let { "?archived=$it" } ?: "")),
            ProjectInfo.serializer(),
        )

    /** GET /api/worktree?projectID= */
    suspend fun worktrees(projectID: String? = null): String =
        get("/api/worktree" + (projectID?.let { "?projectID=" + enc(it) } ?: ""))

    // ---- location --------------------------------------------------------------------------

    /**
     * `GET /api/location?location[directory]=` -> `{directory, workspaceID, project:{...}}`.
     *
     * Their client calls it from exactly one place, `projects.add(directory)` in
     * `current-BpUjPsAD.js` @ 237 410:
     *
     * ```js
     * function E(e){
     *   return p.api.location.get({location:{directory:e}})
     *     .then(async e => (await h.project.refresh(), r.touch(e.project.id), b().find(t=>t.id===e.project.id)))
     *     .catch(...)
     * }
     * ```
     *
     * The response is cached under `C(e) = JSON.stringify([e.directory, e.workspaceID])`, and
     * `project.canonical` is then read back by `vu()` as the **second** link in the directory
     * fallback chain (`session.location.directory ?? location.info({directory})?.project.canonical
     * ?? projects.forDirectory(directory)?.worktree ?? directory`). So `canonical` is the git root
     * of the directory the server resolved - not the directory that was asked for.
     */
    suspend fun locationInfo(directory: String): LocationInfo? = runCatching {
        json.decodeFromString(LocationInfo.serializer(), get("/api/location" + locOnly(directory)))
    }.getOrNull()

    /** `POST /api/location/reload?location[directory]=` -> 204. Their connect handler calls it. */
    suspend fun locationReload(directory: String? = null) {
        post("/api/location/reload" + locOnly(directory), "")
    }

    // ---- session environment / wait / web search / references ----

    /** PUT /api/session/:id/environment {variables} -> 204. Their Env editor per session. */
    suspend fun setEnvironment(sessionId: String, variables: Map<String, String>) {
        put(
            "/api/session/$sessionId/environment",
            buildString {
                append("{\"variables\":{")
                variables.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) append(',')
                    append(json.encodeToString(k)).append(':').append(json.encodeToString(v))
                }
                append("}}")
            },
        )
    }

    /**
     * `POST /api/experimental/session/{id}/wait` - blocks until the session goes idle.
     *
     * The path is the **experimental** one: their rewind flow calls
     * `n.api.experimental.session.wait({sessionID})` (`route-CMUQLRzL.js` @ 512 669) between
     * `session.interrupt` and `session.revert.stage`. The non-experimental `/api/session/{id}/wait`
     * is not in the phone's 173-route contract at all, so this used to 404.
     */
    suspend fun waitSession(sessionId: String): String =
        post("/api/experimental/session/$sessionId/wait", "")

    /**
     * `POST /api/experimental/session/import` body `{info, messages, location}`.
     *
     * Their import surface (`index-BWsziAR3.js` @ 83 741) opens a picker for `application/json`,
     * parses the file, then:
     *
     * ```js
     * i.sdk.api.experimental.session.import({...n, location:{directory:e.worktree}})
     * ```
     *
     * `n` is the parsed document - the same `{info, messages}` shape the Context tab's
     * `Export session` writes, so an export from one machine imports on another.
     *
     * The document is parsed and re-encoded rather than spliced as text: a hand-edited export with
     * trailing whitespace or a BOM must not produce a malformed body, and `location` has to be
     * merged into the top-level object the way `{...n, location}` does.
     *
     * Returns the created session, or null when the server answered with something else.
     */
    suspend fun sessionImport(document: String, directory: String?): Session? {
        val raw = post("/api/experimental/session/import", SessionImport.body(document, directory))
        return runCatching {
            json.decodeFromString(SessionEnvelope.serializer(), raw).data
        }.getOrNull()
    }

    /**
     * `Import session` from a file, streamed.
     *
     * The String overload above needs the document twice - once as text, once as a JSON tree -
     * which caps an import at whatever the heap can hold. This one hands the picker's stream
     * straight to [SessionImport.streamBody], so a 162 MB export costs a 64 KB buffer.
     */
    suspend fun sessionImportStream(input: java.io.InputStream, directory: String?): Session? {
        val body = SessionImport.streamBody(input, directory)
        val raw = withContext(Dispatchers.IO) {
            httpLong.newCall(
                Request.Builder()
                    .url(baseUrl + "/api/experimental/session/import")
                    .post(body)
                    .build()
            ).execute().use { r ->
                val text = r.body?.string().orEmpty()
                check(r.isSuccessful) {
                    "POST /api/experimental/session/import -> ${r.code}: ${text.take(240)}"
                }
                text
            }
        }
        return runCatching {
            json.decodeFromString(SessionEnvelope.serializer(), raw).data
        }.getOrNull()
    }

    /**
     * `Import session` from a staged file.
     *
     * Proven 2026-11-22 13:45: `curl --data-binary @file` (Content-Length, no chunking) imported
     * the 339 409-byte tail with HTTP 200 and a fresh session id, while the app's chunked
     * [sessionImportStream] failed. So the app stages the transformed body to a cache file and
     * posts it with a real length. Memory stays flat either way; only the wire shape changes.
     */
    suspend fun sessionImportFile(file: java.io.File): Session? {
        val raw = withContext(Dispatchers.IO) {
            httpLong.newCall(
                Request.Builder()
                    .url(baseUrl + "/api/experimental/session/import")
                    .post(file.asRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            ).execute().use { r ->
                val text = r.body?.string().orEmpty()
                check(r.isSuccessful) {
                    "POST /api/experimental/session/import -> ${r.code}: ${text.take(240)}"
                }
                text
            }
        }
        return runCatching {
            json.decodeFromString(SessionEnvelope.serializer(), raw).data
        }.getOrNull()
    }

    /** POST /api/websearch {query, providerID?} — their web search tool from the composer. */
    suspend fun webSearch(query: String, providerID: String? = null): String =
        post("/api/websearch", buildString {
            append("{\"query\":").append(json.encodeToString(query))
            if (providerID != null) append(",\"providerID\":").append(json.encodeToString(providerID))
            append('}')
        })

    /** GET /api/websearch/provider — which search providers this build has. */
    suspend fun webSearchProviders(): String = get("/api/websearch/provider")

    /** GET /api/reference?location= — reference documents the composer can @mention. */
    suspend fun references(): String = get("/api/reference")

    /** GET /api/info — server version / runtime, shown in the Extensions rail. */
    suspend fun serverInfo(): String = get("/api/info")

    /** GET /api/vcs — repository identity (root, branch, remote) behind the Review panel. */
    suspend fun vcsInfo(): String = get("/api/vcs")

    /** POST /api/experimental/fs/write?path= — their file editor writes through here. */
    suspend fun writeFile(path: String, text: String) {
        post(
            "/api/experimental/fs/write?path=" + enc(path),
            json.encodeToString(text),
        )
    }

    // ---- terminal (persistent pty) ----

    /** GET /api/pty?location= — the shells their terminal tab lists. */
    suspend fun ptyList(): String = get("/api/pty")

    /** POST /api/pty {command,args,cwd,title,env} — open a terminal. */
    suspend fun ptyCreate(command: String = "/bin/bash", cwd: String? = null, title: String? = null): String =
        post("/api/pty", buildString {
            append("{\"command\":").append(json.encodeToString(command))
            if (cwd != null) append(",\"cwd\":").append(json.encodeToString(cwd))
            if (title != null) append(",\"title\":").append(json.encodeToString(title))
            append('}')
        })

    /** PUT /api/pty/:id {title,size} — resize keeps the far side's TUI honest. */
    suspend fun ptyResize(id: String, title: String? = null, size: Pair<Int, Int>? = null) {
        put("/api/pty/$id", buildString {
            append('{')
            var first = true
            if (title != null) { first = false; append("\"title\":").append(json.encodeToString(title)) }
            if (size != null) {
                if (!first) append(',')
                append("\"size\":{\"cols\":").append(size.first).append(",\"rows\":").append(size.second).append('}')
            }
            append('}')
        })
    }

    /** DELETE /api/pty/:id */
    suspend fun ptyDelete(id: String) {
        delete("/api/pty/$id")
    }

    // ---- persistent pty: the terminal their own panel actually opens ----
    //
    // `/api/pty` above is the old route. Their panel uses `experimental.persistent-pty`
    // (`promise-D9LzW5Qq.js` @ 18 068) and connects over a WebSocket (`solid-B85sHMHe.js`,
    // `function I`). Everything in this block is that route family, verbatim.

    /** `GET /api/experimental/session/{id}/terminal` - the session's live terminals. */
    suspend fun ptyListForSession(sessionId: String): String =
        get("/api/experimental/session/$sessionId/terminal")

    /**
     * `POST /api/experimental/session/{id}/terminal` - open a terminal in the session's directory.
     *
     * Their call (`index-BWsziAR3.js` @ 603 601) sends `args: []`, `env: {}`, the session's
     * directory as `cwd`, and a numbered title. `command` is left out so the server picks the
     * shell.
     */
    suspend fun ptyCreateForSession(
        sessionId: String,
        cwd: String?,
        title: String,
        size: Pair<Int, Int>? = null,
    ): String = post("/api/experimental/session/$sessionId/terminal", buildString {
        append("{\"args\":[],\"env\":{}")
        append(",\"title\":").append(json.encodeToString(title))
        if (cwd != null) append(",\"cwd\":").append(json.encodeToString(cwd))
        if (size != null) {
            append(",\"size\":{\"cols\":").append(size.first).append(",\"rows\":").append(size.second).append('}')
        }
        append('}')
    })

    /** `GET /api/experimental/persistent-pty/{id}/snapshot` - size, output tail, checkpoint. */
    suspend fun ptySnapshot(ptyId: String): String =
        get("/api/experimental/persistent-pty/$ptyId/snapshot")

    /**
     * `POST /api/experimental/persistent-pty/{id}/connect-token`.
     *
     * The header is not decoration: their client sends `x-opencode-ticket: 1` and the route
     * declares 403 among its answers (`promise-D9LzW5Qq.js` @ 18 068), so a call without it is
     * the one case their own code never makes.
     */
    suspend fun ptyConnectToken(ptyId: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(baseUrl + "/api/experimental/persistent-pty/$ptyId/connect-token")
            .header("x-opencode-ticket", "1")
            .post("{}".toRequestBody(ctype))
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            check(r.isSuccessful) { "POST connect-token -> ${r.code}: ${text.take(240)}" }
            text
        }
    }

    /** `PUT /api/experimental/persistent-pty/{id}` - `{attachmentID, size, title}`. */
    suspend fun ptyUpdate(ptyId: String, attachmentID: String?, size: Pair<Int, Int>?, title: String?) {
        put("/api/experimental/persistent-pty/$ptyId", buildString {
            append('{')
            var first = true
            if (attachmentID != null) {
                first = false; append("\"attachmentID\":").append(json.encodeToString(attachmentID))
            }
            if (size != null) {
                if (!first) append(',')
                first = false
                append("\"size\":{\"cols\":").append(size.first).append(",\"rows\":").append(size.second).append('}')
            }
            if (title != null) {
                if (!first) append(',')
                append("\"title\":").append(json.encodeToString(title))
            }
            append('}')
        })
    }

    /** `DELETE /api/experimental/persistent-pty/{id}` */
    suspend fun ptyRemove(ptyId: String) {
        delete("/api/experimental/persistent-pty/$ptyId")
    }

    /** `GET .../terminal/read?lines=` - the last N lines as text. */
    suspend fun ptyReadSession(sessionId: String, lines: Int): String =
        get("/api/experimental/session/$sessionId/terminal/read?lines=$lines")

    /** The origin the WebSocket URL is built from - their `t.url`. */
    internal val origin: String get() = baseUrl

    /** A client that may keep a socket open; the shared one has a 30 s read budget. */
    internal fun socketClient(): OkHttpClient =
        http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    /** `x-opencode-ticket` header support, which [post] does not carry. */
    internal suspend fun postWithHeaders(path: String, body: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(baseUrl + path).post(body.toRequestBody(ctype))
            headers.forEach { (k, v) -> b.header(k, v) }
            http.newCall(b.build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                check(r.isSuccessful) { "POST $path -> ${r.code}: ${text.take(240)}" }
                text
            }
        }

    /**
     * `/api/event` with liveness callbacks. See [SseStream]: heartbeats are the only signal
     * that a socket the tunnel swapped under us is still real.
     */
    fun subscribeWithBeats(
        onEvent: (String) -> Unit,
        onBeat: () -> Unit,
        onDown: (String) -> Unit,
    ): SseStream.Handle = events.open("/api/event", onEvent, onBeat, onDown)

    // ---- SSE ----
    /**
     * Raw-bytes reader for `/api/event`.
     *
     * The chat view uses this rather than [subscribe]: heartbeats are its liveness proof,
     * which is the only way to notice a half-open socket left behind by a TUN rebuild.
     * [subscribe] stays for the guard service, which only needs the frames.
     */
    val events: SseStream by lazy { SseStream(baseUrl, httpSse) }

    /**
     * `/api/event`, with a verdict when the stream dies.
     *
     * OkHttp's [EventSource] does **not** retry: `onFailure`/`onClosed` are the end of it.
     * Handing the caller the reason is what lets it resubscribe — without that, a stream cut
     * by a tunnel rebuild stays dead and the transcript freezes while the server keeps
     * answering. Desktop's own event stream reconnects; this is the same behaviour.
     */
    fun subscribe(
        onEvent: (String) -> Unit,
        onDown: (String) -> Unit = {},
    ): EventSource {
        // real stream path (verified live: /event serves the SPA fallback; /api/event
        // answers text/event-stream with {"type":"server.connected"})
        // Identity encoding: see [SseStream.open] - a gzipped event stream is buffered in the
        // decoder and the heartbeat stops being a liveness signal.
        val req = Request.Builder()
            .url(baseUrl + "/api/event")
            .header("Accept-Encoding", "identity")
            .build()
        return EventSources.createFactory(httpSse).newEventSource(req, object : EventSourceListener() {
            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                onEvent(data)
            }

            override fun onClosed(es: EventSource) {
                onDown("closed by server")
            }

            override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                onDown(
                    "${t?.javaClass?.simpleName ?: "EventSource"}: " +
                        "${t?.message ?: "no message"}" +
                        (response?.let { " (http ${it.code})" } ?: ""),
                )
            }
        })
    }
}

// ---- wire types (trimmed to what the UI needs; unknown keys ignored) ----

@Serializable
data class Session(
    val id: String,
    val title: String? = null,
    @SerialName("parentID") val parentId: String? = null,
    // their `Session.Info.projectID` - the key `permissions.autoApproveProjects` is scoped by
    @SerialName("projectID") val projectID: String? = null,
    val directory: String? = null,
    val updated: String? = null,
    val cost: Double? = null,
    val tokens: SessionTokens? = null,
    val model: SessionModel? = null,
    // live shape nests these: location.directory, time.updated (epoch ms)
    val location: SessionLocation? = null,
    val time: SessionTime? = null,
    // members of `Session.Info` their client also reads
    val outcome: String? = null,
    val revert: kotlinx.serialization.json.JsonElement? = null,
    val fork: kotlinx.serialization.json.JsonElement? = null,
    val permissions: kotlinx.serialization.json.JsonElement? = null,
    val metadata: kotlinx.serialization.json.JsonObject? = null,
) {
    val dir: String?
        get() = directory ?: location?.directory

    val project: String
        get() = dir.orEmpty().replace('\\', '/').trimEnd('/').substringAfterLast('/')
            .ifBlank { "Default Project" }

    val updatedAt: Long?
        get() = time?.updated ?: time?.created

    /** Their sidebar's unread dot: the server bumps `time.updated` on every message and only
     *  `POST /view` moves `time.viewed`. A session the app is showing marks itself viewed, so
     *  the dot only ever means "something happened while he was somewhere else". */
    val unread: Boolean
        get() = (updatedAt ?: 0L) > (time?.viewed ?: 0L)
}

@Serializable
data class SessionLocation(val directory: String? = null)

@Serializable
data class SessionTime(
    val created: Long? = null,
    val updated: Long? = null,
    val busy: Long? = null,
    val idle: Long? = null,
    // their `time.viewed` / `time.archived`: both are epoch ms, absent while unset
    val viewed: Long? = null,
    val archived: Long? = null,
) {
    /** true while the server is still working this session (verified live field pair) */
    val isBusy: Boolean get() = (busy ?: 0L) > (idle ?: 0L)
}

@Serializable
data class SessionTokens(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cache: SessionCache? = null,
) {
    val total: Long get() = input + output + reasoning
}

@Serializable
data class SessionCache(val read: Long = 0, val write: Long = 0)

@Serializable
data class SessionModel(
    val id: String? = null,
    val providerID: String? = null,
    val variant: String? = null,
)

@Serializable
data class MessageWithParts(
    val info: MessageInfo,
    val parts: List<Part> = emptyList(),
)

/** Wire truth (verified live 2026-11-18): the message list is {data:[msg...]} where
 *  user msgs are flat {id, type:"user", text:"..."} and assistant msgs are
 *  {id, type:"assistant", content:[parts], tokens, cost}; "idle" status rows also appear. */
@Serializable
data class WireMessage(
    val id: String,
    val type: String,
    val text: String? = null,
    // verified live: files the user attached come back as
    // {"data":"<base64>","mime":"image/png","source":{"type":"inline"},"name":"x.png"}
    val files: List<WireFile> = emptyList(),
    val content: List<Part> = emptyList(),
    val cost: Double? = null,
    val tokens: TokenUse? = null,
    val model: MessageModelRef? = null,
    val agent: String? = null,
    val time: MsgTime? = null,
    // verified live on 1.19.6: a refused turn is {finish:"error", content:[],
    // error:{type,message}} — with content empty, the reason was the only thing worth showing
    val finish: String? = null,
    val error: WireError? = null,
    // the other members of their `SessionMessage.Info` union
    val description: String? = null,
    val skill: String? = null,
    val name: String? = null,
    val status: String? = null,
    val outcome: String? = null,
    val reason: String? = null,
    val summary: String? = null,
    val command: String? = null,
    val shellID: String? = null,
    val exit: Int? = null,
    val retry: WireRetry? = null,
    val snapshot: WireSnapshot? = null,
)

/** `assistant.retry {attempt, at, error}` — their retry countdown row. */
@Serializable
data class WireRetry(
    val attempt: Int = 0,
    val at: Long = 0,
    val error: WireError? = null,
)

/** `assistant.snapshot {start, end, files[]}` — changed files for that message. */
@Serializable
data class WireSnapshot(
    val start: String? = null,
    val end: String? = null,
    val files: List<String> = emptyList(),
)

@Serializable
data class WireError(val type: String? = null, val message: String? = null)

// ---- usage limits (`UsageLimits.Info` in the server's own schema) ----

@Serializable
data class UsageMeter(
    val percent: Double = 0.0,
    val limited: Boolean = false,
    val resetsAt: Long = 0,
)

@Serializable
data class UsageGo(
    val rolling: UsageMeter? = null,
    val weekly: UsageMeter? = null,
    val monthly: UsageMeter? = null,
)

@Serializable
data class UsageWorkspace(
    val id: String = "",
    val name: String? = null,
    val usageURL: String = "",
)

@Serializable
data class UsageBudget(
    val id: String = "",
    val limit: Double = 0.0,
    val spent: Double = 0.0,
    val scope: String = "",
    val per: String = "",
    val coverage: String = "",
    val picked: Boolean = false,
    val shared: Boolean = false,
    val resetsAt: Long = 0,
)

@Serializable
data class UsageInfo(
    val workspace: UsageWorkspace? = null,
    val go: UsageGo? = null,
    val budgets: List<UsageBudget> = emptyList(),
)

@Serializable
data class UsageEnvelope(
    val location: kotlinx.serialization.json.JsonElement? = null,
    val data: UsageInfo? = null,
)

@Serializable
data class InboxPayload(
    val text: String = "",
    val files: List<WireFile> = emptyList(),
)

@Serializable
data class InboxItem(
    val id: String,
    val type: String = "user",
    val delivery: String? = null,
    val payload: InboxPayload? = null,
)

@Serializable
data class WireFile(
    val data: String? = null,
    val mime: String? = null,
    val url: String? = null,
    val name: String? = null,
)

/** An attachment held in memory until send: bytes as base64 plus what the UI shows. */
data class OutAttachment(
    val name: String,
    val mime: String,
    val base64: String,
    // `Prompt.Mention` — where the @path sits in the text, so their server can highlight
    // it and the model knows the bytes and the sentence belong together
    val mentionStart: Int? = null,
    val mentionEnd: Int? = null,
    val mentionText: String? = null,
) {
    val isImage: Boolean get() = mime.startsWith("image/")
    val dataUrl: String get() = "data:$mime;base64,$base64"
}

/** One `/<skill>` attachment: the id the server keys on and the name the text mentions. */
data class SkillPick(val id: String, val name: String)

@Serializable
data class MessageModelRef(
    val id: String? = null,
    val providerID: String? = null,
    val variant: String? = null,
)

/** Message timing, verified live: {created, firstToken, streamed}. */
@Serializable
data class MsgTime(
    val created: Long? = null,
    @SerialName("firstToken") val firstToken: Long? = null,
    val streamed: Long? = null,
    val completed: Long? = null,
) {
    /** Their footer reads `2m 3s`: last chunk minus the request start. */
    val durationMs: Long?
        get() {
            val end = completed ?: streamed ?: return null
            val start = created ?: return null
            return (end - start).coerceAtLeast(0)
        }

    /** Their `198.1 tok/s` is measured over the streaming window, not the whole call. */
    val streamMs: Long?
        get() {
            val start = firstToken ?: return null
            val end = completed ?: streamed ?: return null
            return (end - start).coerceAtLeast(1)
        }
}

@Serializable
data class MessageInfo(
    val id: String,
    val role: String,
    @SerialName("sessionID") val sessionId: String? = null,
    val tokens: TokenUse? = null,
    val cost: Double? = null,
    val modelID: String? = null,
    val providerID: String? = null,
    val variant: String? = null,
    val agent: String? = null,
    // their message kinds: user | assistant | compaction | agent-switched | model-switched …
    val type: String? = null,
    // `compaction` messages carry `status: "completed" | ...`; `lastAssistantWithUsage` skips
    // everything at or before the last completed one.
    val status: String? = null,
    val time: MsgTime? = null,
)

@Serializable
data class TokenUse(
    val input: Long = 0,
    val output: Long = 0,
    @SerialName("reasoning") val reasoning: Long = 0,
    val cache: TokenCacheUse? = null,
) {
    val total: Long get() = input + output + reasoning
    /** Desktop's header figure: `input + output + reasoning + cache.read + cache.write` of
        the LAST assistant message (`main-CLeW75FU.js` ~2.365M). `cache.write` was missing
        here, so the phone read slightly under the PC. Verified live: in 511 +
        cache.read 116736 + out 494 + reas 91 -> `118k`. */
    val context: Long
        get() = input + output + reasoning + (cache?.read ?: 0) + (cache?.write ?: 0)
}

@Serializable
data class TokenCacheUse(val read: Long = 0, val write: Long = 0)

@Serializable
data class Part(
    val id: String? = null,
    val type: String,
    val text: String? = null,
    // tool parts: {"type":"tool","name":"shell","state":{...},"time":{...}}
    val name: String? = null,
    val state: PartState? = null,
    val time: PartTime? = null,
    // image/file parts carry a data: URL at the top level
    val url: String? = null,
    val mime: String? = null,
    // non-assistant rows carry their own discriminator (idle outcome, shell status, …)
    val status: String? = null,
)

@Serializable
data class PartTime(
    val created: Long? = null,
    val ran: Long? = null,
    val completed: Long? = null,
    @SerialName("firstToken") val firstToken: Long? = null,
) {
    val durationMs: Long?
        get() {
            val end = completed ?: ran ?: return null
            val start = created ?: return null
            return (end - start).coerceAtLeast(0)
        }
}

@Serializable
data class PartContent(
    val type: String? = null,
    val text: String? = null,
    val uri: String? = null,
    val mime: String? = null,
)

/** edit/write state.metadata: files:[{file,patch,status,additions,deletions}] */
@Serializable
data class PartMeta(
    val files: List<PartFile>? = null,
    // shell parts: exit code, and the two ways a run can end without one
    val exit: Int? = null,
    // `route-BPCnB8DM.js`: their shell row reads
    // `command: e.input.command ?? e.metadata.command ?? ""` - the command is on the part's
    // metadata whenever the input object did not survive the wire. Reading only
    // `input.command` is why our `Shell` rows printed the label with nothing beside it.
    val command: String? = null,
    val timeout: Boolean? = null,
    val interrupted: Boolean? = null,
    // grep -> matches, glob -> count (their `Cp(qn(e)).matches` / `.count`)
    val matches: Int? = null,
    val count: Int? = null,
    // todowrite -> the list it just wrote
    val todos: List<TodoItem>? = null,
    /**
     * `current-tool-state.ts:45` — while a tool is still `running`, the websearch tool streams its
     * results into `metadata.output` instead of `state.content`. Kept as a JsonElement because
     * their `Predicate.isString(output)` refuses anything that is not a string.
     */
    val output: kotlinx.serialization.json.JsonElement? = null,
)

/** `todowrite` item: {content, status, priority}. Their `todos` component reads exactly this. */
@Serializable
data class TodoItem(
    val content: String = "",
    val status: String = "pending",
    val priority: String? = null,
) {
    val done: Boolean get() = status == "completed" || status == "cancelled"
    val active: Boolean get() = status == "in_progress" || status == "running"
}

@Serializable
data class PartFile(
    val file: String? = null,
    val patch: String? = null,
    val status: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    // `schema/src/file-diff.ts:15` - the entry a folder listing adds for itself when it stopped
    // before showing or checking every entry. `expandReviewFolders` leaves these rows out
    // (`gui-plugins/src/review/kinds.ts:62-68`).
    val more: Boolean? = null,
)

/** Lenient reader for a tool `input` that arrived as a JSON *string* rather than an object. */
private val PART_INPUT = Json { ignoreUnknownKeys = true; isLenient = true }

@Serializable
data class PartState(
    val status: String? = null,
    val title: String? = null,
    // verified live shape: shell -> input.command; write -> input.filePath;
    // completed tools also carry content:[{type:"text",text:"<output>"}]
    // input must stay JsonElement: some tools send a plain string, not an object.
    val input: kotlinx.serialization.json.JsonElement? = null,
    val content: List<PartContent>? = null,
    val metadata: PartMeta? = null,
    /** `current-tool-state.ts:69` — `state.error.message`, only read when the status is `error`. */
    val error: WireError? = null,
) {
    /**
     * The tool input as an object, whatever shape it arrived in.
     *
     * `state.input` is normally an object, but the replay path also delivers it as a JSON
     * *string*. Every reader below used to test `input as? JsonObject`, which is null for
     * that shape - and that single cast is why a `Used 2 Write` run drew an empty body and
     * why every `Shell` row printed its label with no command beside it (the owner, 24 Nov).
     * Their desktop never meets this because its SDK parses `input` before the timeline
     * reads it; here it is parsed on first access.
     */
    val inputObject: kotlinx.serialization.json.JsonObject?
        get() {
            when (val i = input) {
                is kotlinx.serialization.json.JsonObject -> return i
                is kotlinx.serialization.json.JsonPrimitive -> {
                    val raw = i.content.trim()
                    if (!raw.startsWith("{")) return null
                    return runCatching {
                        PART_INPUT.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
                    }.getOrNull()
                }
                else -> return null
            }
        }

    fun inputString(key: String): String? =
        when (val v = inputObject?.get(key)) {
            is kotlinx.serialization.json.JsonPrimitive -> v.content
            null -> null
            else -> v.toString()
        }

    /** PC's `us(e)` in the tool trigger: every input entry as `key=value`, in object order. */
    fun inputEntries(): List<Pair<String, String>> =
        inputObject?.entries.orEmpty().map { (k, v) ->
            k to when (v) {
                is kotlinx.serialization.json.JsonPrimitive -> v.content
                else -> v.toString()
            }
        }

    /** `todowrite`: the list lives in `input.todos`; some builds echo it back in metadata. */
    fun todos(): List<TodoItem> {
        metadata?.todos?.let { if (it.isNotEmpty()) return it }
        val arr = inputObject?.get("todos") as? kotlinx.serialization.json.JsonArray
            ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun str(k: String) =
                (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
            TodoItem(
                content = str("content") ?: str("text") ?: return@mapNotNull null,
                status = str("status") ?: "pending",
                priority = str("priority"),
            )
        }
    }
}

// ---- extensions: shapes copied from the live server ----

@Serializable
data class McpEntry(
    val name: String = "",
    val status: McpStatus? = null,
    /** Present when the server's auth is an integration (OAuth) rather than a key. */
    val integrationID: String? = null,
) {
    val connected: Boolean get() = status?.status == "connected"
    val stateLabel: String get() = status?.status ?: "unknown"
    val needsAuth: Boolean get() = status?.status == "needs_auth"
}

@Serializable
data class McpStatus(val status: String? = null, val error: String? = null)

/**
 * `Integration.Info` - one row of `GET /api/integration`.
 *
 * Their Settings -> Extensions reads `methods` to find an `oauth` method with no form when an MCP
 * server reports `needs_auth`, then hands that method's id to `integration.oauth.connect`.
 */
@Serializable
data class IntegrationEntry(
    val id: String = "",
    val name: String = "",
    val methods: List<IntegrationMethod> = emptyList(),
    val connections: List<IntegrationConnection> = emptyList(),
) {
    /** `methods.find(e => e.type === 'oauth' && !e.form?.length)` */
    val oauthMethod: IntegrationMethod?
        get() = methods.firstOrNull { it.type == "oauth" && it.form.isNullOrEmpty() }
}

@Serializable
data class IntegrationMethod(
    val id: String = "",
    val type: String = "",
    /** A method with a form needs the user to fill it in; an empty/null form can run as-is. */
    val form: List<kotlinx.serialization.json.JsonObject>? = null,
)

@Serializable
data class IntegrationConnection(
    val id: String = "",
    val type: String = "",
)

@Serializable
data class IntegrationEnvelope(val data: IntegrationEntry? = null)

/**
 * One device-code sign-in attempt, exactly as the server states it.
 *
 * `instructions` is the server's own sentence (`Enter code: QHJM-BZVG`); `url` is the page the
 * browser must open; `time.expires` is a real deadline, and their copy for crossing it is
 * `provider.connect.console.expired`.
 */
@Serializable
data class ConnectStart(
    val attemptID: String = "",
    val url: String = "",
    val instructions: String = "",
    val mode: String = "",
    val time: AttemptTime? = null,
) {
    /** The code from `Enter code: XXXX` - shown on its own under `Device code`. */
    val code: String
        get() = instructions.substringAfter("code:", "").trim().ifBlank { "" }
}

@Serializable
data class ConnectStatus(
    val status: String = "",
    val time: AttemptTime? = null,
)

@Serializable
data class AttemptTime(val created: Long = 0, val expires: Long = 0)

@Serializable
data class ConnectEnvelope(val data: ConnectStart? = null)

@Serializable
data class ConnectStatusEnvelope(val data: ConnectStatus? = null)

/**
 * `GET /api/mcp/resource` - one resource an MCP server exposes.
 *
 * Their `Resource` carries the server it came from, a uri and a display name; `mimeType` is what
 * decides whether the resource opens as text or as a blob.
 */
@Serializable
data class McpResource(
    val server: String = "",
    val uri: String = "",
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val mimeType: String? = null,
)

@Serializable
data class McpResourcePayload(
    val resources: List<McpResource> = emptyList(),
    val location: LocationInfo? = null,
)

@Serializable
data class McpResourceEnvelope(val data: McpResourcePayload? = null)

@Serializable
data class SkillEntry(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val path: String? = null,
    /** the full SKILL.md body, served inline by GET /api/skill (verified: 191736 B total) */
    val content: String? = null,
)

@Serializable
data class PluginEntry(
    val id: String = "",
    val source: PluginSource? = null,
    val state: PluginState? = null,
) {
    val sourceType: String get() = source?.type ?: "plugin"
    val active: Boolean get() = state?.status == "active"
}

@Serializable
data class PluginSource(
    val type: String? = null,
    /** The package spec a `package` source resolves to - the key of `PluginLifecycle.findings`. */
    val target: String? = null,
    /** `true` when the registry has a newer version. Only meaningful on a `package` source. */
    val outdated: Boolean? = null,
)

@Serializable
data class PluginState(val status: String? = null)

/** A background shell started by the agent (or by the phone). Field set = Shell.Info. */
@Serializable
data class ShellEntry(
    val id: String = "",
    val status: String? = null,
    val command: String? = null,
    val cwd: String? = null,
    val shell: String? = null,
    val file: String? = null,
    val pid: Long? = null,
    val exit: Double? = null,
    val signal: String? = null,
    val time: ShellTime? = null,
) {
    val running: Boolean get() = status == "running"
    val exitCode: Int? get() = exit?.toInt()
}

@Serializable
data class ShellTime(val started: Long? = null, val completed: Long? = null)

@Serializable
data class ShellOutput(
    val output: String? = null,
    val cursor: Long? = null,
    val size: Long? = null,
    val truncated: Boolean = false,
)

@Serializable
data class ShellOutEnvelope(val data: ShellOutput? = null)

@Serializable
data class ShellEnvelope(val data: ShellEntry? = null)

@Serializable
data class ModelEnvelope(val data: ModelInfo? = null)

/** `POST /api/experimental/session/import` answers the created session. */
@Serializable
data class SessionEnvelope(val data: Session? = null)

@Serializable
data class PermissionEnvelope(val data: PermissionRequest? = null)

/** `GET /api/command` — one entry per slash command the server knows. */
@Serializable
data class CommandEntry(
    val name: String = "",
    val description: String = "",
)

/**
 * A permission request. Exactly their `Permission.Request`:
 *   {id, sessionID, action, resources[], save[]?, metadata?, source?, message?}
 * `reply` takes a decision of `once` | `always` | `reject`.
 */
@Serializable
data class PermissionRequest(
    val id: String = "",
    val sessionID: String = "",
    val action: String = "",
    val resources: List<String> = emptyList(),
    val save: List<String> = emptyList(),
    val message: String? = null,
    val metadata: kotlinx.serialization.json.JsonObject? = null,
)

/** `GET /api/fs/list` — one directory entry. `type` is `file` or `directory`. */
@Serializable
data class FsEntry(
    val path: String = "",
    val type: String = "file",
) {
    val isDir: Boolean get() = type == "directory"
}

/** One page of `GET /api/session`, plus the `cursor` block the server sends back. */
data class SessionPage(
    val sessions: List<Session>,
    val next: String? = null,
    val previous: String? = null,
)

/** One page of `GET /api/session/:id/message`; `previous` is older history. */
data class MessagePage(
    val messages: List<MessageWithParts>,
    val next: String? = null,
    val previous: String? = null,
)

/** `GET /api/permission/saved` — a rule `Always` wrote, editable and removable. */
@Serializable
data class SavedPermission(
    val id: String = "",
    @SerialName("projectID") val projectID: String = "",
    val action: String = "",
    val resource: String = "",
    val time: SavedTime? = null,
)

@Serializable
data class SavedTime(val created: Long = 0, val updated: Long = 0)

/**
 * `Form.Info` — their server-driven question card. `fields` is a tagged union on `type`
 * (string | number | integer | boolean | multiselect | external); all keys live on one
 * class so an unknown future field type still deserialises instead of failing the page.
 */
@Serializable
data class FormInfo(
    val id: String = "",
    @SerialName("sessionID") val sessionID: String = "",
    val title: String = "",
    val metadata: kotlinx.serialization.json.JsonObject? = null,
    val fields: List<FormField> = emptyList(),
)

@Serializable
data class FormField(
    val key: String = "",
    val type: String = "string",
    val title: String? = null,
    val description: String? = null,
    val required: Boolean = false,
    val hidden: Boolean = false,
    @SerialName("when") val when_: List<FormWhen> = emptyList(),
    val placeholder: String? = null,
    val format: String? = null,
    val default: kotlinx.serialization.json.JsonElement? = null,
    val options: List<FormOption> = emptyList(),
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val minItems: Int? = null,
    val maxItems: Int? = null,
    val custom: Boolean = false,
    val url: String? = null,
)

@Serializable
data class FormOption(
    val value: String = "",
    val label: String = "",
    val description: String? = null,
)

/** `Form.When` — a visibility gate: all conditions must hold (AND) for the field to show. */
@Serializable
data class FormWhen(
    val key: String = "",
    val op: String = "eq",
    val value: kotlinx.serialization.json.JsonElement? = null,
)

/**
 * `QuestionV2.Request` - the `question` tool's blocking request.
 *
 * Proven field-for-field from the running server binary at offset ~117 579 000:
 *
 * ```
 * QuestionV2.ID      = "que_" + id
 * QuestionV2.Option  = { label: "Display text (1-5 words, concise)",
 *                        description?: "Explanation of choice" }
 * QuestionV2.Info    = { question: "Complete question",
 *                        header: "Very short label (max 30 chars)",
 *                        options: Option[],
 *                        multiple?: "Allow selecting multiple choices",
 *                        custom?: "Allow typing a custom answer (default: true)" }
 * QuestionV2.Request = { id, sessionID, questions: Info[], tool?: {messageID, callID} }
 * QuestionV2.Answer  = string[]
 * QuestionV2.Reply   = { answers: Answer[] }
 * ```
 *
 * `custom` defaults to **true** on the server (`K.pipe(G)` - an optional with a
 * default), so "Type your own answer" is the normal case, not the exception.
 */
@Serializable
data class QuestionRequest(
    val id: String = "",
    @SerialName("sessionID") val sessionID: String = "",
    val questions: List<QuestionInfo> = emptyList(),
    val tool: QuestionTool? = null,
)

@Serializable
data class QuestionTool(
    @SerialName("messageID") val messageID: String = "",
    @SerialName("callID") val callID: String = "",
)

@Serializable
data class QuestionInfo(
    /** "Complete question" */
    val question: String = "",
    /** "Very short label (max 30 chars)" */
    val header: String = "",
    /** "Available choices" */
    val options: List<QuestionOption> = emptyList(),
    /** "Allow selecting multiple choices" */
    val multiple: Boolean = false,
    /** "Allow typing a custom answer (default: true)" */
    val custom: Boolean = true,
)

@Serializable
data class QuestionOption(
    /** "Display text (1-5 words, concise)" */
    val label: String = "",
    /** "Explanation of choice" */
    val description: String? = null,
)

/** `GET /api/vcs/branch` — one branch their branch picker lists. */
@Serializable
data class BranchInfo(
    val name: String = "",
    val current: Boolean = false,
)

/**
 * `Vcs.Info` from the live contract: `{provider?: string, branch:{current?, default?}}`.
 * `branch.default` is what makes the Branch review mode appear (`review/model.ts:377-391`).
 */
@Serializable
data class VcsBranch(
    val current: String? = null,
    val default: String? = null,
)

@Serializable
data class VcsInfo(
    val provider: String? = null,
    val branch: VcsBranch? = null,
)

/** `GET /api/project` — one project the sidebar groups sessions under. */
@Serializable
data class ProjectInfo(
    val id: String = "",
    val name: String? = null,
    val directory: String? = null,
    val icon: String? = null,
    val canonical: String? = null,
    /** `Project.Vcs`: the provider id (`git`) or null when the project has no repository. */
    val vcs: String? = null,
)

/**
 * `GET /api/location` - the server's answer for one directory.
 *
 * `directory` is the directory the server resolved (it may differ from the one asked for), and
 * `project.canonical` is the git root behind it. Their cache key is
 * `JSON.stringify([directory, workspaceID])`, so both fields are part of the identity.
 */
@Serializable
data class LocationInfo(
    val directory: String = "",
    val workspaceID: String? = null,
    val project: LocationProject? = null,
)

@Serializable
data class LocationProject(
    val id: String = "",
    val canonical: String? = null,
    val worktree: String? = null,
    val vcs: String? = null,
)


@Serializable
data class AgentEntry(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
)
