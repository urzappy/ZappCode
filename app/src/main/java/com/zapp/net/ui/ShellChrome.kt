package com.zapp.net.ui

/**
 * The one line PC prints above a shell tool row, ported from the `ui.tool.shell.*` keys.
 *
 * Their keys, verbatim from the shipped bundle (`app.asar`, i18n table at 105 524 459):
 *
 *   ui.tool.shell                 = "Shell"
 *   ui.tool.shell.writingCommand  = "Writing command…"
 *   ui.tool.shell.exit            = "Command exited with code {{code}}"
 *   ui.tool.shell.timeout         = "Command timed out"
 *   ui.tool.shell.cancelled       = "Command cancelled"
 *
 * There is **no** "finished" key. A command that exited 0 prints nothing at all, and the line
 * ZAPP used to draw - `"Shell " + state`, i.e. "Shell finished" / "Shell completed" / "Shell
 * error" - was ours, not theirs. Raw state words (`streaming`, `pending`, `completed`) are
 * internal and never reach the screen on the PC.
 *
 * Kept pure so it can be tested without a device, the same way [NotifyPolicy] and
 * [TimelineDetail] are.
 */
object ShellChrome {

    const val WRITING = "Writing command…"
    const val TIMEOUT = "Command timed out"
    const val CANCELLED = "Command cancelled"

    /** `ui.tool.shell.exit` with `{{code}}` filled in. */
    fun exitLabel(code: Int): String = "Command exited with code $code"

    /** States in which the command is still being written or is still on the wire. */
    private val inFlight = setOf("pending", "streaming", "running", "writing", "queued")

    /** States that mean the run ended without an exit code. */
    private val stopped = setOf(
        "cancelled", "canceled", "interrupted", "stopped", "killed", "aborted",
    )

    private val timeouts = setOf("timeout", "timedout", "timed_out")

    private val errored = setOf("error", "failed")

    /**
     * The line to draw, or `""` for none.
     *
     * Order matters and follows their precedence: a run still writing shows the writing line
     * even if stale metadata from a previous attempt is attached; a timeout outranks a
     * cancellation (the server sets `timeout: true` and may also mark it interrupted); a
     * non-zero exit is the only exit they report.
     */
    fun label(
        state: String?,
        exit: Int? = null,
        timeout: Boolean = false,
        interrupted: Boolean = false,
    ): String {
        val s = state.orEmpty().lowercase()
        if (s in inFlight) return WRITING
        if (timeout || s in timeouts) return TIMEOUT
        if (interrupted || s in stopped) return CANCELLED
        if (exit != null && exit != 0) return exitLabel(exit)
        // error/failed with no exit code: their row still shows the failure, but the only
        // label they have for it is the exit line, and there is no code to put in it.
        return ""
    }

    /**
     * Whether the line belongs in the failure colour.
     *
     * A cancelled command is not a failure on the PC - it is a stop he asked for - so it keeps
     * the dim colour and only a real exit error, a timeout, or an `error` status turns red.
     */
    fun failed(
        state: String?,
        exit: Int? = null,
        timeout: Boolean = false,
    ): Boolean {
        val s = state.orEmpty().lowercase()
        if (s in errored) return true
        if (timeout || s in timeouts) return true
        return exit != null && exit != 0
    }

    /** `ui.tool.shell` - the tool's own name, used where a bare name is needed. */
    const val NAME = "Shell"
}
