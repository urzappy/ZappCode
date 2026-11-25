package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Differential trace.
 *
 * Writes the outcome of a fixed scenario list to a JSON file so it can be compared, step for
 * step, against their shipped reducer running in Node (`theirs-run.js`). This test does not
 * assert their behaviour - it records ours. The comparison is a separate script.
 *
 * The scenarios are the same objects, in the same order, as `theirs-run.js`.
 */
class ParityTraceTest {

    private val none = SidePanel.PaneSnapshot()

    private fun snap(
        session: String,
        sideOpen: Boolean,
        empty: Boolean,
        regionActive: String?,
        side: SidePanel.PaneSnapshot,
        split: SidePanel.PaneSnapshot = SidePanel.PaneSnapshot(),
        bottom: SidePanel.PaneSnapshot = SidePanel.PaneSnapshot(),
    ) = SidePanel.Snapshot(
        session = session,
        empty = empty,
        regionActive = regionActive,
        sideOpen = sideOpen,
        panes = mapOf(
            SidePanel.Pane.SIDE to side,
            SidePanel.Pane.SPLIT to split,
            SidePanel.Pane.BOTTOM to bottom,
        ),
    )

    /** Their event names, so the two traces line up without translation. */
    private fun nameOf(e: SidePanel.Event): String = when (e) {
        is SidePanel.Event.Sync -> "sync"
        is SidePanel.Event.Open -> "open"
        is SidePanel.Event.SelectNew -> "select.new"
        is SidePanel.Event.SelectTab -> "select.tab"
        is SidePanel.Event.Close -> "close"
        is SidePanel.Event.Dismiss -> "dismiss"
    }

    private fun run(events: List<SidePanel.Event>): List<String> {
        var st = SidePanel.State()
        val out = mutableListOf<String>()
        for (ev in events) {
            val r = SidePanel.reduce(st, ev)
            st = r.state
            out += """{"event":"${nameOf(ev)}","state":${shape(st)},"effects":${effects(r.effects)}}"""
        }
        return out
    }

    private fun paneJson(p: SidePanel.PaneState): String =
        """{"ids":[${p.ids.joinToString(",") { "\"$it\"" }}],"chosen":${p.chosen?.let { "\"$it\"" } ?: "null"}}"""

    private fun snapPaneJson(x: SidePanel.PaneSnapshot): String =
        """{"active":${x.active?.let { "\"$it\"" } ?: "null"},"keys":[${x.keys.joinToString(",") { "\"$it\"" }}]}"""

    private fun shape(s: SidePanel.State): String =
        """{"serial":${s.serial},"session":"${s.session}","openedEmpty":${s.openedEmpty},""" +
            """"panes":{"side":${paneJson(s.panes.getValue(SidePanel.Pane.SIDE))},""" +
            """"split":${paneJson(s.panes.getValue(SidePanel.Pane.SPLIT))},""" +
            """"bottom":${paneJson(s.panes.getValue(SidePanel.Pane.BOTTOM))}},""" +
            """"snapshot":${snapshotShape(s.snapshot)}}"""

    private fun snapshotShape(s: SidePanel.Snapshot?): String {
        if (s == null) return "null"
        return """{"session":"${s.session}","sideOpen":${s.sideOpen},"empty":${s.empty},""" +
            """"regionActive":${s.regionActive?.let { "\"$it\"" } ?: "null"},""" +
            """"panes":{"side":${snapPaneJson(s.panes.getValue(SidePanel.Pane.SIDE))},""" +
            """"split":${snapPaneJson(s.panes.getValue(SidePanel.Pane.SPLIT))},""" +
            """"bottom":${snapPaneJson(s.panes.getValue(SidePanel.Pane.BOTTOM))}}}"""
    }

    private fun effects(e: List<SidePanel.Effect>): String =
        "[" + e.joinToString(",") {
            """{"type":"${if (it is SidePanel.Effect.Emptied) "emptied" else "?"}"}"""
        } + "]"

    private fun scenarios(): Map<String, List<SidePanel.Event>> = linkedMapOf(
        "plusOnFresh" to listOf(SidePanel.Event.Open(SidePanel.Pane.SIDE)),
        "plusTwice" to listOf(
            SidePanel.Event.Open(SidePanel.Pane.SIDE),
            SidePanel.Event.Open(SidePanel.Pane.SIDE),
        ),
        "plusOnSplit" to listOf(SidePanel.Event.Open(SidePanel.Pane.SPLIT)),
        "chooseThenUnchoose" to listOf(
            SidePanel.Event.Open(SidePanel.Pane.SIDE),
            SidePanel.Event.SelectTab(SidePanel.Pane.SIDE),
        ),
        "closeChosenFallsForward" to listOf(
            SidePanel.Event.Open(SidePanel.Pane.SIDE),
            SidePanel.Event.Open(SidePanel.Pane.SIDE),
            SidePanel.Event.Open(SidePanel.Pane.SIDE),
            SidePanel.Event.SelectNew(SidePanel.Pane.SIDE, "new:1"),
            SidePanel.Event.Close("new:1", empty = false),
        ),
        "closeLastEmpties" to listOf(
            SidePanel.Event.Open(SidePanel.Pane.SIDE),
            SidePanel.Event.Close("new:1", empty = true),
        ),
        "closeLastWithoutEmptyFlag" to listOf(
            SidePanel.Event.Open(SidePanel.Pane.SIDE),
            SidePanel.Event.Close("new:1", empty = false),
        ),
        "closeUnchosenKeepsChoice" to listOf(
            SidePanel.Event.Open(SidePanel.Pane.SIDE),
            SidePanel.Event.Open(SidePanel.Pane.SIDE),
            SidePanel.Event.Close("new:1", empty = false),
        ),
        "syncOtherSessionWipes" to listOf(
            SidePanel.Event.Open(SidePanel.Pane.SIDE),
            SidePanel.Event.Open(SidePanel.Pane.SPLIT),
            SidePanel.Event.Sync(snap("B", false, false, null, none)),
        ),
        "syncSameSessionKeeps" to listOf(
            SidePanel.Event.Open(SidePanel.Pane.SIDE),
            SidePanel.Event.Sync(snap("", false, false, null, none)),
        ),
        "syncOpensEmptyTab" to listOf(
            SidePanel.Event.Sync(snap("", true, true, null, none)),
        ),
        "syncOpensEmptyTabOnlyOnce" to listOf(
            SidePanel.Event.Sync(snap("", true, true, null, none)),
            SidePanel.Event.Sync(snap("", true, true, null, none)),
        ),
        "syncPrunesVanishedTab" to listOf(
            SidePanel.Event.Open(SidePanel.Pane.SIDE),
            SidePanel.Event.Sync(
                snap("", false, false, null, SidePanel.PaneSnapshot(listOf("review"), "review")),
            ),
            SidePanel.Event.Sync(
                snap("", false, false, null, SidePanel.PaneSnapshot(listOf("browser"), "browser")),
            ),
        ),
        "syncClearsEmptySplit" to listOf(
            SidePanel.Event.Open(SidePanel.Pane.SPLIT),
            SidePanel.Event.Sync(snap("", false, false, null, none)),
        ),
        "syncKeepsPlaceholder" to listOf(
            SidePanel.Event.Open(SidePanel.Pane.SIDE),
            SidePanel.Event.Sync(snap("", false, false, null, none)),
        ),
    )

    @Test
    fun writeTrace() {
        val entries = scenarios().entries.toList()
        val sb = StringBuilder("{\n")
        entries.forEachIndexed { i, (name, events) ->
            sb.append(" \"").append(name).append("\": [\n")
            val lines = run(events)
            lines.forEachIndexed { j, line ->
                sb.append("  ").append(line)
                if (j != lines.lastIndex) sb.append(',')
                sb.append('\n')
            }
            sb.append(" ]")
            if (i != entries.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("}\n")
        File("C:/Users/hachkerss/AppData/Local/Temp/opencode/mine-trace.json")
            .writeText(sb.toString(), Charsets.UTF_8)
        assertEquals(15, entries.size)
    }

    // ---- the popover, `iT` --------------------------------------------------------------

    private fun plusNameOf(e: SidePanel.PlusEvent): String = when (e) {
        is SidePanel.PlusEvent.Arm -> "arm"
        is SidePanel.PlusEvent.Disarm -> "disarm"
        is SidePanel.PlusEvent.OpenChange -> "open.change"
        is SidePanel.PlusEvent.ContextMenu -> "context.menu"
        is SidePanel.PlusEvent.Hover -> "hover"
        is SidePanel.PlusEvent.Select -> "select"
        is SidePanel.PlusEvent.CloseAutofocus -> "close.autofocus"
        is SidePanel.PlusEvent.TimerFired -> "timer.fired"
        is SidePanel.PlusEvent.Disposed -> "disposed"
    }

    private fun plusShape(s: SidePanel.PlusState): String {
        // Their disposed state is `{status:"disposed"}` with no attempt at all.
        val attempt = if (s.status == SidePanel.PlusStatus.DISPOSED) "null" else s.attempt.toString()
        val armed = s.armed?.let { if (it == SidePanel.Intent.MENU) "\"menu\"" else "\"new\"" } ?: "null"
        return """{"status":"${s.status.name.lowercase()}","attempt":$attempt,""" +
            """"hover":${s.hover},"armed":$armed,"shown":${s.shown},""" +
            """"choice":${s.choice?.let { "\"$it\"" } ?: "null"}}"""
    }

    private fun plusEffects(e: List<SidePanel.PlusEffect>): String = "[" + e.joinToString(",") { x ->
        when (x) {
            is SidePanel.PlusEffect.OpenNewTab -> """{"type":"open.new-tab"}"""
            is SidePanel.PlusEffect.TimerStop -> """{"type":"timer.stop"}"""
            is SidePanel.PlusEffect.TimerStart ->
                """{"type":"timer.start","attempt":${x.attempt},"delay":${x.delayMs}}"""
            is SidePanel.PlusEffect.RunChoice ->
                """{"type":"run.choice","choice":${x.choice?.let { "\"$it\"" } ?: "null"}}"""
        }
    } + "]"

    private fun plusScenarios(): Map<String, List<SidePanel.PlusEvent>> = linkedMapOf(
        "tapMakesTab" to listOf(
            SidePanel.PlusEvent.Arm(SidePanel.Intent.NEW),
            SidePanel.PlusEvent.OpenChange(open = true, available = true),
        ),
        "tapMakesTabNoRecent" to listOf(
            SidePanel.PlusEvent.Arm(SidePanel.Intent.NEW),
            SidePanel.PlusEvent.OpenChange(open = true, available = false),
        ),
        "holdShowsMenu" to listOf(
            SidePanel.PlusEvent.Arm(SidePanel.Intent.MENU),
            SidePanel.PlusEvent.OpenChange(open = true, available = true),
        ),
        "holdWithNoRecentMakesTab" to listOf(
            SidePanel.PlusEvent.Arm(SidePanel.Intent.MENU),
            SidePanel.PlusEvent.OpenChange(open = true, available = false),
        ),
        "noArmDefaultNew" to listOf(
            SidePanel.PlusEvent.OpenChange(open = true, available = true, defaultIntent = SidePanel.Intent.NEW),
        ),
        "noArmDefaultMenu" to listOf(
            SidePanel.PlusEvent.OpenChange(open = true, available = true, defaultIntent = SidePanel.Intent.MENU),
        ),
        "armTwice" to listOf(
            SidePanel.PlusEvent.Arm(SidePanel.Intent.NEW),
            SidePanel.PlusEvent.Arm(SidePanel.Intent.NEW),
        ),
        "armSwitch" to listOf(
            SidePanel.PlusEvent.Arm(SidePanel.Intent.NEW),
            SidePanel.PlusEvent.Arm(SidePanel.Intent.MENU),
        ),
        "disarmClears" to listOf(
            SidePanel.PlusEvent.Arm(SidePanel.Intent.MENU),
            SidePanel.PlusEvent.Disarm,
        ),
        "closeChangeClears" to listOf(
            SidePanel.PlusEvent.Arm(SidePanel.Intent.MENU),
            SidePanel.PlusEvent.OpenChange(open = false, available = true),
        ),
        "contextMenuOpens" to listOf(SidePanel.PlusEvent.ContextMenu(available = true)),
        "contextMenuUnavailable" to listOf(
            SidePanel.PlusEvent.Arm(SidePanel.Intent.MENU),
            SidePanel.PlusEvent.ContextMenu(available = false),
        ),
        "selectClosesAfterTimer" to listOf(
            SidePanel.PlusEvent.ContextMenu(available = true),
            SidePanel.PlusEvent.Select("file:a"),
            SidePanel.PlusEvent.TimerFired(1),
        ),
        "staleTimerIgnored" to listOf(
            SidePanel.PlusEvent.ContextMenu(available = true),
            SidePanel.PlusEvent.Select("file:a"),
            SidePanel.PlusEvent.TimerFired(0),
        ),
        "closeWhileClosing" to listOf(
            SidePanel.PlusEvent.ContextMenu(available = true),
            SidePanel.PlusEvent.Select("file:a"),
            SidePanel.PlusEvent.OpenChange(open = false, available = true),
        ),
        "closeAutofocus" to listOf(
            SidePanel.PlusEvent.ContextMenu(available = true),
            SidePanel.PlusEvent.Select("file:a"),
            SidePanel.PlusEvent.CloseAutofocus,
        ),
        "pickWhileOpen" to listOf(
            SidePanel.PlusEvent.ContextMenu(available = true),
            SidePanel.PlusEvent.ContextMenu(available = true),
        ),
        "hoverWhileClosed" to listOf(
            SidePanel.PlusEvent.Arm(SidePanel.Intent.MENU),
            SidePanel.PlusEvent.Hover(true),
        ),
        "hoverOffClearsArm" to listOf(
            SidePanel.PlusEvent.Arm(SidePanel.Intent.MENU),
            SidePanel.PlusEvent.Hover(true),
            SidePanel.PlusEvent.Hover(false),
        ),
        "hoverWhileOpen" to listOf(
            SidePanel.PlusEvent.ContextMenu(available = true),
            SidePanel.PlusEvent.Hover(true),
        ),
        "disposedSticks" to listOf(
            SidePanel.PlusEvent.Disposed,
            SidePanel.PlusEvent.Arm(SidePanel.Intent.NEW),
        ),
        "disposedWhileClosing" to listOf(
            SidePanel.PlusEvent.ContextMenu(available = true),
            SidePanel.PlusEvent.Select("file:a"),
            SidePanel.PlusEvent.Disposed,
        ),
    )

    @Test
    fun writePlusTrace() {
        val entries = plusScenarios().entries.toList()
        val sb = StringBuilder("{\n")
        entries.forEachIndexed { i, (name, events) ->
            var st = SidePanel.PlusState()
            val lines = mutableListOf<String>()
            for (ev in events) {
                val r = SidePanel.plusPopover(st, ev)
                st = r.first
                lines += """{"event":"${plusNameOf(ev)}","state":${plusShape(st)},"effects":${plusEffects(r.second)}}"""
            }
            sb.append(" \"").append(name).append("\": [\n")
            lines.forEachIndexed { j, line ->
                sb.append("  ").append(line)
                if (j != lines.lastIndex) sb.append(',')
                sb.append('\n')
            }
            sb.append(" ]")
            if (i != entries.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("}\n")
        File("C:/Users/hachkerss/AppData/Local/Temp/opencode/mine-plus-trace.json")
            .writeText(sb.toString(), Charsets.UTF_8)
        assertEquals(22, entries.size)
    }
}
