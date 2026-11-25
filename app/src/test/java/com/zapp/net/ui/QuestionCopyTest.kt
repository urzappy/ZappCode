package com.zapp.net.ui

import com.zapp.net.api.FormField
import com.zapp.net.api.FormOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ui.question.*` / `session.question.*` parity.
 *
 * Every expected string is a key from the shipped PC bundle. The two states that made
 * this pass necessary are `custom` (a "write your own answer" option the app parsed and
 * never showed) and the two different empty answers - `(no answer)` vs `(not answered)`.
 */
class QuestionCopyTest {

    private fun field(
        type: String = "string",
        options: List<FormOption> = emptyList(),
        custom: Boolean = false,
        placeholder: String? = null,
        required: Boolean = false,
    ) = FormField(
        key = "k",
        type = type,
        options = options,
        custom = custom,
        placeholder = placeholder,
        required = required,
    )

    private fun opt(v: String) = FormOption(value = v, label = v)

    @Test
    fun theStringsAreTheirs() {
        assertEquals("Question", QuestionCopy.TITLE)
        // `session.question.minimize` / `.restore` - the fold control's two labels
        assertEquals("Minimize question", QuestionCopy.MINIMIZE)
        assertEquals("Restore question", QuestionCopy.RESTORE)
        assertEquals("Select one answer", QuestionCopy.SINGLE_HINT)
        assertEquals("Pick any", QuestionCopy.MULTI_HINT)
        assertEquals("Select all answers that apply", QuestionCopy.CLASSIC_MULTI_HINT)
        assertEquals("Type your own answer", QuestionCopy.TYPE_OWN_ANSWER)
        assertEquals("Review your answers", QuestionCopy.REVIEW_TITLE)
        assertEquals("Questions dismissed", QuestionCopy.DISMISSED)
        assertEquals("Submit", QuestionCopy.SUBMIT)
        assertEquals("Cancel", QuestionCopy.CANCEL)
        assertEquals("(no answer)", QuestionCopy.NO_ANSWER)
        assertEquals("(not answered)", QuestionCopy.NOT_ANSWERED)
    }

    @Test
    fun thePlaceholdersEndInARealEllipsis() {
        // U+2026, not "..." - proven from the bundle: codepoints 84,121,112,101,...,226,128,166
        assertEquals("Type your answer\u2026", QuestionCopy.CUSTOM_PLACEHOLDER)
        assertEquals("Type your own answer\u2026", QuestionCopy.CUSTOM_ANSWER_PLACEHOLDER)
        assertTrue(QuestionCopy.CUSTOM_PLACEHOLDER.last() == '\u2026')
        assertTrue(QuestionCopy.CUSTOM_ANSWER_PLACEHOLDER.last() == '\u2026')
    }

    @Test
    fun progressAndAnsweredFillTheirPlaceholders() {
        assertEquals("1 of 3 questions", QuestionCopy.progress(1, 3))
        assertEquals("2 answered", QuestionCopy.answered(2))
        assertEquals("0 answered", QuestionCopy.answered(0))
    }

    @Test
    fun aSingleChoiceFieldSaysSelectOneAnswer() {
        val f = field(options = listOf(opt("a"), opt("b")))
        assertEquals(QuestionCopy.SINGLE_HINT, QuestionCopy.hint(f))
    }

    @Test
    fun aMultiselectSaysPickAny() {
        assertEquals(QuestionCopy.MULTI_HINT, QuestionCopy.hint(field(type = "multiselect", options = listOf(opt("a")))))
    }

    @Test
    fun aPlainTextOrNumberFieldHasNoHint() {
        // Nothing to select, so a hint would be noise.
        assertNull(QuestionCopy.hint(field()))
        assertNull(QuestionCopy.hint(field(type = "number")))
        assertNull(QuestionCopy.hint(field(type = "boolean")))
        assertNull(QuestionCopy.hint(field(type = "integer")))
    }

    @Test
    fun theServerPlaceholderWinsOverTheirs() {
        assertEquals("your@email", QuestionCopy.placeholder(field(placeholder = "your@email")))
        assertEquals(QuestionCopy.CUSTOM_PLACEHOLDER, QuestionCopy.placeholder(field()))
        // a number field keeps the bare word it has always shown
        assertEquals("number", QuestionCopy.placeholder(field(type = "number")))
    }

    @Test
    fun theOwnAnswerRowNeedsBothCustomAndOptions() {
        // `custom: true` is the server opting in; without options there is no list to
        // escape from, so the row would be a second copy of the same text box.
        assertTrue(QuestionCopy.customRowVisible(field(options = listOf(opt("a")), custom = true)))
        assertFalse(QuestionCopy.customRowVisible(field(options = listOf(opt("a")), custom = false)))
        assertFalse(QuestionCopy.customRowVisible(field(custom = true)))
    }

    @Test
    fun skippedAndEmptyAreDifferentAnswers() {
        // `(not answered)` on a required field is a problem he has to fix.
        assertEquals(QuestionCopy.NOT_ANSWERED, QuestionCopy.reviewValue(emptyList(), required = true))
        // `(no answer)` on an optional field is a legitimate answer.
        assertEquals(QuestionCopy.NO_ANSWER, QuestionCopy.reviewValue(emptyList(), required = false))
        assertEquals("a, b", QuestionCopy.reviewValue(listOf("a", "b"), required = true))
    }

    @Test
    fun aSingleSelectReplacesAndAMultiSelectAccumulates() {
        // `Select one answer`: a tap replaces, so two labels can never go out for one
        // question.
        assertEquals(listOf("b"), QuestionCopy.toggle(listOf("a"), "b", multiple = false))
        assertEquals(listOf("a"), QuestionCopy.toggle(emptyList(), "a", multiple = false))
        // `Pick any`: additive, and tapping again removes.
        assertEquals(listOf("a", "b"), QuestionCopy.toggle(listOf("a"), "b", multiple = true))
        assertEquals(listOf("b"), QuestionCopy.toggle(listOf("a", "b"), "a", multiple = true))
        assertEquals(emptyList<String>(), QuestionCopy.toggle(listOf("a"), "a", multiple = true))
    }

    @Test
    fun theReplyIsIndexAlignedWithTheQuestions() {
        // Their payload description: "User answers in order of questions (each answer is
        // an array of selected labels)". A skipped question still occupies its slot -
        // dropping it would shift every later answer onto the wrong question.
        val out = QuestionCopy.assemble(
            total = 3,
            picked = mapOf(0 to listOf("yes"), 2 to listOf("c")),
        )
        assertEquals(3, out.size)
        assertEquals(listOf("yes"), out[0])
        assertEquals(emptyList<String>(), out[1])
        assertEquals(listOf("c"), out[2])
    }

    @Test
    fun anEmptyRequestProducesAnEmptyReply() {
        assertEquals(emptyList<List<String>>(), QuestionCopy.assemble(0, emptyMap()))
    }
}
