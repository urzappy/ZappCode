package com.zapp.net.settings

import android.content.Context

/**
 * PORT of `shell/tabs/closed.ts`, persisted where they persist it
 * (`shell/tabs/tabs.tsx:237` — `Persist.window("tabs.closed")`).
 *
 * Their rules, copied rather than reinterpreted:
 *  - only session tabs are recorded; closing a draft deletes its state instead
 *    (`closed.ts:15-21`)
 *  - the stack keeps the last 100 entries (`closed.ts:11`)
 *  - an entry stores the index it was closed from (`closed.ts:25`), so a reopen returns
 *    to that slot (`tabs.tsx:345` — `Math.min(entry.index, store.length)`)
 *  - `listClosedTabs` walks the stack newest first, keeps one entry per session, and skips
 *    sessions that are open again (`closed.ts:39-59`)
 *  - the menu shows only entries that had a prompt (`recently-closed-tabs-menu.tsx:79`)
 *  - `removeClosedTabs` drops entries for deleted sessions (`closed.ts:129`)
 */
class ClosedTabs(context: Context) {

    data class Entry(
        val id: String,
        val index: Int,
        val closedAt: Long,
        val title: String?,
        val directory: String?,
        val prompted: Boolean,
    )

    private val sp = context.getSharedPreferences("zapp_tabs", Context.MODE_PRIVATE)

    fun list(): List<Entry> =
        sp.getString("closed", "").orEmpty().split(RECORD).filter { it.isNotBlank() }.mapNotNull { decode(it) }

    /** `pushClosedTab` (`closed.ts:19-33`): drop the older entry for the same tab, append, cap at 100. */
    fun push(entry: Entry) {
        save((list().filterNot { it.id == entry.id } + entry).takeLast(CLOSED_TAB_LIMIT))
    }

    /**
     * `takeClosedTab` (`closed.ts:73-111`): pops the most recent entry that is not open
     * again, or the entry naming `target`. Stale entries are discarded on the way.
     */
    fun take(open: List<String>, target: String? = null): Entry? {
        val keep = list().filterNot { it.id in open }
        val entry = if (target != null) keep.lastOrNull { it.id == target } else keep.lastOrNull()
        if (entry != null) save(keep.filterNot { it.id == entry.id })
        return entry
    }

    /** `removeClosedTabs` (`closed.ts:129`): a deleted session cannot be reopened. */
    fun remove(ids: Collection<String>) {
        val current = list()
        val next = current.filterNot { it.id in ids }
        if (next.size != current.size) save(next)
    }

    fun clear() = save(emptyList())

    /** `listClosedTabs` order: newest first, one per session, never one that is open. */
    fun menu(open: List<String>, limit: Int = 10): List<Entry> {
        val openSet = open.toSet()
        val seen = HashSet<String>()
        return list().asReversed().filter { seen.add(it.id) && it.id !in openSet }.take(limit)
    }

    private fun save(entries: List<Entry>) {
        sp.edit().putString("closed", entries.joinToString(RECORD.toString()) { encode(it) }).apply()
    }

    private fun encode(e: Entry): String = listOf(
        e.id,
        e.index.toString(),
        e.closedAt.toString(),
        e.title.orEmpty(),
        e.directory.orEmpty(),
        if (e.prompted) "1" else "0",
    ).joinToString(FIELD.toString())

    private fun decode(raw: String): Entry? {
        val f = raw.split(FIELD)
        if (f.size < 6 || f[0].isBlank()) return null
        return Entry(
            id = f[0],
            index = f[1].toIntOrNull() ?: 0,
            closedAt = f[2].toLongOrNull() ?: 0L,
            title = f[3].ifBlank { null },
            directory = f[4].ifBlank { null },
            prompted = f[5] == "1",
        )
    }

    private companion object {
        /** `closed.ts:11 CLOSED_TAB_LIMIT = 100` */
        const val CLOSED_TAB_LIMIT = 100
        const val FIELD = '\u001F'
        const val RECORD = '\u001E'
    }
}
