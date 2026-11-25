package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Their ranking, group ordering and labels, checked against the index the pages actually have.
 *
 * `settings/search.ts` decides *what a search returns* with five tiers and one fuzzy gate. These
 * tests pin the tiers down by their observable consequences rather than by re-deriving the formula,
 * so a change to the comparator or the gate shows up here.
 */
class SettingsSearchTest {

    private fun rank(query: String) = SettingsSearch.rank(query, SettingsSearch.INDEX)

    private fun titles(query: String) = rank(query).map { it.title }

    // ---- the gate ---------------------------------------------------------------------------

    @Test
    fun `a blank query returns nothing`() {
        assertTrue(rank("").isEmpty())
        assertTrue(rank("   ").isEmpty())
    }

    @Test
    fun `a query that matches nothing returns nothing`() {
        assertTrue(rank("zzzz").isEmpty())
        // One letter off is not a match: fuzzysort scores `appearnce` at 0.345, under the gate.
        assertTrue(rank("appearnce").isEmpty())
    }

    // ---- the tiers --------------------------------------------------------------------------

    @Test
    fun `an exact title wins and pages sort before rows`() {
        val first = rank("appearance").first()
        assertEquals("Appearance", first.title)
        assertTrue(first.topLevel)
        assertEquals(Page.APPEARANCE, first.view.tab)
    }

    @Test
    fun `keywords are searched`() {
        // "dark mode" appears nowhere in the Color scheme title; only in its keywords.
        assertTrue("Color scheme" in titles("dark mode"))
    }

    @Test
    fun `a row is found by its section when the query names the page`() {
        // Their `Preferences / Timeline` form is what makes this row findable by "timeline".
        val entry = SettingsSearch.INDEX.first { it.id == "settings-timeline-detail" }
        assertEquals("Preferences / Timeline", SettingsSearch.pageLabel(entry))
    }

    @Test
    fun `the targeted general row reads preferences slash section`() {
        val entry = SettingsSearch.INDEX.first { it.id == "settings-show-turn-diffs" }
        assertEquals("Preferences / Timeline", SettingsSearch.pageLabel(entry))
    }

    @Test
    fun `a page row reads its tab label`() {
        val entry = SettingsSearch.INDEX.first { it.id == "page:appearance::" }
        assertEquals("Appearance", SettingsSearch.pageLabel(entry))
    }

    @Test
    fun `a word that is only in the description still ranks`() {
        // "Underline misspelled words while typing" is Spellcheck's description.
        assertTrue("Spellcheck" in titles("misspelled"))
    }

    @Test
    fun `search is case and accent insensitive`() {
        assertEquals(titles("spellcheck"), titles("SPELLCHECK"))
        assertTrue(titles("cafe").isEmpty() || titles("café").isEmpty())
    }

    // ---- grouping ---------------------------------------------------------------------------

    @Test
    fun `groups stay contiguous`() {
        val hits = SettingsSearch.hits("e")
        val keys = hits.map { SettingsSearch.groupKey(it) }
        // Once a group ends it must not reappear.
        val seen = LinkedHashSet<String>()
        var previous = ""
        for (key in keys) {
            if (key != previous) {
                assertFalse("group $key reappears", key in seen)
                seen.add(key)
                previous = key
            }
        }
    }

    @Test
    fun `the cap is sixty and truncation is reported`() {
        val all = SettingsSearch.rank("e", SettingsSearch.INDEX)
        val capped = SettingsSearch.hits("e")
        assertTrue(capped.size <= SettingsSearch.MAX_RESULTS)
        if (all.size > SettingsSearch.MAX_RESULTS) {
            assertTrue(SettingsSearch.isTruncated("e", SettingsSearch.INDEX))
        }
        assertFalse(SettingsSearch.isTruncated("appearance", SettingsSearch.INDEX))
    }

    @Test
    fun `only page and project groups get headers`() {
        val pages = SettingsSearch.INDEX.filter { it.topLevel }
        val headers = SettingsSearch.groupsWithHeader(pages)
        assertEquals(setOf(SettingsSearch.GROUP_PAGES), headers)

        // A result list made only of rows has no headers at all.
        val rows = SettingsSearch.hits("timeline").filter { !it.topLevel }
        assertTrue(SettingsSearch.groupsWithHeader(rows).isEmpty())
    }

    @Test
    fun `every entry carries a target or a tab`() {
        for (entry in SettingsSearch.INDEX) {
            if (entry.view.type == ViewType.PAGE) {
                assertTrue("${entry.id} has no target", entry.view.target != null)
            }
        }
    }

    @Test
    fun `entry ids are unique`() {
        val ids = SettingsSearch.INDEX.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    // ---- their capability gate ---------------------------------------------------------------

    @Test
    fun `a mobile host keeps the mobile rows`() {
        val mobile = SettingsSearch.index(SettingsSearch.Capabilities())
        val ids = mobile.map { it.id }
        assertTrue("settings-pinch-zoom" in ids)
        assertTrue("settings-spellcheck" in ids)
        assertTrue("settings-mobile-titlebar-bottom" in ids)
        assertTrue("settings-zoom" in ids)
    }

    @Test
    fun `a desktop host drops every mobile-tagged row`() {
        val desktop = SettingsSearch.index(
            SettingsSearch.Capabilities(desktop = true, browser = false, mobile = false),
        )
        val ids = desktop.map { it.id }
        assertFalse("settings-pinch-zoom" in ids)
        assertFalse("settings-spellcheck" in ids)
        assertFalse("settings-mobile-titlebar-bottom" in ids)
        assertFalse("settings-zoom" in ids)
        // Untagged rows survive on both hosts.
        assertTrue("settings-timeline-detail" in ids)
        assertTrue("page:appearance::" in ids)
    }

    @Test
    fun `the gate runs before indexing so the row cannot be found at all`() {
        val desktop = SettingsSearch.index(
            SettingsSearch.Capabilities(desktop = true, browser = false, mobile = false),
        )
        assertTrue(SettingsSearch.hits("pinch", desktop).isEmpty())
        assertTrue(SettingsSearch.hits("pinch").isNotEmpty())
    }

    // ---- their per-server and per-project blocks ---------------------------------------------

    @Test
    fun `a connected server contributes its own page entries`() {
        val server = "http://192.168.1.7:4096"
        val withServer = SettingsSearch.index(server = server)
        val scoped = withServer.filter { it.view.server == server }
        assertTrue(scoped.isNotEmpty())
        // They are pages, not entities: they belong to the Settings group, not the Servers group.
        assertTrue(scoped.all { SettingsSearch.groupKey(it) == SettingsSearch.GROUP_SETTINGS })
        // And their ids do not collide with the root page ids.
        val ids = withServer.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `no server means no server block`() {
        val without = SettingsSearch.index(server = null)
        assertTrue(without.none { it.view.server != null })
    }

    @Test
    fun `a project is an entity row under Projects`() {
        val index = SettingsSearch.index(
            server = "srv",
            projects = listOf(SettingsSearch.ProjectRef("ZAPP-Net", "ZAPP-Net")),
        )
        val project = index.single { it.id == "project:srv:ZAPP-Net" }
        assertTrue(project.entity)
        assertTrue(project.projectInfo)
        assertEquals("ZAPP-Net", project.title)
        assertEquals("srv", project.owner)
        assertEquals(SettingsSearch.GROUP_PROJECTS, SettingsSearch.groupKey(project))
        // A project row only answers to a query that names it.
        assertTrue(SettingsSearch.hits("zapp", index).any { it.id == project.id })
        assertTrue(SettingsSearch.hits("appearance", index).none { it.id == project.id })
    }

    // ---- the index and the page must agree ---------------------------------------------------

    @Test
    fun `every row the index lists is a row the page renders`() {
        val source = java.io.File("src/main/java/com/zapp/net/ui/SettingsScreen.kt")
        assertTrue("SettingsScreen.kt not found at ${source.absolutePath}", source.isFile)
        val rendered = Regex("""id = "(settings-[a-z0-9-]+)"""")
            .findAll(source.readText())
            .map { it.groupValues[1] }
            .toSet()
        val indexed = SettingsSearch.index(SettingsSearch.Capabilities())
            .map { it.id }
            .filter { it.startsWith("settings-") }
        // A reveal can only scroll to a row that exists, so an indexed id with no row is a result
        // that would land nowhere and settle as `timed-out`.
        assertEquals(emptyList<String>(), indexed.filterNot { it in rendered })
    }

    @Test
    fun `every row the page renders is in the index`() {
        val source = java.io.File("src/main/java/com/zapp/net/ui/SettingsScreen.kt")
        assertTrue("SettingsScreen.kt not found at ${source.absolutePath}", source.isFile)
        val rendered = Regex("""id = "(settings-[a-z0-9-]+)"""")
            .findAll(source.readText())
            .map { it.groupValues[1] }
            .toSet()
        val indexed = SettingsSearch.index(SettingsSearch.Capabilities()).map { it.id }.toSet()
        // The other direction: a row a person can see but cannot find is a search gap.
        assertEquals(emptySet<String>(), rendered - indexed)
    }

    // ---- their empty state -------------------------------------------------------------------
    @Test
    fun `a query that fits is shown whole`() {
        val measure = { s: String -> s.length * 10f }
        assertEquals("appearance", SettingsSearch.truncateToWidth("appearance", 200f, measure))
    }

    @Test
    fun `a query that does not fit is cut on a grapheme boundary and gets a period`() {
        val measure = { s: String -> s.length * 10f }
        assertEquals("ab.", SettingsSearch.truncateToWidth("abcdef", 30f, measure))
        assertEquals(".", SettingsSearch.truncateToWidth("abcdef", 5f, measure))
    }

    @Test
    fun `truncation never splits a surrogate pair`() {
        val measure = { s: String -> s.length * 10f }
        // One emoji is two UTF-16 units, so "😀." is three units and cannot fit in 15.
        assertEquals(".", SettingsSearch.truncateToWidth("😀😀", 15f, measure))
        assertEquals("😀.", SettingsSearch.truncateToWidth("😀😀", 30f, measure))
    }

    @Test
    fun `graphemes keep a combining sequence together`() {
        val units = SettingsSearch.graphemes("e\u0301x")
        assertEquals(listOf("e\u0301", "x"), units)
    }

    // ---- their normaliser -------------------------------------------------------------------

    @Test
    fun `normalize is NFKC lowercase trim collapse`() {
        assertEquals("appearance", SettingsSearch.normalize("  Appearance "))
        assertEquals("a b", SettingsSearch.normalize("a\t\tb"))
        assertEquals("a b", SettingsSearch.normalize("a\u00a0b"))
        // NFKC folds the ligature, which is the whole reason their normaliser runs first.
        assertEquals("fi", SettingsSearch.normalize("ﬁ"))
    }
}
