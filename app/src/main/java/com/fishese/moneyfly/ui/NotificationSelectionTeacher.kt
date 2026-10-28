package com.fishese.moneyfly.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.fishese.moneyfly.R
import com.fishese.moneyfly.capture.NotificationPayload
import com.fishese.moneyfly.recognition.MessageField
import com.fishese.moneyfly.recognition.SlotName

internal data class TaughtSpan(val field: MessageField, val start: Int, val end: Int)

private data class HighlightSnapshot(
    val assignments: Map<SlotName, TaughtSpan>, val sections: Map<MessageField, TaughtSpan>,
)

private fun slotColor(slot: SlotName): Color = when (slot) {
    SlotName.AMOUNT -> Color(0xff70c7ff)
    SlotName.MERCHANT -> Color(0xff99df91)
    SlotName.DATE -> Color(0xffffd275)
    SlotName.CURRENCY -> Color(0xffd1a5ff)
    SlotName.REFERENCE -> Color(0xffffaebc)
    SlotName.ACCOUNT_HINT -> Color(0xff8fdddd)
    SlotName.NOTES -> Color(0xffffbd80)
    SlotName.VARIABLE -> Color(0xffb9b9f7)
}

internal fun moveTextBoundary(text: String, offset: Int, direction: Int): Int {
    val safe = offset.coerceIn(0, text.length)
    return when {
        direction < 0 && safe > 0 -> text.offsetByCodePoints(safe, -1)
        direction > 0 && safe < text.length -> text.offsetByCodePoints(safe, 1)
        else -> safe
    }
}

/** Only trim an explicitly selected currency token; never infer the currency or number format. */
internal fun amountSelection(text: String, span: TaughtSpan): TaughtSpan {
    val selected = text.substring(span.start, span.end)
    val token = "(?:[A-Za-z]{3}|HK\\$|US\\$|S\\$|NT\\$|JP[¥￥]|[$€£¥￥])"
    val match = Regex("^\\s*($token)?\\s*([0-9]+(?:[.,][0-9]+)*)\\s*($token)?\\s*$", RegexOption.IGNORE_CASE).matchEntire(selected) ?: return span
    val prefix = match.groups[1]?.value
    val suffix = match.groups[3]?.value
    fun recognized(value: String?): Boolean = value == null || value.uppercase(java.util.Locale.ROOT) in setOf("HK$", "US$", "S$", "NT$", "JP¥", "JP￥", "$", "€", "£", "¥", "￥", "RMB") || com.fishese.moneyfly.fx.supportedCurrency(value.uppercase(java.util.Locale.ROOT))
    if ((prefix == null && suffix == null) || !recognized(prefix) || !recognized(suffix)) return span
    val number = match.groups[2] ?: return span
    return TaughtSpan(span.field, span.start + number.range.first, span.start + number.range.last + 1)
}

@Composable
private fun teacherLabels(): Map<SlotName, String> {
    return mapOf(
        SlotName.AMOUNT to stringResource(R.string.amount), SlotName.MERCHANT to stringResource(R.string.merchant),
        SlotName.DATE to stringResource(R.string.teacher_optional_date), SlotName.CURRENCY to stringResource(R.string.currency),
        SlotName.REFERENCE to stringResource(R.string.teacher_reference), SlotName.ACCOUNT_HINT to stringResource(R.string.teacher_account),
        SlotName.NOTES to stringResource(R.string.notes), SlotName.VARIABLE to stringResource(R.string.teacher_other),
    )
}

@Composable
internal fun TeacherFieldBar(active: SlotName?, onActiveChange: (SlotName?) -> Unit, enabled: Boolean, marked: Set<SlotName>, hasSection: Boolean) {
    val labels = teacherLabels()
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).testTag("teacher_field_bar"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEach { (slot, label) ->
                FilterChip(selected = active == slot, onClick = { onActiveChange(slot) }, enabled = enabled,
                    label = { Text(if (slot in marked) stringResource(R.string.teacher_marked_field, label) else label) }, leadingIcon = { Surface(color = slotColor(slot), shape = MaterialTheme.shapes.small, modifier = Modifier.size(12.dp)) {} },
                    modifier = Modifier.testTag("teacher_slot_${slot.name}"))
            }
            FilterChip(selected = active == null, onClick = { onActiveChange(null) }, enabled = enabled,
                label = { Text(if (hasSection) stringResource(R.string.teacher_marked_field, stringResource(R.string.teacher_section_chip)) else stringResource(R.string.teacher_section_chip)) }, modifier = Modifier.testTag("teacher_slot_section"))
        }
    }
}

internal data class SelectionAdjustment(val span: TaughtSpan, val sequence: Long)

/** Drag or tap these controls to move endpoints by Unicode code points, without word snapping. */
@Composable
internal fun PreciseSelectionBar(onAdjust: (Boolean, Int) -> Unit) {
    val step = with(LocalDensity.current) { 12.dp.toPx() }
    val adjust by rememberUpdatedState(onAdjust)
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            listOf(true, false).forEach { isStart ->
                Text(stringResource(if (isStart) R.string.teacher_start_label else R.string.teacher_end_label), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { adjust(isStart, -1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp).testTag("precise_${if (isStart) "start" else "end"}_back")) { Text("−") }
                val description = stringResource(if (isStart) R.string.teacher_drag_start else R.string.teacher_drag_end)
                var pending by remember(isStart) { mutableFloatStateOf(0f) }
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(40.dp).semantics { contentDescription = description }.testTag("precise_${if (isStart) "start" else "end"}_drag")
                        .pointerInput(isStart, step) {
                            detectDragGestures(onDragStart = { pending = 0f }, onDrag = { change, distance ->
                                change.consume()
                                pending += distance.x
                                while (pending >= step) { adjust(isStart, 1); pending -= step }
                                while (pending <= -step) { adjust(isStart, -1); pending += step }
                            })
                        }) { Box(contentAlignment = Alignment.Center) { Text("↔") } }
                TextButton(onClick = { adjust(isStart, 1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp).testTag("precise_${if (isStart) "start" else "end"}_forward")) { Text("+") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NotificationSelectionTeacher(
    payload: NotificationPayload,
    assignments: Map<SlotName, TaughtSpan>,
    enabled: Boolean,
    onAssign: (SlotName, TaughtSpan) -> Unit,
    onClear: (SlotName) -> Unit,
    sections: Map<MessageField, TaughtSpan> = emptyMap(),
    onSection: (MessageField, TaughtSpan?) -> Unit = { _, _ -> },
    active: SlotName? = SlotName.AMOUNT,
    adjustment: SelectionAdjustment? = null,
) {
    val labels = teacherLabels()
    val selections = remember { mutableStateMapOf<MessageField, TextRange>() }
    LaunchedEffect(adjustment?.sequence) {
        adjustment?.span?.let { selections[it.field] = TextRange(it.start, it.end) }
    }
    val frozenHighlights = remember { mutableStateMapOf<MessageField, HighlightSnapshot>() }
    var showExtraFields by remember { mutableStateOf(false) }
    val expandedFields = remember { mutableStateMapOf<MessageField, Boolean>() }
    val extraFields = MessageField.entries.filter { it != MessageField.TITLE && it != MessageField.BODY && fieldText(payload, it).isNotEmpty() }
    val fields = listOf(MessageField.TITLE, MessageField.BODY) + if (showExtraFields) extraFields else emptyList()
    Text(stringResource(if (active == null) R.string.teacher_section_select_help else R.string.teacher_select_help), style = MaterialTheme.typography.bodySmall)
    if (active == SlotName.DATE) Text(stringResource(R.string.teacher_date_help), style = MaterialTheme.typography.bodySmall)
    @Composable fun AssignedControls(span: TaughtSpan) {
        val slot = active ?: return
        val text = fieldText(payload, span.field)
        Text(stringResource(R.string.teacher_assigned, labels.getValue(slot), messageFieldLabel(span.field)), style = MaterialTheme.typography.labelMedium)
        if (slot == SlotName.ACCOUNT_HINT) Text(stringResource(R.string.teacher_account_explanation), style = MaterialTheme.typography.bodySmall)
        val section = sections[span.field]
        val regionStart = section?.start ?: 0
        val regionEnd = section?.end ?: text.length
        val value = text.substring(span.start, span.end)
        val region = text.substring(regionStart, regionEnd)
        var count = 0
        if (value.isNotEmpty()) {
            var index = region.indexOf(value)
            while (index >= 0) { count++; index = region.indexOf(value, index + 1) }
        }
        if (count > 1) Text(stringResource(R.string.teacher_repeated_warning, count), color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("teacher_repeated_warning"))
        Text(text.substring(span.start, span.end), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("teacher_assigned_value"))
        @Composable fun adjust(boundary: String, delta: Int, label: String) {
            OutlinedButton(onClick = {
                val startIndex = if (boundary == "start") moveTextBoundary(text, span.start, delta) else span.start
                val endIndex = if (boundary == "end") moveTextBoundary(text, span.end, delta) else span.end
                if (startIndex < endIndex) { val next = TaughtSpan(span.field, startIndex, endIndex); selections[span.field] = TextRange(startIndex, endIndex); onAssign(slot, next) }
            }, enabled = enabled, modifier = Modifier.testTag("teacher_${boundary}_${if (delta < 0) "back" else "forward"}")) { Text(label) }
        }
        Text(stringResource(R.string.teacher_adjust), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            adjust("start", -1, stringResource(R.string.teacher_start_back)); adjust("start", 1, stringResource(R.string.teacher_start_forward))
            adjust("end", -1, stringResource(R.string.teacher_end_back)); adjust("end", 1, stringResource(R.string.teacher_end_forward))
        }
    }
    fields.forEach { field ->
        val text = fieldText(payload, field)
        val frozen = frozenHighlights[field]
        val currentAssignments = assignments.toMap()
        val currentSections = sections.toMap()
        LaunchedEffect(field, frozen != null, currentAssignments, currentSections) {
            if (frozen != null) {
                // Keep selection handles stable during movement, then show the latest colors.
                kotlinx.coroutines.delay(400)
                if (field in frozenHighlights) frozenHighlights[field] = HighlightSnapshot(currentAssignments, currentSections)
            }
        }
        val visibleAssignments = frozen?.assignments ?: currentAssignments
        val visibleSections = frozen?.sections ?: currentSections
        val annotated = remember(text, field, visibleAssignments, visibleSections) {
            AnnotatedString.Builder(text).apply {
                val section = visibleSections[field]
                if (section == null && visibleAssignments.values.any { it.field == field } && text.isNotEmpty()) addStyle(SpanStyle(background = Color.Gray.copy(alpha = .13f)), 0, text.length)
                else if (section != null && section.start >= 0 && section.end <= text.length && section.start < section.end)
                    addStyle(SpanStyle(background = Color.Gray.copy(alpha = .13f)), section.start, section.end)
                visibleAssignments.forEach { (slot, span) ->
                    if (span.field == field && span.start >= 0 && span.end <= text.length && span.start < span.end) {
                        val value = text.substring(span.start, span.end)
                        val startAt = section?.start ?: 0
                        val endAt = section?.end ?: text.length
                        if (value.isNotEmpty()) {
                            var index = text.indexOf(value, startAt)
                            while (index >= 0 && index + value.length <= endAt) {
                                addStyle(SpanStyle(background = slotColor(slot).copy(alpha = .45f)), index, index + value.length)
                                index = text.indexOf(value, index + 1)
                            }
                        }
                    }
                }
            }.toAnnotatedString()
        }
        val expanded = expandedFields[field] == true
        val fieldLabel = if (field == MessageField.BODY) stringResource(R.string.teacher_message) else messageFieldLabel(field)
        OutlinedTextField(
            value = TextFieldValue(annotated, selections[field] ?: TextRange.Zero),
            onValueChange = { next ->
                if (next.selection != selections[field]) {
                    selections[field] = next.selection
                    val start = next.selection.min
                    val end = next.selection.max
                    if (enabled && start >= 0 && end > start && end <= text.length) {
                        val original = TaughtSpan(field, start, end)
                        val span = if (active == SlotName.AMOUNT) amountSelection(text, original) else original
                        if (active == null) onSection(field, span) else onAssign(active, span)
                    }
                }
            },
            readOnly = true, enabled = enabled && text.isNotEmpty(), label = { Text(fieldLabel) },
            minLines = if (expanded) 6 else if (field == MessageField.TITLE) 1 else 2,
            maxLines = if (expanded) 12 else if (field == MessageField.TITLE) 2 else 4,
            modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
                if (state.isFocused && field !in frozenHighlights) {
                    frozenHighlights[field] = HighlightSnapshot(assignments.toMap(), sections.toMap())
                } else if (!state.isFocused) frozenHighlights.remove(field)
            }.testTag("teacher_text_${field.name}"),
        )
        if (text.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { expandedFields[field] = !expanded }, enabled = enabled,
                    modifier = Modifier.testTag("teacher_resize_${field.name}")) {
                    Text(stringResource(if (expanded) R.string.teacher_collapse_field else R.string.teacher_expand_field, fieldLabel))
                }
            }
        }
        active?.let { assignments[it] }?.takeIf { it.field == field }?.let { AssignedControls(it) }
    }
    if (extraFields.isNotEmpty()) TextButton(onClick = { showExtraFields = !showExtraFields }, modifier = Modifier.testTag("teacher_extra_fields")) {
        Text(stringResource(if (showExtraFields) R.string.teacher_hide_extra else R.string.teacher_show_extra))
    }
    sections.forEach { (field, _) ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.teacher_section_active, messageFieldLabel(field)), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onSection(field, null) }, enabled = enabled, modifier = Modifier.testTag("teacher_clear_section_${field.name}")) { Text(stringResource(R.string.teacher_clear_section)) }
        }
    }
    Text(stringResource(R.string.teacher_fixed_legend), style = MaterialTheme.typography.bodySmall)
    if (active != null && assignments.containsKey(active)) TextButton(onClick = { onClear(active) }, enabled = enabled, modifier = Modifier.testTag("teacher_clear")) { Text(stringResource(R.string.teacher_clear)) }
}
