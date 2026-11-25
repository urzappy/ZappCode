package com.zapp.net.ui

import com.zapp.net.ui.MarkdownImages.Effect
import com.zapp.net.ui.MarkdownImages.Entry
import com.zapp.net.ui.MarkdownImages.Event
import com.zapp.net.ui.MarkdownImages.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `session-ui/src/components/markdown-image-machine.ts` — the loader's transition table.
 *
 * The cases that matter are the ones a naive loader gets wrong: a late reply from an abandoned
 * attempt, the backoff doubling, the retry cap, and a path disappearing from the container.
 */
class MarkdownImagesTest {

    private fun dispatch(vararg events: Event): MarkdownImages.Transition {
        var state: State = MarkdownImages.INITIAL
        var last = MarkdownImages.Transition(state, emptyList())
        for (e in events) {
            last = MarkdownImages.transition(state, e)
            state = last.state
        }
        return last
    }

    @Test
    fun `the constants are theirs`() {
        assertEquals(15_000, MarkdownImages.READ_TIMEOUT_MS)
        assertEquals(1_000, MarkdownImages.RETRY_BASE_MS)
        assertEquals(3, MarkdownImages.MAX_RETRIES)
    }

    @Test
    fun `a new path starts a read with attempt one`() {
        val t = MarkdownImages.transition(MarkdownImages.INITIAL, Event.PathsSynced(setOf("a.png")))
        assertEquals(
            State.Active(1, mapOf("a.png" to Entry.Loading(1, 0))),
            t.state,
        )
        assertEquals(listOf<Effect>(Effect.Read("a.png", 1, 15_000)), t.effects)
    }

    @Test
    fun `syncing the same set changes nothing`() {
        val first = MarkdownImages.transition(MarkdownImages.INITIAL, Event.PathsSynced(setOf("a.png")))
        val second = MarkdownImages.transition(first.state, Event.PathsSynced(setOf("a.png")))
        assertEquals(first.state, second.state)
        assertTrue(second.effects.isEmpty())
    }

    @Test
    fun `a removed path cancels its read`() {
        val loaded = dispatch(
            Event.PathsSynced(setOf("a.png")),
            Event.PathsSynced(emptySet()),
        )
        assertEquals(State.Active(1, emptyMap()), loaded.state)
        assertEquals(listOf<Effect>(Effect.Cancel(1)), loaded.effects)
    }

    @Test
    fun `a removed loaded path with a revocable url releases it`() {
        val t = dispatch(
            Event.PathsSynced(setOf("a.png")),
            Event.ReadSucceeded("a.png", 1, "blob:1", revoke = true),
            Event.PathsSynced(emptySet()),
        )
        assertEquals(listOf<Effect>(Effect.RevokeUrl("blob:1")), t.effects)
    }

    @Test
    fun `a loaded svg data url is not released`() {
        val t = dispatch(
            Event.PathsSynced(setOf("a.svg")),
            Event.ReadSucceeded("a.svg", 1, "data:image/svg+xml,x", revoke = false),
            Event.PathsSynced(emptySet()),
        )
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `success stores the url`() {
        val t = dispatch(
            Event.PathsSynced(setOf("a.png")),
            Event.ReadSucceeded("a.png", 1, "blob:1", revoke = true),
        )
        assertEquals(State.Active(1, mapOf("a.png" to Entry.Loaded("blob:1", true))), t.state)
        assertEquals(listOf<Effect>(Effect.Cancel(1)), t.effects)
        assertEquals("blob:1", MarkdownImages.selectUrl(t.state, "a.png"))
    }

    @Test
    fun `a failure backs off one second`() {
        val t = dispatch(
            Event.PathsSynced(setOf("a.png")),
            Event.ReadFailed("a.png", 1),
        )
        assertEquals(State.Active(2, mapOf("a.png" to Entry.Waiting(2, 1))), t.state)
        assertEquals(
            listOf<Effect>(Effect.Cancel(1), Effect.Wait("a.png", 2, 1_000)),
            t.effects,
        )
    }

    @Test
    fun `each retry doubles the wait`() {
        val t = dispatch(
            Event.PathsSynced(setOf("a.png")),
            Event.ReadFailed("a.png", 1),
            Event.RetryDue("a.png", 2),
            Event.ReadTimedOut("a.png", 3),
        )
        // failures was 1, so the second wait is 2 ** 1 * 1000.
        assertEquals(
            listOf<Effect>(Effect.Cancel(3), Effect.Wait("a.png", 4, 2_000)),
            t.effects,
        )
    }

    @Test
    fun `the third failure waits four seconds`() {
        val t = dispatch(
            Event.PathsSynced(setOf("a.png")),
            Event.ReadFailed("a.png", 1),
            Event.RetryDue("a.png", 2),
            Event.ReadFailed("a.png", 3),
            Event.RetryDue("a.png", 4),
            Event.ReadFailed("a.png", 5),
        )
        assertEquals(
            listOf<Effect>(Effect.Cancel(5), Effect.Wait("a.png", 6, 4_000)),
            t.effects,
        )
    }

    @Test
    fun `after three failures the image is failed`() {
        val t = dispatch(
            Event.PathsSynced(setOf("a.png")),
            Event.ReadFailed("a.png", 1),
            Event.RetryDue("a.png", 2),
            Event.ReadFailed("a.png", 3),
            Event.RetryDue("a.png", 4),
            Event.ReadFailed("a.png", 5),
            Event.RetryDue("a.png", 6),
            Event.ReadFailed("a.png", 7),
        )
        assertEquals(State.Active(7, mapOf("a.png" to Entry.Failed)), t.state)
        assertEquals(listOf<Effect>(Effect.Cancel(7)), t.effects)
    }

    @Test
    fun `an empty read fails at once, with no retry`() {
        val t = dispatch(
            Event.PathsSynced(setOf("a.png")),
            Event.ReadEmpty("a.png", 1),
        )
        assertEquals(State.Active(1, mapOf("a.png" to Entry.Failed)), t.state)
        assertEquals(listOf<Effect>(Effect.Cancel(1)), t.effects)
    }

    @Test
    fun `a late reply from an old attempt is ignored`() {
        val t = dispatch(
            Event.PathsSynced(setOf("a.png")),
            Event.ReadFailed("a.png", 1),
            Event.RetryDue("a.png", 2),
            // attempt 1 answers after the retry already started attempt 2
            Event.ReadSucceeded("a.png", 1, "blob:old", revoke = false),
        )
        assertEquals(State.Active(3, mapOf("a.png" to Entry.Loading(3, 1))), t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `a late revocable reply is still released`() {
        // ":327 — A reader that ignores its abort signal can still hand back a blob URL after
        // the entry moved on; release it."
        val t = dispatch(
            Event.PathsSynced(setOf("a.png")),
            Event.ReadFailed("a.png", 1),
            Event.RetryDue("a.png", 2),
            Event.ReadSucceeded("a.png", 1, "blob:old", revoke = true),
        )
        assertEquals(listOf<Effect>(Effect.RevokeUrl("blob:old")), t.effects)
    }

    @Test
    fun `a retry that arrives before the wait is ignored`() {
        val t = dispatch(
            Event.PathsSynced(setOf("a.png")),
            Event.RetryDue("a.png", 1),
        )
        // entry is Loading(1,0), not Waiting, so the event does nothing
        assertEquals(State.Active(1, mapOf("a.png" to Entry.Loading(1, 0))), t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `two new paths get consecutive attempts`() {
        val t = MarkdownImages.transition(
            MarkdownImages.INITIAL,
            Event.PathsSynced(setOf("a.png", "b.png")),
        )
        assertEquals(
            listOf<Effect>(Effect.Read("a.png", 1, 15_000), Effect.Read("b.png", 2, 15_000)),
            t.effects,
        )
        assertEquals(2, (t.state as State.Active).attempts)
    }

    @Test
    fun `an empty path is never read`() {
        val t = MarkdownImages.transition(MarkdownImages.INITIAL, Event.PathsSynced(setOf("")))
        assertEquals(State.Active(0, emptyMap()), t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `disposal releases everything and stops`() {
        val t = dispatch(
            Event.PathsSynced(setOf("a.png")),
            Event.ReadSucceeded("a.png", 1, "blob:1", revoke = true),
            Event.Disposed,
        )
        assertEquals(MarkdownImages.DISPOSED, t.state)
        assertEquals(listOf<Effect>(Effect.RevokeUrl("blob:1")), t.effects)
        assertNull(MarkdownImages.selectUrl(t.state, "a.png"))
    }

    @Test
    fun `a disposed machine ignores every event`() {
        val t = MarkdownImages.transition(MarkdownImages.DISPOSED, Event.PathsSynced(setOf("a.png")))
        assertEquals(MarkdownImages.DISPOSED, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `selectUrl is null while loading and after failure`() {
        val loading = MarkdownImages.transition(MarkdownImages.INITIAL, Event.PathsSynced(setOf("a.png")))
        assertNull(MarkdownImages.selectUrl(loading.state, "a.png"))
        val failed = dispatch(Event.PathsSynced(setOf("a.png")), Event.ReadEmpty("a.png", 1))
        assertNull(MarkdownImages.selectUrl(failed.state, "a.png"))
    }
}
