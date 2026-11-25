package com.zapp.net.guard

import android.content.Context

/**
 * Which failures have already been answered.
 *
 * `MainActivity.wallSeen` lived in memory inside a ViewModel, and the ViewModel is recreated
 * on every activity restart and reconnect. Measured 2026-11-20 01:37: the same stored
 * `provider.free-limit` (same message, `rows=16` unchanged) was re-handled at 01:37:21,
 * 01:37:25, 01:37:28 and 01:37:31, and each handling registered and installed a brand-new
 * WARP identity:
 *
 * ```
 * 01:37:21.611  messages: sid=ses_e0b1... rows=16
 * 01:37:24.116  tunnel UP: v6=2606:4700:110:84f4:...      <- identity 1 burned
 * 01:37:28.414  [hop] rotated #1 -> 2a09:bac1:5ac0:40::/64 <- identity 2
 * 01:37:31.134  tunnel UP: v6=2606:4700:110:8d0c:...      <- identity 3
 * ```
 *
 * Four identities in ten seconds, all for one error that was minutes old. A wall is an
 * *event*, not a state: it must be answered once, ever. This ledger is on disk so a process
 * restart, a reconnect, or the debug frame mirror cannot replay it.
 *
 * The fallback matters too. When a frame carries no `assistantMessageID` there is nothing to
 * key on, so the ledger refuses a second wall of the same type on the same session inside
 * [MIN_GAP_MS]. That is a rate limit rather than a dedupe, but it still bounds the damage.
 */
object WallLedger {

    private const val PREFS = "zapp_walls"
    private const val KEY = "handled"

    /** How long a handled key is remembered. The free window is ~7h; 12h clears it. */
    private const val RETAIN_MS = 12 * 60 * 60 * 1000L

    /** Minimum spacing between two id-less walls of the same type on the same session. */
    private const val MIN_GAP_MS = 90_000L

    @Volatile
    private var appCtx: Context? = null

    fun attach(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    private fun prefs(ctx: Context): android.content.SharedPreferences {
        val c = ctx.applicationContext
        appCtx = c
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /**
     * True only the first time this wall is seen.
     *
     * @param messageID the server's `assistantMessageID`; the stable identity of the failure
     * @param type the classified wall type, so a *different* refusal is still answerable
     */
    @Synchronized
    fun claim(ctx: Context, sessionID: String?, messageID: String?, type: String): Boolean {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val rows = p.getStringSet(KEY, emptySet()).orEmpty()
            .mapNotNull { decode(it) }
            .filter { now - it.second < RETAIN_MS }
            .toMutableList()

        val sid = sessionID.orEmpty()
        if (!messageID.isNullOrBlank()) {
            // ONE EVENT, ONE KEY.
            //
            // The type used to be part of the key, so one wall could be claimed several times.
            // Measured 23 Nov 18:29, the same stored error was claimed under three names:
            //
            //   ses_df80...|msg_207f...|text:Free usage limit reached   <- stored transcript
            //   ses_df80...|msg_207f...|provider.quota                   <- live frame
            //   ses_df80...|msg_207f...|provider.free-limit              <- same frame, narrowed
            //
            // Each claim rotated the tunnel. Each rotation tears the TUN down and rebuilds it,
            // and rebuilding it is what killed the stream mid-reply:
            //
            //   18:29:04  already answered ... text:Free usage limit reached
            //   18:29:09  [wall] wall:provider.quota
            //   18:29:16  [wall] wall:provider.free-limit
            //   18:29:21  [vpn] verify: no reading on 162.159.192.2:2408 -> re-roll
            //
            // The message id is the identity. A single assistant message carries at most one
            // error part, so two different types on the same message id are never two different
            // refusals - they are our own classifier disagreeing with itself, and the narrower
            // name (`provider.free-limit`) is a strict subset of the wider one
            // (`provider.quota`), which `QuotaWall.narrowByText` produces from the same bytes.
            // Keeping the type in the key lets our own naming split one event into three.
            //
            // The type is still logged so the reason survives; it just no longer names the row.
            val key = "$sid|$messageID"
            if (rows.any { it.first == key }) {
                GuardState.log(
                    "wall",
                    "already answered: $key ($type) — not hopping again " +
                        "(a wall is an event, not a state)",
                )
                // still prune what we kept so the file cannot grow forever
                write(p, rows)
                return false
            }
            rows.add(key to now)
            write(p, rows)
            return true
        }

        // No message id: fall back to a per-(session,type) rate limit.
        val prefix = "$sid||$type"
        val last = rows.filter { it.first == prefix }.maxOfOrNull { it.second } ?: 0L
        if (now - last < MIN_GAP_MS) {
            GuardState.log(
                "wall",
                "id-less wall of $type ${(now - last) / 1000}s after the last one — ignoring",
            )
            write(p, rows)
            return false
        }
        rows.add(prefix to now)
        write(p, rows)
        return true
    }

    private fun write(p: android.content.SharedPreferences, rows: List<Pair<String, Long>>) {
        p.edit().putStringSet(KEY, rows.map { "${it.second}#${it.first}" }.toSet()).apply()
    }

    private fun decode(row: String): Pair<String, Long>? {
        val at = row.indexOf('#')
        if (at <= 0) return null
        val ts = row.substring(0, at).toLongOrNull() ?: return null
        return row.substring(at + 1) to ts
    }
}
