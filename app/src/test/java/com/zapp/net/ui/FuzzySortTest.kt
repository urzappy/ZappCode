package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Differential test for [FuzzySort] against the **real** `fuzzysort@3.1.0`.
 *
 * Every expected value below was produced by running the shipped library in Node 26 on
 *
 *     fuzzysort.single(R(query), R(target)).score
 *
 * where `R` is `settings/search.ts`'s normaliser (`NFKC`, lowercase, trim, collapse whitespace) -
 * the same two calls their ranking makes. The values are not hand-written and not derived from
 * reading the algorithm; they are the library's own output, so a mistake in the port shows up as a
 * numeric mismatch rather than as a plausible-looking number.
 *
 * 393 cases: all 248 that score at all (including every one on either side of the `0.6` gate their
 * ranking uses), plus an even sample of the 5 083 rejections.
 */
class FuzzySortTest {

    private data class Case(val query: String, val target: String, val expected: Double?)

    /** Their ranking scores `single(R(query), R(target))`; the corpus stores the raw strings. */
    private fun n(text: String): String = SettingsSearch.normalize(text)

    private val cases = listOf(
        Case("a", "Preferences", null),
        Case("a", "Help and feedback", 0.752164868361),
        Case("a", "Appearance", 0.811790334111),
        Case("a", "Notifications", 0.378563622856),
        Case("a", "Keyboard shortcuts", 0.411985225296),
        Case("a", "About", 0.866273143171),
        Case("a", "Experimental", 0.345078071381),
        Case("a", "Language", 0.596355974238),
        Case("a", "Interface mode", 0.393665242882),
        Case("a", "Default environment", 0.466116913425),
        Case("a", "Auto-accept permissions", 0.748878695473),
        Case("a", "Follow-up behavior", 0.321740443410),
        Case("a", "Timeline detail", 0.328744551470),
        Case("a", "Background activity", 0.703154580020),
        Case("a", "Collapse long messages", 0.435088069971),
        Case("a", "Show custom agents", 0.697466000143),
        Case("a", "Sidebar organize", 0.412005645558),
        Case("a", "Interface font", 0.393665242882),
        Case("a", "Terminal font", 0.393672135116),
        Case("a", "Agent", 0.866273143171),
        Case("a", "Interface sounds", 0.393651461516),
        Case("a", "Workspaces", 0.393692818032),
        Case("ap", "Appearance", 0.863755933409),
        Case("ap", "Auto-accept permissions", 0.683566997035),
        Case("ap", "Timeline detail", null),
        Case("ap", "Collapse long messages", 0.525165547636),
        Case("app", "Appearance", 0.874098657890),
        Case("app", "Language", null),
        Case("app", "Auto-accept permissions", 0.294197484882),
        Case("app", "Shell", null),
        Case("appe", "Appearance", 0.884007761208),
        Case("appe", "Auto-accept permissions", 0.294198113073),
        Case("appe", "Providers", null),
        Case("appear", "Appearance", 0.907598582378),
        Case("appear", "Reduce motion", null),
        Case("appearance", "Appearance", 1.000000000000),
        Case("appearance", "New question UI", null),
        Case("appearnce", "Appearance", 0.345100833175),
        Case("appearnce", "Spellcheck", null),
        Case("appearanc", "Appearance", 0.965375762179),
        Case("appearanc", "About", null),
        Case("appearanc", "MCP servers", null),
        Case("pref", "Preferences", 0.874505336015),
        Case("pref", "Errors", null),
        Case("prefs", "Preferences", 0.329999584118),
        Case("prefs", "Interface font", null),
        Case("preference", "Preferences", 0.965377192000),
        Case("preference", "Collapse long messages", null),
        Case("prefer", "Preferences", 0.895006046065),
        Case("prefer", "Default environment", null),
        Case("perf", "Preferences", null),
        Case("perf", "Workspaces", null),
        Case("perm", "Experimental", 0.345542579414),
        Case("perm", "Auto-accept permissions", 0.807061907587),
        Case("perm", "Reduce motion", null),
        Case("perm", "Permissions", 0.874505336015),
        Case("permission", "Auto-accept permissions", 0.834134314127),
        Case("permission", "Sidebar organize", null),
        Case("permission", "Permissions", 0.965377192000),
        Case("permissions", "Auto-accept permissions", 0.839560640153),
        Case("permissions", "Background activity", null),
        Case("permissions", "Permissions", 1.000000000000),
        Case("auto", "Language", null),
        Case("auto", "Default environment", 0.298313815614),
        Case("auto", "Auto-accept permissions", 0.807786906388),
        Case("accept", "Preferences", null),
        Case("accept", "Auto-accept permissions", 0.815676062344),
        Case("accept", "Providers", null),
        Case("autoaccept", "Auto-accept permissions", 0.724719153152),
        Case("autoaccept", "Reduce motion", null),
        Case("auto accept", "Auto-accept permissions", 0.811625530651),
        Case("auto accept", "New question UI", null),
        Case("auto-accept", "Auto-accept permissions", 0.839582374889),
        Case("auto-accept", "Spellcheck", null),
        Case("ac", "Help and feedback", 0.389479429478),
        Case("ac", "Appearance", 0.331605402498),
        Case("ac", "Keyboard shortcuts", 0.315570655299),
        Case("ac", "Language", null),
        Case("ac", "Interface mode", 0.478872940605),
        Case("ac", "Auto-accept permissions", 0.797017895132),
        Case("ac", "Background activity", 0.805802517905),
        Case("ac", "Interface font", 0.478872940605),
        Case("ac", "Interface sounds", 0.478826197083),
        Case("ac", "Workspaces", 0.478966552663),
        Case("aa", "Help and feedback", 0.315571753796),
        Case("aa", "Appearance", 0.347424994615),
        Case("aa", "Experimental", null),
        Case("aa", "Language", 0.344636675432),
        Case("aa", "Auto-accept permissions", 0.703485184051),
        Case("aa", "Background activity", 0.323850051887),
        Case("aa", "Collapse long messages", 0.308365647225),
        Case("aa", "Sidebar organize", 0.322785249940),
        Case("color", "Appearance", null),
        Case("color", "Color scheme", 0.874628946804),
        Case("color", "Extensions", null),
        Case("colour", "Agent", null),
        Case("scheme", "New question UI", null),
        Case("scheme", "Color scheme", 0.884066846312),
        Case("the", "Spellcheck", null),
        Case("the", "Theme", 0.940775654242),
        Case("them", "About", null),
        Case("them", "Theme", 0.965282411732),
        Case("them", "Plugins", null),
        Case("theme", "Theme", 1.000000000000),
        Case("theme", "Interface sounds", null),
        Case("t", "Notifications", 0.512160666116),
        Case("t", "Keyboard shortcuts", 0.321740443410),
        Case("t", "About", 0.435368897244),
        Case("t", "Experimental", 0.354757499854),
        Case("t", "Interface mode", 0.512088293879),
        Case("t", "Default environment", 0.393630797225),
        Case("t", "Auto-accept permissions", 0.511441200713),
        Case("t", "Pinch to zoom", 0.758497927710),
        Case("t", "Timeline detail", 0.780812663854),
        Case("t", "Background activity", 0.321739159230),
        Case("t", "Timeline width", 0.786036793027),
        Case("t", "Show turn diffs", 0.759238107172),
        Case("t", "Show custom agents", 0.365767777076),
        Case("t", "New timeline", 0.779811947968),
        Case("t", "New question UI", 0.365778860918),
        Case("t", "Theme", 0.866273143171),
        Case("t", "Interface font", 0.512088293879),
        Case("t", "Code font", 0.365801037061),
        Case("t", "Terminal font", 0.791669731391),
        Case("t", "Reduce motion", 0.354754638293),
        Case("t", "Agent", 0.435368897244),
        Case("t", "Questions", 0.435302675898),
        Case("t", "Interface sounds", 0.511943834880),
        Case("t", "Projects", 0.393706611822),
        Case("t", "Extensions", 0.512378356688),
        Case("te", "About", null),
        Case("te", "Interface mode", 0.610181231702),
        Case("te", "Default environment", 0.329668072295),
        Case("te", "Auto-accept permissions", 0.333601768997),
        Case("te", "Timeline detail", 0.352522154727),
        Case("te", "Timeline width", 0.352524870038),
        Case("te", "Show custom agents", 0.310624634638),
        Case("te", "New timeline", 0.336477442337),
        Case("te", "Theme", 0.358362155989),
        Case("te", "Interface font", 0.610181231702),
        Case("te", "Terminal font", 0.842800647534),
        Case("te", "Interface sounds", 0.609715386823),
        Case("te", "Extensions", 0.611123626843),
        Case("ter", "Interface mode", 0.654266020934),
        Case("ter", "Default environment", 0.298033703062),
        Case("ter", "Auto-accept permissions", 0.293083575949),
        Case("ter", "Follow-up behavior", null),
        Case("ter", "Interface font", 0.654266020934),
        Case("ter", "Terminal font", 0.851099632654),
        Case("ter", "Interface sounds", 0.653408525030),
        Case("font", "About", null),
        Case("font", "Default environment", 0.284915748574),
        Case("font", "Interface font", 0.850623111255),
        Case("font", "Code font", 0.894447459372),
        Case("font", "Terminal font", 0.857638827012),
        Case("fnt", "Preferences", null),
        Case("fnt", "Default environment", 0.285905152936),
        Case("fnt", "Interface font", 0.320235957191),
        Case("fnt", "Code font", 0.335031320576),
        Case("fnt", "Terminal font", 0.314280541607),
        Case("fnt", "Servers", null),
        Case("fnot", "Default environment", 0.271265319333),
        Case("fnot", "Questions", null),
        Case("terminal font", "Color scheme", null),
        Case("terminal font", "Terminal font", 1.000000000000),
        Case("term font", "Default environment", 0.281556459757),
        Case("term font", "Timeline width", null),
        Case("term font", "Terminal font", 0.858040056632),
        Case("code font", "Interface mode", null),
        Case("code font", "Code font", 1.000000000000),
        Case("zoom", "Preferences", null),
        Case("zoom", "Pinch to zoom", 0.857638827012),
        Case("zoom", "Zoom", 1.000000000000),
        Case("zoom", "Models", null),
        Case("zm", "Pinch to zoom", 0.312209613799),
        Case("zm", "Zoom", 0.352552032701),
        Case("zm", "Permissions", null),
        Case("reduce", "Sidebar organize", null),
        Case("reduce", "Reduce motion", 0.874675726332),
        Case("motion", "Timeline detail", null),
        Case("motion", "Reduce motion", 0.874548024238),
        Case("reduce motion", "Experimental", null),
        Case("reduce motion", "Reduce motion", 1.000000000000),
        Case("reduce motion", "Projects", null),
        Case("reduce motion", "Skills", null),
        Case("reduce motion", "Experimental", null),
        Case("reduce motion", "Reduce motion", 1.000000000000),
        Case("reduce motion", "Projects", null),
        Case("reduce motion", "Skills", null),
        Case("notif", "Notifications", 0.866178554713),
        Case("notif", "Terminal font", null),
        Case("notifications", "Notifications", 1.000000000000),
        Case("notifications", "Show custom agents", null),
        Case("notifcation", "Notifications", 0.347439436614),
        Case("notifcation", "Follow-up behavior", null),
        Case("not", "Appearance", null),
        Case("not", "Notifications", 0.851099632654),
        Case("not", "Default environment", 0.283291875691),
        Case("not", "Interface font", 0.292245065211),
        Case("not", "Terminal font", 0.296805468569),
        Case("not", "Plugins", null),
        Case("no", "Notifications", 0.842800647534),
        Case("no", "Interface mode", 0.323856822389),
        Case("no", "Default environment", 0.310238540263),
        Case("no", "Pinch to zoom", 0.337221948797),
        Case("no", "New question UI", 0.325514392199),
        Case("no", "Interface font", 0.323856822389),
        Case("no", "Terminal font", 0.325517215219),
        Case("no", "Interface sounds", 0.323854114050),
        Case("no", "Extensions", 0.336481140764),
        Case("no", "Plugins", null),
        Case("serv", "Errors", null),
        Case("serv", "Servers", 0.922476585274),
        Case("serv", "MCP servers", 0.874308336334),
        Case("server", "Code font", null),
        Case("server", "Servers", 0.965359355224),
        Case("server", "MCP servers", 0.894951749330),
        Case("servers", "Show custom agents", null),
        Case("servers", "Servers", 1.000000000000),
        Case("servers", "MCP servers", 0.907581677022),
        Case("shell", "Pinch to zoom", null),
        Case("shell", "Shell", 1.000000000000),
        Case("sh", "Keyboard shortcuts", 0.812037848668),
        Case("sh", "About", null),
        Case("sh", "Spellcheck", 0.338762393116),
        Case("sh", "Show turn diffs", 0.831501992909),
        Case("sh", "Show custom agents", 0.817238178512),
        Case("sh", "Color scheme", 0.329679026894),
        Case("sh", "Shell", 0.920688336465),
        Case("bash", "Help and feedback", null),
        Case("bash", "Keyboard shortcuts", 0.306937540083),
        Case("bash", "Models", null),
        Case("zsh", "Reduce motion", null),
        Case("powershell", "New timeline", null),
        Case("mcp", "Follow-up behavior", null),
        Case("mcp", "MCP servers", 0.865635999033),
        Case("mcps", "Notifications", null),
        Case("mcps", "Extensions", null),
        Case("mcps", "MCP servers", 0.742736422055),
        Case("plugin", "Permissions", null),
        Case("plugin", "Plugins", 0.965359355224),
        Case("plugins", "Color scheme", null),
        Case("plugins", "Plugins", 1.000000000000),
        Case("skill", "Background activity", null),
        Case("skill", "Skills", 0.965338240657),
        Case("skills", "Language", null),
        Case("skills", "Skills", 1.000000000000),
        Case("skills", "Shell", null),
        Case("ext", "Experimental", 0.329994851611),
        Case("ext", "Workspaces", null),
        Case("ext", "Extensions", 0.874098657890),
        Case("extensions", "Zoom", null),
        Case("extensions", "Extensions", 1.000000000000),
        Case("proj", "New timeline", null),
        Case("proj", "Projects", 0.907449624376),
        Case("project", "Pinch to zoom", null),
        Case("project", "Projects", 0.965368674879),
        Case("workspace", "Keyboard shortcuts", null),
        Case("workspace", "Workspaces", 0.965375762179),
        Case("workspace", "MCP servers", null),
        Case("worktree", "Questions", null),
        Case("provider", "Color scheme", null),
        Case("provider", "Providers", 0.965373281053),
        Case("model", "Background activity", null),
        Case("model", "Models", 0.965338240657),
        Case("models", "Language", null),
        Case("models", "Models", 1.000000000000),
        Case("models", "Shell", null),
        Case("exp", "Experimental", 0.858019944629),
        Case("exp", "Workspaces", null),
        Case("experiment", "Experimental", 0.941188127793),
        Case("experiment", "Zoom", null),
        Case("experimental", "Experimental", 1.000000000000),
        Case("experimental", "New timeline", null),
        Case("about", "About", 1.000000000000),
        Case("about", "Pinch to zoom", null),
        Case("license", "Notifications", null),
        Case("license", "Extensions", null),
        Case("version", "Agent", null),
        Case("credits", "New question UI", null),
        Case("key", "Keyboard shortcuts", 0.823708809518),
        Case("key", "Spellcheck", null),
        Case("keybind", "Keyboard shortcuts", null),
        Case("keybind", "Servers", null),
        Case("keyboard", "Keyboard shortcuts", 0.851741600628),
        Case("keyboard", "Questions", null),
        Case("hotkey", "Color scheme", null),
        Case("shortcut", "Keyboard shortcuts", 0.851690978879),
        Case("shortcut", "Background activity", null),
        Case("shortcuts", "Keyboard shortcuts", 0.858623901332),
        Case("shortcuts", "Language", null),
        Case("shortcuts", "Skills", null),
        Case("timeline", "Timeline detail", 0.874706588097),
        Case("timeline", "Timeline width", 0.884202950441),
        Case("timeline", "New timeline", 0.907604868102),
        Case("timeline", "Providers", null),
        Case("time", "Default environment", 0.290496450295),
        Case("time", "Timeline detail", 0.845195246251),
        Case("time", "Timeline width", 0.851529039736),
        Case("time", "New timeline", 0.865875680725),
        Case("time", "Questions", null),
        Case("detail", "Timeline detail", 0.858449920906),
        Case("detail", "Theme", null),
        Case("background", "Background activity", 0.858662059180),
        Case("background", "Timeline width", null),
        Case("activity", "Language", null),
        Case("activity", "Background activity", 0.845340692822),
        Case("activity", "Shell", null),
        Case("width", "Timeline width", 0.858226554849),
        Case("width", "Workspaces", null),
        Case("fold", "Terminal font", null),
        Case("collapse", "Collapse long messages", 0.829104729615),
        Case("collapse", "Show custom agents", null),
        Case("turn", "Auto-accept permissions", null),
        Case("turn", "Show turn diffs", 0.844986069570),
        Case("diff", "Appearance", null),
        Case("diff", "Show turn diffs", 0.844362011382),
        Case("diff", "Extensions", null),
        Case("diffs", "Show turn diffs", 0.851270290839),
        Case("diffs", "Permissions", null),
        Case("custom", "Show custom agents", 0.839496684682),
        Case("custom", "Color scheme", null),
        Case("agents", "Timeline detail", null),
        Case("agents", "Show custom agents", 0.839301854797),
        Case("question", "Experimental", null),
        Case("question", "New question UI", 0.874693061236),
        Case("question", "Questions", 0.965373281053),
        Case("question", "Shell", null),
        Case("questions", "Questions", 1.000000000000),
        Case("questions", "Workspaces", null),
        Case("ui", "Default environment", 0.323850051887),
        Case("ui", "Auto-accept permissions", 0.312199535749),
        Case("ui", "Follow-up behavior", 0.310624634638),
        Case("ui", "Background activity", 0.312608779740),
        Case("ui", "Show turn diffs", 0.321235124691),
        Case("ui", "New question UI", 0.818988424660),
        Case("ui", "Reduce motion", 0.327247846445),
        Case("ui", "Questions", 0.344634423835),
        Case("ui", "Projects", null),
        Case("ui", "Plugins", 0.350424453280),
        Case("sidebar", "Sidebar organize", 0.858642396663),
        Case("sidebar", "Zoom", null),
        Case("organize", "Show custom agents", null),
        Case("organize", "Sidebar organize", 0.866209566793),
        Case("interface", "Interface mode", 0.895039848024),
        Case("interface", "Pinch to zoom", null),
        Case("interface", "Interface font", 0.895039848024),
        Case("interface", "Interface sounds", 0.874712087400),
        Case("lang", "About", null),
        Case("lang", "Language", 0.907449624376),
        Case("lang", "Collapse long messages", 0.301990736442),
        Case("lang", "Skills", null),
        Case("language", "Language", 1.000000000000),
        Case("language", "Projects", null),
        Case("zoom in", "Pinch to zoom", 0.704175048171),
        Case("zoom in", "Terminal font", null),
        Case("zzz", "Show turn diffs", null),
        Case("qqq", "Default environment", null),
        Case("xyz", "Preferences", null),
        Case("xyz", "Workspaces", null),
        Case("spellcheck", "Spellcheck", 1.000000000000),
        Case("spellcheck", "Zoom", null),
        Case("spell", "Spellcheck", 0.894962556956),
        Case("spell", "New timeline", null),
        Case("check", "Follow-up behavior", null),
        Case("check", "Spellcheck", 0.600236831711),
        Case("pinch", "Notifications", null),
        Case("pinch", "Pinch to zoom", 0.866178554713),
        Case("pinch", "Servers", null),
        Case("follow", "Follow-up behavior", 0.839537679871),
        Case("follow", "Questions", null),
        Case("behavior", "Follow-up behavior", 0.851679109555),
        Case("behavior", "Theme", null),
        Case("help", "Help and feedback", 0.833935815253),
        Case("help", "Timeline width", null),
        Case("feedback", "Help and feedback", 0.858597567941),
        Case("feedback", "Interface mode", null),
        Case("feedback", "Shell", null),
        Case("c man", "CheatManager.h", 0.581907081949),
        Case("c man", "Manifest.cpp", 0.718504076066),
        Case("manifest", "Manifest.cpp", 0.907625557137),
        Case("strawberry", "straw berry", 0.755612607155),
        Case("strawberry", "strawberry", 1.000000000000),
        Case("café", "cafe", 1.000000000000),
        Case("cafe", "café", 1.000000000000),
        Case("uber", "über", 1.000000000000),
        Case("uber", "uber", 1.000000000000),
        Case("日本語", "日本語入力", 0.940775654242),
        Case("api key", "Connect api key credentials", 0.804385265233),
        Case("x", "x", 1.000000000000),
        Case("x", "xx", 0.941192221952),
        Case("x", "axbxc", 0.597295620353),
        Case("s", "Settings", 0.829120904935),
    )

    @Test
    fun everyCaseMatchesTheShippedLibrary() {
        var checked = 0
        for (case in cases) {
            val actual = FuzzySort.single(n(case.query), n(case.target))
            if (case.expected == null) {
                assertNull(
                    "expected no match for query=${case.query} target=${case.target}, got $actual",
                    actual,
                )
            } else {
                assertTrue(
                    "expected a score for query=${case.query} target=${case.target}, got null",
                    actual != null,
                )
                assertEquals(
                    "query=${case.query} target=${case.target}",
                    case.expected,
                    actual!!,
                    1e-9,
                )
            }
            checked += 1
        }
        assertEquals("the whole table must run", cases.size, checked)
        assertEquals(393, cases.size)
    }

    /** The gate their ranking uses. Both sides of `0.6` are represented above; this names it. */
    @Test
    fun theGateIsReproducible() {
        assertTrue(FuzzySort.single("appearance", "appearance")!! >= 0.6)
        assertTrue(FuzzySort.single("appearanc", "appearance")!! >= 0.6)
        assertTrue(FuzzySort.single("cafe", "café")!! >= 0.6)
        assertTrue(FuzzySort.single("uber", "über")!! >= 0.6)

        // A typo is not a match: "appearnce" misses the e and scores 0.345.
        assertTrue(FuzzySort.single("appearnce", "appearance")!! < 0.6)
        // "c man" prefers CheatManager.h but only just; Manifest.cpp scores higher in fuzzysort's
        // own ordering penalty, which is why this pair is in the table.
        assertEquals(0.5819070819488763, FuzzySort.single("c man", n("CheatManager.h"))!!, 1e-9)
    }

    @Test
    fun accentsAreRemovedInsideLatinRunsOnly() {
        assertEquals("cafe", FuzzySort.removeAccents("café"))
        assertEquals("uber", FuzzySort.removeAccents("über"))
        // Japanese must survive: this is the reason their helper is not a plain NFD strip.
        assertEquals("日本語", FuzzySort.removeAccents("日本語"))
        // A decomposed Latin run collapses to the same text as a precomposed one.
        assertEquals(FuzzySort.removeAccents("cafe"), FuzzySort.removeAccents("cafe\u0301"))
    }

    @Test
    fun emptyInputsNeverMatch() {
        assertNull(FuzzySort.single("", "Theme"))
        assertNull(FuzzySort.single("Theme", ""))
        assertNull(FuzzySort.single("   ", "Theme"))
    }

    /** A character class absent from the target is rejected before any scan. */
    @Test
    fun aMissingCharacterClassIsRejected() {
        assertNull(FuzzySort.single("zzz", "Preferences"))
        assertNull(FuzzySort.single("7", "Preferences"))
    }

    @Test
    fun scoresStayInsideTheNormalisedRange() {
        for (case in cases) {
            val score = FuzzySort.single(n(case.query), n(case.target)) ?: continue
            assertTrue("score $score for ${case.query}/${case.target}", score > 0.0)
            assertTrue("score $score for ${case.query}/${case.target}", score <= 1.0 + abs(1e-12))
        }
    }
}