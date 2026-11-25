package com.zapp.net.ui

/**
 * The two shapes an `execute` part arrives in, and the card that draws them.
 *
 * Proven from the phone's own store (`oc.db`, five `"name":"execute"` records) and from the
 * desktop's compiled renderer (`route-CMUQLRzL.js`):
 *
 * ```
 * batch   input = { calls: [{ name, args }] }        -> [data-component=execute-tool-calls]
 * single  input = { code: "..." }                    -> the large card, `dm(e)`
 *         content = [{ type: "text", text: "..." }]  -> the Output section
 * ```
 *
 * The single shape was **invisible** in ZAPP: the old renderer only ever looked at `input.calls`
 * and returned early when it was absent, so every real `execute` call drew nothing at all.
 *
 * Their large card, verbatim from `dm(e)`:
 *
 * ```
 * header:  "Execute"  +  (error ? "Failed" : status=="running" ? "Running" : "Completed")
 *                     +  plural("ui.tool.execute.calls", calls)
 * section: "Code"   + plural("ui.tool.execute.lines", code.split(" ").length) + Copy code
 * section: "Output" + ... + Copy output      (or "Error" + Copy error when the call failed)
 * ```
 *
 * **The `lines` count is their own quirk.** `r = e => e.split(" ").length` counts *words*, not
 * newlines, and it is fed straight into the `{{count}} line` key. Reproduced exactly, because
 * "copy their shape" means the number on screen matches theirs - not the number we think is
 * right.
 */
object ExecuteChrome {

    /** What the header says. Their ternary, in their order. */
    fun status(failed: Boolean, running: Boolean): String = when {
        failed -> MessageCopy.FAILED
        running -> MessageCopy.RUNNING
        else -> MessageCopy.COMPLETED
    }

    /**
     * `r(e) = e.split(" ").length` - the token count their `lines` key is fed.
     *
     * `"".split(" ")` in JS is `[""]`, length 1, so an empty block would say "1 line"; their
     * sections only render when there is something to show, and so does ours.
     */
    fun count(text: String): Int = if (text.isEmpty()) 0 else text.split(" ").size

    /** `ui.tool.execute.lines` fed with [count]. */
    fun lines(text: String): String = MessageCopy.lines(count(text))

    /** The section label for the second block: `Error` wins over `Output`. */
    fun resultLabel(failed: Boolean): String = if (failed) MessageCopy.ERROR else MessageCopy.OUTPUT

    /** The copy button's name for the second block. */
    fun resultCopyLabel(failed: Boolean): String =
        if (failed) MessageCopy.COPY_ERROR else MessageCopy.COPY_OUTPUT
}
