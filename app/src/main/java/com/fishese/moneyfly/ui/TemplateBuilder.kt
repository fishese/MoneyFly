package com.fishese.moneyfly.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fishese.moneyfly.R
import com.fishese.moneyfly.capture.InboxItem
import com.fishese.moneyfly.capture.NotificationPayload
import com.fishese.moneyfly.ledger.*
import com.fishese.moneyfly.recognition.*
import java.util.UUID
import kotlinx.coroutines.launch

/** The observed values and preview copies are deliberately memory-only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemplateBuilder(receipt: InboxItem, store: LedgerStore, close: () -> Unit, recordEnabled: Boolean = true, recordExisting: Boolean = false, recordEntry: () -> Unit) {
    val original = remember(receipt.id) { receipt }
    val payload = original.payload ?: return
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var amountSource by remember { mutableStateOf(MessageField.BODY.name) }
    var merchant by remember { mutableStateOf("") }
    var merchantSource by remember { mutableStateOf(MessageField.BODY.name) }
    var reference by remember { mutableStateOf("") }
    var referenceSource by remember { mutableStateOf(MessageField.BODY.name) }
    var accountHint by remember { mutableStateOf("") }
    var accountHintSource by remember { mutableStateOf(MessageField.BODY.name) }
    var dateText by remember { mutableStateOf("") }
    var dateSource by remember { mutableStateOf(MessageField.BODY.name) }
    var notesText by remember { mutableStateOf("") }
    var notesSource by remember { mutableStateOf(MessageField.BODY.name) }
    val taught = remember { mutableStateMapOf<SlotName, TaughtSpan>() }
    val sections = remember { mutableStateMapOf<MessageField, TaughtSpan>() }
    var teachingField by remember { mutableStateOf<SlotName?>(SlotName.AMOUNT) }
    var preciseAdjustment by remember { mutableStateOf<SelectionAdjustment?>(null) }
    var sectionBeingEdited by remember { mutableStateOf<MessageField?>(null) }
    var otherText by remember { mutableStateOf("") }
    var otherTextSource by remember { mutableStateOf(MessageField.BODY.name) }
    var fixedMerchant by remember { mutableStateOf(false) }
    var merchantMissing by remember { mutableStateOf(false) }
    var currency by remember { mutableStateOf("HKD") }
    var currencyExample by remember { mutableStateOf("") }
    var currencySource by remember { mutableStateOf(MessageField.BODY.name) }
    var fixedCurrency by remember { mutableStateOf(true) }
    var currencyConfirmed by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(EntryKind.EXPENSE.name) }
    var numberFormat by remember { mutableStateOf(NumberConvention.DECIMAL_DOT_GROUP_COMMA.name) }
    var numberFormatChosen by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<TemplatePreview?>(null) }
    var definition by remember { mutableStateOf<TemplateDefinition?>(null) }
    val expandedPatterns = remember { mutableStateMapOf<MessageField, Boolean>() }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var retryLocked by remember { mutableStateOf(false) }
    val id = rememberSaveable(receipt.id) { UUID.randomUUID().toString() }
    var operationId by rememberSaveable(receipt.id) { mutableStateOf(UUID.randomUUID().toString()) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val invalidSelection = stringResource(R.string.template_invalid_selection)
    val requiredName = stringResource(R.string.teacher_missing_name)
    val requiredAmount = stringResource(R.string.teacher_missing_amount)
    val requiredMerchant = stringResource(R.string.teacher_missing_merchant)
    val requiredCurrency = stringResource(R.string.teacher_missing_currency)
    val previewRequired = stringResource(R.string.teacher_preview_required)
    val previewNeedsChanges = stringResource(R.string.teacher_preview_needs_changes)
    val saveFailed = stringResource(R.string.template_save_failed)
    val retryFailed = stringResource(R.string.template_retry_save)
    fun invalidate() { preview = null; definition = null; error = null; operationId = UUID.randomUUID().toString() }
    val editable = !busy && !retryLocked
    fun assignSpan(slot: SlotName, span: TaughtSpan) {
                        val value = fieldText(payload, span.field).substring(span.start, span.end)
                        taught[slot] = span
                        when (slot) {
                            SlotName.AMOUNT -> { amount = value; amountSource = span.field.name; if (!numberFormatChosen) numberFormat = suggestedNumberConvention(value).name }
                            SlotName.MERCHANT -> { merchant = value; merchantSource = span.field.name; merchantMissing = false }
                            SlotName.CURRENCY -> { currencyExample = value; currencySource = span.field.name; fixedCurrency = false; currencyConfirmed = false }
                            SlotName.DATE -> { dateText = value; dateSource = span.field.name }
                            SlotName.REFERENCE -> { reference = value; referenceSource = span.field.name }
                            SlotName.ACCOUNT_HINT -> { accountHint = value; accountHintSource = span.field.name }
                            SlotName.NOTES -> { notesText = value; notesSource = span.field.name }
                            SlotName.VARIABLE -> { otherText = value; otherTextSource = span.field.name }
                        }
                        invalidate()
    }
    Dialog({ if (!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DialogSystemBars()
        Surface(Modifier.fillMaxSize()) {
            Scaffold(modifier = Modifier.imePadding(), topBar = {
                Column {
                    TopAppBar(title = { Text(stringResource(R.string.create_template)) }, navigationIcon = { TextButton(onClick = close, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
                    TeacherFieldBar(teachingField, { focusManager.clearFocus(); teachingField = it }, editable, taught.keys.toSet(), sections.isNotEmpty())
                    val currentSpan = if (teachingField == null) sectionBeingEdited?.let { sections[it] } else teachingField?.let { taught[it] }
                    if (editable && currentSpan != null) PreciseSelectionBar { isStart, direction ->
                        val slot = teachingField
                        val span = if (slot == null) sectionBeingEdited?.let { sections[it] } else taught[slot]
                        if (span != null) {
                            val text = fieldText(payload, span.field)
                            val start = if (isStart) moveTextBoundary(text, span.start, direction) else span.start
                            val end = if (isStart) span.end else moveTextBoundary(text, span.end, direction)
                            if (start < end) {
                                val next = TaughtSpan(span.field, start, end)
                                if (slot == null) { sections[span.field] = next; invalidate() } else assignSpan(slot, next)
                                preciseAdjustment = SelectionAdjustment(next, (preciseAdjustment?.sequence ?: 0L) + 1)
                            }
                        }
                    }
                }
            }, bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val missing = buildList {
                            if (name.isBlank()) add(requiredName)
                            if (amount.isBlank()) add(requiredAmount)
                            if (!merchantMissing && merchant.isBlank()) add(requiredMerchant)
                            if (!currencyConfirmed || (!fixedCurrency && currencyExample.isBlank())) add(requiredCurrency)
                            if (isEmpty() && preview?.canEnable != true) add(if (preview == null) previewRequired else previewNeedsChanges)
                        }
                        if (error == null && missing.isNotEmpty()) Text(missing.joinToString(" · "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("template_missing"))
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("template_error")) }
                        Text(stringResource(R.string.save_template_enrollment, original.sourcePackage), style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = recordEntry, enabled = !busy && recordEnabled, modifier = Modifier.weight(1f).testTag("builder_record_entry")) { Text(stringResource(if (recordExisting) R.string.view_entry else R.string.record_entry)) }
                            Button(enabled = !busy && preview?.canEnable == true && definition != null, modifier = Modifier.weight(1f).testTag("save_template"), onClick = {
                                val frozen = definition ?: return@Button
                                val command = LedgerCommand.PutTemplate(frozen.copy(enabled = true), operationId = operationId)
                                busy = true
                                scope.launch {
                                    try {
                                        val result = store.execute(command)
                                        if (result.applied) close() else { error = result.message?.takeIf { it.isNotBlank() } ?: saveFailed; retryLocked = false }
                                    } catch (_: Exception) { error = retryFailed; retryLocked = true }
                                    finally { busy = false }
                                }
                            }) { Text(stringResource(if (busy) R.string.saving else R.string.save_template)) }
                        }
                    }
                }
            }) { padding ->
                Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.builder_explanation))
                    Text(original.sourcePackage, style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(name, { name = it; invalidate() }, label = { Text(stringResource(R.string.template_name)) }, modifier = Modifier.fillMaxWidth().testTag("template_name"), enabled = editable)
                    NotificationSelectionTeacher(payload, taught, editable, sections = sections, active = teachingField, adjustment = preciseAdjustment, onSection = { field, span -> if (span == null) { sections.remove(field); if (sectionBeingEdited == field) sectionBeingEdited = null } else { sections[field] = span; sectionBeingEdited = field }; invalidate() }, onAssign = { slot, span -> assignSpan(slot, span) }, onClear = { slot ->
                        taught.remove(slot)
                                               when (slot) {
                            SlotName.AMOUNT -> amount = ""
                            SlotName.MERCHANT -> merchant = ""
                            SlotName.CURRENCY -> currencyExample = ""
                            SlotName.DATE -> dateText = ""
                            SlotName.REFERENCE -> reference = ""
                            SlotName.ACCOUNT_HINT -> accountHint = ""
                            SlotName.NOTES -> notesText = ""
                            SlotName.VARIABLE -> otherText = ""
                        }
                        invalidate()
                    })
                    Text(stringResource(R.string.field_examples_hint), style = MaterialTheme.typography.bodySmall)
                    ExampleField(stringResource(R.string.amount), amountSource, amount, "template_amount", editable, { taught.remove(SlotName.AMOUNT); amountSource = it; invalidate() }, { taught.remove(SlotName.AMOUNT); amount = it; if (!numberFormatChosen) numberFormat = suggestedNumberConvention(it).name; invalidate() })
                    Choice(stringResource(R.string.merchant_behavior), fixedMerchant.toString(), listOf("false" to stringResource(R.string.dynamic_merchant), "true" to stringResource(R.string.fixed_merchant)), enabled = editable) { fixedMerchant = it.toBoolean(); invalidate() }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(merchantMissing, { merchantMissing = it; if (it) { taught.remove(SlotName.MERCHANT); merchant = "" }; invalidate() }, enabled = editable, modifier = Modifier.testTag("template_no_merchant"))
                        Text(stringResource(R.string.template_no_merchant))
                    }
                    if (!merchantMissing) ExampleField(stringResource(R.string.merchant), merchantSource, merchant, "template_merchant", editable, { taught.remove(SlotName.MERCHANT); merchantSource = it; invalidate() }, { taught.remove(SlotName.MERCHANT); merchant = it; invalidate() })
                    if (merchantMissing) Text(stringResource(R.string.template_no_merchant_hint), style = MaterialTheme.typography.bodySmall)
                    else if (fixedMerchant) Text(stringResource(R.string.fixed_merchant_scope), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.optional_varying_fields), style = MaterialTheme.typography.bodySmall)
                    ExampleField(stringResource(R.string.reference_example), referenceSource, reference, "template_reference", editable, { taught.remove(SlotName.REFERENCE); referenceSource = it; invalidate() }, { taught.remove(SlotName.REFERENCE); reference = it; invalidate() })
                    ExampleField(stringResource(R.string.account_hint_example), accountHintSource, accountHint, "template_account_hint", editable, { taught.remove(SlotName.ACCOUNT_HINT); accountHintSource = it; invalidate() }, { taught.remove(SlotName.ACCOUNT_HINT); accountHint = it; invalidate() })
                    ExampleField(stringResource(R.string.template_other_text), otherTextSource, otherText, "template_other_text", editable, { taught.remove(SlotName.VARIABLE); otherTextSource = it; invalidate() }, { taught.remove(SlotName.VARIABLE); otherText = it; invalidate() })
                    Text(stringResource(R.string.template_other_text_hint), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.account_hint_boundary), style = MaterialTheme.typography.bodySmall)
                    Choice(stringResource(R.string.currency_behavior), fixedCurrency.toString(), listOf("true" to stringResource(R.string.assumed_currency), "false" to stringResource(R.string.dynamic_currency)), enabled = editable) { fixedCurrency = it.toBoolean(); currencyConfirmed = false; invalidate() }
                    if (!fixedCurrency) ExampleField(stringResource(R.string.currency_token), currencySource, currencyExample, "template_currency_token", editable, { taught.remove(SlotName.CURRENCY); currencySource = it; invalidate() }, { taught.remove(SlotName.CURRENCY); currencyExample = it; invalidate() })
                    OutlinedTextField(currency, { currency = it.uppercase(java.util.Locale.ROOT).take(3); currencyConfirmed = false; invalidate() }, label = { Text(stringResource(R.string.currency)) }, singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(currencyConfirmed, { currencyConfirmed = it; invalidate() }, enabled = editable, modifier = Modifier.testTag("confirm_template_currency"))
                        Text(stringResource(if (fixedCurrency) R.string.confirm_currency_assumption else R.string.confirm_currency_token), Modifier.weight(1f))
                    }
                    Choice(stringResource(R.string.kind), kind, EntryKind.entries.map { it.name to kindLabel(it) }, enabled = editable) { kind = it; invalidate() }
                    Choice(stringResource(R.string.number_format), numberFormat, listOf(NumberConvention.DECIMAL_DOT_NO_GROUPING.name to stringResource(R.string.number_dot), NumberConvention.DECIMAL_DOT_GROUP_COMMA.name to stringResource(R.string.number_dot_grouped), NumberConvention.DECIMAL_COMMA_GROUP_DOT.name to stringResource(R.string.number_comma), NumberConvention.INTEGER_NO_GROUPING.name to stringResource(R.string.number_integer)), enabled = editable) { numberFormat = it; numberFormatChosen = true; invalidate() }
                    Text(stringResource(R.string.template_scope_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = editable, modifier = Modifier.fillMaxWidth().testTag("preview_template"), onClick = {
                        invalidate()
                        try {
                            require(name.isNotBlank()) { requiredName }
                            require(amount.isNotBlank()) { requiredAmount }
                            require(merchantMissing || merchant.isNotBlank()) { requiredMerchant }
                            require(currencyConfirmed && (fixedCurrency || currencyExample.isNotBlank())) { requiredCurrency }
                            fun observed(source: String, value: String, slot: SlotName): ObservedSlot {
                                val field = MessageField.valueOf(source)
                                val span = taught[slot]?.takeIf { it.field == field && it.end <= fieldText(payload, field).length && fieldText(payload, field).substring(it.start, it.end) == value }
                                return ObservedSlot(field, value, slot, span?.start)
                            }
                            val selections = mutableListOf(observed(amountSource, amount, SlotName.AMOUNT))
                            if (!merchantMissing && !fixedMerchant) selections += observed(merchantSource, merchant, SlotName.MERCHANT)
                            else if (!merchantMissing) require(fieldText(payload, MessageField.valueOf(merchantSource)).contains(merchant)) { "The fixed merchant is not in the chosen notification field. Select it again." }
                            if (reference.isNotBlank()) selections += observed(referenceSource, reference, SlotName.REFERENCE)
                            if (accountHint.isNotBlank()) selections += observed(accountHintSource, accountHint, SlotName.ACCOUNT_HINT)
                            if (otherText.isNotBlank()) selections += observed(otherTextSource, otherText, SlotName.VARIABLE)
                            if (!fixedCurrency) selections += observed(currencySource, currencyExample, SlotName.CURRENCY)
                            if (dateText.isNotBlank()) selections += observed(dateSource, dateText, SlotName.DATE)
                            if (notesText.isNotBlank()) selections += observed(notesSource, notesText, SlotName.NOTES)
                            val compiled = compileObservedPatterns(payload, selections, sections)
                            val staticPredicates = compileStaticRestrictions(payload, selections, compiled)
                            val template = TemplateDefinition(id, name.trim(), original.sourcePackage, compiled,
                                predicates = staticPredicates + if (fixedMerchant && !merchantMissing) listOf(TemplatePredicate(MessageField.valueOf(merchantSource), PredicateOperation.CONTAINS, merchant)) else emptyList(),
                                kind = EntryKind.valueOf(kind), fixedCurrency = currency.takeIf { fixedCurrency }, fixedCurrencyEvidence = if (fixedCurrency) FixedCurrencyEvidence.USER_CONFIRMED_ASSUMPTION else null,
                                fixedMerchant = merchant.takeIf { fixedMerchant && !merchantMissing }, currencyTokens = if (fixedCurrency) emptyList() else currencyTokenOptions(CurrencyToken(currencyExample, currency, currencyExample in setOf("$", "¥", "￥"))), numberConvention = NumberConvention.valueOf(numberFormat))
                            definition = template
                            preview = TemplateEngine.preview(template, payload)
                        } catch (e: IllegalArgumentException) { error = e.message?.takeIf { it.isNotBlank() } ?: invalidSelection }
                    }) { Text(stringResource(R.string.preview_template)) }
                    definition?.let { template ->
                        Text(stringResource(R.string.pattern_preview), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.teacher_fixed_explanation), style = MaterialTheme.typography.bodySmall)
                        template.patterns.forEach { pattern ->
                            pattern.segments.filterIsInstance<PatternSegment.Slot>().groupingBy { it.name }.eachCount().filterValues { it > 1 }.forEach { (slot, count) ->
                                Text(stringResource(R.string.teacher_repeated_preview, slot.name.lowercase(), count), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        template.patterns.forEach { pattern ->
                            val selectedFields = pattern.segments.filterIsInstance<PatternSegment.Slot>().map { it.name.name.lowercase() }.distinct()
                            Text(messageFieldLabel(pattern.field), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(if (pattern.section) R.string.teacher_preview_section else R.string.teacher_preview_whole_field), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.teacher_preview_fields, selectedFields.joinToString(", ")), style = MaterialTheme.typography.bodyMedium)
                            val expanded = expandedPatterns[pattern.field] == true
                            TextButton(onClick = { expandedPatterns[pattern.field] = !expanded }, modifier = Modifier.testTag("template_pattern_toggle_${pattern.field.name}")) {
                                Text(stringResource(if (expanded) R.string.teacher_hide_matching_text else R.string.teacher_show_matching_text))
                            }
                            if (expanded) {
                                val color = MaterialTheme.colorScheme.primary
                                val matchingText = buildAnnotatedString {
                                    pattern.segments.forEach { segment -> when (segment) {
                                        is PatternSegment.Literal -> append(segment.text)
                                        is PatternSegment.Slot -> withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append("{${segment.name.name.lowercase()}}") }
                                    } }
                                }
                                SelectionContainer { Text(matchingText, modifier = Modifier.testTag("template_pattern_${pattern.field.name}")) }
                            }
                        }
                        template.predicates.forEach { restriction -> Text(stringResource(R.string.template_literal_restriction, messageFieldLabel(restriction.field), restriction.literal)) }
                    }
                    preview?.let { report ->
                        Text(stringResource(if (report.canEnable) R.string.preview_passed else R.string.preview_needs_changes), color = if (report.canEnable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        PreviewResult(stringResource(R.string.current_notification), report.current)
                        report.examples.forEachIndexed { index, example ->
                            Text(stringResource(if (example.shouldMatch) R.string.synthetic_positive else R.string.synthetic_negative, index + 1), style = MaterialTheme.typography.titleSmall)
                            MessageField.entries.forEach { field ->
                                val text = fieldText(example.payload, field)
                                if (text.isNotEmpty()) Text("${messageFieldLabel(field)}: $text")
                            }
                            PreviewResult(stringResource(R.string.extracted_preview), example.result)
                        }
                        report.issues.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                        Text(stringResource(R.string.enable_template_effect), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewResult(title: String, result: RecognitionResult) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    result.draft?.let { Text("${it.currency} ${it.amount} · ${kindLabel(it.kind)} · ${it.merchant}") }
    if (result.status == RecognitionStatus.NO_MATCH) Text(stringResource(R.string.preview_no_match))
    result.issues.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun ExampleField(label: String, source: String, value: String, tag: String, enabled: Boolean, changeSource: (String) -> Unit, changeValue: (String) -> Unit) {
    Choice(label + " · " + stringResource(R.string.message_field), source, MessageField.entries.map { it.name to messageFieldLabel(it) }, enabled = enabled) { changeSource(it) }
    OutlinedTextField(value, changeValue, enabled = enabled, label = { Text(label + " · " + stringResource(R.string.observed_example)) }, modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable
internal fun messageFieldLabel(field: MessageField): String = stringResource(when (field) {
    MessageField.TITLE -> R.string.notification_title
    MessageField.BODY -> R.string.notification_body
    MessageField.BIG_TEXT -> R.string.notification_expanded_body
    MessageField.SUB_TEXT -> R.string.notification_subtext
    MessageField.LINES -> R.string.notification_lines
})

internal data class ObservedSlot(val field: MessageField, val value: String, val slot: SlotName, val start: Int? = null)

/** Suggest formatting only; the visible number-format choice remains editable. */
internal fun suggestedNumberConvention(value: String): NumberConvention = when {
    Regex("[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]+)?").matches(value) -> NumberConvention.DECIMAL_DOT_GROUP_COMMA
    Regex("(?:[0-9]+|[0-9]{1,3}(?:\\.[0-9]{3})+),[0-9]+").matches(value) -> NumberConvention.DECIMAL_COMMA_GROUP_DOT
    else -> NumberConvention.DECIMAL_DOT_GROUP_COMMA
}

/** Replaces unique, nonoverlapping user-observed spans; remaining literals match as one whole field. */
internal fun compileObservedPatterns(payload: NotificationPayload, selections: List<ObservedSlot>, sections: Map<MessageField, TaughtSpan> = emptyMap()): List<FieldPattern> {
    require(selections.all { it.value.isNotEmpty() }) { "Select a nonempty value for every field." }
    require(sections.keys.all { field -> selections.any { it.field == field } }) { "Select at least one changing field inside each matching section." }
    return MessageField.entries.filter { field -> selections.any { it.field == field } }.map { field ->
        val fullText = fieldText(payload, field)
        val section = sections[field]
        require(section == null || (section.field == field && section.start >= 0 && section.end <= fullText.length && section.start < section.end)) { "Choose a valid matching section." }
        val sectionStart = section?.start ?: 0
        val text = if (section == null) fullText else fullText.substring(section.start, section.end)
        val repeated = mutableSetOf<SlotName>()
        val spans = selections.filter { it.field == field }.flatMap { selection ->
            val start = selection.start?.minus(sectionStart) ?: text.indexOf(selection.value)
            require(start >= 0 && start + selection.value.length <= text.length && text.regionMatches(start, selection.value, 0, selection.value.length)) { "The ${selection.slot.name.lowercase()} selection no longer matches the notification. Select it again." }
            val occurrences = mutableListOf<Int>()
            var position = text.indexOf(selection.value)
            while (position >= 0) { occurrences += position; position = text.indexOf(selection.value, position + 1) }
            if (occurrences.size > 1) repeated += selection.slot
            occurrences.map { Triple(it, it + selection.value.length, selection.slot) }
        }.sortedBy { it.first }
        var position = 0
        val segments = mutableListOf<PatternSegment>()
        spans.forEach { (start, end, slot) ->
            require(start >= position) { "Selections overlap. Adjust their start or end boundaries." }
            if (start > position) segments += PatternSegment.Literal(text.substring(position, start))
            segments += PatternSegment.Slot(slot, linked = slot in repeated)
            position = end
        }
        if (position < text.length || segments.isEmpty()) segments += PatternSegment.Literal(text.substring(position))
        // A recognized currency token gives the adjacent numeric amount a safe boundary.
        val bounded = segments.mapIndexed { index, segment ->
            if (segment is PatternSegment.Slot && segment.name == SlotName.AMOUNT &&
                listOfNotNull(segments.getOrNull(index - 1), segments.getOrNull(index + 1))
                    .any { it is PatternSegment.Slot && it.name == SlotName.CURRENCY }) segment.copy(numericOnly = true)
            else segment
        }
        FieldPattern(field, bounded, section != null)
    }
}

internal fun fieldText(payload: NotificationPayload, field: MessageField): String = when (field) {
    MessageField.TITLE -> payload.title
    MessageField.BODY -> payload.body
    MessageField.BIG_TEXT -> payload.bigText
    MessageField.SUB_TEXT -> payload.subText
    MessageField.LINES -> payload.textLines.joinToString("\n")
}

/** A short title/body may duplicate changing expanded content; never freeze those values. */
internal fun compileStaticRestrictions(payload: NotificationPayload, selections: List<ObservedSlot>, patterns: List<FieldPattern>): List<TemplatePredicate> =
    if (payload.title.isNotEmpty() && patterns.none { it.field == MessageField.TITLE } && selections.none { it.value.isNotEmpty() && payload.title.contains(it.value) })
        listOf(TemplatePredicate(MessageField.TITLE, PredicateOperation.EQUALS, payload.title)) else emptyList()

@Composable
internal fun TemplateSettings(templates: List<TemplateDefinition>, store: LedgerStore, execute: (LedgerCommand, () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<TemplateDefinition?>(null) }
    var applying by remember { mutableStateOf<TemplateDefinition?>(null) }
    var reports by remember { mutableStateOf<Map<String, TemplateApplyReport>>(emptyMap()) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var applyError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.operation_failed)
    OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("template_settings")) { Text(stringResource(R.string.templates)) }
    if (expanded) {
        Text(stringResource(R.string.template_settings_hint), style = MaterialTheme.typography.bodySmall)
        if (templates.isEmpty()) Text(stringResource(R.string.no_templates))
        templates.forEach { template ->
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(template.name, style = MaterialTheme.typography.titleSmall)
                    Text(template.sourcePackage, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(stringResource(R.string.template_enabled), Modifier.weight(1f))
                        Switch(template.enabled, { reports = reports - template.id; execute(LedgerCommand.SetTemplateEnabled(template.id, template.revision, it)) {} })
                        TextButton(onClick = { deleting = template }) { Text(stringResource(R.string.delete)) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        TextButton(onClick = { applyError = null; applying = template }, modifier = Modifier.weight(1.4f).testTag("apply_template_${template.id}")) {
                            Text(stringResource(R.string.template_apply_existing))
                        }
                        TextButton(onClick = { viewing = template.id }, modifier = Modifier.weight(1f).testTag("view_template_${template.id}")) {
                            Text(stringResource(R.string.template_view))
                        }
                    }
                    val identical = templates.count { it.id != template.id && it.hasSameContent(template) }
                    if (identical > 0) Text(stringResource(R.string.template_identical_copies, identical), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    reports[template.id]?.let { report ->
                        Text(stringResource(R.string.template_apply_report, report.checked, report.matched, report.recorded, report.review, report.unmatched),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("template_report_${template.id}"))
                    }
                }
            }
        }
    }
    viewing?.let { id -> templates.find { it.id == id }?.let { TemplateDetails(it, templates, close = { viewing = null }, edit = { viewing = null; editing = it.id }) } }
    editing?.let { id -> templates.find { it.id == id }?.let { TemplateEditor(it, store) { editing = null } } }
    deleting?.let { template ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete_template)) }, text = { Text(stringResource(R.string.template_delete_hint)) },
            confirmButton = { TextButton(onClick = { execute(LedgerCommand.DeleteTemplate(template.id, template.revision)) { deleting = null } }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } })
    }
    applying?.let { template ->
        val command = remember(template.id, template.revision) { LedgerCommand.ApplyTemplateToPending(template.id, template.revision) }
        AlertDialog(
            onDismissRequest = { if (!busy) applying = null },
            title = { Text(stringResource(R.string.template_apply_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.template_apply_explanation, template.name))
                applyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val result = store.execute(command)
                            if (result.applied) {
                                result.templateReport?.let { reports = reports + (template.id to it) }
                                applying = null
                            } else applyError = result.message ?: failed
                        } catch (_: Exception) { applyError = failed }
                        finally { busy = false }
                    }
                }, modifier = Modifier.testTag("confirm_apply_template")) { Text(stringResource(if (busy) R.string.saving else R.string.template_apply_existing)) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { applying = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
