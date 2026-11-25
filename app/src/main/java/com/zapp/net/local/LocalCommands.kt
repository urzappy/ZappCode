package com.zapp.net.local

import android.content.Context
import android.util.Log
import com.zapp.net.guard.GuardState
import com.zapp.net.guard.RotationEngine
import com.zapp.net.shizuku.Shell
import kotlinx.coroutines.runBlocking

/**
 * Commands the composer answers without asking the model.
 *
 * Why this exists, measured 04:15 on the owner's own screen: he typed "rotate the tunnel" four
 * times and got a quota wall four times. The model had no guard tool to call, and every one
 * of those attempts was a real turn against a walled IP - it cost quota to fail.
 *
 * So the app answers these itself. Zero tokens, zero network, works with the quota dead and
 * the model walled. The model keeps everything else; this table only covers actions the app
 * can perform directly and does not need help with.
 *
 * Matching is deliberate about the *verb*: "rotate the tunnel" and "new ip" both hop, but a
 * sentence like "why did it rotate" must reach the model, so a bare keyword buried in prose
 * does not match. Each pattern is anchored at the start of the trimmed line.
 */
object LocalCommands {

    private const val TAG = "ZAPP"

    /** What the reply looks like: a headline plus optional detail lines. */
    data class Reply(val title: String, val body: String = "")

    private val TABLE: List<Pair<Regex, suspend (Context, MatchResult) -> Reply>> = listOf(
        // ---- guard control ----
        Regex("^(?:/)?(?:rotate|rot|hop|new ?ip|change ?ip|bagong ?ip|palit ?ip)\\b.*", RegexOption.IGNORE_CASE) to
            { ctx, _ -> rotate(ctx) },

        Regex("^(?:/)?(?:status|guard|tunnel|vpn ?status|kumusta)\\s*$", RegexOption.IGNORE_CASE) to
            { _, _ -> status() },

        Regex("^(?:/)?(?:clear ?wall|wal|wall|bakit)$", RegexOption.IGNORE_CASE) to
            { _, _ -> wallReport() },

        // ---- device ----
        Regex("^(?:/)?(?:sh|shell)\\s+(.+)", RegexOption.IGNORE_CASE) to
            { ctx, m -> shell(ctx, m.groupValues[1]) },

        Regex("^(?:/)?(?:shot|screenshot|screen|screencap)\\s*$", RegexOption.IGNORE_CASE) to
            { ctx, _ -> shot(ctx) },

        Regex("^(?:/)?(?:help|\\?|commands)\\s*$", RegexOption.IGNORE_CASE) to
            { _, _ -> help() },
    )

    /**
     * Returns a [Reply] when the text is one of ours, or null to let the model have it.
     * Never throws: a command that breaks reports its own failure instead of killing the send.
     */
    suspend fun handle(ctx: Context, text: String): Reply? {
        val line = text.trim()
        if (line.isEmpty()) return null
        for ((pattern, action) in TABLE) {
            val m = pattern.matchEntire(line) ?: continue
            return runCatching { action(ctx, m) }
                .getOrElse { t ->
                    Log.e(TAG, "local: ${pattern.pattern} threw: $t")
                    Reply("Command failed", "${t.javaClass.simpleName}: ${t.message}")
                }
        }
        return null
    }

    // ------------------------------------------------------------------ guard

    private suspend fun rotate(ctx: Context): Reply {
        val before = GuardState.status.value
        val t0 = System.currentTimeMillis()
        val reg = runCatching { RotationEngine.hopNow(ctx, "manual") }.getOrNull()
        val ms = System.currentTimeMillis() - t0
        if (reg == null) {
            return Reply(
                "Rotation did not complete",
                "The engine returned nothing after ${ms} ms. Check Guard for the edge and wall " +
                    "lines - a burn that is still young is honoured rather than ignored.",
            )
        }
        val after = GuardState.status.value
        val moved = after.currentV6 != before.currentV6
        return Reply(
            if (moved) "Rotated in ${ms} ms" else "Rotated, same prefix",
            buildString {
                append("before  ").append(before.currentV6).append('\n')
                append("after   ").append(after.currentV6)
                if (moved) append("   (new /64)") else append("   (unchanged)")
            },
        )
    }

    private fun status(): Reply {
        val s = GuardState.status.value
        return Reply(
            when {
                !s.vpnRunning -> "Guard is down"
                s.verified -> "Guard is up and verified"
                else -> "Guard is up, not yet verified"
            },
            buildString {
                append("v6        ").append(s.currentV6).append('\n')
                append("hops      ").append(s.hops).append("    walls ").append(s.walls).append('\n')
                append("streaming ").append(if (s.streaming) "yes" else "no")
                if (s.pausedUntilMs > System.currentTimeMillis()) {
                    val left = (s.pausedUntilMs - System.currentTimeMillis()) / 1000
                    append("\npaused    ").append(left).append("s left")
                }
            },
        )
    }

    private fun wallReport(): Reply {
        val s = GuardState.status.value
        val lastWall = GuardState.events.value.lastOrNull { it.kind == "wall" }
        return Reply(
            "Walls: ${s.walls} total",
            buildString {
                if (lastWall == null) {
                    append("No wall recorded since this process started.")
                } else {
                    append(lastWall.text)
                }
                append("\nv6        ").append(s.currentV6)
                append("\n\nA wall is answered once per message and remembered. If the chat is " +
                    "still stuck, type `rotate` - that is a manual hop and it outranks a " +
                    "machine-earned backoff.")
            },
        )
    }

    // ----------------------------------------------------------------- device

    private fun shell(ctx: Context, cmd: String): Reply {
        if (!Shell.alive() || !Shell.granted()) {
            return Reply(
                "No shell",
                "Shizuku binder=${Shell.alive()} granted=${Shell.granted()}.\n" +
                    "Start Shizuku, then tap Grant on the Shell tab.",
            )
        }
        val out = runBlocking { Shell.exec(ctx, cmd, 120_000) }
            ?: return Reply("No answer", "The shell service did not reply within 120 s.")
        return Reply(
            if (out.code == 0) "shell rc=0" else "shell rc=${out.code}",
            out.text.trimEnd().ifBlank { "(no output)" },
        )
    }

    private fun shot(ctx: Context): Reply {
        val path = "/sdcard/zapp_screen.png"
        val out = runBlocking { Shell.exec(ctx, "screencap -p $path && stat -c %s $path", 30_000) }
            ?: return Reply("No screen", "The shell service did not reply.")
        return Reply(
            if (out.code == 0) "Captured" else "Capture failed (rc=${out.code})",
            "$path\n${out.text.trim()}",
        )
    }

    private fun help(): Reply = Reply(
        "The app answers these itself - no model, no quota",
        """
        |rotate            hop to a new WARP /64        (also: hop, new ip)
        |status            tunnel, prefix, hops, walls   (also: guard)
        |wall              what the last wall said
        |sh <command>      run as uid 2000 via Shizuku
        |shot              capture the screen
        |
        |Anything else goes to the model as normal.
        """.trimMargin(),
    )

    /** Composer hint: the first word that would be handled locally, if any. */
    fun preview(text: String): String? {
        val line = text.trim()
        if (line.isEmpty()) return null
        for ((pattern, _) in TABLE) {
            if (pattern.matchEntire(line) != null) {
                return when {
                    pattern.pattern.startsWith("^(?:/)?(?:rotate") -> "Run rotate"
                    pattern.pattern.startsWith("^(?:/)?(?:status") -> "Run status"
                    pattern.pattern.startsWith("^(?:/)?(?:sh") -> "Run shell"
                    pattern.pattern.startsWith("^(?:/)?(?:shot") -> "Run capture"
                    pattern.pattern.startsWith("^(?:/)?(?:help") -> "Show commands"
                    else -> "Run wall check"
                }
            }
        }
        return null
    }
}
