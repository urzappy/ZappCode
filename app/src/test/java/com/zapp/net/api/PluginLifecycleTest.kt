package com.zapp.net.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every expectation here came out of `theirs-mcp-plugin.mjs`, which is `shell-D_fdbe5y.js`'s
 * `Ws` / `Gs` / `Ks` / `qs` pasted verbatim and run in Node.
 */
class PluginLifecycleTest {

    private fun eff(e: PluginLifecycle.Effect): String = when (e) {
        is PluginLifecycle.Effect.Check -> "check:${e.attempt}"
        is PluginLifecycle.Effect.Update -> "update:${e.id}:${e.target}"
        is PluginLifecycle.Effect.Failed -> "failed:${e.message}"
        PluginLifecycle.Effect.Refresh -> "refresh"
    }

    private fun go(s: PluginLifecycle.State, e: PluginLifecycle.Event) =
        PluginLifecycle.reduce(s, e).let { it.state to it.effects.map(::eff) }

    private val initial = PluginLifecycle.State()

    /** The state after a check that reported `a` outdated and `b` current. */
    private val found = PluginLifecycle.State(
        status = "idle",
        nextId = 1,
        findings = mapOf(
            "a" to PluginLifecycle.Finding(true, 0),
            "b" to PluginLifecycle.Finding(false, 0),
        ),
    )

    /** `found` with an update for `a` in flight at id 1. */
    private val updating = found.copy(
        nextId = 2,
        updating = listOf(PluginLifecycle.Updating(1, "a")),
    )

    // ---- initial + check ----

    @Test
    fun `the machine starts idle with no findings`() {
        assertEquals("idle", initial.status)
        assertEquals(0, initial.nextId)
        assertTrue(initial.findings.isEmpty())
        assertTrue(initial.updating.isEmpty())
        assertFalse(initial.checking)
    }

    @Test
    fun `a check issues attempt zero and moves nextId`() {
        val (s, e) = go(initial, PluginLifecycle.Event.CheckRequested)
        assertEquals("checking", s.status)
        assertEquals(0, s.attempt)
        assertEquals(1, s.nextId)
        assertTrue(s.checking)
        assertEquals(listOf("check:0"), e)
    }

    @Test
    fun `a second check while one is running is dropped`() {
        val checking = initial.copy(status = "checking", attempt = 0, nextId = 1)
        val (s, e) = go(checking, PluginLifecycle.Event.CheckRequested)
        assertEquals(checking, s)
        assertTrue(e.isEmpty())
    }

    @Test
    fun `a check result for a stale attempt is dropped`() {
        val checking = initial.copy(status = "checking", attempt = 0, nextId = 1)
        val (s, e) = go(
            checking,
            PluginLifecycle.Event.CheckSucceeded(99, mapOf("a" to true)),
        )
        assertEquals(checking, s)
        assertTrue(e.isEmpty())
    }

    @Test
    fun `a successful check records every target at its attempt`() {
        val checking = initial.copy(status = "checking", attempt = 0, nextId = 1)
        val (s, e) = go(
            checking,
            PluginLifecycle.Event.CheckSucceeded(0, mapOf("a" to true, "b" to false)),
        )
        assertEquals("idle", s.status)
        assertEquals(
            mapOf(
                "a" to PluginLifecycle.Finding(true, 0),
                "b" to PluginLifecycle.Finding(false, 0),
            ),
            s.findings,
        )
        assertTrue(e.isEmpty())
    }

    @Test
    fun `a failed check returns to idle with a failed effect`() {
        val checking = initial.copy(status = "checking", attempt = 0, nextId = 1)
        val (s, e) = go(checking, PluginLifecycle.Event.CheckFailed(0, "nope"))
        assertEquals("idle", s.status)
        assertTrue(s.findings.isEmpty())
        assertEquals(listOf("failed:nope"), e)
    }

    // ---- list.loaded ----

    @Test
    fun `a list that agrees with every finding drops all of them`() {
        val (s, e) = go(
            found,
            PluginLifecycle.Event.ListLoaded(mapOf("a" to true, "b" to false)),
        )
        assertTrue(s.findings.isEmpty())
        assertTrue(e.isEmpty())
    }

    @Test
    fun `a list that disagrees keeps that finding`() {
        val (s, _) = go(
            found,
            PluginLifecycle.Event.ListLoaded(mapOf("a" to false, "b" to false)),
        )
        assertEquals(mapOf("a" to PluginLifecycle.Finding(true, 0)), s.findings)
    }

    @Test
    fun `a list missing a target drops its finding`() {
        val (s, _) = go(found, PluginLifecycle.Event.ListLoaded(mapOf("b" to false)))
        assertTrue(s.findings.isEmpty())
    }

    // ---- update ----

    @Test
    fun `an update issues the next id and records it in flight`() {
        val (s, e) = go(found, PluginLifecycle.Event.UpdateRequested("a"))
        assertEquals(2, s.nextId)
        assertEquals(listOf(PluginLifecycle.Updating(1, "a")), s.updating)
        assertEquals(listOf("update:1:a"), e)
    }

    @Test
    fun `a second update for the same target is dropped`() {
        val (s, e) = go(updating, PluginLifecycle.Event.UpdateRequested("a"))
        assertEquals(updating, s)
        assertTrue(e.isEmpty())
    }

    @Test
    fun `a successful update writes outdated false at its own id and refreshes`() {
        val (s, e) = go(updating, PluginLifecycle.Event.UpdateSucceeded(1))
        assertTrue(s.updating.isEmpty())
        assertEquals(PluginLifecycle.Finding(false, 1), s.findings["a"])
        assertEquals(listOf("refresh"), e)
    }

    @Test
    fun `a failed update leaves the finding alone and reports`() {
        val (s, e) = go(updating, PluginLifecycle.Event.UpdateFailed(1, "bad"))
        assertTrue(s.updating.isEmpty())
        assertEquals(PluginLifecycle.Finding(true, 0), s.findings["a"])
        assertEquals(listOf("failed:bad"), e)
    }

    @Test
    fun `an update result for an id that is not in flight is dropped`() {
        val (s, e) = go(updating, PluginLifecycle.Event.UpdateSucceeded(42))
        assertEquals(updating, s)
        assertTrue(e.isEmpty())
    }

    // ---- the two overwrite guards ----

    @Test
    fun `a check cannot overwrite a target that is being updated`() {
        // Their line: `e.updating.some(e=>e.target===n)` skips it, so `a` keeps {true, at 0}
        // while `b` takes the new finding at attempt 2.
        val checking = updating.copy(status = "checking", attempt = 2, nextId = 3)
        val (s, _) = go(
            checking,
            PluginLifecycle.Event.CheckSucceeded(2, mapOf("a" to true, "b" to true)),
        )
        assertEquals(PluginLifecycle.Finding(true, 0), s.findings["a"])
        assertEquals(PluginLifecycle.Finding(true, 2), s.findings["b"])
    }

    @Test
    fun `a check older than a finding cannot overwrite it`() {
        // `a` was written by the update at id 1; a check at attempt 0 is older, so it loses.
        val after = found.copy(findings = found.findings + ("a" to PluginLifecycle.Finding(false, 1)))
        val checking = after.copy(status = "checking", attempt = 0, nextId = 2)
        val (s, _) = go(
            checking,
            PluginLifecycle.Event.CheckSucceeded(0, mapOf("a" to true)),
        )
        assertEquals(PluginLifecycle.Finding(false, 1), s.findings["a"])
    }

    // ---- server.changed / disposed ----

    @Test
    fun `a server change clears findings and keeps nextId`() {
        val (s, e) = go(found, PluginLifecycle.Event.ServerChanged)
        assertEquals("idle", s.status)
        assertEquals(1, s.nextId)
        assertTrue(s.findings.isEmpty())
        assertTrue(s.updating.isEmpty())
        assertTrue(e.isEmpty())
    }

    @Test
    fun `disposing drops every later event`() {
        val (s, e) = go(found, PluginLifecycle.Event.Disposed)
        assertEquals("disposed", s.status)
        assertTrue(e.isEmpty())
        val (again, e2) = go(s, PluginLifecycle.Event.CheckRequested)
        assertEquals("disposed", again.status)
        assertTrue(e2.isEmpty())
    }

    // ---- the per-row view ----

    @Test
    fun `the view falls back to the server flag when no finding exists`() {
        val v = PluginLifecycle.view(initial, "a", outdated = true)
        assertTrue(v.outdated)
        assertFalse(v.updating)
    }

    @Test
    fun `a finding wins over the server flag`() {
        val v = PluginLifecycle.view(found, "a", outdated = false)
        assertTrue(v.outdated)
    }

    @Test
    fun `a row being updated reports updating`() {
        val v = PluginLifecycle.view(updating, "a", outdated = true)
        assertTrue(v.outdated)
        assertTrue(v.updating)
    }

    @Test
    fun `a disposed machine shows neither flag`() {
        val v = PluginLifecycle.view(PluginLifecycle.State(status = "disposed"), "a", true)
        assertFalse(v.outdated)
        assertFalse(v.updating)
    }

    // ---- outdatedMap ----

    @Test
    fun `outdatedMap keeps only package sources and only their target`() {
        val entries = listOf(
            PluginEntry(
                id = "p1",
                source = PluginSource(type = "package", target = "npm:a", outdated = true),
            ),
            PluginEntry(
                id = "p2",
                source = PluginSource(type = "package", target = "npm:b", outdated = false),
            ),
            PluginEntry(
                id = "p3",
                source = PluginSource(type = "builtin", target = "npm:c", outdated = true),
            ),
            PluginEntry(id = "p4", source = PluginSource(type = "package", target = null)),
            PluginEntry(id = "p5", source = null),
        )
        assertEquals(
            mapOf("npm:a" to true, "npm:b" to false),
            PluginLifecycle.outdatedMap(entries),
        )
    }
}
