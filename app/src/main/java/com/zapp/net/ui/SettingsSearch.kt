package com.zapp.net.ui

import java.text.BreakIterator
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * The route value their `settings/route.ts` calls `SettingsView`.
 *
 * `type` is [ViewType.RAIL] for the rail list itself and [ViewType.PAGE] for a page opened below it,
 * which is what their `"root"` / non-root split decides. On desktop the rail is always on screen, so
 * only a non-root view has a Back that returns to the search; on this phone the rail *is* a list, so
 * an opened page is that nested level. The field is carried rather than assumed so
 * [SettingsSearchMachine.canReturnToSearchOrigin] reads the same field theirs does.
 *
 * `searchActivation` is their fence: the machine stamps a counter on the view it navigates to, and
 * a view that arrives without the current counter (a link inside a page, history, a fresh open)
 * ends the activation instead of hijacking it.
 */
data class SettingsView(
    val type: ViewType,
    val tab: Page,
    val server: String? = null,
    val project: String? = null,
    val target: String? = null,
    val subtab: Subtab? = null,
    val searchActivation: Int? = null,
) {
    /** `{ ...view, target: undefined, searchActivation: undefined }` - the origin snapshot. */
    fun asOrigin(): SettingsView = copy(target = null, searchActivation = null)
}

enum class ViewType { RAIL, PAGE }

enum class Subtab(val key: String) {
    MCPS("mcps"),
    PLUGINS("plugins"),
    SKILLS("skills"),
    LSPS("lsps"),
}

/**
 * PORT of `settings/search.ts` - their settings search index and ranking.
 *
 * ## What is theirs
 *
 * Everything in this file that decides *what a search returns* is a direct port:
 *
 * - [normalize] is their `R`: `NFKC`, lowercase, trim, collapse whitespace.
 * - [rank] is their `L`, including the word gate (`any` word as a substring, **not** `every`), the
 *   five scoring tiers in their order, and the fuzzysort fallback at `score >= 0.6` from
 *   [FuzzySort].
 * - [groupKey] is their `z`, [grouped] is their `B` with its 60-result cap and group ordering,
 *   [groupsWithHeader] is their `V`, [isTruncated] is their `footer` condition.
 * - [pageLabel] is the `page` computation inside their `W`, including the
 *   `"Preferences / Section"` form for a targeted row on the general tab.
 * - [INDEX] is their `z`, `B` and `V` arrays with their real label keys and keywords.
 *
 * ## The one thing that is not theirs
 *
 * Their final tie-break is `title.localeCompare(other)` - the **host locale's** collation. A phone
 * has no single host locale, so [COLLATOR] is `Locale.ROOT`, which is deterministic and is the
 * same *kind* of comparison (accent- and case-aware) rather than a byte comparison. Their titles
 * are English ASCII, where the two agree except on case ordering.
 *
 * ## What is deliberately not here
 *
 * Their `W` also builds per-server and per-project entries, and filters by
 * `available: "desktop" | "mobile"`. This phone has one server, no project routes inside Settings,
 * and rows for every entry below, so the index is built once, flat, with no availability filter -
 * filtering out a row that the page actually shows would make search lie about its own page.
 */
object SettingsSearch {

    /** Their `B`'s cap: `R().slice(0, 60)`. */
    const val MAX_RESULTS = 60

    /** Their `l < .6` gate in `L`. */
    const val FUZZY_GATE = 0.6

    // ---------------------------------------------------------------------------------------
    // their capability gate
    // ---------------------------------------------------------------------------------------

    /**
     * Their `available` tag. `settings/search.ts` keeps an entry only when the host it is running
     * on carries the matching flag:
     *
     *     t.available === "desktop" && !e.desktop
     *     || t.available === "browser" && !e.browser
     *     || t.available === "mobile"  && !e.mobile
     *     || r(t, ...)
     *
     * So an entry with no tag is always indexed, and a tagged entry is indexed only on a host that
     * has that capability. This phone is the mobile host: `desktop` and `browser` are false.
     */
    enum class Capability(val wire: String) {
        DESKTOP("desktop"),
        BROWSER("browser"),
        MOBILE("mobile"),
    }

    /** The `e` object their builder receives. */
    data class Capabilities(
        val desktop: Boolean = false,
        val browser: Boolean = false,
        val mobile: Boolean = true,
    ) {
        fun supports(capability: Capability): Boolean = when (capability) {
            Capability.DESKTOP -> desktop
            Capability.BROWSER -> browser
            Capability.MOBILE -> mobile
        }
    }

    /**
     * One project their per-project block would list. The phone has no project routes inside
     * Settings, so the list this app passes is empty - but the shape is theirs, and the builder
     * that consumes it is the one their `projects.forEach` uses.
     */
    data class ProjectRef(val key: String, val name: String)

    // ---- their group keys (`settings.search.group.*`) ----

    const val GROUP_PAGES = "Pages"
    const val GROUP_PROJECTS = "Projects"
    const val GROUP_SERVERS = "Servers"
    const val GROUP_SETTINGS = "Settings"

    // ---- their search copy ----

    /** `settings.search.refine`, shown only when the cap hid something. */
    const val REFINE = "Narrow your search to see more specific results."

    // ---------------------------------------------------------------------------------------
    // their `R`
    // ---------------------------------------------------------------------------------------

    /**
     * `R` - `normalize("NFKC").toLowerCase().trim().replace(/\s+/g, " ")`.
     *
     * The whitespace class is JavaScript's `\s`, which is wider than Java's default, so it is
     * spelled out: Java's `\s` alone would leave a non-breaking space in the query and change
     * which words split.
     */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase()
            .trim()
            .replace(JS_WHITESPACE, " ")

    private val JS_WHITESPACE = Regex(
        "[\\t\\n\\u000B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+",
    )

    /** Their final tie-break, `title.localeCompare(other)`, at a locale this phone can repeat. */
    private val COLLATOR: Collator = Collator.getInstance(Locale.ROOT)

    // ---------------------------------------------------------------------------------------
    // their entry shape
    // ---------------------------------------------------------------------------------------

    /**
     * One searchable row, the fields their `t.push({...})` sets.
     *
     * `entity` marks a server or project row itself (their `entity: true`), which changes both the
     * group it lands in and whether its owner is shown in a header.
     */
    data class Entry(
        val id: String,
        val title: String,
        val description: String = "",
        val keywords: String = "",
        val owner: String = "",
        val page: String = "",
        val server: String? = null,
        val project: String? = null,
        val projectName: String? = null,
        val topLevel: Boolean = false,
        val entity: Boolean = false,
        val projectInfo: Boolean = false,
        /** Their `available`. Null means the row is indexed on every host. */
        val available: Capability? = null,
        val view: SettingsView,
    )

    // ---------------------------------------------------------------------------------------
    // their `L`
    // ---------------------------------------------------------------------------------------

    private data class Scored(val entry: Entry, val score: Int)

    /**
     * `L` - rank every entry against [query].
     *
     * The gate and the tiers are copied in their order, and the distinction between them matters:
     * a multi-word query passes the gate when **any** word appears as a substring, but only earns
     * tier 2+ when **every** word does. That is why `dark mode` still returns `Color scheme`
     * through keywords while `zzz` returns nothing at all.
     */
    fun rank(query: String, entries: List<Entry>): List<Entry> {
        val n = normalize(query)
        if (n.isEmpty()) return emptyList()

        val scored = entries.mapNotNull { entry ->
            // A project row only answers to a query that names its project.
            val projectName = entry.projectName?.let { normalize(it) }
            if (projectName != null && !" $n ".contains(" $projectName ")) return@mapNotNull null

            val rest = if (projectName != null) normalize(" $n ".replace(" $projectName ", " ")) else n
            if (rest.isEmpty()) return@mapNotNull null

            val words = rest.split(" ")
            val title = normalize(entry.title)
            val titleKeywords = normalize("$title ${entry.keywords}")
            val description = normalize(entry.description)
            val ownerPage = normalize("${entry.owner} ${if (entry.entity) "" else entry.page}")
            val fuzzy = FuzzySort.single(rest, title) ?: 0.0

            val anyWordAsSubstring = words.any { "$titleKeywords $description".contains(it) }
            if (!anyWordAsSubstring && fuzzy < FUZZY_GATE) return@mapNotNull null

            val tier = when {
                title == rest -> 5
                words.all { title.contains(it) } -> 4
                words.all { titleKeywords.contains(it) } -> 3
                words.all { "$titleKeywords $description $ownerPage".contains(it) } -> 2
                fuzzy >= FUZZY_GATE -> 1
                else -> 0
            }

            if (tier == 0) null else Scored(entry, tier)
        }

        // Their comparator: top-level first, then tier, then title.
        return scored.sortedWith(
            compareByDescending<Scored> { it.entry.topLevel }
                .thenByDescending { it.score }
                .thenBy(COLLATOR) { it.entry.title },
        ).map { it.entry }
    }

    /** `z` - which group a row belongs to. */
    fun groupKey(entry: Entry): String = when {
        entry.topLevel -> GROUP_PAGES
        entry.entity -> if (entry.project != null) GROUP_PROJECTS else GROUP_SERVERS
        else -> GROUP_SETTINGS
    }

    /** `B` - rank, cap at [MAX_RESULTS], then order by first appearance of each group. */
    fun grouped(query: String, entries: List<Entry>): List<Entry> {
        val top = rank(query, entries).take(MAX_RESULTS)
        val order = top.map { groupKey(it) }.distinct()
        return order.flatMap { key -> top.filter { groupKey(it) == key } }
    }

    /** `V` - only a group holding a page or a project row gets a header. */
    fun groupsWithHeader(entries: List<Entry>): Set<String> =
        entries.filter { it.topLevel || it.projectInfo }.map { groupKey(it) }.toSet()

    /** Their `footer`: shown only when the cap actually hid something. */
    fun isTruncated(query: String, entries: List<Entry>): Boolean =
        rank(query, entries).size > grouped(query, entries).size

    /**
     * The `page` label their `W` computes for a row, which is the muted second line under the title.
     *
     * A targeted row on the general tab reads `Preferences / <section>` so the reader knows which
     * block of Preferences it lives in. Everything else reads its section, or its tab.
     */
    fun pageLabel(entry: Entry, multiServer: Boolean = false): String {
        val view = entry.view
        val section = entry.sectionLabel
        val isTargetedGeneral = entry.server == null && view.tab == Page.PREFERENCES && view.target != null

        if (isTargetedGeneral) {
            return "${TAB_LABEL.getValue(Page.PREFERENCES)} / ${section ?: GENERAL_SECTION}"
        }

        if (section != null) return section
        if (view.type != ViewType.RAIL && view.tab == Page.PREFERENCES) return GENERAL_SECTION
        if (view.tab == Page.SERVERS && multiServer) return "Servers"
        return TAB_LABEL.getValue(view.tab)
    }

    /** `settings.general.section.general` */
    private const val GENERAL_SECTION = "General"

    /** Their `U` - the label each tab contributes when a row has no section of its own. */
    private val TAB_LABEL: Map<Page, String> = mapOf(
        Page.PREFERENCES to "Preferences",
        Page.APPEARANCE to "Appearance",
        Page.NOTIFICATIONS to "Notifications",
        Page.SHORTCUTS to "Keyboard shortcuts",
        Page.PROJECTS to "Projects",
        Page.WORKTREES to "Worktrees",
        Page.PROVIDERS to "Providers",
        Page.MODELS to "Models",
        Page.EXTENSIONS to "Extensions",
        Page.SERVERS to "Server",
        Page.EXPERIMENTAL to "Experimental",
        Page.ABOUT to "About",
        Page.PAIRING to "Pairing",
    )

    // ---------------------------------------------------------------------------------------
    // the index
    // ---------------------------------------------------------------------------------------

    /** The section a row belongs to, which becomes the `Preferences / Section` label. */
    private val Entry.sectionLabel: String?
        get() = SECTIONS[id]

    /** Their `r.section`, keyed by the row id. */
    private val SECTIONS: Map<String, String> = mapOf(
        "settings-timeline-detail" to "Timeline",
        "settings-background-activity" to "Timeline",
        "settings-timeline-width" to "Timeline",
        "settings-fold-user-messages" to "Timeline",
        "settings-show-turn-diffs" to "Timeline",
        "settings-show-custom-agents" to GENERAL_SECTION,
        "settings-notifications-agent" to "Desktop notifications",
        "settings-notifications-permissions" to "Desktop notifications",
        "settings-notifications-questions" to "Desktop notifications",
        "settings-notifications-errors" to "Desktop notifications",
        "settings-sounds-agent" to "Sound effects",
        "settings-sounds-permissions" to "Sound effects",
        "settings-sounds-questions" to "Sound effects",
        "settings-sounds-errors" to "Sound effects",
        "settings-sounds-interface" to "Sound effects",
    )

    /** A page row: `topLevel`, no target, no subtab - exactly their `!i.target && !i.subtab && !s`. */
    private fun page(
        page: Page,
        subtab: Subtab? = null,
        keywords: String = "",
        label: String = page.title,
        available: Capability? = null,
        server: String? = null,
    ): Entry = Entry(
        id = "page:${page.key}:${subtab?.key ?: ""}:${server ?: ""}",
        title = label,
        keywords = keywords,
        page = TAB_LABEL.getValue(page),
        topLevel = subtab == null && server == null,
        server = server,
        available = available,
        view = SettingsView(type = ViewType.RAIL, tab = page, subtab = subtab, server = server),
    )

    /** A row inside a page. */
    private fun row(
        page: Page,
        id: String,
        label: String,
        description: String = "",
        keywords: String = "",
        section: String? = null,
        available: Capability? = null,
        server: String? = null,
    ): Entry = Entry(
        id = id,
        title = label,
        description = description,
        keywords = keywords,
        page = section?.let { "${TAB_LABEL.getValue(Page.PREFERENCES)} / $it" }
            ?: TAB_LABEL.getValue(page),
        server = server,
        available = available,
        view = SettingsView(type = ViewType.PAGE, tab = page, target = id, server = server),
    )

    /**
     * Their `z` (root pages and their rows), then their `B` (the per-server page entries).
     *
     * Titles and keywords are the bundle's, not paraphrases. Their `available` filter is not
     * applied - see the class comment.
     */
    private val ROOT: List<Entry> = buildList {
        // --- their `z` ---
        add(page(Page.PREFERENCES))
        add(
            row(
                Page.PREFERENCES, "settings-help-feedback", "Help & feedback",
                keywords = "support report issue",
                available = Capability.DESKTOP,
            ),
        )
        add(page(Page.APPEARANCE))
        add(page(Page.NOTIFICATIONS))
        add(page(Page.SHORTCUTS, keywords = "keybind keyboard hotkey"))
        add(page(Page.ABOUT, keywords = "version license credits"))
        add(page(Page.EXPERIMENTAL, keywords = "experiments preview beta"))
        add(row(Page.PREFERENCES, "settings-language", "Language"))
        add(
            row(
                Page.PREFERENCES, "settings-tab-layout", "Interface layout",
                keywords = "top side tabs inbox sidebar mode layout vertical horizontal focus pro",
            ),
        )
        add(
            row(
                Page.PREFERENCES, "settings-workspace-destination", "Default environment",
                description = "Choose where new sessions start",
                keywords = "worktree workspace default destination",
            ),
        )
        add(
            row(
                Page.PREFERENCES, "settings-auto-accept-permissions", "Auto-accept permissions",
                description = "Approve permission requests without asking.",
                keywords = "permissions approve allow auto accept default",
            ),
        )
        add(
            row(
                Page.PREFERENCES, "settings-follow-up-behavior", "Follow-up behavior",
                keywords = "queue steer follow up",
            ),
        )
        add(row(Page.PREFERENCES, "settings-pinch-zoom", "Pinch to zoom", available = Capability.MOBILE))
        add(
            row(
                Page.PREFERENCES, "settings-spellcheck", "Spellcheck",
                description = "Underline misspelled words while typing",
                keywords = "spelling spell check dictionary",
                available = Capability.MOBILE,
            ),
        )
        add(row(Page.PREFERENCES, "settings-mobile-titlebar-bottom", "Bottom navigation", available = Capability.MOBILE))
        add(
            row(
                Page.PREFERENCES, "settings-timeline-detail", "Timeline detail",
                section = "Timeline",
                keywords = "thinking reasoning tools timeline summary detailed",
            ),
        )
        add(
            row(
                Page.PREFERENCES, "settings-background-activity", "Background activity",
                description = "Show running subagents and shells above the composer",
                section = "Timeline",
                available = Capability.DESKTOP,
                keywords = "capsule panel subagent shell background activity position alignment hidden left center right",
            ),
        )
        add(
            row(
                Page.PREFERENCES, "settings-timeline-width", "Timeline width",
                description = "Narrower lines are easier to scan. Wider lines show more at once",
                section = "Timeline",
                keywords = "column line length measure narrow focused comfortable wide readability",
                available = Capability.DESKTOP,
            ),
        )
        add(
            row(
                Page.PREFERENCES, "settings-fold-user-messages", "Collapse long messages",
                description = "Show the first few lines. Expand to read your full message.",
                section = "Timeline",
                keywords = "fold collapse long messages prompts read more",
            ),
        )
        add(
            row(
                Page.PREFERENCES, "settings-show-turn-diffs", "Changed files",
                description = "List the files each turn changed when it finishes",
                section = "Timeline",
            ),
        )
        add(
            row(
                Page.PREFERENCES, "settings-show-custom-agents", "Show agent",
                section = GENERAL_SECTION,
            ),
        )

        // --- their `V`'s experimental rows, which sit on the experimental tab ---
        add(
            row(
                Page.EXPERIMENTAL, "settings-new-timeline", "New timeline",
                description = "A redesigned session timeline that groups what the agent did, with one status line below the turn",
                keywords = "new timeline redesign activity explored edited ran grouping status",
            ),
        )
        add(
            row(
                Page.EXPERIMENTAL, "settings-new-question-ui", "New question UI",
                description = "Preview the keyboard-first question dock",
                keywords = "question answers keyboard dock redesign",
            ),
        )
        add(
            row(
                Page.EXPERIMENTAL, "settings-sidebar-organize", "Organize vertical sidebar",
                description = "Choose how to organize sessions in side tabs.",
                keywords = "vertical side tabs projects recents organize sidebar",
            ),
        )

        // --- appearance rows ---
        add(
            row(
                Page.APPEARANCE, "settings-color-scheme", "Color scheme",
                keywords = "dark mode light mode system",
            ),
        )
        add(row(Page.APPEARANCE, "settings-theme", "Theme"))
        add(row(Page.APPEARANCE, "settings-ui-font", "UI Font", keywords = "interface typeface"))
        add(row(Page.APPEARANCE, "settings-code-font", "Code Font", keywords = "code typeface"))
        add(row(Page.APPEARANCE, "settings-terminal-font", "Terminal Font", keywords = "terminal typeface"))
        add(
            row(
                Page.APPEARANCE, "settings-zoom", "Zoom",
                keywords = "scale size text font larger bigger accessibility",
                available = Capability.MOBILE,
            ),
        )
        add(
            row(
                Page.APPEARANCE, "settings-reduce-motion", "Reduce motion",
                keywords = "animation accessibility",
            ),
        )

        // --- notification rows ---
        add(
            row(
                Page.NOTIFICATIONS, "settings-notifications-agent", "Agent",
                description = "Show system notification when the agent is complete or needs attention",
                keywords = "desktop notifications agent",
            ),
        )
        add(
            row(
                Page.NOTIFICATIONS, "settings-notifications-permissions", "Permissions",
                description = "Show system notification when a permission is required",
                keywords = "desktop notifications permissions",
            ),
        )
        add(
            row(
                Page.NOTIFICATIONS, "settings-notifications-questions", "Questions",
                description = "Show system notification when the agent asks a question",
                keywords = "desktop notifications questions mcp elicitation input",
            ),
        )
        add(
            row(
                Page.NOTIFICATIONS, "settings-notifications-errors", "Errors",
                description = "Show system notification when an error occurs",
                keywords = "desktop notifications errors",
            ),
        )

        // --- sound rows ---
        add(
            row(
                Page.NOTIFICATIONS, "settings-sounds-agent", "Agent",
                description = "Play sound when the agent is complete or needs attention",
                keywords = "sound audio agent",
            ),
        )
        add(
            row(
                Page.NOTIFICATIONS, "settings-sounds-permissions", "Permissions",
                description = "Play sound when a permission is required",
                keywords = "sound audio permissions",
            ),
        )
        add(
            row(
                Page.NOTIFICATIONS, "settings-sounds-questions", "Questions",
                description = "Play sound when the agent asks a question",
                keywords = "sound audio questions mcp elicitation input",
            ),
        )
        add(
            row(
                Page.NOTIFICATIONS, "settings-sounds-errors", "Errors",
                description = "Play sound when an error occurs",
                keywords = "sound audio errors",
            ),
        )
        add(
            row(
                Page.NOTIFICATIONS, "settings-sounds-interface", "Interface",
                description = "Play subtle sounds when the wordmark appears and when you play with it",
                keywords = "sound audio interface effects wordmark logo",
            ),
        )

        // --- their `B`: the page entries the server block contributes ---
        add(page(Page.PROJECTS))
        add(page(Page.WORKTREES, keywords = "workspaces disk usage cleanup delete"))
        add(page(Page.PROVIDERS, keywords = "connect api key credentials"))
        add(page(Page.MODELS, keywords = "model picker visibility"))
        add(page(Page.EXTENSIONS))
        add(page(Page.EXTENSIONS, Subtab.MCPS, "model context protocol tools", "MCPs"))
        add(page(Page.EXTENSIONS, Subtab.PLUGINS, "extension gui built-in", "Plugins"))
        add(page(Page.EXTENSIONS, Subtab.SKILLS, "", "Skills"))
        add(
            row(
                Page.SERVERS, "settings-shell", "Terminal shell",
                description = "Shell used by the terminal and agent tools",
                available = Capability.DESKTOP,
                keywords = "bash zsh powershell",
            ),
        )

        // Their root block ends on the Servers tab - with no server selected it is where the
        // shell setting lives - so the page is indexed even before a server is connected.
        add(page(Page.SERVERS, keywords = "terminal shell server connection endpoint"))
        // --- ours: the pairing page has no PC counterpart ---
        add(page(Page.PAIRING, keywords = "device qr link token"))
    }

    /**
     * Their builder `W`: the root rows, then one block per server, then one block per project.
     *
     * The capability gate is theirs, run in the same place - before an entry is added, not as a
     * filter on the results - so a `desktop` row is absent from a phone's index entirely rather
     * than being found and then hidden.
     */
    fun index(
        caps: Capabilities = Capabilities(),
        server: String? = null,
        projects: List<ProjectRef> = emptyList(),
    ): List<Entry> = buildList {
        addAll(ROOT.filter { it.available == null || caps.supports(it.available) })
        if (server != null) addAll(serverEntries(server))
        projects.forEach { project -> add(projectEntry(server.orEmpty(), project)) }
    }

    /** Their `B`: the pages a connected server contributes, scoped to that server. */
    private fun serverEntries(server: String): List<Entry> = buildList {
        add(page(Page.PROJECTS, server = server))
        add(page(Page.WORKTREES, keywords = "workspaces disk usage cleanup delete", server = server))
        add(page(Page.PROVIDERS, keywords = "connect api key credentials", server = server))
        add(page(Page.MODELS, keywords = "model picker visibility", server = server))
        add(page(Page.EXTENSIONS, server = server))
        add(page(Page.EXTENSIONS, Subtab.MCPS, "model context protocol tools", "MCPs", server = server))
        add(page(Page.EXTENSIONS, Subtab.PLUGINS, "extension gui built-in", "Plugins", server = server))
        add(page(Page.EXTENSIONS, Subtab.SKILLS, "", "Skills", server = server))
    }

    /**
     * Their per-project entity row: the project itself, findable by its own name, owned by its
     * server, and headed under `Projects`.
     */
    private fun projectEntry(server: String, project: ProjectRef): Entry = Entry(
        id = "project:$server:${project.key}",
        title = project.name,
        owner = server,
        page = TAB_LABEL.getValue(Page.PROJECTS),
        entity = true,
        server = server,
        project = project.key,
        projectInfo = true,
        view = SettingsView(
            type = ViewType.PAGE,
            tab = Page.PROJECTS,
            server = server,
            project = project.key,
        ),
    )

    /** `Intl.Segmenter(granularity: "grapheme")` - the same segmentation Java's iterator gives. */
    fun graphemes(text: String): List<String> {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        val out = ArrayList<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            out.add(text.substring(start, end))
            start = end
            end = iterator.next()
        }
        return out
    }

    /**
     * Their `J`: keep the whole query when it fits, otherwise the longest **grapheme** prefix that
     * fits with a trailing period.
     *
     * Their binary search runs over `Array.from(new Intl.Segmenter(...).segment(query))`, so a
     * cut never lands inside a character - which is the difference between truncating `日本語` to
     * `日本` and truncating it to half a code point. `measure` is the caller's text measurement so
     * the rule can be tested without a screen.
     */
    fun truncateToWidth(query: String, available: Float, measure: (String) -> Float): String {
        if (measure(query) <= available) return query
        val units = graphemes(query)
        var low = 0
        var high = units.size
        while (low < high) {
            val mid = (low + high + 1) / 2
            val candidate = units.subList(0, mid).joinToString("").trimEnd() + "."
            if (measure(candidate) <= available) low = mid else high = mid - 1
        }
        return units.subList(0, low).joinToString("").trimEnd() + "."
    }

    /**
     * The index this phone builds: the mobile host, the one server it is paired with, and the
     * projects that server reports. `SettingsScreen` passes the live server; the default is the
     * same shape with no server attached, which is what the tests walk.
     */
    val INDEX: List<Entry> = index()

    /** Their result list for a query, capped and grouped. */
    fun hits(query: String, entries: List<Entry> = INDEX): List<Entry> = grouped(query, entries)
}
