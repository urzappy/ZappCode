package com.zapp.net.termux

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Direct RUN_COMMAND dispatch into Termux's real process.
 *
 * Why this exists next to [CmdRelay]: the relay is an adb-facing broadcast, but the app
 * itself can hand Termux a command far more cheaply by naming the service. Both paths end
 * in `RunCommandService`, which is the *only* Termux context with working network — the
 * `run-as` shell lives in Android's `runas_app` SELinux domain and has no socket access
 * (measured: `curl` to https://packages.termux.dev returns 000 and DNS resolution times
 * out there, while the same binaries reach opencode.ai from the real app process).
 *
 * Ported shape from the reference `zen_lane.py`: one long-lived helper owns the daemon,
 * the caller only asks for a state, never for a process handle.
 */
object RunCommand {

    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"
    private const val SH = "/data/data/com.termux/files/usr/bin/sh"

    /**
     * Run [cmd] under Termux's `sh`, in the background, with output appended to [out].
     *
     * @return true when the service accepted the request; false when Termux is missing,
     *         `allow-external-apps` is off, or the permission was revoked.
     */
    fun run(ctx: Context, cmd: String, out: String = "zapp_cmd.log"): Boolean {
        // The `{ }` group keeps a multi-line body a single command for RUN_COMMAND, and
        // the redirect means a failing command still leaves a readable trace.
        //
        // THE LOG PATH IS DECIDED INSIDE TERMUX, and that is not tidiness - it is the bug
        // that made every fresh install look dead. Measured 22 Nov 23:54 on this phone:
        //
        //   dumpsys package com.termux -> READ/WRITE_EXTERNAL_STORAGE: granted=false
        //   appops get com.termux      -> WRITE_EXTERNAL_STORAGE: ignore
        //
        // A redirection that cannot create its file fails *before* the command runs, so
        // `>>/sdcard/...` turned the whole watchdog into a silent no-op: no python, no
        // proxy, no error, nothing in the log to read. `$HOME` is always writable by
        // Termux, so it is the default; `/sdcard` is used only when it really answers.
        val name = out.substringAfterLast('/')
        val full = "L=\$HOME/$name; [ -w /sdcard ] && L=/sdcard/$name; " +
            "{ $cmd ; } >>\"\$L\" 2>&1"
        val i = Intent(ACTION).setClassName("com.termux", SERVICE)
        i.putExtra("com.termux.RUN_COMMAND_PATH", SH)
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", full))
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        return try {
            ctx.startForegroundService(i)
            Log.i("ZAPP", "termux: $cmd")
            true
        } catch (e: Exception) {
            Log.e("ZAPP", "termux FAIL: ${e.message}")
            false
        }
    }
}
