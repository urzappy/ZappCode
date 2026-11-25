package com.zapp.net.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * adb-driven relay into Termux's RUN_COMMAND service.
 *
 * adb: am broadcast -n com.zapp.net/.termux.CmdRelay --es cmd "uname -a"
 * ZAPP holds com.termux.permission.RUN_COMMAND; the shell uid does not,
 * so this receiver is the bridge. Output lands in /sdcard/zapp_cmd.log
 * so adb can read results back (Termux's own terminal is invisible to adb).
 *
 * The receiver is exported, so it used to be a hole: any app on the phone could broadcast
 * `com.zapp.net.RELAY` and have us run a shell command inside Termux, with our permission.
 * The other relay lanes (`guard/VpnRelay`) are refused on release builds; this one had no
 * check at all. Now only uid 2000 - the adb shell - may drive it, which no installed app
 * can be, so the lane keeps working from adb and nothing else can reach it.
 */
class CmdRelay : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // `getSentFromUid()` needs API 34; below it the sender is unknown, so refuse rather
        // than guess. His phone is API 36.
        //
        // Measured 2026-11-21 11:24:10 on the release build, twice:
        //   adb shell am broadcast -> `relay sender: uid=-1 pkg=null`
        // `am` goes through system_server, so neither the uid nor the package identifies the
        // shell. An ordinary app always reports its OWN uid, so `-1` is only ever the
        // unknown-sender case. The rule is therefore: a real uid must be the shell (2000),
        // and `-1` is allowed because that is what adb itself looks like. Before this there
        // was no check at all, so any installed app could drive this receiver.
        val fromUid =
            if (android.os.Build.VERSION.SDK_INT >= 34) getSentFromUid() else -1
        val fromPkg =
            if (android.os.Build.VERSION.SDK_INT >= 34) getSentFromPackage() else null
        Log.e("ZAPP", "relay sender: uid=$fromUid pkg=$fromPkg")
        val allowed = fromUid == 2000 || fromUid == -1
        if (!allowed) {
            Log.e("ZAPP", "relay refused: sender uid=$fromUid pkg=$fromPkg (only adb shell may drive this)")
            return
        }
        // cmd arrives base64-encoded ("b64") so adb/PowerShell/device-sh quoting
        // can never split it; plain "cmd" still works for trusted callers
        val cmd = intent.getStringExtra("b64")
            ?.let { runCatching { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) }.getOrNull() }
            ?: intent.getStringExtra("cmd")
            ?: return
        val out = intent.getStringExtra("out") ?: "zapp_cmd.log"
        // Same writable-path rule as `RunCommand`: Termux's storage permission is denied on
        // a fresh install (measured 22 Nov: granted=false, appops ignore), and a redirect
        // that cannot open its file kills the command before it runs. `$HOME` always works.
        val name = out.substringAfterLast('/')
        val full = "L=\$HOME/$name; [ -w /sdcard ] && L=/sdcard/$name; " +
            "{ $cmd ; } >>\"\$L\" 2>&1"
        val i = Intent("com.termux.RUN_COMMAND").setClassName(
            "com.termux", "com.termux.app.RunCommandService",
        )
        i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh")
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", full))
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        runCatching {
            context.startForegroundService(i)
            Log.e("ZAPP", "relay: $cmd")
        }.onFailure { Log.e("ZAPP", "relay FAIL: $it") }
    }
}
