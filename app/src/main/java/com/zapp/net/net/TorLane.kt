package com.zapp.net.net

import android.content.Context
import android.util.Log
import com.zapp.net.termux.RunCommand
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Tor lane, ported from the reference `src/tor_manager.py`.
 *
 * Their three pieces map one-to-one:
 *   - `is_port_open` / `test_socks5_alive`        -> [probe]
 *   - `start_portable_tor`                        -> [ensure]
 *   - `signal_tor_newnym` / `rotate_tor_circuit`  -> [newnym] / [rotate]
 *
 * **Where the daemon is started from matters.** Measured on this phone:
 *   - `run-as com.termux` lives in Android's `runas_app` SELinux domain and has no
 *     network at all (`curl` -> 000, DNS times out), so it cannot host Tor.
 *   - the Termux app process can host it, but it is killed whenever Android reclaims it,
 *     and a broadcast to RUN_COMMAND cannot revive a dead app on Android 16.
 *   - the `opencode serve` process survives both, runs as the Termux uid, and exposes
 *     `POST /api/shell`. That is the only durable owner, so [ensure] uses it first.
 *
 * Status is read over the **control port**, never from the log file: the app cannot read
 * Termux's data directory, but `GETINFO status/bootstrap-phase` answers over a socket.
 */
object TorLane {

    const val SOCKS_PORT = 9050
    const val CONTROL_PORT = 9051

    data class State(
        val running: Boolean = false,
        val bootstrap: Int = 0,
        val summary: String = "not started",
        val exitIp: String = "",
        val lastError: String = "",
    ) {
        val ready: Boolean get() = running && bootstrap == 100
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /**
     * Last exit address we actually read. `api.ipify.org` occasionally times out, and a
     * failed *before* read is why the wall log sometimes printed `tor exit  -> 204.8.96.88`
     * (measured 2026-11-20 00:55 and 01:04:36). Falling back to this keeps the before/after
     * pair honest instead of blank.
     */
    @Volatile private var lastExit: String = ""

    private const val TORRC = "/data/data/com.termux/files/home/.tor/torrc"
    private const val TOR = "/data/data/com.termux/files/usr/bin/tor"

    /**
     * Make Tor available: answer from the control port when it is already up, otherwise
     * ask the server to own it. Their `start_portable_tor` has the same "already running"
     * short-circuit so a keeper tick never kills a working circuit.
     */
    fun ensure(ctx: Context, base: String = "http://127.0.0.1:4096", token: String = "opencode:zapp"): Boolean {
        if (refresh().running) return true

        // The server is the durable owner. `exec` keeps the shell session alive for as
        // long as the daemon runs, which is what stops the daemon being reaped with it.
        val cmd = "$TOR -f $TORRC"
        if (shellExec(base, token, "exec $cmd", 3_600_000)) {
            Log.i("ZAPP", "tor: started via server shell")
        } else {
            // Fallback for a live Termux app: RUN_COMMAND survives a restart of the app.
            val rc = RunCommand.run(
                ctx,
                "pgrep -f bin/tor >/dev/null || ( nohup $cmd </dev/null >/dev/null 2>&1 & )",
            )
            Log.i("ZAPP", "tor: run-command fallback -> $rc")
        }
        Thread.sleep(1_500)
        return refresh().running
    }

    /** Stop the daemon, both owners included. */
    fun stop(ctx: Context, base: String = "http://127.0.0.1:4096", token: String = "opencode:zapp") {
        shellExec(base, token, "pkill -f bin/tor", 8_000)
        RunCommand.run(ctx, "pkill -f bin/tor")
        _state.value = State(summary = "stopped")
    }

    /**
     * `POST /api/shell` on the phone's own server.
     *
     * Kept as a raw call on purpose: this is the transport itself, so routing it through
     * the chat client would make the lane depend on the thing it is meant to fix.
     */
    private fun shellExec(base: String, token: String, command: String, timeoutMs: Int): Boolean {
        return try {
            val conn = (URL("$base/api/shell").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty(
                    "Authorization",
                    "Basic " + android.util.Base64.encodeToString(
                        token.toByteArray(), android.util.Base64.NO_WRAP,
                    ),
                )
            }
            val body = org.json.JSONObject()
                .put("command", command)
                .put("timeout", timeoutMs)
                .toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            _state.value = _state.value.copy(lastError = e.message.orEmpty())
            false
        }
    }

    /**
     * One control-port round trip.
     *
     * Tor answers with `250-...` continuation lines and a final `250 ...`; reading until
     * that last line is what keeps the socket usable for the next command.
     */
    private fun control(command: String, timeoutMs: Int = 2_500): List<String> {
        val s = Socket()
        return try {
            s.connect(InetSocketAddress("127.0.0.1", CONTROL_PORT), timeoutMs)
            s.soTimeout = timeoutMs
            val out = OutputStreamWriter(s.getOutputStream(), Charsets.US_ASCII)
            val inp = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
            out.write("AUTHENTICATE \"\"\r\n")
            out.flush()
            readReply(inp)
            out.write(command + "\r\n")
            out.flush()
            readReply(inp)
        } catch (e: Exception) {
            emptyList()
        } finally {
            runCatching { s.close() }
        }
    }

    private fun readReply(inp: BufferedReader): List<String> {
        val lines = ArrayList<String>(4)
        while (true) {
            val line = inp.readLine() ?: break
            lines.add(line)
            if (line.length >= 4 && line[3] == ' ') break   // "250 ..." / "5xx ..." ends
            if (lines.size > 64) break
        }
        return lines
    }

    /** `GETINFO status/bootstrap-phase` -> `PROGRESS=100`. */
    fun refresh(): State {
        val reply = control("GETINFO status/bootstrap-phase")
        if (reply.isEmpty()) {
            _state.value = _state.value.copy(running = false, bootstrap = 0, summary = "control port closed")
            return _state.value
        }
        val line = reply.firstOrNull { it.contains("bootstrap-phase=") }.orEmpty()
        val progress = Regex("PROGRESS=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val summary = Regex("SUMMARY=\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: ""
        _state.value = _state.value.copy(
            running = true,
            bootstrap = progress,
            summary = summary.ifBlank { "bootstrapping $progress%" },
        )
        return _state.value
    }

    /** Their `signal_tor_newnym`: a fresh circuit, therefore a fresh exit. */
    fun newnym(): Boolean = control("SIGNAL NEWNYM").any { it.startsWith("250") }

    /**
     * Their `rotate_tor_circuit`: ask for a new circuit, then prove the exit changed.
     * Returns the previous and current exit so the caller can log a real pair.
     */
    fun rotate(): Pair<String, String> {
        // A blank *before* read is a transport miss, not "no exit": fall back to the last
        // address we saw so the logged pair is real.
        val before = exitIp().ifBlank { lastExit }
        repeat(3) {
            newnym()
            Thread.sleep(2_000)
            val after = exitIp()
            if (after.isNotBlank() && after != before) return before to after
        }
        return before to exitIp().ifBlank { lastExit }
    }

    /**
     * Exit address as seen through the SOCKS listener.
     *
     * Plain HTTP on purpose: `api.ipify.org` over port 80 needs no TLS state machine and
     * answers with the bare address, exactly like their `get_egress_ip`.
     */
    fun exitIp(timeoutMs: Int = 8_000): String {
        return try {
            Socks5.connect("127.0.0.1", SOCKS_PORT, "api.ipify.org", 80, timeoutMs).use { s ->
                val out = s.getOutputStream()
                out.write(
                    ("GET / HTTP/1.1\r\nHost: api.ipify.org\r\n" +
                        "User-Agent: zapp\r\nConnection: close\r\n\r\n").toByteArray(),
                )
                out.flush()
                val text = s.getInputStream().bufferedReader().readText()
                val body = text.substringAfter("\r\n\r\n", "").trim()
                // A chunked reply prefixes the body with `<hex>\r\n`, and the address is the
                // only line that looks like one — matching the whole body returned "" on
                // 2026-11-20 00:55, which is why the log read `tor exit  -> 204.8.96.88`.
                val ip = Regex("[0-9a-fA-F:.]{3,45}")
                body.lineSequence().map { it.trim() }.lastOrNull { ip.matches(it) }
                    .orEmpty()
                    .also { if (it.isNotBlank()) lastExit = it }
            }
        } catch (e: Exception) {
            ""
        }
    }

    /** Their `test_socks5_alive`: a completed handshake, not merely an open port. */
    fun probe(): Boolean = try {
        Socks5.connect("127.0.0.1", SOCKS_PORT, "api.ipify.org", 80, 6_000).use { true }
    } catch (e: Exception) {
        false
    }
}
