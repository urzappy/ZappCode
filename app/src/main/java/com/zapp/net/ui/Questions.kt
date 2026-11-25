package com.zapp.net.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.MainViewModel
import com.zapp.net.api.QuestionInfo
import com.zapp.net.api.QuestionOption
import com.zapp.net.api.QuestionRequest
import com.zapp.net.ui.theme.Accent
import com.zapp.net.ui.theme.AccentContainer
import com.zapp.net.ui.theme.AccentSoft
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.Surface
import com.zapp.net.ui.theme.Surface2
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextMid
import com.zapp.net.ui.theme.TextPrimary

/**
 * The `question` tool's card.
 *
 * This is the one place in the whole app where the model is **stopped** waiting on
 * the owner. Before this file the request arrived, sat in a store nothing read, and the
 * turn hung with no way to move it except typing a sentence into the composer.
 *
 * The route and the payload are proven from the running server binary, not guessed:
 *
 * ```
 * GET  /api/session/:sessionID/question                    -> {data: QuestionV2.Request[]}
 * POST /api/session/:sessionID/question/:requestID/reply   {answers: string[][]}
 * POST /api/session/:sessionID/question/:requestID/reject
 * GET  /api/question/request
 *
 * QuestionV2.Info = { question, header, options: [{label, description?}],
 *                     multiple?: "Allow selecting multiple choices",
 *                     custom?:   "Allow typing a custom answer (default: true)" }
 * ```
 *
 * **Wording is theirs, arrangement is ours.** `ui.question.*` and `session.question.*`
 * live in the bundle's i18n table, but the component that draws them is absent from
 * the extract - `typeOwnAnswer` appears only inside the 130 locale tables in
 * `app.asar` and in no source file. So every string here comes from [QuestionCopy]
 * (a port) while the layout is a design decision, recorded as a disclosed deviation.
 */
@Composable
fun QuestionCard(vm: MainViewModel, req: QuestionRequest, extra: Int = 0) {
    // index -> selected labels, in the order they were tapped
    val picked = remember(req.id) { mutableStateMapOf<Int, List<String>>() }
    // index -> the text he typed into the own-answer box
    val typed = remember(req.id) { mutableStateMapOf<Int, String>() }
    // index -> the own-answer box is open
    val own = remember(req.id) { mutableStateMapOf<Int, Boolean>() }
    /**
     * `session.question.minimize` / `.restore` - PC lets a question card fold down to its header
     * and come back. The card can be tall (one block per question), and a long prompt above it
     * must not be pushed off screen by a card he is not ready to answer.
     *
     * Remembered per request id, so a card that arrives while another is minimised starts open.
     */
    var minimised by remember(req.id) { mutableStateOf(false) }
    /**
     * `ui.messagePart.review.title` = "Review your answers" - PC reads the answers back before
     * sending them. The step exists because a question card can hold several questions and a
     * mis-tap on a single-select is invisible once the row scrolls out of view.
     */
    var reviewing by remember(req.id) { mutableStateOf(false) }

    val total = req.questions.size
    val answeredCount = (0 until total).count {
        picked[it].orEmpty().isNotEmpty() || typed[it].orEmpty().isNotBlank()
    }

    fun answerOf(i: Int): List<String> {
        val t = typed[i].orEmpty().trim()
        return if (own[i] == true && t.isNotEmpty()) listOf(t) else picked[i].orEmpty()
    }

    /** Every question has an answer - the only state in which Submit is offered. */
    val complete = (0 until total).all { answerOf(it).isNotEmpty() }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // `session.question.title` = "Question"
            Text(QuestionCopy.TITLE, fontSize = 11.5.sp, color = TextPrimary, maxLines = 1)
            // `session.question.progress` = "{{current}} of {{total}} questions"
            if (total > 1) {
                Spacer(Modifier.width(8.dp))
                Text(
                    QuestionCopy.progress(answeredCount, total),
                    fontSize = 10.sp,
                    color = TextDim,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            if (extra > 0) {
                Text("+$extra more", fontSize = 10.sp, color = TextDim)
                Spacer(Modifier.width(8.dp))
            }
            // `session.question.minimize` / `session.question.restore`
            Icon(
                if (minimised) OcIcons.ChevronRight else OcIcons.ChevronDown,
                if (minimised) QuestionCopy.RESTORE else QuestionCopy.MINIMIZE,
                tint = TextDim,
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { minimised = !minimised }
                    .padding(2.dp),
            )
        }

        // A minimised card keeps its header, its progress and its state; only the body folds.
        // Nothing is discarded, so restoring shows exactly what was typed before.
        if (minimised) return@Column

        if (reviewing) {
            // `ui.messagePart.review.title` - one row per question: its short header, then the
            // answer. `reviewValue` keeps `(not answered)` and `(no answer)` distinct, so a
            // skipped question is visible instead of reading as a deliberate blank.
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(
                    QuestionCopy.REVIEW_TITLE,
                    fontSize = 11.sp,
                    color = TextMid,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
                req.questions.forEachIndexed { i, q ->
                    Text(
                        q.header.ifBlank { q.question },
                        fontSize = 9.5.sp,
                        color = TextDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                    Text(
                        QuestionCopy.reviewValue(answerOf(i), required = true),
                        fontSize = 11.5.sp,
                        color = if (answerOf(i).isEmpty()) TextDim else TextPrimary,
                    )
                }
            }
        } else {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            req.questions.forEachIndexed { i, q ->
                Spacer(Modifier.padding(top = 8.dp))
                if (q.header.isNotBlank()) {
                    // "Very short label (max 30 chars)" - their own description of `header`
                    Text(q.header, fontSize = 9.5.sp, color = TextDim, maxLines = 1)
                }
                Text(q.question, fontSize = 11.5.sp, color = TextPrimary)
                // `ui.question.multiHint` / `ui.question.singleHint`
                Text(
                    if (q.multiple) QuestionCopy.MULTI_HINT else QuestionCopy.SINGLE_HINT,
                    fontSize = 9.5.sp,
                    color = TextDim,
                )
                if (q.options.isNotEmpty()) {
                    QuestionOptions(
                        options = q.options,
                        selected = picked[i].orEmpty(),
                        multiple = q.multiple,
                        onPick = { label ->
                            picked[i] = QuestionCopy.toggle(picked[i].orEmpty(), label, q.multiple)
                            // picking a listed option closes the own-answer box: they are
                            // alternatives, and sending both would be two answers to one
                            // single-select question
                            if (!q.multiple) {
                                own[i] = false
                                typed.remove(i)
                            }
                        },
                    )
                }
                // `custom` defaults to true on the server, so this is the normal case.
                if (q.custom) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(5.dp))
                            .clickable {
                                own[i] = own[i] != true
                                if (own[i] != true) {
                                    typed.remove(i)
                                } else if (!q.multiple) {
                                    picked.remove(i)
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (own[i] == true) "\u2713" else "\u25cb",
                            fontSize = 11.sp,
                            color = if (own[i] == true) Accent else TextDim,
                        )
                        Spacer(Modifier.width(6.dp))
                        // `ui.messagePart.option.typeOwnAnswer` = "Type your own answer"
                        Text(
                            QuestionCopy.TYPE_OWN_ANSWER,
                            fontSize = 11.sp,
                            color = if (own[i] == true) AccentSoft else TextMid,
                        )
                    }
                    if (own[i] == true) {
                        QuestionField(
                            value = typed[i].orEmpty(),
                            placeholder = QuestionCopy.CUSTOM_ANSWER_PLACEHOLDER,
                            onChange = { typed[i] = it },
                        )
                    }
                }
            }
        }
        }

        Spacer(Modifier.padding(top = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (reviewing) {
                // `ui.common.back` = "Back" - returns to the answers, keeping every one of them.
                QuestionChip(QuestionCopy.BACK) { reviewing = false }
                Spacer(Modifier.width(8.dp))
                // `ui.common.submit` = "Submit". Only offered when every question has an answer:
                // the wire has no `required` flag, so an empty answer would be sent as `[]` and
                // the model would read that as a deliberate blank. The reject path exists for
                // "I do not want to answer", and this is not it.
                QuestionChip(QuestionCopy.SUBMIT, primary = true, enabled = complete) {
                    vm.replyQuestion(
                        req,
                        QuestionCopy.assemble(
                            total = total,
                            picked = (0 until total).associateWith { answerOf(it) },
                        ),
                    )
                }
            } else {
                // `ui.common.next` = "Next" - the review step is theirs (`ui.messagePart.review
                // .title`), so the answers are read back before they are sent.
                QuestionChip(QuestionCopy.NEXT, primary = true) { reviewing = true }
            }
            Spacer(Modifier.width(8.dp))
            // `ui.common.cancel` = "Cancel". Cancelling a question is a reject, not a
            // silent dismissal: the server hands the model
            // `QuestionV2.RejectedError` ("The user dismissed this question") so the turn
            // can continue instead of waiting forever.
            QuestionChip(QuestionCopy.CANCEL) { vm.rejectQuestion(req) }
            if (total > 1) {
                Spacer(Modifier.weight(1f))
                // `ui.question.subtitle.answered` = "{{count}} answered"
                Text(QuestionCopy.answered(answeredCount), fontSize = 9.5.sp, color = TextDim)
            }
        }
    }
}

@Composable
private fun QuestionOptions(
    options: List<QuestionOption>,
    selected: List<String>,
    multiple: Boolean,
    onPick: (String) -> Unit,
) {
    Column(Modifier.padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { o ->
            val on = o.label in selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(5.dp))
                    .clickable { onPick(o.label) }
                    .padding(horizontal = 4.dp, vertical = 5.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // a checkbox reads as "several allowed", a circle as "one of these"
                Text(
                    when {
                        multiple && on -> "\u2713"
                        multiple -> "\u25a1"
                        on -> "\u25cf"
                        else -> "\u25cb"
                    },
                    fontSize = 11.sp,
                    color = if (on) Accent else TextDim,
                )
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(o.label, fontSize = 11.sp, color = TextPrimary)
                    if (!o.description.isNullOrBlank()) {
                        Text(
                            o.description,
                            fontSize = 10.sp,
                            color = TextDim,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionField(value: String, placeholder: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = TextPrimary, fontSize = 11.5.sp),
        cursorBrush = SolidColor(Accent),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Surface)
            .border(1.dp, Line, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(placeholder, fontSize = 11.5.sp, color = TextDim)
            }
            inner()
        },
    )
}

@Composable
private fun QuestionChip(
    text: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text,
        fontSize = 11.sp,
        color = when {
            !enabled -> TextDim
            primary -> AccentSoft
            else -> TextMid
        },
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (primary && enabled) AccentContainer else Surface)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
