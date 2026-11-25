package com.zapp.net.termux

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The one bit of state `serve.sh` can see at launch.
 *
 * The server is spawned by Termux, not by ZAPP, so it can only learn the chosen lane
 * through the filesystem: `$HOME/.zapp/tor-lane` exists  ->  export HTTPS_PROXY,
 * absent -> direct egress through the WARP tun.
 *
 * Reference shape: `zen-lane.sh` writes its own lane selection before starting
 * opencode, and `zen_proxy.py` defaults to `mode = "warp"`. Tor is opt-in.
 *
 * Measured 2026-11-20 01:19: the marker did not exist and did not exist in code either,
 * so a "switch off" could leave the previous launch's proxy env in place. Now the switch
 * owns the file and `serve.sh` owns the decision.
 */
object TorLaneMarker {

    private const val DIR = ".zapp"
    private const val FILE = "tor-lane"

    private fun file(ctx: Context): File {
        // The server's HOME is fixed by serve.sh; ZAPP talks to the same Termux uid.
        val home = File("/data/data/com.termux/files/home")
        val root = if (home.exists() && home.canRead()) home else File(ctx.filesDir.parentFile, "termux-home")
        return File(File(root, DIR), FILE)
    }

    fun sync(ctx: Context, on: Boolean) {
        runCatching {
            val f = file(ctx)
            if (on) {
                f.parentFile?.mkdirs()
                if (!f.exists()) f.writeText("")
            } else if (f.exists()) {
                f.delete()
            }
        }.onFailure { Log.e("ZAPP", "tor-lane marker: $it") }
    }
}
