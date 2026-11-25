package com.zapp.net.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One live terminal, driven the way their panel drives it.
 *
 * Their flow, in order (`panel-BOR33-HA.js` `async function ce(e)`):
 *
 * 1. `GET .../persistent-pty/{id}/snapshot` -> `{info:{size, output:{tail}}, checkpoint}`
 * 2. `restore({size, checkpoint})` on the emulator
 * 3. `POST .../connect-token` (with `x-opencode-ticket: 1`) -> `{ticket}`
 * 4. `new WebSocket(<connect url with cursor = snapshot.info.output.tail>)`
 * 5. `open` -> `socket.opened`; `message` -> binary is **raw output**, text is an event;
 *    `error` -> `error`; `close` -> `close` (which re-enters this flow from step 1)
 *
 * So a reconnect does **not** replay from the beginning: it re-snapshots and resumes at the tail
 * the server already has. That is why the scrollback here is a bounded buffer and not the truth -
 * the server's snapshot is.
 */
class PtySession(
    private val client: OpenCodeClient,
    val ptyId: String,
    private val attachmentID: String,
    private val scope: CoroutineScope,
    initialCols: Int = 80,
    initialRows: Int = 24,
) {

    data class State(
        val status: Status = Status.Idle,
        val cols: Int = 80,
        val rows: Int = 24,
        val title: String = "",
        val exitCode: Int? = null,
        /** The last [OUTPUT_CAP] characters the server sent, in order. */
        val output: String = "",
        val controller: Boolean = false,
        val attempt: Int = 0,
        val error: String? = null,
    ) {
        enum class Status { Idle, Connecting, Live, Reconnecting, Exited, Closed, Failed }
    }

    private val _state = MutableStateFlow(State(cols = initialCols, rows = initialRows))
    val state: StateFlow<State> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private var socket: WebSocket? = null
    private var loop: Job? = null
    private val closed = AtomicBoolean(false)

    /** Their scrollback is xterm's, bounded by `scrollback`; ours is this many characters. */
    private companion object {
        const val OUTPUT_CAP = 400_000
    }

    fun start() {
        if (loop != null) return
        loop = scope.launch { run() }
    }

    /** Their `M.write` - keystrokes go out inside the 5-byte frame, never as a bare string. */
    fun write(bytes: ByteArray) {
        val s = _state.value
        socket?.send(okio.ByteString.of(*PtyProtocol.inputFrame(s.cols, s.rows, bytes)))
    }

    /** A resize is the same frame with no payload. */
    fun resize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        if (cols == _state.value.cols && rows == _state.value.rows) return
        _state.value = _state.value.copy(cols = cols, rows = rows)
        socket?.send(okio.ByteString.of(*PtyProtocol.inputFrame(cols, rows, null)))
    }

    /** `DELETE .../persistent-pty/{id}` then drop the socket - their tab close. */
    fun close(kill: Boolean = false) {
        closed.set(true)
        loop?.cancel()
        socket?.close(1000, null)
        socket = null
        _state.value = _state.value.copy(status = State.Status.Closed)
        if (kill) scope.launch { runCatching { client.ptyRemove(ptyId) } }
    }

    private suspend fun run() {
        var attempt = 0
        while (!closed.get() && scope.isActive) {
            // A process that exited is not coming back; their `exited` event is terminal.
            if (_state.value.status == State.Status.Exited) return
            _state.value = _state.value.copy(
                status = if (attempt == 0) State.Status.Connecting else State.Status.Reconnecting,
                attempt = attempt,
                error = null,
            )
            val live = runCatching { connectOnce() }
            if (closed.get()) return
            val failure = live.exceptionOrNull()
            if (failure != null) {
                _state.value = _state.value.copy(
                    status = State.Status.Failed,
                    error = failure.message?.take(200),
                )
            }
            // Their event-stream reconnect: first retry immediately, then 1 s, 2 s, 4 s, 8 s,
            // 15 s and stay at 15 s. `solid-B85sHMHe.js` `D` / the `_e(D, signal)` sleep.
            attempt++
            delay(backoff(attempt))
        }
    }

    private fun backoff(attempt: Int): Long = when (attempt) {
        0, 1 -> 0L
        else -> minOf(15_000L, 500L * (1L shl minOf(attempt - 2, 5)))
    }

    private suspend fun connectOnce() {
        val snap = client.ptySnapshot(ptyId)
        val root = runCatching { json.parseToJsonElement(snap).jsonObject }.getOrNull()
        val info = root?.get("info")?.let { runCatching { it.jsonObject }.getOrNull() }
        val size = info?.get("size")?.let { runCatching { it.jsonObject }.getOrNull() }
        val tail = info?.get("output")?.let { runCatching { it.jsonObject }.getOrNull() }
            ?.get("tail")?.toString()?.trim('"')?.toLongOrNull() ?: 0L

        val cols = size?.get("cols")?.toString()?.toIntOrNull() ?: _state.value.cols
        val rows = size?.get("rows")?.toString()?.toIntOrNull() ?: _state.value.rows
        _state.value = _state.value.copy(cols = cols, rows = rows)

        val tokenJson = client.ptyConnectToken(ptyId)
        val ticket = runCatching {
            json.parseToJsonElement(tokenJson).jsonObject["ticket"]?.toString()?.trim('"').orEmpty()
        }.getOrDefault("")

        val url = PtyProtocol.connectUrl(
            base = client.origin,
            ptyID = ptyId,
            ticket = ticket,
            cursor = tail,
            attachmentID = attachmentID,
            takeover = true,
        )

        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        val ws = client.socketClient().newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = _state.value.copy(status = State.Status.Live, attempt = 0)
                // The server may have resized the pty while we were away; push our size once so
                // the two agree (`P` frames carry it on every keystroke anyway).
                val s = _state.value
                webSocket.send(okio.ByteString.of(*PtyProtocol.inputFrame(s.cols, s.rows, null)))
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                // Server -> client binary is raw output, with no header. Their
                // `M.output(new Uint8Array(e.data))`.
                appendOutput(bytes.toByteArray().toString(Charsets.UTF_8))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val ev = PtyProtocol.decode(text)) {
                    is PtyProtocol.Event.Attached -> _state.value = _state.value.copy(
                        cols = ev.size?.cols ?: _state.value.cols,
                        rows = ev.size?.rows ?: _state.value.rows,
                        controller = ev.role == "controller",
                    )
                    is PtyProtocol.Event.Resized -> _state.value =
                        _state.value.copy(cols = ev.cols, rows = ev.rows)
                    is PtyProtocol.Event.TitleChanged ->
                        _state.value = _state.value.copy(title = ev.title)
                    is PtyProtocol.Event.Exited ->
                        _state.value = _state.value.copy(
                            status = State.Status.Exited,
                            exitCode = ev.exitCode,
                        )
                    is PtyProtocol.Event.ControllerChanged ->
                        _state.value = _state.value.copy(controller = ev.attachmentID == attachmentID)
                    else -> Unit
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed.get()) {
                    _state.value = _state.value.copy(
                        status = State.Status.Failed,
                        error = t.message?.take(200) ?: response?.code?.toString(),
                    )
                }
                done.complete(Unit)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(Unit)
            }
        })
        socket = ws
        done.await()
        socket = null
    }

    /**
     * Append raw bytes, then trim to [OUTPUT_CAP].
     *
     * The server sends whatever the process wrote, escape sequences included; the terminal view
     * is what interprets them. Nothing is stripped here, because stripping on this side would
     * lose a sequence split across two frames.
     */
    private fun appendOutput(chunk: String) {
        if (chunk.isEmpty()) return
        val next = _state.value.output + chunk
        _state.value = _state.value.copy(
            output = if (next.length > OUTPUT_CAP) next.substring(next.length - OUTPUT_CAP) else next,
        )
    }
}
