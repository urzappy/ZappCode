package com.zapp.net.termux

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Hands the bundled `assets/serve.sh` to Termux's RUN_COMMAND service.
 *
 * Why this exists: the phone must be able to run its own `opencode serve` with no PC,
 * and no one should have to open Termux and type a command. ZAPP holds
 * `com.termux.permission.RUN_COMMAND`, the script is idempotent (it exits if a server
 * is already live), so this can safely fire on every app start and after boot.
 *
 * A cached or frozen app process gets its broadcasts deferred by the OS, so this is
 * called from `MainViewModel.init` (app is warm by definition) and from BootReceiver.
 */
object BootRelay {
    private const val NAME = "serve.sh"
    private const val PROXY = "zapp_proxy.py"

    /**
     * "Does the engine answer?" from any thread, including the UI thread.
     *
     * A blocking `Socket.connect` on the main thread throws `NetworkOnMainThreadException`
     * instead of connecting - proven on this phone at 14:38:46 with our own log line. When the
     * caller is the UI thread the probe hops to a worker and waits at most 1.5 s, which is the
     * same budget the socket itself uses. Off the UI thread it is called directly.
     */
    private fun engineAnswers(): Boolean {
        val onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        if (!onMain) return TermuxSetup.engineListening()
        val latch = java.util.concurrent.CountDownLatch(1)
        var up = false
        Thread {
            up = TermuxSetup.engineListening()
            latch.countDown()
        }.apply { isDaemon = true }.start()
        return runCatching {
            latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            up
        }.getOrDefault(false)
    }

    /**
     * @return true when Termux accepted the handshake. False means assets were missing,
     *         Termux is absent, or the permission was refused - each one logged above.
     */
    fun runPending(ctx: Context, fromSetup: Boolean = false, force: Boolean = false): Boolean {
        // Never while the setup screen is still walking the user through Termux.
        //
        // the owner, 23 Nov: "yung notification na error command i dont want to see it remove that".
        // `runPending` is called from the app's own start-up path, which happens during
        // onboarding too - and on a fresh install `allow-external-apps` is not written yet, so
        // Termux answers with its error toast. Nothing here can start an engine that has no
        // packages, so during setup the only sender is the setup screen itself, after its own
        // probe came back OK.
        if (!fromSetup && !com.zapp.net.onboarding.Onboarding.termuxReady(ctx)) {
            Log.e("ZAPP", "boot relay skipped: onboarding has not finished")
            return false
        }
        // Measured 23 Nov 14:38:46 on the clean install, from the main thread:
        //   port 4096 probe: NetworkOnMainThreadException: null
        // `runPending` is called from `LaunchedEffect` and from `Root`'s start-up path, both of
        // which are the main thread, and the engine check below is a blocking socket connect.
        // Android refuses that outright, the exception came back as "not up", and the relay
        // fired a command that was not needed. The check has to be off the UI thread no matter
        // which caller reaches it.
        // A PORT IS NOT A VERDICT.
        //
        // Measured 23 Nov 20:10:44-20:13:06 on v45: `opencode serve` was alive at 2h02m and
        // `/sdcard/zapp_boot.log` stopped at `serve: guard lane attached` with no `serve ready`.
        // Its Bun event loop was blocked, so it answered nothing - but the kernel still completed
        // TCP handshakes from the listen backlog, so `portOpen(4096)` said "up" and this relay
        // refused to run. The app retried into that socket for the rest of the session.
        //
        // `force` is for callers that already proved the engine is not answering (a failed
        // authenticated read, not an open port). `serve.sh` probes `/api/agent` itself and
        // replaces a wedged process, so relaying it is exactly the repair.
        if (!force && engineAnswers()) {
            Log.e("ZAPP", "boot relay skipped: engine already answers on 4096")
            return true
        }
        if (force) Log.e("ZAPP", "boot relay forced: caller proved the engine is not answering")

        // Both scripts are AES-GCM blobs under `assets/lane/` now; `Vault` is the only thing
        // that knows how to open them. the owner, 22 Nov: "i dont want them to copy my script
        // make it unreadable" - a plain `assets/serve.sh` is readable by anyone who unzips
        // the APK, which is the whole launcher and the whole egress proxy.
        //
        // The blobs are produced by `node make-vault.js`, which reads `reference/lane/` and
        // NEVER a plaintext file inside `assets/`. Measured 2026-11-23 21:29: copying a plain
        // serve.sh over `assets/lane/serve.bin` made `Vault.serve` return null and the relay
        // logged `vault lane/serve.bin unreadable` - so the engine could never be started at
        // all. Every rebuild that touches `reference/lane/` must run make-vault.js first.
        val body = Vault.serve(ctx)
        if (body.isNullOrBlank()) {
            Log.e("ZAPP", "boot relay: vault lane/serve.bin unreadable")
            return false
        }
        // The egress proxy has to exist on disk before serve.sh decides where the server
        // sends its provider calls. Staged in the same command so the two can never drift:
        // a stale proxy with a fresh serve.sh would pin nothing and say nothing about it.
        val proxy = Vault.proxy(ctx)
        if (proxy.isNullOrBlank()) {
            Log.e("ZAPP", "boot relay: vault lane/proxy.bin unreadable")
            return false
        }
        // keep a copy in the app dir so a failure is inspectable
        runCatching { File(ctx.filesDir, NAME).writeText(body) }
        runCatching { File(ctx.filesDir, PROXY).writeText(proxy) }

        // THE PAYLOAD NEVER RIDES IN THE INTENT ANY MORE.
        //
        // Measured 2026-11-24 00:47-01:03: `boot relay fired (39745 chars)` seven times in
        // forty minutes, ActivityManager showed the intent accepted and `TermuxService`
        // started, and NOTHING ever ran - no `serve.sh` process, `/sdcard/zapp_boot.log`
        // frozen at 11-23 20:47 for four hours. The inline command was:
        //
        //     echo <53032 base64> | base64 -d > ~/zapp-serve.sh && \
        //     echo <70284 base64> | base64 -d > ~/zapp_proxy.py && ... && setsid sh ...
        //     = ~123 716 bytes of a single argv string
        //
        // Linux's hard per-argument ceiling is MAX_ARG_STRLEN = 131 072 (32 pages), so that
        // command sat at 94% of the cliff, and the first `&&` to fail kills the chain before
        // `setsid` - silently, because the redirect only covers the command that never ran.
        // It also grew: the v64 vault took serve.bin 39 299 -> 39 773 B, and every lane
        // rebuild pushes it closer.
        //
        // Termux CAN read `Android/media/<pkg>` (that tree is exempt from scoped-storage
        // redaction, unlike `Android/data`), and the app can always write it without any
        // permission. So the payload is staged there and the relay sends ~400 bytes.
        val media = ctx.externalMediaDirs?.firstOrNull()
        if (media == null) {
            Log.e("ZAPP", "boot relay: no external media dir - cannot stage the payload")
            return false
        }
        val staged = runCatching {
            media.mkdirs()
            File(media, NAME).writeText(body)
            File(media, PROXY).writeText(proxy)
        }.getOrElse {
            Log.e("ZAPP", "boot relay: staging failed: $it")
            return false
        }
        val stagedServe = File(media, NAME)
        val stagedProxy = File(media, PROXY)
        if (stagedServe.length() != body.toByteArray().size.toLong() ||
            stagedProxy.length() != proxy.toByteArray().size.toLong()
        ) {
            Log.e(
                "ZAPP",
                "boot relay: staged size mismatch serve=${stagedServe.length()} proxy=${stagedProxy.length()}",
            )
            return false
        }
        Log.e(
            "ZAPP",
            "boot relay: staged serve=${stagedServe.length()} proxy=${stagedProxy.length()} at ${media.absolutePath}",
        )
        // The payload also goes over loopback, and that is the path a NEW USER gets.
        //
        // Measured 2026-11-24 15:03, minutes after a full wipe of both apps - the state every
        // first-time user is in: `appops get com.termux MANAGE_EXTERNAL_STORAGE` -> `default`.
        // Termux could not read `Android/media/com.zapp.net`, so `cp` failed on the first `&&`,
        // nothing reached `$HOME`, no engine started, `.zapp/status` never appeared, and step 4
        // sat at `121.1 MB once - nothing downloaded yet` while the 39,745 B + 52,685 B staged
        // files were sitting there readable only to this app. Asking the user to grant Termux
        // all-files access would fix it, but a first run must not depend on a permission that
        // has nothing to do with the engine.
        //
        // Both blobs are already in this process's memory, and loopback needs no permission:
        // Termux already calls 127.0.0.1:4096 in the other direction. So they are served here
        // for the few seconds the relay needs, and the `/sdcard` copy stays only as a fallback.
        val server = PayloadServer.start(mapOf(NAME to body.toByteArray(), PROXY to proxy.toByteArray()))
        val port = server?.port ?: 0
        // The password is the app's own per-install device token (`Onboarding.token`), passed in
        // front of `sh` so `serve.sh` inherits it. Nothing fixed ships in the APK, nothing is
        // typed, and the value is the same one the client sends as `Basic opencode:<token>`.
        //
        // Confirmed 2026-11-22 13:40 on the running server: its environment held
        // `OPENCODE_SERVER_PASSWORD=<64-hex token>` and `curl -u opencode:<token>` returned 200
        // on /api/info, /api/agent and /api/session. Do not remove this.
        val token = com.zapp.net.onboarding.Onboarding.ensureToken(ctx)
        val i = Intent("com.termux.RUN_COMMAND").setClassName(
            "com.termux", "com.termux.app.RunCommandService",
        )
        i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh")
        i.putExtra(
            "com.termux.RUN_COMMAND_ARGUMENTS",
            // Short by design - see the staging comment above. No `base64`, no 124 KB argv.
            // The old shape piped both base64 blobs straight into the command, so
            // `$HOME/zapp-serve.sh` was only ever written by a chain that had to survive
            // MAX_ARG_STRLEN first (measured 2026-11-24: 123 716 bytes = 94% of the
            // 131 072 limit, and the relay fired seven times with nothing running).
            arrayOf(
                "-c",
                // NO KILL. `serve.sh` is the only owner of the engine's lifecycle - the same
                // rule the lane already lives by (`up.sh` is the one owner, nothing else
                // restarts `zapp_lane`). An app-side `pkill -9 -f '[o]pencode serve'` broke
                // that rule and produced the worst state of all, measured 2026-11-23 21:07:
                //
                //     ServeError: [cause]: Error: Failed to start server. Is port 4096 in use?
                //
                // Two engines raced for the port, one died, and the survivor was then killed
                // by the retry ladder - leaving NOTHING on 4096 while the app kept hammering.
                // A relay only ever asks serve.sh to run; serve.sh decides, under its own
                // lock, whether the engine is healthy, stale or absent.
                //
                // DETACHED (`setsid`), and this is not cosmetic. `serve.sh` installs python
                // and glibc-runner with `pkg` on a fresh Termux, and that mirror hunt takes
                // minutes. Run in the service's own session, it dies the moment Termux
                // closes the session - measured 22 Nov 22:55:30: the log stops at
                // "Testing the available mirrors:" and nothing is ever installed, while
                // every retry starts the hunt again from zero. `setsid` gives it its own
                // session, so it outlives the relay exactly like the server it starts.
                //
                // Same writable-path rule as `RunCommand`: Termux's storage permission can
                // be denied, and a redirect that cannot open its file kills the whole
                // command. `$HOME` always works, so it is the fallback.
                // LOOPBACK FIRST, `/sdcard` as the fallback. `curl` and `busybox` are both in
                // Termux's own bootstrap, so neither adds a package; `wget` alone is not, which
                // is why it is spelled `busybox wget`. The whole chain is ~600 bytes, nowhere
                // near the 131 072-byte argv ceiling that killed the old inline base64.
                "D=/sdcard/Android/media/com.zapp.net; P=$port; " +
                    "g(){ curl -fsS -m 30 -o \"\$2\" \"http://127.0.0.1:\$P/\$1\" " +
                    "&& [ -s \"\$2\" ] && return 0; " +
                    "busybox wget -q -O \"\$2\" \"http://127.0.0.1:\$P/\$1\" " +
                    "&& [ -s \"\$2\" ] && return 0; rm -f \"\$2\"; return 1; }; " +
                    "g $NAME \$HOME/$NAME || cp \"\$D/$NAME\" \$HOME/$NAME; " +
                    "g $PROXY \$HOME/$PROXY || cp \"\$D/$PROXY\" \$HOME/$PROXY; " +
                    "chmod 700 \$HOME/$PROXY; " +
                    "[ -s \$HOME/$NAME ] || " +
                    "echo \"relay: no serve.sh - loopback P=$port and \$D both failed\" " +
                    ">>\$HOME/zapp_boot.log; " +
                    "{ L=\$HOME/zapp_boot.log; [ -w /sdcard ] && L=/sdcard/zapp_boot.log; " +
                    "OPENCODE_SERVER_PASSWORD=$token setsid sh \$HOME/$NAME " +
                    ">>\"\$L\" 2>&1 </dev/null & }",
            ),
        )
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")

        return runCatching { ctx.startForegroundService(i) }
            .onSuccess { Log.e("ZAPP", "boot relay fired (${body.length} chars)") }
            .onFailure { Log.e("ZAPP", "boot relay failed: $it") }
            .isSuccess
    }
}
