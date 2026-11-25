package com.zapp.net.settings

import android.content.Context

/**
 * The review mode a session last chose, ported from `gui-plugins/src/review/model.ts:411`.
 *
 * Their rule: the stored choice is shown while the session still offers it; when it is not
 * offered (Branch after returning to the default branch) the panel falls back to the first
 * offered mode, and the stored choice stays so it returns when the mode reappears.
 *
 * Backend is its own SharedPreferences file for the same reason as `Drafts`: typing or
 * tapping the rail must never re-emit the settings flow.
 */
class ReviewMode(context: Context) {

    private val sp = context.getSharedPreferences("zapp_review", Context.MODE_PRIVATE)

    /** `stored()?.mode ?? "git"` — the default is Git, not "the first offered mode". */
    fun get(scope: String): String = sp.getString(scope, "git").orEmpty().ifBlank { "git" }

    fun put(scope: String, mode: String) {
        sp.edit().putString(scope, mode).apply()
    }
}
