package com.zapp.net.ui

import com.zapp.net.api.Part

/**
 * The one line under the timeline: `session.timeline.status.*` plus `ui.sessionTurn.status.*`.
 *
 * Both namespaces are verbatim from the shipped PC bundle
 * (`main-CLeW75FU.js.map`, i18n table at offset ~3 913 200):
 *
 * ```
 * session.timeline.status.working          = "Working"
 * session.timeline.status.waitingAnswer    = "Waiting for your answer"
 * session.timeline.status.waitingApproval  = "Waiting for approval"
 * session.timeline.status.waitingShell     = "Waiting for shell"
 *
 * ui.sessionTurn.status.thinking           = "Thinking"
 * ui.sessionTurn.status.thinkingWithTopic  = "Thinking - {{topic}}"
 * ui.sessionTurn.status.gatheringThoughts  = "Gathering thoughts"
 * ui.sessionTurn.status.consideringNextSteps = "Considering next steps"
 * ui.sessionTurn.status.searchingCodebase  = "Searching the codebase"
 * ui.sessionTurn.status.searchingWeb       = "Searching the web"
 * ui.sessionTurn.status.runningCommands    = "Running commands"
 * ui.sessionTurn.status.makingEdits        = "Making edits"
 * ui.sessionTurn.status.gatheringContext   = "Exploring"
 * ui.sessionTurn.status.gatheredContext    = "Explored"
 * ui.sessionTurn.status.delegating         = "Delegating work"
 * ui.sessionTurn.status.planning           = "Planning next steps"
 * ```
 *
 * ZAPP shipped a hard-coded `"Working"` in that band for every turn, with a comment above it
 * claiming the waiting states were implemented. They were not. The three gates below are the
 * whole point of the band: a turn that is blocked on him looks identical to a turn that is
 * working, which is exactly the state he cannot afford to miss.
 *
 * Pure, so it is testable off-device - same reason [ShellChrome] and [TimelineDetail] are.
 */
object TurnStatus {

    // `session.timeline.status.*`
    const val WORKING = "Working"
    const val WAITING_ANSWER = "Waiting for your answer"
    const val WAITING_APPROVAL = "Waiting for approval"
    const val WAITING_SHELL = "Waiting for shell"

    // `ui.sessionTurn.status.*`
    const val THINKING = "Thinking"
    const val GATHERING_THOUGHTS = "Gathering thoughts"
    const val CONSIDERING_NEXT_STEPS = "Considering next steps"
    const val SEARCHING_CODEBASE = "Searching the codebase"
    const val SEARCHING_WEB = "Searching the web"
    const val RUNNING_COMMANDS = "Running commands"
    const val MAKING_EDITS = "Making edits"
    const val GATHERING_CONTEXT = "Exploring"
    const val GATHERED_CONTEXT = "Explored"
    const val DELEGATING = "Delegating work"
    const val PLANNING = "Planning next steps"

    /** `ui.sessionTurn.status.thinkingWithTopic` with `{{topic}}` filled in. */
    fun thinkingWithTopic(topic: String): String =
        if (topic.isBlank()) THINKING else "Thinking - ${topic.trim()}"

    private val shellTools = setOf("shell", "bash", "execute", "terminal", "run")
    private val editTools = setOf("edit", "write", "patch", "multiedit", "apply_patch", "multiedit")
    private val readTools = setOf("read", "list", "ls", "open")
    private val searchTools = setOf("grep", "glob", "find", "search", "codesearch")
    private val webTools = setOf("webfetch", "fetch", "websearch", "web_search")
    private val agentTools = setOf("task", "agent", "delegate", "subagent")
    private val todoTools = setOf("todowrite", "todoread", "todo")

    /** `ui.messagePart.compaction.running` = "Compacting". */
    const val COMPACTING = "Compacting"

    /** `ui.messagePart.compaction.failed` = "Session compaction failed". */
    const val COMPACTION_FAILED = "Session compaction failed"

    /**
     * The label for a single step, in their vocabulary.
     *
     * `grep`/`glob`/`find` say "Searching the codebase", not "Exploring": PC has both keys and
     * they mean different things - reading files explores, looking for something searches.
     */
    fun step(part: Part?): String {
        if (part == null) return GATHERING_THOUGHTS
        when (part.type) {
            // the owner, 22 Nov: "this two thinking make mo yung isa palitan name working". The
            // collapsible row above already reads "Thinking" while it streams, so a second
            // "Thinking" in the band below was the same word twice in one column. The band's job
            // is the turn, not the thought: `session.timeline.status.working` is the string their
            // status line uses for a turn that is simply running.
            "reasoning" -> return WORKING
            "text" -> return CONSIDERING_NEXT_STEPS
            "tool" -> Unit
            else -> return WORKING
        }
        return when (part.name.orEmpty().lowercase()) {
            in shellTools -> RUNNING_COMMANDS
            in editTools -> MAKING_EDITS
            in searchTools -> SEARCHING_CODEBASE
            in readTools -> GATHERING_CONTEXT
            in webTools -> SEARCHING_WEB
            in agentTools -> DELEGATING
            in todoTools -> PLANNING
            else -> WORKING
        }
    }

    /**
     * The line for a live turn.
     *
     * A gate outranks the step: if the server is holding a permission, a question or a shell
     * request, the turn is not progressing and saying "Running commands" over it is a lie.
     *
     * @param approvals permissions still unanswered
     * @param answers   question forms still unanswered
     * @param shells    shell requests still unanswered
     * @param last      the newest part of the live turn
     * @param running   whether that part is still producing
     */
    fun footer(
        last: Part?,
        running: Boolean,
        approvals: Int = 0,
        answers: Int = 0,
        shells: Int = 0,
        compacting: Boolean = false,
    ): String {
        // Order is a judgement, not a port: the extract gives the four strings but not their
        // precedence. Approval is first because a blocked tool stops the whole turn, a question
        // second because the model cannot continue without the answer, a shell request last
        // because it blocks one call rather than the turn.
        if (approvals > 0) return WAITING_APPROVAL
        if (answers > 0) return WAITING_ANSWER
        if (shells > 0) return WAITING_SHELL
        // A compaction is a real operation with a duration, and it happens *before* the turn's
        // first part exists - without this the band says "Gathering thoughts" while the server
        // is rewriting the whole history.
        if (compacting) return COMPACTING
        if (last == null) return GATHERING_THOUGHTS
        if (running) return step(last)
        // Every part has landed and the turn has not closed: the model is deciding what next.
        // That is exactly what `consideringNextSteps` is for, and it is the honest alternative
        // to leaving "Working" up while nothing is happening.
        return CONSIDERING_NEXT_STEPS
    }

    /**
     * `gatheredContext` = "Explored" - the past-tense form, used on a step row that has finished
     * rather than on the live footer.
     */
    fun finished(part: Part?): String {
        val s = step(part)
        return if (s == GATHERING_CONTEXT || s == SEARCHING_CODEBASE) GATHERED_CONTEXT else s
    }
}
