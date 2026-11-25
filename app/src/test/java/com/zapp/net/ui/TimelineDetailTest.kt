package com.zapp.net.ui

import com.zapp.net.ui.TimelineDetail.Expansion
import com.zapp.net.ui.TimelineDetail.Placement
import com.zapp.net.ui.TimelineDetail.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `session-ui/src/timeline/detail.ts`, checked off-device.
 *
 * The preset table is a contract: a wrong `placement` here silently hides a row the user
 * asked to see, so each preset is asserted field by field against the source.
 */
class TimelineDetailTest {

    @Test
    fun `there are five presets in their source order`() {
        assertEquals(
            listOf("everything", "detailed", "compact", "quiet", "text-only"),
            TimelineDetail.presets.map { it.first },
        )
    }

    @Test
    fun `the shipped default is compact`() {
        assertEquals("compact", TimelineDetail.presetId(TimelineDetail.default))
    }

    @Test
    fun `everything shows every row on its own`() {
        val v = TimelineDetail.presets[0].second
        assertEquals(Rule(Placement.Separate, Expansion.Expanded), v.shell)
        assertEquals(Rule(Placement.Separate, Expansion.Expanded), v.edit)
        assertEquals(Rule(Placement.Separate, Expansion.Expanded), v.thinking)
        assertEquals(Rule(Placement.Separate), v.subagents)
        assertEquals(Rule(Placement.Separate), v.notices)
        assertEquals(Rule(Placement.Separate), v.tools)
        assertEquals(Rule(Placement.Separate), v.commentary)
    }

    @Test
    fun `detailed groups thinking but keeps shell and edit apart`() {
        val v = TimelineDetail.presets[1].second
        assertEquals(Rule(Placement.Separate, Expansion.Expanded), v.shell)
        assertEquals(Rule(Placement.Separate, Expansion.Expanded), v.edit)
        assertEquals(Rule(Placement.Grouped, Expansion.Collapsed), v.thinking)
        assertEquals(Rule(Placement.Grouped), v.notices)
        assertEquals(Rule(Placement.Grouped), v.tools)
    }

    @Test
    fun `compact groups shell edit and thinking, collapsed`() {
        val v = TimelineDetail.presets[2].second
        assertEquals(Rule(Placement.Grouped, Expansion.Collapsed), v.shell)
        assertEquals(Rule(Placement.Grouped, Expansion.Collapsed), v.edit)
        assertEquals(Rule(Placement.Grouped, Expansion.Collapsed), v.thinking)
        assertEquals(Rule(Placement.Separate), v.subagents)
        assertEquals(Rule(Placement.Grouped), v.notices)
        assertEquals(Rule(Placement.Grouped), v.tools)
        assertEquals(Rule(Placement.Separate), v.commentary)
    }

    @Test
    fun `quiet hides shell thinking notices and tools but keeps edits grouped`() {
        val v = TimelineDetail.presets[3].second
        assertEquals(Rule(Placement.Hidden, Expansion.Collapsed), v.shell)
        assertEquals(Rule(Placement.Grouped, Expansion.Collapsed), v.edit)
        assertEquals(Rule(Placement.Hidden, Expansion.Collapsed), v.thinking)
        assertEquals(Rule(Placement.Separate), v.subagents)
        assertEquals(Rule(Placement.Hidden), v.notices)
        assertEquals(Rule(Placement.Hidden), v.tools)
    }

    @Test
    fun `text-only hides everything except commentary`() {
        val v = TimelineDetail.presets[4].second
        listOf(v.shell, v.edit, v.thinking).forEach {
            assertEquals(Placement.Hidden, it.placement)
        }
        assertEquals(Placement.Hidden, v.subagents.placement)
        assertEquals(Placement.Hidden, v.notices.placement)
        assertEquals(Placement.Hidden, v.tools.placement)
        assertEquals(Placement.Separate, v.commentary.placement)
    }

    @Test
    fun `a hidden rule ignores its expansion when matching a preset`() {
        val quiet = TimelineDetail.presets[3].second
        val edited = quiet.copy(shell = Rule(Placement.Hidden, Expansion.Expanded))
        assertEquals("quiet", TimelineDetail.presetId(edited))
    }

    @Test
    fun `a details key the preset does not have is not a match`() {
        val compact = TimelineDetail.presets[2].second
        // `tools` carries no `details` on the wire, so `"details" in expected` is false and
        // their comparison fails rather than ignoring the extra key. Faithful to `:185-189`.
        val edited = compact.copy(tools = Rule(Placement.Grouped, Expansion.Expanded))
        assertNull(TimelineDetail.presetId(edited))
    }

    @Test
    fun `a rule that omits its expansion still matches`() {
        val compact = TimelineDetail.presets[2].second
        // `!(\"details\" in current)` short-circuits: a value without the key matches whatever
        // expansion the preset declares.
        val edited = compact.copy(shell = Rule(Placement.Grouped))
        assertEquals("compact", TimelineDetail.presetId(edited))
    }

    @Test
    fun `a different placement is not any preset`() {
        val compact = TimelineDetail.presets[2].second
        val edited = compact.copy(shell = Rule(Placement.Separate, Expansion.Collapsed))
        assertNull(TimelineDetail.presetId(edited))
    }

    @Test
    fun `a different expansion on a visible rule is not any preset`() {
        val compact = TimelineDetail.presets[2].second
        val edited = compact.copy(shell = Rule(Placement.Grouped, Expansion.Expanded))
        assertNull(TimelineDetail.presetId(edited))
    }

    @Test
    fun `prose has no category`() {
        assertNull(TimelineDetail.category("text", null))
    }

    @Test
    fun `reasoning is thinking`() {
        assertEquals("thinking", TimelineDetail.category("reasoning", null))
    }

    @Test
    fun `the three shell tool names are shell`() {
        listOf("shell", "execute", "bash").forEach {
            assertEquals("shell", TimelineDetail.category("tool", it))
        }
    }

    @Test
    fun `the four edit tool names are edit`() {
        listOf("edit", "write", "patch", "apply_patch").forEach {
            assertEquals("edit", TimelineDetail.category("tool", it))
        }
    }

    @Test
    fun `subagent and task are subagents`() {
        listOf("subagent", "task").forEach {
            assertEquals("subagents", TimelineDetail.category("tool", it))
        }
    }

    @Test
    fun `every other tool is tools`() {
        assertEquals("tools", TimelineDetail.category("tool", "read"))
        assertEquals("tools", TimelineDetail.category("tool", "grep"))
        assertEquals("tools", TimelineDetail.category("tool", null))
    }

    @Test
    fun `a shell is failed by its exit code or its timeout`() {
        assertTrue(TimelineDetail.shellResultFailed(timeout = true, exit = 0))
        assertTrue(TimelineDetail.shellResultFailed(timeout = false, exit = 1))
        assertFalse(TimelineDetail.shellResultFailed(timeout = false, exit = 0))
        assertFalse(TimelineDetail.shellResultFailed(timeout = false, exit = null))
    }

    @Test
    fun `a completed compaction is not required`() {
        assertFalse(TimelineDetail.noticeRequired("compaction", "completed", null, null))
        assertTrue(TimelineDetail.noticeRequired("compaction", "running", null, null))
    }

    @Test
    fun `a plain synthetic notice is not required`() {
        assertFalse(TimelineDetail.noticeRequired("synthetic", null, "completed", "shell"))
    }

    @Test
    fun `a synthetic error is required`() {
        assertTrue(TimelineDetail.noticeRequired("synthetic", null, "error", "shell"))
    }

    @Test
    fun `a failed shell notice is required but a clean one is not`() {
        assertTrue(
            TimelineDetail.noticeRequired("synthetic", null, "completed", "shell", timeout = false, exit = 2),
        )
        assertFalse(
            TimelineDetail.noticeRequired("synthetic", null, "completed", "shell", timeout = false, exit = 0),
        )
    }

    @Test
    fun `a hidden notices category still shows a required notice on its own`() {
        val quiet = TimelineDetail.presets[3].second
        assertEquals(
            Placement.Separate,
            TimelineDetail.noticePlacement("synthetic", null, "error", "shell", quiet),
        )
        assertEquals(
            Placement.Hidden,
            TimelineDetail.noticePlacement("synthetic", null, "completed", "shell", quiet, exit = 0),
        )
    }

    @Test
    fun `background shell work always stands alone even when grouped`() {
        val compact = TimelineDetail.presets[2].second
        assertEquals(
            Placement.Separate,
            TimelineDetail.noticePlacement("synthetic", null, "completed", "shell", compact, exit = 0),
        )
        assertEquals(
            Placement.Separate,
            TimelineDetail.noticePlacement("synthetic", null, "completed", "subagent", compact),
        )
    }

    @Test
    fun `a plain notice follows the notices rule`() {
        val compact = TimelineDetail.presets[2].second
        assertEquals(
            Placement.Grouped,
            TimelineDetail.noticePlacement("synthetic", null, "completed", "other", compact),
        )
    }
}
