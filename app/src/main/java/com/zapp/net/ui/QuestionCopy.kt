package com.zapp.net.ui

import com.zapp.net.api.FormField

/**
 * The question card's vocabulary, verbatim from the shipped PC bundle
 * (`main-CLeW75FU.js.map`, i18n table at offset ~3 913 800).
 *
 * ```
 * session.question.title                    = "Question"
 * session.question.progress                 = "{{current}} of {{total}} questions"
 * session.question.minimize                 = "Minimize question"
 * session.question.restore                  = "Restore question"
 *
 * ui.question.singleHint                    = "Select one answer"
 * ui.question.multiHint                     = "Pick any"
 * ui.question.classic.multiHint             = "Select all answers that apply"
 * ui.question.custom.placeholder            = "Type your answer…"
 * ui.question.custom.answerPlaceholder      = "Type your own answer…"
 * ui.question.subtitle.answered             = "{{count}} answered"
 * ui.question.review.notAnswered            = "(not answered)"
 * ui.question.answer.none                   = "(no answer)"
 *
 * ui.messagePart.option.typeOwnAnswer       = "Type your own answer"
 * ui.messagePart.review.title               = "Review your answers"
 * ui.messagePart.questions.dismissed        = "Questions dismissed"
 *
 * ui.common.submit / cancel / back / next   = "Submit" / "Cancel" / "Back" / "Next"
 * ```
 *
 * **What is a port and what is not.** The strings are a port. The layout is not: the
 * question renderer is absent from the extract (`main-CLeW75FU.js.map` has no
 * `question*.tsx`; `typeOwnAnswer` appears only in the 130 locale tables inside
 * `app.asar`). So the wording is theirs and the arrangement is ours, and that is
 * recorded as a disclosed deviation in `ZAPP-PARITY-MATRIX.md`.
 *
 * **What the server actually sends.** Proven from the phone's own store
 * (`opencode.db`, `tool name=question`):
 *
 * ```json
 * {"type":"tool","name":"question","state":{
 *   "status":"running",
 *   "input":{"questions":[{"question":"…","header":"…","options":[
 *     {"label":"…","description":"…"}]}]},
 *   "metadata":{}}}
 * ```
 *
 * and once answered, `status:"completed"` with
 * `metadata.answers = [["Make files longer"]]`. The custom-answer switch is
 * `Form.StringField.custom` / `Form.MultiselectField.custom` in the live contract
 * (`openapi.json`), which ZAPP parsed and then never read.
 *
 * Pure, so it is testable off-device - same reason [TurnStatus] and [ShellChrome] are.
 */
object QuestionCopy {

    const val TITLE = "Question"
    const val MINIMIZE = "Minimize question"
    const val RESTORE = "Restore question"

    const val SINGLE_HINT = "Select one answer"
    const val MULTI_HINT = "Pick any"
    const val CLASSIC_MULTI_HINT = "Select all answers that apply"

    /** U+2026, not three dots - the PC value ends in a real ellipsis. */
    const val CUSTOM_PLACEHOLDER = "Type your answer\u2026"
    const val CUSTOM_ANSWER_PLACEHOLDER = "Type your own answer\u2026"

    const val NOT_ANSWERED = "(not answered)"
    const val NO_ANSWER = "(no answer)"

    const val TYPE_OWN_ANSWER = "Type your own answer"
    const val REVIEW_TITLE = "Review your answers"
    const val DISMISSED = "Questions dismissed"

    const val SUBMIT = "Submit"
    const val CANCEL = "Cancel"
    const val BACK = "Back"
    const val NEXT = "Next"

    /** `session.question.progress` = "{{current}} of {{total}} questions". */
    fun progress(current: Int, total: Int): String = "$current of $total questions"

    /** `ui.question.subtitle.answered` = "{{count}} answered". */
    fun answered(count: Int): String = "$count answered"

    /**
     * The hint under a field's title.
     *
     * `classic.multiHint` ("Select all answers that apply") and `multiHint`
     * ("Pick any") are two separate keys, so PC has two presentations. Ours is the
     * compact one: the phone card is narrow and "Pick any" is the short form.
     * `classic.multiHint` is kept in [CLASSIC_MULTI_HINT] for the record but is not
     * rendered - a disclosed deviation, not an oversight.
     */
    fun hint(field: FormField): String? = when (field.type) {
        "multiselect" -> MULTI_HINT
        "string" -> if (field.options.isNotEmpty()) SINGLE_HINT else null
        else -> null
    }

    /**
     * The placeholder for a free-text field.
     *
     * The field's own `placeholder` wins when the server sent one; otherwise it is
     * theirs. A number field says `number` because it always did - PC has no key for
     * it and ZAPP has shown it since the first form card.
     */
    fun placeholder(field: FormField): String = when {
        !field.placeholder.isNullOrBlank() -> field.placeholder
        field.type == "number" || field.type == "integer" -> "number"
        else -> CUSTOM_PLACEHOLDER
    }

    /**
     * The label for the synthetic "write your own" row.
     *
     * Only rendered when the server sets `custom: true` on a field with options -
     * that flag is the whole reason `typeOwnAnswer` exists in their bundle.
     */
    fun customRowVisible(field: FormField): Boolean =
        field.custom && field.options.isNotEmpty()

    /**
     * What a field is worth in the review step.
     *
     * `answer.none` is `(no answer)` and `review.notAnswered` is `(not answered)`:
     * two different states. `(no answer)` is an answer that arrived empty; `(not
     * answered)` is a field he skipped. Conflating them would hide a required field.
     */
    fun reviewValue(picked: List<String>, required: Boolean): String = when {
        picked.isEmpty() && required -> NOT_ANSWERED
        picked.isEmpty() -> NO_ANSWER
        else -> picked.joinToString(", ")
    }

    // ---- answer assembly ----

    /**
     * One tap on an option, in their selection model.
     *
     * `multiple: true` is `Pick any` - additive, and tapping again removes. Everything
     * else is `Select one answer` - the tap replaces. A single-select that appended
     * would send two labels for a one-answer question and the server would have no way
     * to know which one he meant.
     */
    fun toggle(current: List<String>, label: String, multiple: Boolean): List<String> =
        if (multiple) {
            if (label in current) current - label else current + label
        } else {
            listOf(label)
        }

    /**
     * The reply payload, index-aligned with the questions.
     *
     * Their description: "User answers in order of questions (each answer is an array
     * of selected labels)". A question he did not touch must still occupy its slot as
     * an empty array - dropping it would shift every later answer onto the wrong
     * question.
     */
    fun assemble(total: Int, picked: Map<Int, List<String>>): List<List<String>> =
        (0 until total).map { picked[it].orEmpty() }
}
