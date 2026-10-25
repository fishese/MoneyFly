package com.fishese.moneyfly.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
    var numberFormat by remember { mutableStateOf(NumberConvention.DECIMAL_DOT_NO_GROUPING.name) }
    var numberFormatChosen by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<TemplatePreview?>(null) }
    var definition by remember { mutableStateOf<TemplateDefinition?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var retryLocked by remember { mutableStateOf(false) }
    val id = rememberSaveable(receipt.id) { UUID.randomUUID().toString() }
    var operationId by rememberSaveable(receipt.id) { mutableStateOf(UUID.randomUUID().toString()) }
    val scope = rememberCoroutineScope()
    val invalidSelection = stringResource(R.string.template_invalid_selection)
    val saveFailed = stringResource(R.string.template_save_failed)
    val retryFailed = stringResource(R.string.template_retry_save)
    fun invalidate() { preview = null; definition = null; error = null; operationId = UUID.randomUUID().toString() }
    val editable = !busy && !retryLocked
    Dialog({ if (!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DialogSystemBars()
        Surface(Modifier.fillMaxSize()) {
            Scaffold(modifier = Modifier.imePadding(), topBar = {
                TopAppBar(title = { Text(stringResource(R.string.create_template)) }, navigationIcon = { TextButton(onClick = close, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
            }, bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                        if (result.applied) close() else { error = saveFailed; retryLocked = false }
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
                    SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { NotificationContent(original, expanded = true) } }
                    OutlinedTextField(name, { name = it; invalidate() }, label = { Text(stringResource(R.string.template_name)) }, modifier = Modifier.fillMaxWidth().testTag("template_name"), enabled = editable)
                    Text(stringResource(R.string.field_examples_hint), style = MaterialTheme.typography.bodySmall)
                    ExampleField(stringResource(R.string.amount), amountSource, amount, "template_amount", editable, { amountSource = it; invalidate() }, { amount = it; if (!numberFormatChosen) numberFormat = suggestedNumberConvention(it).name; invalidate() })
                    Choice(stringResource(R.string.merchant_behavior), fixedMerchant.toString(), listOf("false" to stringResource(R.string.dynamic_merchant), "true" to stringResource(R.string.fixed_merchant)), enabled = editable) { fixedMerchant = it.toBoolean(); invalidate() }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(merchantMissing, { merchantMissing = it; invalidate() }, enabled = editable, modifier = Modifier.testTag("template_no_merchant"))
                        Text(stringResource(R.string.template_no_merchant))
                    }
                    if (!merchantMissing) ExampleField(stringResource(R.string.merchant), merchantSource, merchant, "template_merchant", editable, { merchantSource = it; invalidate() }, { merchant = it; invalidate() })
                    if (merchantMissing) Text(stringResource(R.string.template_no_merchant_hint), style = MaterialTheme.typography.bodySmall)
                    else if (fixedMerchant) Text(stringResource(R.string.fixed_merchant_scope), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.optional_varying_fields), style = MaterialTheme.typography.bodySmall)
                    ExampleField(stringResource(R.string.reference_example), referenceSource, reference, "template_reference", editable, { referenceSource = it; invalidate() }, { reference = it; invalidate() })
                    ExampleField(stringResource(R.string.account_hint_example), accountHintSource, accountHint, "template_account_hint", editable, { accountHintSource = it; invalidate() }, { accountHint = it; invalidate() })
                    ExampleField(stringResource(R.string.template_other_text), otherTextSource, otherText, "template_other_text", editable, { otherTextSource = it; invalidate() }, { otherText = it; invalidate() })
                    Text(stringResource(R.string.template_other_text_hint), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.account_hint_boundary), style = MaterialTheme.typography.bodySmall)
                    Choice(stringResource(R.string.currency_behavior), fixedCurrency.toString(), listOf("true" to stringResource(R.string.assumed_currency), "false" to stringResource(R.string.dynamic_currency)), enabled = editable) { fixedCurrency = it.toBoolean(); currencyConfirmed = false; invalidate() }
                    if (!fixedCurrency) ExampleField(stringResource(R.string.currency_token), currencySource, currencyExample, "template_currency_token", editable, { currencySource = it; invalidate() }, { currencyExample = it; invalidate() })
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
                            require(name.isNotBlank() && currencyConfirmed && (merchantMissing || merchant.isNotBlank()))
                            val selections = mutableListOf(ObservedSlot(MessageField.valueOf(amountSource), amount, SlotName.AMOUNT))
                            if (!merchantMissing && !fixedMerchant) selections += ObservedSlot(MessageField.valueOf(merchantSource), merchant, SlotName.MERCHANT)
                            else if (!merchantMissing) require(fieldText(payload, MessageField.valueOf(merchantSource)).contains(merchant))
                            if (reference.isNotBlank()) selections += ObservedSlot(MessageField.valueOf(referenceSource), reference, SlotName.REFERENCE)
                            if (accountHint.isNotBlank()) selections += ObservedSlot(MessageField.valueOf(accountHintSource), accountHint, SlotName.ACCOUNT_HINT)
                            if (otherText.isNotBlank()) selections += ObservedSlot(MessageField.valueOf(otherTextSource), otherText, SlotName.VARIABLE)
                            if (!fixedCurrency) selections += ObservedSlot(MessageField.valueOf(currencySource), currencyExample, SlotName.CURRENCY)
                            val compiled = compileObservedPatterns(payload, selections)
                            val staticPredicates = compileStaticRestrictions(payload, selections, compiled)
                            val template = TemplateDefinition(id, name.trim(), original.sourcePackage, compiled,
                                predicates = staticPredicates + if (fixedMerchant && !merchantMissing) listOf(TemplatePredicate(MessageField.valueOf(merchantSource), PredicateOperation.CONTAINS, merchant)) else emptyList(),
                                kind = EntryKind.valueOf(kind), fixedCurrency = currency.takeIf { fixedCurrency }, fixedCurrencyEvidence = if (fixedCurrency) FixedCurrencyEvidence.USER_CONFIRMED_ASSUMPTION else null,
                                fixedMerchant = merchant.takeIf { fixedMerchant && !merchantMissing }, currencyTokens = if (fixedCurrency) emptyList() else listOf(CurrencyToken(currencyExample, currency, true)), numberConvention = NumberConvention.valueOf(numberFormat))
                            definition = template
                            preview = TemplateEngine.preview(template, payload)
                        } catch (_: IllegalArgumentException) { error = invalidSelection }
                    }) { Text(stringResource(R.string.preview_template)) }
                    definition?.let { template ->
                        Text(stringResource(R.string.pattern_preview), style = MaterialTheme.typography.titleMedium)
                        template.patterns.forEach { pattern -> Text("${messageFieldLabel(pattern.field)}: " + pattern.segments.joinToString("") { when (it) { is PatternSegment.Literal -> it.text; is PatternSegment.Slot -> "{${it.name.name.lowercase()}}" } }) }
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
private fun messageFieldLabel(field: MessageField): String = stringResource(when (field) {
    MessageField.TITLE -> R.string.notification_title
    MessageField.BODY -> R.string.notification_body
    MessageField.BIG_TEXT -> R.string.notification_expanded_body
    MessageField.SUB_TEXT -> R.string.notification_subtext
    MessageField.LINES -> R.string.notification_lines
})

internal data class ObservedSlot(val field: MessageField, val value: String, val slot: SlotName)

/** Suggest formatting only; the visible number-format choice remains editable. */
internal fun suggestedNumberConvention(value: String): NumberConvention = when {
    Regex("[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]+)?").matches(value) -> NumberConvention.DECIMAL_DOT_GROUP_COMMA
    Regex("(?:[0-9]+|[0-9]{1,3}(?:\\.[0-9]{3})+),[0-9]+").matches(value) -> NumberConvention.DECIMAL_COMMA_GROUP_DOT
    else -> NumberConvention.DECIMAL_DOT_NO_GROUPING
}

/** Replaces unique, nonoverlapping user-observed spans; remaining literals match as one whole field. */
internal fun compileObservedPatterns(payload: NotificationPayload, selections: List<ObservedSlot>): List<FieldPattern> {
    require(selections.all { it.value.isNotEmpty() })
    return MessageField.entries.filter { field -> selections.any { it.field == field } }.map { field ->
        val text = fieldText(payload, field)
        val spans = selections.filter { it.field == field }.map { selection ->
            val start = text.indexOf(selection.value)
            require(start >= 0 && text.indexOf(selection.value, start + 1) < 0)
            Triple(start, start + selection.value.length, selection.slot)
        }.sortedBy { it.first }
        var position = 0
        val segments = mutableListOf<PatternSegment>()
        spans.forEach { (start, end, slot) ->
            require(start >= position)
            if (start > position) segments += PatternSegment.Literal(text.substring(position, start))
            segments += PatternSegment.Slot(slot)
            position = end
        }
        if (position < text.length || segments.isEmpty()) segments += PatternSegment.Literal(text.substring(position))
        FieldPattern(field, segments)
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
internal fun TemplateSettings(templates: List<TemplateDefinition>, execute: (LedgerCommand, () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<TemplateDefinition?>(null) }
    OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("template_settings")) { Text(stringResource(R.string.templates)) }
    if (expanded) {
        Text(stringResource(R.string.template_settings_hint), style = MaterialTheme.typography.bodySmall)
        if (templates.isEmpty()) Text(stringResource(R.string.no_templates))
        templates.forEach { template ->
            Text(template.name, style = MaterialTheme.typography.titleSmall)
            Text(template.sourcePackage, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.template_enabled), Modifier.weight(1f))
                Switch(template.enabled, { execute(LedgerCommand.SetTemplateEnabled(template.id, template.revision, it)) {} })
                TextButton(onClick = { deleting = template }) { Text(stringResource(R.string.delete)) }
            }
        }
    }
    deleting?.let { template ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete_template)) }, text = { Text(stringResource(R.string.template_delete_hint)) },
            confirmButton = { TextButton(onClick = { execute(LedgerCommand.DeleteTemplate(template.id, template.revision)) { deleting = null } }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
