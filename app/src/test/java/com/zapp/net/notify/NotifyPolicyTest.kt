package com.zapp.net.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of `shell/notifications/notification.tsx`, checked off-device.
 *
 * Each test names the desktop line it locks down, so a future edit that "simplifies" a branch
 * fails here before it reaches the phone.
 */
class NotifyPolicyTest {

    private fun decide(
        kind: NotifyKind,
        sessionTitle: String? = "Fix the parser",
        hasParent: Boolean = false,
        muted: Boolean = false,
        hasOpenTab: Boolean = true,
        foreground: Boolean = false,
        visibleSessionId: String? = null,
        settings: NotifySettings = NotifySettings(),
    ) = NotifyPolicy.decide(
        kind = kind,
        sessionId = "ses_test",
        sessionTitle = sessionTitle,
        hasParent = hasParent,
        muted = muted,
        hasOpenTab = hasOpenTab,
        foreground = foreground,
        visibleSessionId = visibleSessionId,
        settings = settings,
    )

    // ---- defaults are theirs (`settings/model.tsx:635-656`) -----------------------------

    @Test
    fun `defaults match desktop`() {
        val d = NotifySettings()
        assertEquals(false, d.agent)
        assertEquals(true, d.permissions)
        assertEquals(true, d.questions)
        assertEquals(false, d.errors)
        assertTrue(d.soundAgent && d.soundPermissions && d.soundQuestions && d.soundErrors)
    }

    // ---- `:641` no open tab, no notification -------------------------------------------

    @Test
    fun `agent without an open tab is silent`() {
        assertNull(decide(NotifyKind.AGENT, hasOpenTab = false))
    }

    @Test
    fun `permission without an open tab is silent`() {
        assertNull(decide(NotifyKind.PERMISSION, hasOpenTab = false))
    }

    // ---- `:629` / `:677` / `:749` mute --------------------------------------------------

    @Test
    fun `muted session is silent for every kind`() {
        NotifyKind.values().forEach { kind ->
            assertNull("$kind", decide(kind, muted = true))
        }
    }

    // ---- `:637` / `:681` root only ------------------------------------------------------

    @Test
    fun `child session reports no outcome`() {
        assertNull(decide(NotifyKind.AGENT, hasParent = true))
        assertNull(decide(NotifyKind.ERROR, hasParent = true))
    }

    @Test
    fun `child session still forwards a permission it is blocked on`() {
        // `:739` resolves the target tab to the root instead of dropping the request.
        val d = decide(NotifyKind.PERMISSION, hasParent = true)
        assertEquals("Permission required", d?.title)
    }

    // ---- `:645` / `:653` banner and chime are separate ----------------------------------

    @Test
    fun `agent banner off but chime on is a chime, not silence`() {
        val d = decide(NotifyKind.AGENT, settings = NotifySettings(agent = false, soundAgent = true))
        assertEquals(false, d?.banner)
        assertEquals(true, d?.sound)
    }

    @Test
    fun `agent banner and chime both off is silence`() {
        assertNull(decide(NotifyKind.AGENT, settings = NotifySettings(agent = false, soundAgent = false)))
    }

    @Test
    fun `agent banner on is a banner with the session title as the body`() {
        val d = decide(NotifyKind.AGENT, settings = NotifySettings(agent = true))
        assertEquals(true, d?.banner)
        assertEquals("Response ready", d?.title)
        assertEquals("Fix the parser", d?.body)
        assertEquals(NotifyChannel.AGENT, d?.channel)
    }

    // ---- `:697` error fallback -----------------------------------------------------------

    @Test
    fun `error without a title falls back to their string`() {
        val d = decide(
            NotifyKind.ERROR,
            sessionTitle = null,
            settings = NotifySettings(errors = true),
        )
        assertEquals("Session error", d?.title)
        assertEquals("An error occurred", d?.body)
    }

    // ---- `:743` category split ------------------------------------------------------------

    @Test
    fun `question and elicitation use the questions category`() {
        val off = NotifySettings(questions = false, soundQuestions = false)
        assertNull(decide(NotifyKind.QUESTION, settings = off))
        assertNull(decide(NotifyKind.ELICITATION, settings = off))

        assertEquals("Question requires an answer", decide(NotifyKind.QUESTION)?.title)
        assertEquals("Input requested", decide(NotifyKind.ELICITATION)?.title)
    }

    @Test
    fun `websearch provider uses the permissions category`() {
        val questionsOff = NotifySettings(questions = false, soundQuestions = false)
        assertEquals(
            "Permission required",
            decide(NotifyKind.WEBSEARCH, settings = questionsOff)?.title,
        )
        val permissionsOff = NotifySettings(permissions = false, soundPermissions = false)
        assertNull(decide(NotifyKind.WEBSEARCH, settings = permissionsOff))
    }

    @Test
    fun `request notifications use the request channel`() {
        assertEquals(NotifyChannel.REQUEST, decide(NotifyKind.PERMISSION)?.channel)
        assertEquals(NotifyChannel.REQUEST, decide(NotifyKind.QUESTION)?.channel)
    }

    // ---- the phone term -------------------------------------------------------------------

    @Test
    fun `foreground on the same session is silent`() {
        assertNull(
            decide(
                NotifyKind.AGENT,
                settings = NotifySettings(agent = true),
                foreground = true,
                visibleSessionId = "ses_test",
            ),
        )
    }

    @Test
    fun `foreground on another session still notifies`() {
        val d = decide(
            NotifyKind.AGENT,
            settings = NotifySettings(agent = true),
            foreground = true,
            visibleSessionId = "ses_other",
        )
        assertEquals("Response ready", d?.title)
    }

    @Test
    fun `background notifies even on the last visible session`() {
        val d = decide(
            NotifyKind.AGENT,
            settings = NotifySettings(agent = true),
            foreground = false,
            visibleSessionId = "ses_test",
        )
        assertEquals("Response ready", d?.title)
    }
}
