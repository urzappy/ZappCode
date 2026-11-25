package com.zapp.net.settings

import android.content.Context

/**
 * Composer drafts, ported from `composer/state.ts` + `runtime/persistence/storage.ts`.
 *
 * Their behaviour, not their backend:
 *  - one draft per scope: a session id, or the draft tab (`Persist.draft(draftID, "prompt")`)
 *  - saved 100 ms after the last edit (`persistSaveDelay = 100`)
 *  - a send resets the store to `DEFAULT_PROMPT`, which persists as an empty draft
 *  - closing the tab removes every persisted draft key for it (`removeDraftPersisted`)
 *
 * Backend is a plain SharedPreferences file: synchronous, and it never re-emits the
 * settings flow (which triggers reconnect logic on every emission).
 */
class Drafts(context: Context) {

    private val sp = context.getSharedPreferences("zapp_drafts", Context.MODE_PRIVATE)

    fun get(scope: String): String = sp.getString(scope, "").orEmpty()

    /** An empty draft is deleted, matching their removal of the default prompt value. */
    fun put(scope: String, text: String) {
        val e = sp.edit()
        if (text.isEmpty()) e.remove(scope) else e.putString(scope, text)
        e.apply()
    }

    fun remove(scope: String) {
        sp.edit().remove(scope).apply()
    }
}
