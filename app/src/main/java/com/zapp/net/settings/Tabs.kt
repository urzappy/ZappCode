package com.zapp.net.settings

import android.content.Context

/**
 * The open tab list, ported from `shell/tabs/tabs.tsx` (`Persist.window("tabs")`).
 *
 * Their active tab is `open.find(tab => tabKey(tab) === recentKey()) ?? open.at(-1)`
 * (`tabs.tsx:919`), so the fallback when `tabs.recent` is missing is the **last open
 * tab**, not nothing and not "newest session by update time". We persist the order the
 * same way: ids on one line each, last = most recently opened.
 */
class Tabs(context: Context) {

    private val sp = context.getSharedPreferences("zapp_tabs", Context.MODE_PRIVATE)

    fun list(): List<String> =
        sp.getString("open", "").orEmpty().split('\n').filter { it.isNotBlank() }

    /** `newDraft` (`tabs.tsx:1105-1111`): `tabs.push(tab)` — the draft appears at the end. */
    fun touch(id: String) {
        val next = (list() - id) + id
        sp.edit().putString("open", next.joinToString("\n")).apply()
    }

    fun remove(id: String) {
        val next = list() - id
        sp.edit().putString("open", next.joinToString("\n")).apply()
    }

    /** `addSessionTab` (`tabs.tsx:993-1039`): an open tab keeps its position; a new one is pushed. */
    fun ensure(id: String) {
        val current = list()
        if (id in current) return
        sp.edit().putString("open", (current + id).joinToString("\n")).apply()
    }

    /** `tabs.tsx:345`: `store.splice(index, 0, tab)` — a reopened tab returns to its old slot. */
    fun insert(id: String, index: Int) {
        val current = list()
        if (id in current) return
        val next = current.toMutableList()
        next.add(index.coerceIn(0, next.size), id)
        sp.edit().putString("open", next.joinToString("\n")).apply()
    }
}
