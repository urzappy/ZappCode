package com.zapp.net.termux

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Termux refuses every external command until `allow-external-apps=true` sits in
 * `~/.termux/termux.properties`. Measured on the bundled F-Droid 0.118:
 *
 * ```
 * PluginUtils.checkIfAllowExternalAppsPolicyIsViolated
 *   -> SharedProperties.isPropertyValueTrue(ctx, TermuxPropertiesFile, "allow-external-apps", true)
 *   -> SharedProperties.getProperty -> getPropertiesFromFile(ctx, file)   // reads the file every call
 * ```
 *
 * Two facts follow, both from that smali and not from a guess:
 *
 *  1. There is no cache, so the line takes effect on the *next* command - Termux does not
 *     have to be restarted, and the user does not have to be told to restart it.
 *  2. Android gives one app no way to write another app's private home, so the line can
 *     only be typed inside Termux. That is why this object carries the exact line, copies
 *     it, and opens Termux instead of pretending the app can do it alone.
 *
 * [probe] is the part that stops the guessing. It dispatches a real RUN_COMMAND with a
 * `PendingIntent` result and reads Termux's own answer:
 *
 * ```
 * intent extra "result" -> Bundle { stdout, stderr, exitCode, err, errmsg }
 * ```
 *
 * `err == 0` is proof the service accepted the command. Anything else carries Termux's
 * own `errmsg`, which is the sentence the user used to have to guess at.
 */
object TermuxSetup {
    private const val PKG = "com.termux"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"
    private const val PERM = "com.termux.permission.RUN_COMMAND"
    private const val SH = "/data/data/com.termux/files/usr/bin/sh"

    const val ACTION_PROBE_RESULT = "com.zapp.net.termux.PROBE_RESULT"
    const val EXTRA_RESULT = "result"
    private const val EXTRA_NONCE = "nonce"

    /** Pasting this twice is harmless; `grep -qx` keeps the file from growing. */
    const val ENABLE_COMMAND =
        "mkdir -p ~/.termux; touch ~/.termux/termux.properties; " +
            "grep -qx allow-external-apps=true ~/.termux/termux.properties 2>/dev/null || " +
            "echo allow-external-apps=true >> ~/.termux/termux.properties; " +
            "echo 'ZAPP: Termux will now accept commands from ZAPP'"

    enum class State { OK, NO_TERMUX, NO_PERMISSION, NEEDS_PROPERTY, NO_BOOTSTRAP, SILENT }

    data class Probe(val state: State, val detail: String = "")

    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Bundle?>>()

    /** Called by [TermuxProbeReceiver] when Termux answers. */
    fun deliver(nonce: String, bundle: Bundle?) {
        waiters[nonce]?.complete(bundle)
    }

    fun installed(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(PKG, 0)
    }.isSuccess

    fun granted(ctx: Context): Boolean = runCatching {
        ContextCompat.checkSelfPermission(ctx.applicationContext, PERM) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * Sends `echo` through the real service and waits for Termux's own result bundle.
     * 12 s: a cold Termux process has to start, load its bootstrap and fork a shell.
     */
    suspend fun probe(ctx: Context, timeoutMs: Long = 12_000): Probe = withContext(Dispatchers.IO) {
        if (!installed(ctx)) return@withContext Probe(State.NO_TERMUX)
        if (!granted(ctx)) return@withContext Probe(State.NO_PERMISSION)
        val bundle = capture(ctx, "echo zapp-probe", timeoutMs)
        val p = classify(bundle, timeoutMs)
        Log.e("ZAPP", "termux probe: ${p.state} ${p.detail.take(160)}")
        p
    }

    /**
     * The phone's own words about the build, read back through Termux.
     *
     * the owner, 22 Nov: "get the termux logs and make that into the opening for new user".
     * This is that - the same lines `serve.sh` writes, fetched with a real RUN_COMMAND and
     * shown on the setup screen, so the wait has a reason on it instead of a spinner that
     * cannot say why it is spinning.
     */
    /**
     * Fire one small, fixed command into Termux and do not wait for an answer.
     *
     * `ProxyKeeper` uses this to run `$HOME/.zapp/lane/up.sh` when 41988 goes quiet. It is the
     * same RUN_COMMAND path `BootRelay` and [probe] already use, so nothing new is trusted -
     * and the command is a constant chosen by this app, never user text.
     *
     * Returns false when Termux is missing or `allow-external-apps` is not set, so the caller
     * can tell "asked and refused" from "asked and worked". Sending before the property
     * exists is what makes Termux post its own error toast, so that case is filtered here.
     */
    fun relay(ctx: Context, command: String): Boolean {
        if (!installed(ctx) || !granted(ctx)) return false
        val i = Intent(ACTION).setClassName(PKG, SERVICE)
        i.putExtra("com.termux.RUN_COMMAND_PATH", SH)
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        return runCatching { ctx.startForegroundService(i) }
            .onSuccess { Log.e("ZAPP", "relay: $command") }
            .onFailure { Log.e("ZAPP", "relay failed: $it") }
            .isSuccess
    }

    suspend fun logTail(ctx: Context, lines: Int = 4): List<String> {
        if (!installed(ctx) || !granted(ctx)) return emptyList()
        // `$HOME` FIRST. The old order preferred `/sdcard/zapp_serve.log`, and on this phone a
        // stale file from an earlier build sat there while `serve.sh` was writing the live one
        // to `$HOME` - so the screen showed nothing while the terminal showed the truth.
        // Measured 23 Nov 13:56: the log box was empty on every render, yet `tail ~/zapp_serve.log`
        // in Termux printed the whole install. `$HOME` is the file serve.sh always writes.
        val cmd = "L=\$HOME/zapp_serve.log; [ -f \"\$L\" ] || L=/sdcard/zapp_serve.log; " +
            "tail -n $lines \"\$L\" 2>/dev/null"
        val b = capture(ctx, cmd, 8_000) ?: return emptyList()
        return b.getString("stdout").orEmpty().lines()
            .map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * The relay's own aftermath, in Termux's words.
     *
     * Added 2026-11-24 after the wipe exposed the exact state a new user starts in: with
     * `MANAGE_EXTERNAL_STORAGE` at `default`, the old relay's first `cp` failed, `&&` killed the
     * line, and step 4 could only say `nothing downloaded yet` - no reason, no file sizes, no
     * log. `serve.sh` writes `$HOME/zapp_boot.log` before it touches anything else, so the two
     * sizes plus the last lines name the failure instead of hiding it.
     *
     * Costs one RUN_COMMAND and is only asked when the handoff was accepted and nothing moved.
     */
    suspend fun diagnose(ctx: Context): List<String> {
        if (!installed(ctx) || !granted(ctx)) return emptyList()
        val cmd =
            "A=\$(stat -c %s \"\$HOME/serve.sh\" 2>/dev/null || echo 0); " +
                "B=\$(stat -c %s \"\$HOME/zapp_proxy.py\" 2>/dev/null || echo 0); " +
                "echo \"home: serve=\$A proxy=\$B\"; " +
                "tail -n 5 \"\$HOME/zapp_boot.log\" 2>/dev/null"
        val b = capture(ctx, cmd, 6_000) ?: return emptyList()
        return b.getString("stdout").orEmpty().lines()
            .map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The engine archive as it lands, so step 4 can show a real number instead of a timer. */
    const val ENGINE_TOTAL = 121_172_970L
    const val ENGINE_NAME = "opencode-arm64.tar.gz"

    /**
     * Bytes of the engine archive already on disk.
     *
     * `curl -C -` resumes, so this number only ever grows and a dropped transfer shows up as a
     * stall instead of as "nothing is happening". `stat -c %s` comes from the same Termux
     * package that already provides `tail` above, so it adds no dependency.
     */
    suspend fun engineBytes(ctx: Context): Long {
        if (!installed(ctx) || !granted(ctx)) return 0L
        val b = capture(ctx, "stat -c %s \"\$HOME/$ENGINE_NAME\" 2>/dev/null || echo 0", 4_000)
            ?: return 0L
        return b.getString("stdout").orEmpty().trim().toLongOrNull() ?: 0L
    }

    /**
     * One real RUN_COMMAND with a result bundle - the only path in this app that gets an
     * answer back from Termux, so the probe and the log reader share it instead of each
     * owning a copy of the intent shape.
     */
    private suspend fun capture(ctx: Context, cmd: String, timeoutMs: Long): Bundle? =
        withContext(Dispatchers.IO) {
            val nonce = java.util.UUID.randomUUID().toString()
            val waiter = CompletableDeferred<Bundle?>()
            waiters[nonce] = waiter
            try {
                val reply = Intent(ctx, TermuxProbeReceiver::class.java)
                    .setAction(ACTION_PROBE_RESULT)
                    .putExtra(EXTRA_NONCE, nonce)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(ctx, nonce.hashCode(), reply, flags)

                val i = Intent(ACTION).setClassName(PKG, SERVICE)
                i.putExtra("com.termux.RUN_COMMAND_PATH", SH)
                i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", cmd))
                i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
                i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)

                val sent = runCatching { ctx.startForegroundService(i) }
                    .onFailure { Log.e("ZAPP", "termux capture: dispatch failed: $it") }
                    .isSuccess
                if (!sent) return@withContext null
                withTimeoutOrNull(timeoutMs) { waiter.await() }
            } finally {
                waiters.remove(nonce)
            }
        }

    private fun classify(bundle: Bundle?, timeoutMs: Long): Probe {
        if (bundle == null) {
            // The service never answered. On a phone where Termux was just installed and
            // never opened, `$PREFIX/bin/sh` does not exist yet and the service is not even
            // registered, so the two cases are told apart by whether Termux ever ran.
            return Probe(State.SILENT, "no answer in ${timeoutMs / 1000}s")
        }
        // Raw dump: the shape of this bundle is Termux's, not ours, and the only way to be
        // sure which key carries the verdict is to print it once.
        Log.e(
            "ZAPP",
            "termux probe bundle: " +
                bundle.keySet().sorted().joinToString(" ") { "$it=${bundle.get(it)}" },
        )
        val err = bundle.getInt("err", 0)
        val msg = bundle.getString("errmsg").orEmpty()
        val out = bundle.getString("stdout").orEmpty().trim()
        val exit = bundle.getInt("exitCode", -1)
        // The command echoes a known word, so a real round trip proves itself even if
        // Termux reports a non-zero `err` for a background session.
        if (err == 0 || (exit == 0 && out.contains("zapp-probe"))) {
            return Probe(State.OK, out)
        }
        return when {
            msg.contains("allow-external-apps") -> Probe(State.NEEDS_PROPERTY, msg)
            msg.contains("permission", ignoreCase = true) -> Probe(State.NO_PERMISSION, msg)
            msg.contains("executable", ignoreCase = true) ||
                msg.contains("No such file", ignoreCase = true) -> Probe(State.NO_BOOTSTRAP, msg)
            else -> Probe(State.SILENT, "err=$err exit=$exit ${msg.take(200)}")
        }
    }

    fun copyCommand(ctx: Context) {
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ZAPP Termux setup", ENABLE_COMMAND))
        }.onFailure { Log.e("ZAPP", "termux setup: clipboard refused: $it") }
    }

    /** Opens Termux's own terminal. On a fresh install this also extracts the bootstrap. */
    fun openTermux(ctx: Context): Boolean = runCatching {
        val i = ctx.packageManager.getLaunchIntentForPackage(PKG) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    }.onFailure { Log.e("ZAPP", "termux setup: cannot open Termux: $it") }.getOrDefault(false)

    /**
     * Is the engine's own port answering.
     *
     * The handshake only proves Termux *accepted* the command. On a fresh install `serve.sh`
     * then installs python, installs glibc-runner and downloads a 121 MB engine, so "accepted"
     * and "connected" are minutes apart. This is the difference the setup screen has to show,
     * or a first run looks broken while it is working.
     */
    fun engineListening(): Boolean = portOpen(4096)

    /** The lane's CONNECT proxy, started by `serve.sh` once python3 exists. */
    fun laneListening(): Boolean = portOpen(41988)

    /**
     * The same check, off the main thread.
     *
     * Measured 23 Nov on the clean install: `serve.sh` printed `serve ready 14:15:01`,
     * `/proc/net/tcp` showed `0100007F:1000` in LISTEN under Termux's uid, and the setup screen
     * still said "Engine answered - finishing" with a running timer - the step-4 tick never
     * filled and the button never became "Finish".
     *
     * The cause is that `portOpen` blocks a `Socket.connect` and `LaunchedEffect` runs on the
     * main thread, so Android throws `NetworkOnMainThreadException` before the connect even
     * happens. `runCatching` in `portOpen` swallowed it and answered `false`, every tick, on
     * every phone. A composable must never call the blocking form.
     */
    suspend fun engineListeningNow(): Boolean =
        withContext(Dispatchers.IO) { portOpen(4096) }

    suspend fun laneListeningNow(): Boolean =
        withContext(Dispatchers.IO) { portOpen(41988) }

    /**
     * What `serve.sh` says it is doing right now - the phone's own answer, not our guess.
     *
     * the owner, 23 Nov: "its show download done while the termux is downloading pa ... so para
     * alam namin na tapos naba or nag stuck lang". The screen used to measure one file, so the
     * moment the 121 MB tarball landed it claimed "download done" while python, glibc and the
     * unpack were still running. `serve.sh` now writes `$HOME/.zapp/status` at every stage
     * boundary and `$HOME/.zapp/beat` every 3 s, so this reads the real stage, the real bytes
     * on disk, and whether the installer is still alive.
     *
     * One RUN_COMMAND per read, so it is polled every few seconds, never on every frame.
     */
    data class Stage(
        /** mirror, python, download, check, unpack, glibc, start, ready, or none. */
        val id: String = "none",
        /** Epoch the current stage began. */
        val since: Long = 0L,
        /** Expected bytes for this stage, 0 when it has no byte counter. */
        val total: Long = 0L,
        /** Bytes actually on disk for this stage's file. */
        val bytes: Long = 0L,
        /** Epoch of the last sign of life from the installer. */
        val beat: Long = 0L,
    )

    suspend fun readStage(ctx: Context): Stage = snapshot(ctx).stage

    /**
     * Stage + the phone's own last log lines in ONE `RUN_COMMAND`.
     *
     * Termux starts a foreground service for every command it is handed, and that service must
     * post a notification. Two reads per poll meant two notifications per poll; one read means
     * one. The log lines come from the same shell that already has the status file open, so
     * nothing is lost by merging them.
     */
    data class Snapshot(val stage: Stage = Stage(), val lines: List<String> = emptyList())

    suspend fun snapshot(ctx: Context, lines: Int = 4): Snapshot {
        if (!installed(ctx) || !granted(ctx)) return Snapshot()
        val cmd =
            "S=\$HOME/.zapp/status; B=\$HOME/.zapp/beat; " +
                "if [ -r \"\$S\" ]; then IFS='|' read -r G E T F < \"\$S\"; " +
                "else G=none; E=0; T=0; F=; fi; " +
                "Y=0; if [ -n \"\$F\" ] && [ -r \"\$F\" ]; then " +
                "Y=\$(stat -c %s \"\$F\" 2>/dev/null || echo 0); fi; " +
                "K=\$(cat \"\$B\" 2>/dev/null || echo 0); " +
                "echo \"S:\$G|\$E|\$T|\$Y|\$K\"; " +
                "L=\$HOME/zapp_serve.log; [ -f \"\$L\" ] || L=/sdcard/zapp_serve.log; " +
                "echo L:; tail -n $lines \"\$L\" 2>/dev/null"
        val b = capture(ctx, cmd, 7_000) ?: return Snapshot()
        val all = b.getString("stdout").orEmpty().lines().map { it.trim() }
        val head = all.firstOrNull { it.startsWith("S:") }.orEmpty().removePrefix("S:")
        val stage = if (head.count { it == '|' } >= 4) {
            val p = head.split("|")
            Stage(
                id = p[0].trim().ifEmpty { "none" },
                since = p[1].trim().toLongOrNull() ?: 0L,
                total = p[2].trim().toLongOrNull() ?: 0L,
                bytes = p[3].trim().toLongOrNull() ?: 0L,
                beat = p[4].trim().toLongOrNull() ?: 0L,
            )
        } else {
            Stage()
        }
        val marker = all.indexOfFirst { it == "L:" }
        val log = if (marker >= 0) {
            all.drop(marker + 1).filter { it.isNotEmpty() && !it.startsWith("S:") }
        } else {
            emptyList()
        }
        return Snapshot(stage, log)
    }

    /**
     * 1200 ms, not 300.
     *
     * Measured 23 Nov on a fresh phone: the setup screen said "Engine starting - 16s" while
     * `serve.sh` had already printed `serve ready`, which it writes only *after* the engine
     * answered on 4096. The port was open; the probe gave up first. A phone unpacking a
     * 121 MB engine and pre-warming four API routes does not answer a loopback connect in
     * 300 ms, and a false negative there reads to the user as "stuck".
     */
    private fun portOpen(port: Int): Boolean = try {
        java.net.Socket().use {
            it.connect(java.net.InetSocketAddress("127.0.0.1", port), 1200)
            true
        }
    } catch (t: Throwable) {
        // Silent before this. Measured 23 Nov on the clean install: the engine was LISTENING on
        // 4096 while the setup screen showed a running timer forever, and nothing anywhere said
        // why. A probe that fails has to say so, once per distinct reason, or the next person
        // debugging this is blind again.
        Log.w("ZAPP", "port $port probe: ${t.javaClass.simpleName}: ${t.message}")
        false
    }
}

/**
 * Manifest-declared and not exported: a `PendingIntent` runs with the identity of the app
 * that created it, so Termux can deliver into this receiver without it being reachable by
 * anything else on the phone.
 */
class TermuxProbeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val nonce = intent.getStringExtra("nonce").orEmpty()
        if (nonce.isEmpty()) return
        @Suppress("DEPRECATION")
        val bundle = intent.getBundleExtra(TermuxSetup.EXTRA_RESULT)
        TermuxSetup.deliver(nonce, bundle)
    }
}
