package com.zapp.net.vpn

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.zapp.net.guard.WarpReg
import java.io.File

/**
 * Runtime rekey over wireguard-go's own UAPI socket - the fast identity swap.
 *
 * ## Why this exists
 *
 * `GoBackend.setState(tunnel, UP, config)` always runs
 *
 * ```java
 * if (currentTunnel != null) setStateInternal(currentTunnel, null, DOWN); // wgTurnOff
 * setStateInternal(tunnel, config, UP);                                   // establish() + wgTurnOn
 * ```
 *
 * (verified by decompiling `tunnel-1.0.20260102.aar`, `GoBackend.setState` bytecode 57-99:
 * the `UP` branch unconditionally calls `setStateInternal(currentTunnel, null, DOWN)` first).
 * That is a full TUN teardown and rebuild: ~200-700 ms, and every socket riding the tunnel
 * dies. It is also why a "warm pool" could not be faster than a cold hop.
 *
 * The native device itself does not need any of that. `libwg-go.so` carries the UAPI text
 * protocol (`private_key`, `listen_port`, `public_key`, `allowed_ip=%s`, `preshared_key`,
 * `UAPIListen: %v`) and opens a control socket per tunnel. Writing
 *
 * ```
 * set=1
 * replace_peers=true
 * private_key=<new>
 * public_key=<peer>
 * endpoint=<ip:port>
 * allowed_ip=0.0.0.0/0
 * allowed_ip=::/0
 *
 * ```
 *
 * replaces the device's keys in place. The TUN fd, the routes, the DNS servers and the
 * VpnService are untouched; the next packet leaves under the new identity. That is a Unix
 * socket connect + write + one line back - single-digit milliseconds at worst, and the
 * measured cost is logged on every swap.
 *
 * ## What it cannot do
 *
 * Established TCP flows die anyway, because their source address changes at Cloudflare's
 * edge. Nothing can fix that; a new request is needed either way. What this removes is the
 * *tunnel* rebuild, which was the 200-700 ms part.
 *
 * The socket path is not a guess: the blob in `jniLibs` was patched by
 * `tools/patch-wg-go.ps1` and contains the literal
 * `/data/data/com.zapp.net/files/wg-uapi-sockets.00` plus a `%s/%s.sock` format, and
 * `ZappVpnService` creates that directory before every bring-up.
 */
object Uapi {

    /**
     * Seconds since the live device last completed a WireGuard handshake, or null when the
     * socket is gone or the field is unreadable.
     *
     * `get=1` reports `last_handshake_time_sec` as an absolute Unix timestamp and `0` for
     * "never". This is the peer's own answer, so it outranks any probe: if the handshake moved
     * in the last few minutes, the tunnel is alive and a silent probe is the probe's problem.
     */
    fun handshakeAgeSec(filesDir: File, tunnelName: String): Long? {
        val sock = resolve(filesDir, tunnelName) ?: return null
        val ls = LocalSocket()
        return runCatching {
            ls.connect(LocalSocketAddress(sock.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            ls.outputStream.write("get=1\n\n".toByteArray(Charsets.UTF_8))
            ls.outputStream.flush()
            val buf = ByteArray(4096)
            val n = ls.inputStream.read(buf)
            val text = String(buf, 0, if (n > 0) n else 0, Charsets.UTF_8)
            val v = Regex("(?m)^last_handshake_time_sec=(\\d+)$").find(text)
                ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            if (v <= 0L) null else (System.currentTimeMillis() / 1000) - v
        }.getOrNull().also { runCatching { ls.close() } }
    }

    /** Same directory `ZappVpnService.bringUp` creates before `establish()`. */
    fun dir(filesDir: File): File = File(filesDir, "wg-uapi-sockets.00")

    /**
     * Read the live device's state with `get=1`.
     *
     * `last_handshake_time_sec` is the one field that separates "the config was wrong" from
     * "Cloudflare dropped the source address": a handshake that completes but carries no data
     * means the keys are right and the interface address is the problem.
     */
    fun status(filesDir: File, tunnelName: String): String {
        val sock = resolve(filesDir, tunnelName) ?: return "no socket"
        val ls = LocalSocket()
        return runCatching {
            ls.connect(LocalSocketAddress(sock.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            ls.outputStream.write("get=1\n\n".toByteArray(Charsets.UTF_8))
            ls.outputStream.flush()
            val buf = ByteArray(4096)
            val n = ls.inputStream.read(buf)
            String(buf, 0, if (n > 0) n else 0, Charsets.UTF_8)
                .lines()
                .filter { it.startsWith("last_handshake") || it.startsWith("public_key") || it.startsWith("errno") }
                .joinToString(" ")
        }.getOrElse { "get failed: ${it.javaClass.simpleName}: ${it.message}" }
            .also { runCatching { ls.close() } }
    }

    /**
     * The live control socket.
     *
     * The blob's `%s/%s.sock` format is filled with the **interface** name, not the tunnel
     * name: measured on this device, `uapi sockets: tun0.sock` while the tunnel object was
     * `zappwarp7`. So the name is tried first, then any `*.sock` in the directory - there is
     * only ever one live device.
     */
    fun resolve(filesDir: File, tunnelName: String): File? {
        val exact = File(dir(filesDir), "$tunnelName.sock")
        if (exact.exists()) return exact
        // NOT `isFile()`: a Unix domain socket is not a regular file, so `isFile()` is false
        // for the very thing we are looking for. Measured 09:28 - `listing` printed
        // `tun0.sock` while `resolve` returned null on the next call, because the filter
        // rejected a socket for not being a file.
        //
        // With more than one socket present (`uapi sockets: tun0.sock, tun1.sock`, measured
        // 11:47 after a rebuild) `firstOrNull` could hand back the socket of the interface the
        // previous tunnel left behind, so `get=1` answered for an identity that is no longer
        // bound. A socket's mtime is its creation time and is not touched by connections, so
        // the newest one is the live interface.
        val all = dir(filesDir).listFiles()?.filter { it.name.endsWith(".sock") }.orEmpty()
        if (all.size > 1) {
            android.util.Log.e(
                "ZAPP",
                "uapi: ${all.size} sockets, using newest " +
                    all.maxByOrNull { it.lastModified() }?.name,
            )
        }
        return all.maxByOrNull { it.lastModified() }
    }

    /** `<dir>/<tunnelName>.sock`, the naming the blob's `%s/%s.sock` format implies. */
    fun socketFor(filesDir: File, tunnelName: String): File = File(dir(filesDir), "$tunnelName.sock")

    /** Everything in the socket directory, for the log line that proves the listener exists. */
    fun listing(filesDir: File): String = runCatching {
        val f = dir(filesDir)
        if (!f.isDirectory) "no dir" else
            f.listFiles()?.joinToString(", ") { it.name }?.ifBlank { "empty" } ?: "unreadable"
    }.getOrDefault("error")

    /**
     * Apply [reg]'s identity to the running device.
     *
     * Returns the elapsed time in microseconds, or null when the socket is absent or the
     * device refused the config. Never throws: the caller falls back to a full rebuild.
     * [lastError] carries why it refused, because a silent null cost a whole test round.
     */
    @Volatile var lastError: String? = null

    fun rekey(filesDir: File, tunnelName: String, reg: WarpReg, endpoint: String): Long? {
        lastError = null
        val sock = resolve(filesDir, tunnelName) ?: run {
            lastError = "no socket"
            return null
        }
        val started = System.nanoTime()
        val body = buildString {
            append("set=1\n")
            // Clear the old peer: without this the device keeps both and the previous
            // identity can still answer, which is the opposite of a swap.
            append("replace_peers=true\n")
            append("private_key=").append(reg.privHex).append('\n')
            append("public_key=").append(reg.peerPubHex).append('\n')
            append("endpoint=").append(endpoint).append('\n')
            append("allowed_ip=0.0.0.0/0\n")
            append("allowed_ip=::/0\n")
            append("persistent_keepalive_interval=25\n")
            append('\n')
        }.toByteArray(Charsets.UTF_8)

        val ls = LocalSocket()
        return runCatching {
            ls.connect(LocalSocketAddress(sock.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            ls.outputStream.write(body)
            ls.outputStream.flush()
            // The device answers `errno=0` on success. Read it, because a refused config
            // looks exactly like an accepted one from the write side.
            val reply = ByteArray(256)
            val n = ls.inputStream.read(reply)
            val line = String(reply, 0, if (n > 0) n else 0, Charsets.UTF_8).trim()
            if (line.contains("errno=0")) {
                (System.nanoTime() - started) / 1_000
            } else {
                lastError = "reply[$n]=${line.take(160).replace("\n", "|")}"
                null
            }
        }.getOrElse { e ->
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            null
        }.also { runCatching { ls.close() } }
    }
}
