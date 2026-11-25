package com.zapp.net.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every expectation here came out of `theirs-mcp-plugin.mjs`, which is `panel-QKwfqHQW.js`'s
 * `Te` / `Ee` / `ke` / `Ae` / `je` / `Me` / `De` pasted verbatim and run in Node. Nothing was
 * written from reading the code.
 */
class McpToggleTest {

    private fun eff(e: McpToggle.Effect): String = when (e) {
        is McpToggle.Effect.RunToggle -> "run-toggle:${e.name}:${e.attempt}:${e.enabled}:${e.timeoutMs}"
        is McpToggle.Effect.AbortToggle -> "abort-toggle:${e.attempt}"
        is McpToggle.Effect.OpenDialog -> "open-dialog:${e.attempt}:${e.name}"
        is McpToggle.Effect.CloseDialog -> "close-dialog:${e.attempt}"
        is McpToggle.Effect.OpenExternal -> "open-external:${e.url}"
        is McpToggle.Effect.OpenSignIn -> "open-sign-in:${e.name}:${e.signIn}"
        is McpToggle.Effect.ToastFailed -> "toast-failed:${e.message}"
        is McpToggle.Effect.ToastTimedOut -> "toast-timed-out:${e.name}"
    }

    private fun go(s: McpToggle.State, e: McpToggle.Event) =
        McpToggle.dispatch(s, e).let { it.state to it.effects.map(::eff) }

    /** `Te` - the fresh machine. */
    private val fresh = McpToggle.State()

    /** A machine with one toggle in flight for `srv`, attempt 1. */
    private val inflight = McpToggle.State(
        issued = 1,
        toggles = listOf(McpToggle.Toggle("srv", 1)),
    )

    /** The same, plus the error dialog open at attempt 2. */
    private val withDialog = McpToggle.State(
        issued = 2,
        toggles = listOf(McpToggle.Toggle("srv", 1)),
        dialog = McpToggle.Dialog("open", 2, "srv", "first", null),
    )

    // ---- mounts and the settle rule ----

    @Test
    fun `a fresh machine is active with nothing mounted`() {
        assertEquals("active", fresh.status)
        assertEquals(0, fresh.mounts)
        assertEquals(0, fresh.issued)
        assertTrue(fresh.toggles.isEmpty())
        assertEquals(McpToggle.CLOSED_DIALOG, fresh.dialog)
    }

    @Test
    fun `mounting increments and produces no effect`() {
        val (s, e) = go(fresh, McpToggle.Event.Mounted)
        assertEquals(1, s.mounts)
        assertTrue(e.isEmpty())
    }

    @Test
    fun `unmounting an unmounted machine disposes it`() {
        // `je` settles: active, mounts 0, no toggles, no dialog -> disposed.
        val (s, e) = go(fresh, McpToggle.Event.Unmounted)
        assertEquals("disposed", s.status)
        assertTrue(e.isEmpty())
    }

    @Test
    fun `a disposed machine ignores every later event`() {
        val disposed = McpToggle.State(status = "disposed")
        for (ev in listOf(
            McpToggle.Event.Mounted,
            McpToggle.Event.Unmounted,
            McpToggle.Event.Requested("srv", true, "connected"),
            McpToggle.Event.ErrorOpened("srv", "x"),
            McpToggle.Event.Disposed,
        )) {
            val (s, e) = go(disposed, ev)
            assertEquals("disposed", s.status)
            assertTrue(e.isEmpty())
        }
    }

    // ---- toggle.requested ----

    @Test
    fun `a request issues the next attempt and carries the 60s timeout`() {
        val (s, e) = go(fresh, McpToggle.Event.Requested("srv", true, "connected"))
        assertEquals(1, s.issued)
        assertEquals(listOf(McpToggle.Toggle("srv", 1)), s.toggles)
        assertEquals(listOf("run-toggle:srv:1:true:60000"), e)
    }

    @Test
    fun `a pending row refuses the request and the machine then settles away`() {
        val (s, e) = go(fresh, McpToggle.Event.Requested("srv", true, "pending"))
        assertEquals("disposed", s.status)
        assertTrue(e.isEmpty())
    }

    @Test
    fun `a second tap on the same server is dropped`() {
        val (s, e) = go(inflight, McpToggle.Event.Requested("srv", false, "connected"))
        assertEquals(inflight, s)
        assertTrue(e.isEmpty())
    }

    @Test
    fun `busy and pending follow the in-flight list`() {
        assertFalse(fresh.busy("srv"))
        assertFalse(fresh.busyOrPending("srv", "connected"))
        assertTrue(inflight.busy("srv"))
        assertTrue(inflight.busyOrPending("srv", "connected"))
        // `pending` alone is enough even when nothing is in flight for that name.
        assertTrue(fresh.busyOrPending("other", "pending"))
        assertFalse(McpToggle.State(status = "disposed").busy("srv"))
    }

    // ---- toggle results ----

    @Test
    fun `a successful toggle drops the row and the machine settles`() {
        val (s, e) = go(inflight, McpToggle.Event.Succeeded("srv", 1))
        assertEquals("disposed", s.status)
        assertEquals(listOf("abort-toggle:1"), e)
    }

    @Test
    fun `a result for an attempt that is not in flight is dropped`() {
        val (s, e) = go(inflight, McpToggle.Event.Succeeded("srv", 99))
        assertEquals(inflight, s)
        assertTrue(e.isEmpty())
    }

    @Test
    fun `a timeout reports the server by name`() {
        val (s, e) = go(inflight, McpToggle.Event.TimedOut("srv", 1))
        assertEquals("disposed", s.status)
        assertEquals(listOf("abort-toggle:1", "toast-timed-out:srv"), e)
    }

    @Test
    fun `a transport failure toasts the raw message`() {
        val (s, e) = go(
            inflight,
            McpToggle.Event.Failed("srv", 1, "boom", server = false),
        )
        assertEquals("disposed", s.status)
        assertEquals(listOf("abort-toggle:1", "toast-failed:boom"), e)
    }

    @Test
    fun `a server failure with no dialog prefixes the server name`() {
        val (_, e) = go(
            inflight,
            McpToggle.Event.Failed("srv", 1, "auth", server = true),
        )
        assertEquals(listOf("abort-toggle:1", "toast-failed:srv: auth"), e)
    }

    @Test
    fun `a server failure while its dialog is open updates the dialog instead`() {
        val (s, e) = go(
            withDialog,
            McpToggle.Event.Failed("srv", 1, "auth", server = true),
        )
        assertEquals("active", s.status)
        assertTrue(s.toggles.isEmpty())
        assertEquals("auth", s.dialog.error)
        assertEquals(listOf("abort-toggle:1"), e)
    }

    // ---- the error dialog ----

    @Test
    fun `opening an error bumps issued and emits open-dialog`() {
        val (s, e) = go(inflight, McpToggle.Event.ErrorOpened("srv", "first"))
        assertEquals(2, s.issued)
        assertEquals(McpToggle.Dialog("open", 2, "srv", "first", null), s.dialog)
        assertEquals(listOf("open-dialog:2:srv"), e)
    }

    @Test
    fun `a dialog result for the wrong attempt is dropped`() {
        val (s, e) = go(withDialog, McpToggle.Event.ErrorDismissed(999))
        assertEquals(withDialog, s)
        assertTrue(e.isEmpty())
    }

    @Test
    fun `dismissing closes the dialog`() {
        val (s, e) = go(withDialog, McpToggle.Event.ErrorDismissed(2))
        assertEquals(McpToggle.CLOSED_DIALOG, s.dialog)
        assertTrue(e.isEmpty())
    }

    @Test
    fun `copying records the text it copied`() {
        val (s, _) = go(withDialog, McpToggle.Event.ErrorCopied(2, "first"))
        assertEquals("first", s.dialog.copied)
        assertTrue(s.dialog.copiedNow)
    }

    @Test
    fun `an unloaded server update is ignored`() {
        val (s, e) = go(
            withDialog,
            McpToggle.Event.ServerUpdated(2, loaded = false, status = "connected"),
        )
        assertEquals(withDialog, s)
        assertTrue(e.isEmpty())
    }

    @Test
    fun `a connected or disabled status closes the dialog with a close effect`() {
        val (s, e) = go(
            withDialog,
            McpToggle.Event.ServerUpdated(2, loaded = true, status = "connected"),
        )
        assertEquals(McpToggle.CLOSED_DIALOG, s.dialog)
        assertEquals(listOf("close-dialog:2"), e)
    }

    @Test
    fun `a failed status replaces the dialog's error text`() {
        val (s, _) = go(
            withDialog,
            McpToggle.Event.ServerUpdated(2, loaded = true, status = "failed", error = "later"),
        )
        assertEquals("later", s.dialog.error)
        assertEquals("open", s.dialog.status)
    }

    // ---- authorising ----

    @Test
    fun `authorising opens the url and keeps the toggle in flight`() {
        val (s, e) = go(
            inflight,
            McpToggle.Event.Authorizing("srv", 1, "https://x", "tok"),
        )
        assertEquals(inflight, s)
        assertEquals(
            listOf("open-external:https://x", "open-sign-in:srv:tok"),
            e,
        )
    }

    @Test
    fun `authorising without a sign-in payload emits one effect`() {
        val (_, e) = go(inflight, McpToggle.Event.Authorizing("srv", 1, "https://x", null))
        assertEquals(listOf("open-external:https://x"), e)
    }

    // ---- dispose ----

    @Test
    fun `disposing aborts every toggle and closes the dialog`() {
        val s = withDialog.copy(toggles = listOf(McpToggle.Toggle("a", 1), McpToggle.Toggle("b", 2)))
        val (after, e) = go(s, McpToggle.Event.Disposed)
        assertEquals("disposed", after.status)
        assertEquals(
            listOf("abort-toggle:1", "abort-toggle:2", "close-dialog:2"),
            e,
        )
    }
}
