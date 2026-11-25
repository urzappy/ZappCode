package com.zapp.net.notify

/**
 * The decision half of `shell/notifications/notification.tsx`, with no Android in it, so the
 * rules can be tested off-device against theirs.
 *
 * Every branch below cites the line it comes from:
 *
 *  - `:629` / `:677` — a muted session says nothing.
 *  - `:637` / `:681` — only a root session reports an outcome; a child's report wakes the
 *    parent, which is what `hasRunningChild` (`:621-623`) is protecting.
 *  - `:641` / `:739` — no open tab, no notification.
 *  - `:645` / `:653` — the chime and the banner are separate switches.
 *  - `:697` — an error with no title falls back to `notification.session.error.fallbackDescription`.
 *  - `:743` — `question` and `mcp-elicitation` are the "questions" category; `websearch.provider`
 *    is a permission.
 *  - `:797` — the banner title is the request title, the body is the session title.
 */
data class NotifySettings(
    val agent: Boolean = false,
    val permissions: Boolean = true,
    val questions: Boolean = true,
    val errors: Boolean = false,
    val soundAgent: Boolean = true,
    val soundPermissions: Boolean = true,
    val soundQuestions: Boolean = true,
    val soundErrors: Boolean = true,
    /**
     * `settings/model.tsx:655` — `sounds.interfaceEnabled`, default true. Desktop plays a
     * subtle tone when the wordmark appears and while you play with it
     * (`settings.general.sounds.interface.description`). There is no wordmark on the phone, so
     * the flag is carried and shown in settings but nothing consumes it yet.
     */
    val soundInterface: Boolean = true,
    /**
     * The four sound **ids** (`settings/model.tsx:641-653`). Desktop's defaults, read out of the
     * shipped defaults block in `main-CLeW75FU.js` @ 1 574 282:
     *
     *     sounds: { agentEnabled:!0, agent:`staplebops-01`, permissionsEnabled:!0,
     *               permissions:`staplebops-02`, questionsEnabled:!0, questions:`staplebops-05`,
     *               errorsEnabled:!0, errors:`nope-03`, interfaceEnabled:!0 }
     *
     * An id is only consulted when the matching `sound*` switch is on. `"none"` never reaches
     * here: their picker turns `None` into `enabled = false` rather than storing it.
     */
    val idAgent: String = "staplebops-01",
    val idPermissions: String = "staplebops-02",
    val idQuestions: String = "staplebops-05",
    val idErrors: String = "nope-03",
)

/** The four triggers, split the way their `requestTitles` table splits them. */
enum class NotifyKind { AGENT, ERROR, PERMISSION, QUESTION, ELICITATION, WEBSEARCH }

enum class NotifyChannel { AGENT, REQUEST }

/**
 * `banner = false` means chime only: desktop plays `sounds.*` even when `notifications.*` is
 * off, so a silent banner or a bare tone are both real outcomes. A null decision is silence.
 */
data class NotifyDecision(
    val title: String,
    val body: String,
    val channel: NotifyChannel,
    val banner: Boolean,
    val sound: Boolean,
    /** Which file the chime plays - their `sounds.agent()` etc. */
    val soundId: String,
)

object NotifyPolicy {

    fun decide(
        kind: NotifyKind,
        sessionId: String,
        sessionTitle: String?,
        hasParent: Boolean,
        muted: Boolean,
        hasOpenTab: Boolean,
        foreground: Boolean,
        visibleSessionId: String?,
        settings: NotifySettings,
    ): NotifyDecision? {
        // `:637` / `:681`
        if ((kind == NotifyKind.AGENT || kind == NotifyKind.ERROR) && hasParent) return null
        // `:629` / `:677` / `:749`
        if (muted) return null
        // `:641` / `:739`
        if (!hasOpenTab) return null
        // The phone term. Desktop notifies a tab whether or not it is the focused one; a banner
        // over the chat already on screen is noise, so only that case is dropped.
        if (foreground && visibleSessionId == sessionId) return null

        val title = sessionTitle?.takeIf { it.isNotBlank() }
        val body = title ?: sessionId

        val decision = when (kind) {
            NotifyKind.AGENT -> NotifyDecision(
                "Response ready", body, NotifyChannel.AGENT, settings.agent, settings.soundAgent,
                settings.idAgent,
            )
            NotifyKind.ERROR -> NotifyDecision(
                // `:697` — `session.title ?? notification.session.error.fallbackDescription`
                "Session error", title ?: "An error occurred",
                NotifyChannel.AGENT, settings.errors, settings.soundErrors, settings.idErrors,
            )
            NotifyKind.PERMISSION -> NotifyDecision(
                "Permission required", body, NotifyChannel.REQUEST,
                settings.permissions, settings.soundPermissions, settings.idPermissions,
            )
            NotifyKind.QUESTION -> NotifyDecision(
                "Question requires an answer", body, NotifyChannel.REQUEST,
                settings.questions, settings.soundQuestions, settings.idQuestions,
            )
            NotifyKind.ELICITATION -> NotifyDecision(
                "Input requested", body, NotifyChannel.REQUEST,
                settings.questions, settings.soundQuestions, settings.idQuestions,
            )
            NotifyKind.WEBSEARCH -> NotifyDecision(
                "Permission required", body, NotifyChannel.REQUEST,
                settings.permissions, settings.soundPermissions, settings.idPermissions,
            )
        }

        // Neither a banner nor a tone is a no-op; say so instead of posting silence.
        if (!decision.banner && !decision.sound) return null
        return decision
    }
}
