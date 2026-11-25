package com.zapp.net.guard

import android.content.Context

/**
 * The reference guard's `pending` / `resumed` maps (`zen_guard.py`, `_resumable`,
 * `_dispatch_auto_resume`), ported to the phone.
 *
 * Their rule, kept exactly: a wall marks the *chat that hit it*; a proven new address
 * marks the moment it landed; a chat is resumable while the last proof is newer than
 * the last time that chat was continued. Nothing is sent to a chat that already got its
 * "continue" for this address, and nothing is sent on an address that was never proven.
 *
 * Entries older than [MAX_AGE_MS] are dropped, as theirs are (`PENDING_MAX_AGE`).
 */
object ResumeQueue {

    private const val FILE = "zapp_resume"
    private const val KEY_PENDING = "pending"
    private const val KEY_RESUMED = "resumed"
    private const val KEY_LAST_OK = "lastOkHopMs"

    /** Their `PENDING_MAX_AGE = 30 * 60`. */
    const val MAX_AGE_MS = 30 * 60_000L

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun mark(ctx: Context, sessionId: String, atMs: Long = System.currentTimeMillis()) {
        if (sessionId.isBlank()) return
        val p = sp(ctx)
        val cur = p.getString(KEY_PENDING, "").orEmpty()
        val map = decode(cur).toMutableMap()
        // their `pending.setdefault(sid, now)`: first sighting wins
        if (!map.containsKey(sessionId)) map[sessionId] = atMs
        p.edit().putString(KEY_PENDING, encode(map)).apply()
        prune(ctx)
    }

    /** Called only after the tunnel proved a fresh address through the egress probe. */
    @Synchronized
    fun markProven(ctx: Context, atMs: Long = System.currentTimeMillis()) {
        sp(ctx).edit().putLong(KEY_LAST_OK, atMs).apply()
    }

    fun lastOkHopMs(ctx: Context): Long = sp(ctx).getLong(KEY_LAST_OK, 0L)

    /** Chats that hit a wall and have not been continued since the last proven address. */
    @Synchronized
    fun resumable(ctx: Context): List<String> {
        val p = sp(ctx)
        val lastOk = p.getLong(KEY_LAST_OK, 0L)
        if (lastOk <= 0L) return emptyList()
        val resumed = decode(p.getString(KEY_RESUMED, "").orEmpty())
        return decode(p.getString(KEY_PENDING, "").orEmpty())
            .filter { (sid, _) -> (resumed[sid] ?: 0L) < lastOk }
            .keys.toList()
    }

    @Synchronized
    fun markResumed(ctx: Context, sessionIds: Collection<String>, atMs: Long = System.currentTimeMillis()) {
        val p = sp(ctx)
        val resumed = decode(p.getString(KEY_RESUMED, "").orEmpty()).toMutableMap()
        val pending = decode(p.getString(KEY_PENDING, "").orEmpty()).toMutableMap()
        sessionIds.forEach {
            resumed[it] = atMs
            pending.remove(it)
        }
        p.edit()
            .putString(KEY_RESUMED, encode(resumed))
            .putString(KEY_PENDING, encode(pending))
            .apply()
    }

    @Synchronized
    fun prune(ctx: Context) {
        val p = sp(ctx)
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        val pending = decode(p.getString(KEY_PENDING, "").orEmpty()).filterValues { it >= cutoff }
        val resumed = decode(p.getString(KEY_RESUMED, "").orEmpty()).filterValues { it >= cutoff }
        p.edit()
            .putString(KEY_PENDING, encode(pending))
            .putString(KEY_RESUMED, encode(resumed))
            .apply()
    }

    // "sid=ts;sid=ts" — sessions are opaque and never contain '=' or ';'
    private fun encode(m: Map<String, Long>): String =
        m.entries.joinToString(";") { "${it.key}=${it.value}" }

    private fun decode(s: String): Map<String, Long> {
        if (s.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, Long>()
        s.split(';').forEach { row ->
            val i = row.lastIndexOf('=')
            if (i > 0) {
                val ts = row.substring(i + 1).toLongOrNull() ?: return@forEach
                out[row.substring(0, i)] = ts
            }
        }
        return out
    }
}
