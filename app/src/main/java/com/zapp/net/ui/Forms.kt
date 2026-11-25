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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import com.zapp.net.api.FormField
import com.zapp.net.api.FormInfo
import com.zapp.net.api.FormOption
import com.zapp.net.ui.theme.Accent
import com.zapp.net.ui.theme.AccentContainer
import com.zapp.net.ui.theme.AccentSoft
import com.zapp.net.ui.theme.Border
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.Surface
import com.zapp.net.ui.theme.Surface2
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextMid
import com.zapp.net.ui.theme.TextPrimary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add

/**
 * Their `Form` card — the shape a tool (or an MCP elicitation) uses to ask the user
 * something more complex than yes/no. Server contract:
 *
 *   GET  /api/session/:id/form          -> [Form.Info]
 *   POST /api/session/:id/form/:id/reply  {answer: {key: value}}
 *   DELETE /api/session/:id/form/:id      (?message=)
 *
 * Field types come from `schema/src/form.ts`: string | number | integer | boolean |
 * multiselect | external, with `when` conditions that hide a field until its dependency
 * holds. Inactive fields are neither required nor answerable, so they are skipped on send.
 *
 * **The wording is PC's, the arrangement is ours.** The question renderer is absent
 * from the extract — `typeOwnAnswer` appears only in the locale tables — so every
 * string below comes from [QuestionCopy] (a real port) while the layout is a design
 * decision. The one behaviour this pass adds is `custom`: `Form.StringField.custom` and
 * `Form.MultiselectField.custom` exist in the live contract and ZAPP parsed the flag and
 * then never read it, so a "write your own answer" option was invisible.
 */
@Composable
fun FormCard(vm: MainViewModel, form: FormInfo, extra: Int = 0) {
    val answers = remember(form.id) { mutableStateMapOf<String, JsonElement>() }
    // fields whose option list has been swapped for a free-text box
    val typingOwn = remember(form.id) { mutableStateMapOf<String, Boolean>() }
    var submitTried by remember(form.id) { mutableStateOf(false) }

    // seed defaults once
    remember(form.id) {
        form.fields.forEach { f ->
            f.default?.let { answers[f.key] = it }
        }
        true
    }

    fun visible(f: FormField): Boolean {
        if (f.hidden) return false
        if (f.when_.isEmpty()) return true
        return f.when_.all { c ->
            val cur = answers[c.key] ?: return@all false
            val eq = when (cur) {
                is JsonArray -> cur.any { it.toString().trim('"') == c.value.toString().trim('"') }
                else -> cur.toString().trim('"') == c.value.toString().trim('"')
            }
            if (c.op == "neq") !eq else eq
        }
    }

    val shown = form.fields.filter { visible(it) }
    val missing = shown.filter { it.required && answers[it.key] == null }
    val answeredCount = shown.count { answers[it.key] != null }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // `session.question.title` = "Question". The server's own `form.title` is the
        // subject of the question and sits underneath, not instead: on PC the header is
        // the constant and the form's title is the content.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(QuestionCopy.TITLE, fontSize = 11.5.sp, color = TextPrimary, maxLines = 1)
            // `session.question.progress` = "{{current}} of {{total}} questions" - only
            // worth the space when there is more than one field to get through.
            if (shown.size > 1) {
                Spacer(Modifier.width(8.dp))
                Text(
                    QuestionCopy.progress(answeredCount.coerceAtMost(shown.size), shown.size),
                    fontSize = 10.sp,
                    color = TextDim,
                    maxLines = 1,
                )
            }
            if (extra > 0) {
                Spacer(Modifier.weight(1f))
                Text("+$extra more", fontSize = 10.sp, color = TextDim)
            }
        }
        if (form.title.isNotBlank()) {
            Text(form.title, fontSize = 11.sp, color = TextMid, maxLines = 2)
        }

        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            shown.forEach { f ->
                Spacer(Modifier.padding(top = 6.dp))
                val label = f.title ?: f.key
                Text(
                    label + if (f.required) " *" else "",
                    fontSize = 10.5.sp,
                    color = TextMid,
                )
                if (!f.description.isNullOrBlank()) {
                    Text(f.description, fontSize = 10.sp, color = TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                // `ui.question.singleHint` / `ui.question.multiHint`
                QuestionCopy.hint(f)?.let {
                    Text(it, fontSize = 9.5.sp, color = TextDim)
                }
                val own = typingOwn[f.key] == true
                when (f.type) {
                    "boolean" -> BoolRow(
                        value = (answers[f.key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
                        onChange = { answers[f.key] = JsonPrimitive(it) },
                    )
                    "multiselect" -> if (own) {
                        FieldBox(
                            value = (answers[f.key] as? JsonPrimitive)?.content.orEmpty(),
                            placeholder = QuestionCopy.CUSTOM_ANSWER_PLACEHOLDER,
                            onChange = { answers[f.key] = JsonPrimitive(it) },
                        )
                    } else {
                        MultiRow(
                            field = f,
                            selected = (answers[f.key] as? JsonArray)?.map { it.toString().trim('"') } ?: emptyList(),
                            onChange = { list ->
                                answers[f.key] = buildJsonArray { list.forEach { add(it) } }
                            },
                        )
                    }
                    "external" -> Text(
                        f.url.orEmpty(),
                        fontSize = 10.sp,
                        color = AccentSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    else -> {
                        val isNum = f.type == "number" || f.type == "integer"
                        if (!own) {
                            FieldBox(
                                value = (answers[f.key] as? JsonPrimitive)?.content.orEmpty(),
                                placeholder = QuestionCopy.placeholder(f),
                                onChange = { v ->
                                    answers[f.key] = if (isNum) {
                                        v.toDoubleOrNull()?.let { d ->
                                            if (f.type == "integer") JsonPrimitive(d.toLong()) else JsonPrimitive(d)
                                        } ?: JsonPrimitive(v)
                                    } else JsonPrimitive(v)
                                },
                            )
                        } else {
                            FieldBox(
                                value = (answers[f.key] as? JsonPrimitive)?.content.orEmpty(),
                                placeholder = QuestionCopy.CUSTOM_ANSWER_PLACEHOLDER,
                                onChange = { answers[f.key] = JsonPrimitive(it) },
                            )
                        }
                        if (f.options.isNotEmpty() && !own) {
                            OptionList(f.options) { opt ->
                                answers[f.key] = JsonPrimitive(opt.value)
                            }
                        }
                    }
                }
                // `ui.messagePart.option.typeOwnAnswer` = "Type your own answer". The
                // server opts in with `custom: true`; ZAPP used to drop that flag.
                if (QuestionCopy.customRowVisible(f)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(5.dp))
                            .clickable {
                                typingOwn[f.key] = !own
                                if (!own) answers.remove(f.key)
                            }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (own) "\u2713" else "\u25cb",
                            fontSize = 11.sp,
                            color = if (own) Accent else TextDim,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            QuestionCopy.TYPE_OWN_ANSWER,
                            fontSize = 11.sp,
                            color = if (own) AccentSoft else TextMid,
                        )
                    }
                }
            }
        }

        if (submitTried && missing.isNotEmpty()) {
            Text(
                // `ui.question.review.notAnswered` is the parenthesised form used in the
                // review list; the inline error keeps the plain name so it reads as a
                // sentence rather than a list.
                "Missing: " + missing.joinToString(", ") { it.title ?: it.key },
                fontSize = 10.sp,
                color = TextDim,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.padding(top = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // `ui.common.submit` = "Submit"
            FormChip(QuestionCopy.SUBMIT, primary = true) {
                submitTried = true
                if (missing.isEmpty()) {
                    val answer = buildJsonObject {
                        shown.forEach { f ->
                            answers[f.key]?.let { put(f.key, it) }
                        }
                    }
                    vm.answerForm(form, answer.toString())
                }
            }
            Spacer(Modifier.width(8.dp))
            // `ui.common.cancel` = "Cancel"
            FormChip(QuestionCopy.CANCEL) { vm.dismissForm(form) }
            if (shown.size > 1) {
                Spacer(Modifier.weight(1f))
                // `ui.question.subtitle.answered` = "{{count}} answered"
                Text(QuestionCopy.answered(answeredCount), fontSize = 9.5.sp, color = TextDim)
            }
        }
    }
}

@Composable
private fun FieldBox(value: String, placeholder: String, onChange: (String) -> Unit) {
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
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, fontSize = 11.5.sp, color = TextDim)
            }
            inner()
        },
    )
}

@Composable
private fun OptionList(options: List<FormOption>, onPick: (FormOption) -> Unit) {
    Column(Modifier.padding(top = 3.dp)) {
        options.forEach { o ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(5.dp))
                    .clickable { onPick(o) }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(o.label.ifBlank { o.value }, fontSize = 11.sp, color = TextPrimary)
                if (!o.description.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        o.description,
                        fontSize = 10.sp,
                        color = TextDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun BoolRow(value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.padding(top = 3.dp).clip(RoundedCornerShape(6.dp)).clickable { onChange(!value) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (value) "\u2713" else "\u25cb", fontSize = 12.sp, color = if (value) Accent else TextDim)
        Spacer(Modifier.width(6.dp))
        Text(if (value) "Yes" else "No", fontSize = 11.sp, color = TextMid)
    }
}

@Composable
private fun MultiRow(field: FormField, selected: List<String>, onChange: (List<String>) -> Unit) {
    Column(Modifier.padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        field.options.forEach { o ->
            val on = selected.contains(o.value)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(5.dp))
                    .clickable {
                        onChange(if (on) selected - o.value else selected + o.value)
                    }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (on) "\u2713" else "\u25cb", fontSize = 11.sp, color = if (on) Accent else TextDim)
                Spacer(Modifier.width(6.dp))
                Text(o.label.ifBlank { o.value }, fontSize = 11.sp, color = TextPrimary)
            }
        }
    }
}

@Composable
private fun FormChip(text: String, primary: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 11.sp,
        color = if (primary) AccentSoft else TextMid,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (primary) AccentContainer else Surface)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

