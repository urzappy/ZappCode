package com.zapp.net.settings

import android.content.Context

/**
 * The review's opened diffs and its active file, per session.
 *
 * `gui-plugins/src/review/model.ts:1033-1041` - the session store owns `open` (the expanded
 * file list) and `file` (the active one); the narrow review reads them as props and reports
 * changes back through `onOpenChange` (`review/mobile.tsx:62-66`), so a session that is
 * reopened shows the same expanded diffs. Desktop persists that store; ZAPP keeps the same
 * two values per session id here.
 */
class ReviewOpen(ctx: Context) {

    private val sp = ctx.getSharedPreferences("zapp_review_open", Context.MODE_PRIVATE)

    fun files(session: String): Set<String> =
        sp.getStringSet(session, emptySet())?.toSet() ?: emptySet()

    fun saveFiles(session: String, files: Set<String>) {
        sp.edit().putStringSet(session, files).apply()
    }

    fun active(session: String): String? = sp.getString(key(session), null)

    fun saveActive(session: String, path: String?) {
        sp.edit().apply {
            if (path == null) remove(key(session)) else putString(key(session), path)
        }.apply()
    }

    private fun key(session: String) = "$session:file"
}
