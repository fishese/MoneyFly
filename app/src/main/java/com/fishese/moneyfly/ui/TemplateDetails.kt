package com.fishese.moneyfly.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fishese.moneyfly.R
import com.fishese.moneyfly.recognition.*

internal fun FieldPattern.displayPattern(): String = segments.joinToString("") { segment ->
    when (segment) {
        is PatternSegment.Literal -> segment.text
        is PatternSegment.Slot -> "{${segment.name.name.lowercase()}}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemplateDetails(template: TemplateDefinition, templates: List<TemplateDefinition>, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DialogSystemBars()
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.template_view)) },
                navigationIcon = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } }) }) { padding ->
                Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp).testTag("template_details"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(template.name, style = MaterialTheme.typography.headlineSmall)
                    SelectionContainer { Text(template.sourcePackage) }
                    Text(stringResource(R.string.template_identity, template.id, template.revision), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(if (template.enabled) R.string.template_enabled else R.string.template_disabled))
                    Text(kindLabel(template.kind), style = MaterialTheme.typography.titleMedium)
                    val sameApp = templates.count { it.id != template.id && it.sourcePackage == template.sourcePackage }
                    Text(stringResource(R.string.template_overlap_explanation, sameApp), style = MaterialTheme.typography.bodySmall)
                    val identical = templates.filter { it.id != template.id && it.hasSameContent(template) }
                    identical.forEach { Text(stringResource(R.string.template_identical_to, it.name, it.id), color = MaterialTheme.colorScheme.tertiary) }
                    Text(stringResource(R.string.pattern_preview), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.template_pattern_help), style = MaterialTheme.typography.bodySmall)
                    template.patterns.forEach { pattern ->
                        Text(messageFieldLabel(pattern.field), style = MaterialTheme.typography.labelLarge)
                        SelectionContainer { Text(pattern.displayPattern(), modifier = Modifier.testTag("template_pattern_${pattern.field.name}")) }
                        pattern.segments.filterIsInstance<PatternSegment.Slot>().forEach { slot ->
                            Text(stringResource(R.string.template_slot_details, slot.name.name.lowercase(), slot.maxLength,
                                stringResource(if (slot.numericOnly) R.string.template_slot_numeric else R.string.template_slot_text)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(stringResource(R.string.template_conditions), style = MaterialTheme.typography.titleMedium)
                    if (template.predicates.isEmpty()) Text(stringResource(R.string.template_no_conditions))
                    template.predicates.forEach { predicate ->
                        Text("${messageFieldLabel(predicate.field)} · ${stringResource(when (predicate.operation) {
                            PredicateOperation.EQUALS -> R.string.exact_match
                            PredicateOperation.CONTAINS -> R.string.contains_match
                            PredicateOperation.STARTS_WITH -> R.string.template_starts_with
                            PredicateOperation.ENDS_WITH -> R.string.template_ends_with
                        })} · ${stringResource(if (predicate.ignoreCase) R.string.template_case_insensitive else R.string.template_case_sensitive)}")
                        SelectionContainer { Text(predicate.literal) }
                    }
                    Text(stringResource(R.string.currency_behavior), style = MaterialTheme.typography.titleMedium)
                    template.fixedCurrency?.let { Text(it) }
                    template.fixedCurrencyEvidence?.let { Text(stringResource(if (it == FixedCurrencyEvidence.USER_CONFIRMED_ASSUMPTION) R.string.assumed_currency else R.string.template_currency_literal)) }
                    template.currencyTokens.forEach { Text("${it.token} → ${it.currency}" + if (it.userConfirmedAmbiguous) " · ${stringResource(R.string.assumed_currency)}" else "") }
                    Text(stringResource(R.string.number_format), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(when (template.numberConvention) {
                        NumberConvention.DECIMAL_DOT_GROUP_COMMA -> R.string.number_dot_grouped
                        NumberConvention.DECIMAL_COMMA_GROUP_DOT -> R.string.number_comma
                        NumberConvention.DECIMAL_DOT_NO_GROUPING -> R.string.number_dot
                        NumberConvention.INTEGER_NO_GROUPING -> R.string.number_integer
                    }))
                    template.fixedMerchant?.let { Text(stringResource(R.string.merchant), style = MaterialTheme.typography.titleMedium); SelectionContainer { Text(it) } }
                    Text(stringResource(R.string.account_hint_boundary), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
