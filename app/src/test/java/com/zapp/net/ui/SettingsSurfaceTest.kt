package com.zapp.net.ui

import com.zapp.net.settings.SettingsPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings surface's shape, checked without Android.
 *
 * These assertions are about **their** route table and **their** strings, not about pixels:
 *
 *  - `settings/route.ts` gives twelve root tabs; the shipped window adds `Pairing`, so the
 *    rail is thirteen entries and no key may repeat.
 *  - `settings/search-machine.ts` matches a row by title or description, case-insensitively,
 *    and returns nothing for an empty query - a settings search that lists everything on an
 *    empty box is not their behaviour.
 *  - `settings/model.tsx:601-659` gives the defaults; [SettingsPrefs] must agree with them or
 *    a fresh install would differ from a fresh PC install.
 */
class SettingsSurfaceTest {

    @Test
    fun `every page has a key title and description`() {
        for (p in Page.entries) {
            assertTrue("key blank for $p", p.key.isNotBlank())
            assertTrue("title blank for $p", p.title.isNotBlank())
            assertTrue("description blank for $p", p.description.isNotBlank())
        }
    }

    @Test
    fun `page keys are unique`() {
        val keys = Page.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `rail and tail cover every page exactly once`() {
        val all = Page.rail + Page.tail
        assertEquals(Page.entries.size, all.size)
        assertEquals(Page.entries.toSet(), all.toSet())
    }

    @Test
    fun `rail starts with preferences and ends with pairing`() {
        assertEquals(Page.PREFERENCES, Page.rail.first())
        assertEquals(Page.PAIRING, Page.rail.last())
        assertEquals(listOf(Page.EXPERIMENTAL, Page.ABOUT), Page.tail)
    }

    @Test
    fun `their root tab keys are present`() {
        // `settings/route.ts:48-61`, verbatim, plus the window's pairing page.
        val expected = setOf(
            "general", "appearance", "notifications", "shortcuts", "projects", "workspaces",
            "providers", "models", "extensions", "servers", "pairing", "experimental", "about",
        )
        assertEquals(expected, Page.entries.map { it.key }.toSet())
    }

    @Test
    fun `empty query finds nothing`() {
        assertTrue(SettingsSearch.hits("").isEmpty())
        assertTrue(SettingsSearch.hits("   ").isEmpty())
    }

    @Test
    fun `search matches a row title case insensitively`() {
        val hits = SettingsSearch.hits("SPELLCHECK")
        assertTrue(hits.any { it.view.tab == Page.PREFERENCES && it.title == SettingsCopy.SPELLCHECK })
    }

    @Test
    fun `search matches a row description`() {
        val hits = SettingsSearch.hits("misspelled")
        assertTrue(hits.any { it.view.tab == Page.PREFERENCES })
    }

    @Test
    fun `search finds a page by its own title`() {
        val hits = SettingsSearch.hits("worktrees")
        assertTrue(hits.any { it.view.tab == Page.WORKTREES })
    }

    @Test
    fun `search hits are unique`() {
        val hits = SettingsSearch.hits("e")
        assertEquals(hits.size, hits.map { it.id }.toSet().size)
    }

    @Test
    fun `every page has an index entry`() {
        val indexed = SettingsSearch.INDEX.map { it.view.tab }.toSet()
        for (p in Page.entries) {
            assertTrue("no index entry for $p", p in indexed)
        }
    }

    @Test
    fun `the result cap is theirs`() {
        assertEquals(60, SettingsSearch.MAX_RESULTS)
        assertTrue(SettingsSearch.hits("e").size <= SettingsSearch.MAX_RESULTS)
    }

    @Test
    fun `worktree parse tolerates every shape the server can send`() {
        assertTrue(WorktreeList.parse("").isEmpty())
        assertTrue(WorktreeList.parse("not json").isEmpty())
        assertTrue(WorktreeList.parse("{}").isEmpty())
        assertTrue(WorktreeList.parse("[]").isEmpty())
        assertTrue(WorktreeList.parse("[{}]").isEmpty())

        val one = WorktreeList.parse("""[{"name":"fix","directory":"/tmp/fix"}]""")
        assertEquals(1, one.size)
        assertEquals("fix", one[0].name)
        assertEquals("/tmp/fix", one[0].directory)

        // A row with no `name` falls back to the last path segment, never to an empty label.
        val noName = WorktreeList.parse("""[{"directory":"/tmp/a/b"}]""")
        assertEquals("b", noName[0].name)

        // `path` is accepted because older builds used it.
        val alt = WorktreeList.parse("""[{"path":"/tmp/c"}]""")
        assertEquals("/tmp/c", alt[0].directory)
    }

    @Test
    fun `defaults match defaultSettings`() {
        // settings/model.tsx:601-659
        assertEquals("left", SettingsPrefs.DEFAULT_BACKGROUND_ACTIVITY)
        assertEquals("comfortable", SettingsPrefs.DEFAULT_TIMELINE_WIDTH)
        assertEquals("steer", SettingsPrefs.DEFAULT_FOLLOW_UP)
        assertEquals("top", SettingsPrefs.DEFAULT_TITLEBAR_POSITION)
        assertEquals("list", SettingsPrefs.DEFAULT_SIDEBAR_ORGANIZE)
        assertEquals("horizontal", SettingsPrefs.DEFAULT_TAB_LAYOUT)
        assertEquals("system", SettingsPrefs.DEFAULT_REDUCE_MOTION)
        assertEquals("last-used", SettingsPrefs.DEFAULT_DESTINATION)
        assertEquals(14, SettingsPrefs.DEFAULT_FONT_SIZE)
        // The sound ids are not `SettingsPrefs` business any more - the chime reads them from
        // `NotifySettings`, so that is where the defaults are pinned.
        assertEquals("staplebops-01", com.zapp.net.notify.NotifySettings().idAgent)
        assertEquals("staplebops-02", com.zapp.net.notify.NotifySettings().idPermissions)
        assertEquals("staplebops-05", com.zapp.net.notify.NotifySettings().idQuestions)
        assertEquals("nope-03", com.zapp.net.notify.NotifySettings().idErrors)
    }

    @Test
    fun `sound labels round trip`() {
        assertEquals("Staplebops 01", SettingsCopy.soundLabel("staplebops-01"))
        assertEquals("Nope 03", SettingsCopy.soundLabel("nope-03"))
        assertEquals("None", SettingsCopy.soundLabel("none"))
        // An id this build does not know is printed as-is rather than replaced by a guess.
        assertEquals("mystery-99", SettingsCopy.soundLabel("mystery-99"))
    }

    @Test
    fun `sound catalogue has no duplicate ids`() {
        val ids = SettingsCopy.soundOptions.map { it.first }
        assertEquals(ids.size, ids.toSet().size)
        assertFalse(ids.isEmpty())
    }

    @Test
    fun `interface layout maps both ways`() {
        for (label in listOf(SettingsCopy.TOP_TABS, SettingsCopy.SIDE_TABS, SettingsCopy.INBOX)) {
            assertTrue(interfaceLabel(interfaceId(label)) == label)
        }
    }

    @Test
    fun `default environment maps both ways`() {
        for (label in listOf(SettingsCopy.LAST_USED, SettingsCopy.LOCAL_DIR, SettingsCopy.NEW_WORKTREE)) {
            assertEquals(label, destinationLabel(destinationId(label)))
        }
    }

    @Test
    fun `their page descriptions are the shipped ones`() {
        assertEquals("Customize preferences and theme and default behavior", Page.PREFERENCES.description)
        assertEquals("Customize theme and fonts", Page.APPEARANCE.description)
        assertEquals("Choose when to receive notifications and hear sounds", Page.NOTIFICATIONS.description)
        assertEquals("Customize shortcuts for common actions", Page.SHORTCUTS.description)
        assertEquals("Manage projects and their settings", Page.PROJECTS.description)
        assertEquals("Review worktrees and manage disk usage", Page.WORKTREES.description)
        assertEquals("Connect and manage model providers", Page.PROVIDERS.description)
        assertEquals("Choose which models appear in model picker", Page.MODELS.description)
        assertEquals("Manage extensions available on this server", Page.EXTENSIONS.description)
        assertEquals("Manage this server's connection and preferences", Page.SERVERS.description)
        assertEquals("Try experimental features", Page.EXPERIMENTAL.description)
        assertEquals("OpenCode, the open source coding agent", Page.ABOUT.description)
    }
}
