package com.zapp.net.settings

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * `ui.promptInput.history` - "Prompt history", ported from `dropzone-DPkZ7Cwn.js`.
 *
 * What theirs does, exactly:
 *  - `entries(query)` is newest-first; each entry is `{ prompt: parts[], metadata }`
 *  - `capture()` snapshots the *current* composer (text + parts + cursor) before stepping, so
 *    stepping back to index 0 (`history.restore`) puts his unsent draft back untouched
 *  - a send appends one entry; the list is per session, not global
 *  - the popover filters by the typed query and shows each entry's filename parts as its
 *    description (`e.prompt.flatMap(e => J(e) ? [e.filename] : []).join(', ')`)
 *
 * Backend is SharedPreferences like [Drafts]: one JSON array per scope, newest first.
 */
class PromptHistory(context: Context) {

    private val sp = context.getSharedPreferences("zapp_prompt_history", Context.MODE_PRIVATE)

    /** `entries()` - newest first. */
    fun entries(scope: String): List<Entry> {
        val raw = sp.getString(scope, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text", "")
                if (text.isBlank()) null else Entry(text, o.optString("files", ""))
            }
        }.getOrDefault(emptyList())
    }

    /** Appends one sent prompt. The wire form is not stored - the visible text is. */
    fun add(scope: String, text: String, files: String = "") {
        val t = text.trim()
        if (t.isEmpty()) return
        val list = entries(scope).filterNot { it.text == t }.toMutableList()
        list.add(0, Entry(t, files))
        while (list.size > MAX) list.removeAt(list.size - 1)
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().put("text", e.text).put("files", e.files))
        }
        sp.edit().putString(scope, arr.toString()).apply()
    }

    fun clear(scope: String) {
        sp.edit().remove(scope).apply()
    }

    data class Entry(val text: String, val files: String)

    private companion object {
        /** Their store is bounded; 100 prompts is far past anything a phone will scroll. */
        const val MAX = 100
    }
}
