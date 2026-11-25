package com.zapp.net.settings

import android.content.Context

/**
 * Where the narrow review's scroll position lives between visits.
 *
 * Desktop keeps it in its layout storage, which is why `ReviewScrollContext.layoutReady` exists:
 * a restore must not run before that storage can be read. On the phone the store is one
 * `SharedPreferences` file keyed by session id, which is always readable, so `layoutReady` is
 * `true` from the first frame - the branch is still ported because it is theirs.
 *
 * The value is a **list index**, not a pixel offset: the row heights change with wrapping, so an
 * offset would land in a different place on every re-measure.
 */
object ReviewScrollStore {
    private const val FILE = "zapp_review_scroll"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(ctx: Context, sessionID: String): Int =
        prefs(ctx).getInt(sessionID, 0).coerceAtLeast(0)

    fun put(ctx: Context, sessionID: String, index: Int) {
        if (sessionID.isBlank()) return
        prefs(ctx).edit().putInt(sessionID, index.coerceAtLeast(0)).apply()
    }

    fun clear(ctx: Context, sessionID: String) {
        prefs(ctx).edit().remove(sessionID).apply()
    }
}
