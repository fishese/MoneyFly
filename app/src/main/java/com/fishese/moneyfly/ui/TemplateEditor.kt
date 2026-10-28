package com.fishese.moneyfly.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fishese.moneyfly.R
import com.fishese.moneyfly.ledger.*
import com.fishese.moneyfly.recognition.*
import java.util.UUID
import kotlinx.coroutines.launch

/** Replace only whole currency-token occurrences, never arbitrary substrings inside words. */
internal fun useCurrencyFromPattern(template: TemplateDefinition, token: String, currency: String): TemplateDefinition {
    require(token.isNotBlank() && token.length <= 16) { "Enter the exact currency text found in the pattern." }
    var count = 0
    val patterns = template.patterns.map { pattern ->
        val segments = mutableListOf<PatternSegment>()
        for (segment in pattern.segments) {
            if (segment !is PatternSegment.Literal) { segments += segment; continue }
            val text = segment.text
            var cursor = 0
            var position = text.indexOf(token)
            while (position >= 0) {
                val end = position + token.length
                val left = position == 0 || !text[position - 1].isLetterOrDigit() || !token.first().isLetterOrDigit()
                val right = end == text.length || !text[end].isLetterOrDigit() || !token.last().isLetterOrDigit()
                if (left && right) {
                    if (position > cursor) segments += PatternSegment.Literal(text.substring(cursor, position))
                    segments += PatternSegment.Slot(SlotName.CURRENCY)
                    count++; cursor = end
                }
                position = text.indexOf(token, position + token.length)
            }
            if (cursor < text.length) segments += PatternSegment.Literal(text.substring(cursor))
        }
        pattern.copy(segments = segments)
    }
    require(count > 0) { "That currency text was not found in the fixed pattern. Check the exact spelling." }
    val normalized = normalizeEditedPatterns(patterns)
    return template.copy(patterns = normalized, fixedCurrency = null, fixedCurrencyEvidence = null,
        currencyTokens = currencyTokenOptions(CurrencyToken(token, currency, token in setOf("$", "¥", "￥"))))
}

/** Keep the current slot settings where possible and enforce equal repeated values. */
internal fun normalizeEditedPatterns(patterns: List<FieldPattern>): List<FieldPattern> {
    val counts = patterns.flatMap { it.segments }.filterIsInstance<PatternSegment.Slot>().groupingBy { it.name }.eachCount()
    return patterns.map { pattern ->
        pattern.copy(segments = pattern.segments.mapIndexed { i, segment ->
            if (segment !is PatternSegment.Slot) segment else {
                val adjacentCurrency = segment.name == SlotName.AMOUNT && listOfNotNull(pattern.segments.getOrNull(i - 1), pattern.segments.getOrNull(i + 1))
                    .any { it is PatternSegment.Slot && it.name == SlotName.CURRENCY }
                segment.copy(linked = (counts[segment.name] ?: 0) > 1, numericOnly = segment.numericOnly || adjacentCurrency)
            }
        })
    }
}

internal fun updateTemplatePatterns(original: List<FieldPattern>, expressions: Map<MessageField, String>): List<FieldPattern> {
    val changed = original.map { pattern ->
        val expression = expressions[pattern.field] ?: pattern.displayPattern()
        if (expression == pattern.displayPattern()) pattern else {
            val parsed = TemplateEngine.parsePattern(pattern.field, expression)
            val byName = pattern.segments.filterIsInstance<PatternSegment.Slot>().groupBy { it.name }
            val seen = mutableMapOf<SlotName, Int>()
            pattern.copy(segments = parsed.segments.map { segment ->
                if (segment !is PatternSegment.Slot) segment else {
                    val index = seen.getOrDefault(segment.name, 0); seen[segment.name] = index + 1
                    byName[segment.name]?.getOrNull(index) ?: segment
                }
            })
        }
    }
    return normalizeEditedPatterns(changed)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemplateEditor(template: TemplateDefinition, store: LedgerStore, close: () -> Unit) {
    var draft by remember(template.id, template.revision) { mutableStateOf(template) }
    var name by remember(template.id, template.revision) { mutableStateOf(template.name) }
    val patterns = remember(template.id, template.revision) { mutableStateMapOf<MessageField, String>().apply { template.patterns.forEach { put(it.field, it.displayPattern()) } } }
    var token by remember(template.id, template.revision) { mutableStateOf(template.fixedCurrency.orEmpty()) }
    var currency by remember(template.id, template.revision) { mutableStateOf(template.fixedCurrency ?: template.currencyTokens.firstOrNull()?.currency ?: "HKD") }
    var error by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }
    var confirmedSymbol by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var operationId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    val scope = rememberCoroutineScope()
    fun invalidate() { checked = false; error = null; operationId = UUID.randomUUID().toString() }
    fun buildDraft(): TemplateDefinition = draft.copy(name = name.trim(), patterns = updateTemplatePatterns(draft.patterns, patterns))
    fun validation(candidate: TemplateDefinition): String? {
        val problems = TemplateEngine.validate(candidate)
        if (problems.isNotEmpty()) return problems.joinToString("\n")
        val examples = TemplateEngine.generatedExamples(candidate)
        return if (examples.size == 3 && examples.all { (it.result.status == RecognitionStatus.READY) == it.shouldMatch }) null
            else "The generated examples did not pass. Review the pattern and field boundaries."
    }
    Dialog(onDismissRequest = { if (!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DialogSystemBars()
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.template_edit)) }, navigationIcon = { TextButton(onClick = close, enabled = !busy) { Text(stringResource(R.string.cancel)) } }) },
                bottomBar = { Surface(tonalElevation = 3.dp) { Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { try { val next = buildDraft(); error = validation(next); checked = error == null } catch (e: IllegalArgumentException) { checked = false; error = e.message } }, enabled = !busy, modifier = Modifier.weight(1f).testTag("edit_template_check")) { Text(stringResource(R.string.template_check)) }
                    Button(onClick = {
                        val candidate = try { buildDraft() } catch (e: IllegalArgumentException) { error = e.message; return@Button }
                        error = validation(candidate)
                        if (error != null) { checked = false; return@Button }
                        busy = true
                        scope.launch {
                            try {
                                val result = store.execute(LedgerCommand.PutTemplate(candidate, expectedRevision = template.revision, operationId = operationId))
                                if (result.applied) close() else { error = result.message ?: "Could not save the template."; checked = false }
                            } catch (_: Exception) { error = "Save was interrupted. Retry the same change or cancel and check the template list." }
                            finally { busy = false }
                        }
                    }, enabled = !busy && checked, modifier = Modifier.weight(1f).testTag("edit_template_save")) { Text(stringResource(R.string.save)) }
                } } }) { padding ->
                Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.template_edit_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(name, { name = it; invalidate() }, label = { Text(stringResource(R.string.template_name)) }, modifier = Modifier.fillMaxWidth().testTag("edit_template_name"))
                    Text(stringResource(R.string.currency_behavior), style = MaterialTheme.typography.titleMedium)
                    if (draft.fixedCurrency != null) {
                        Text(stringResource(R.string.template_current_fixed_currency, draft.fixedCurrency!!))
                        Text(stringResource(R.string.template_currency_edit_hint), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(token, { token = it; invalidate() }, label = { Text(stringResource(R.string.template_currency_text)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("edit_currency_token"))
                        OutlinedTextField(currency, { currency = it.uppercase(java.util.Locale.ROOT).take(3); invalidate() }, label = { Text(stringResource(R.string.currency)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = confirmedSymbol, onCheckedChange = { confirmedSymbol = it; invalidate() }, modifier = Modifier.testTag("edit_confirm_symbol"))
                            Text(stringResource(R.string.template_confirm_symbol), style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = {
                            try {
                                require(token !in setOf("$", "¥", "￥") || confirmedSymbol) { "Confirm which currency this symbol represents." }
                                val converted = useCurrencyFromPattern(buildDraft(), token, currency)
                                draft = converted
                                converted.patterns.forEach { patterns[it.field] = it.displayPattern() }
                                invalidate()
                            } catch (e: IllegalArgumentException) { error = e.message ?: "Could not find that currency text." }
                        }, modifier = Modifier.testTag("edit_use_currency")) { Text(stringResource(R.string.template_use_currency_text)) }
                    } else {
                        Text(stringResource(R.string.template_current_dynamic_currency))
                        Text(draft.currencyTokens.joinToString(" · ") { "${it.token} → ${it.currency}" }, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(token, { token = it; confirmedSymbol = false; invalidate() }, label = { Text(stringResource(R.string.template_currency_text)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(currency, { currency = it.uppercase(java.util.Locale.ROOT).take(3); confirmedSymbol = false; invalidate() }, label = { Text(stringResource(R.string.currency)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(confirmedSymbol, { confirmedSymbol = it; invalidate() })
                            Text(stringResource(R.string.template_confirm_symbol), style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = {
                            val candidate = CurrencyToken(token, currency, token in setOf("$", "¥", "￥") && confirmedSymbol)
                            val updated = draft.copy(currencyTokens = draft.currencyTokens.filterNot { it.token == token } + candidate)
                            val issues = TemplateEngine.validate(updated)
                            if (issues.isEmpty()) { draft = updated; invalidate() } else error = issues.joinToString("\n")
                        }) { Text(stringResource(R.string.template_add_currency_token)) }
                    }
                    Choice(stringResource(R.string.kind), draft.kind.name, EntryKind.entries.map { it.name to kindLabel(it) }) { draft = draft.copy(kind = EntryKind.valueOf(it)); invalidate() }
                    Choice(stringResource(R.string.number_format), draft.numberConvention.name, listOf(
                        NumberConvention.DECIMAL_DOT_GROUP_COMMA.name to stringResource(R.string.number_dot_grouped),
                        NumberConvention.DECIMAL_DOT_NO_GROUPING.name to stringResource(R.string.number_dot),
                        NumberConvention.DECIMAL_COMMA_GROUP_DOT.name to stringResource(R.string.number_comma),
                        NumberConvention.INTEGER_NO_GROUPING.name to stringResource(R.string.number_integer),
                    )) { draft = draft.copy(numberConvention = NumberConvention.valueOf(it)); invalidate() }
                    Text(stringResource(R.string.pattern_preview), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.template_edit_pattern_hint), style = MaterialTheme.typography.bodySmall)
                    draft.patterns.forEach { pattern ->
                        OutlinedTextField(patterns[pattern.field].orEmpty(), { patterns[pattern.field] = it; invalidate() },
                            label = { Text(messageFieldLabel(pattern.field)) }, modifier = Modifier.fillMaxWidth().testTag("edit_pattern_${pattern.field.name}"), minLines = 2, maxLines = 8)
                        if (pattern.section) Text(stringResource(R.string.teacher_section_detail), style = MaterialTheme.typography.bodySmall)
                    }
                    if (draft.predicates.isNotEmpty()) {
                        Text(stringResource(R.string.template_conditions), style = MaterialTheme.typography.titleMedium)
                        draft.predicates.forEachIndexed { index, predicate ->
                            OutlinedTextField(predicate.literal, { value ->
                                draft = draft.copy(predicates = draft.predicates.mapIndexed { i, p -> if (i == index) p.copy(literal = value) else p })
                                invalidate()
                            }, label = { Text(messageFieldLabel(predicate.field) + " · " + predicate.operation.name.lowercase()) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Text(stringResource(R.string.template_edit_existing_hint), style = MaterialTheme.typography.bodySmall)
                    if (checked) Text(stringResource(R.string.template_check_passed), color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("edit_template_passed"))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("edit_template_error")) }
                }
            }
        }
    }
}
