package com.fishese.moneyfly.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fishese.moneyfly.R
import com.fishese.moneyfly.capture.InboxItem
import com.fishese.moneyfly.capture.InboxStatus
import com.fishese.moneyfly.capture.InboxStore
import com.fishese.moneyfly.capture.NotificationPayload
import com.fishese.moneyfly.ledger.LedgerCommand
import com.fishese.moneyfly.recognition.IgnoreRule
import com.fishese.moneyfly.recognition.IgnoreRuleMatcher
import com.fishese.moneyfly.recognition.MessageField
import com.fishese.moneyfly.recognition.DefaultStatusRules
import java.util.UUID
import kotlinx.coroutines.launch

/** Propose only known status patterns or exact text; saving still requires match review. */
internal fun suggestedIgnorePattern(payload: NotificationPayload?, field: MessageField): String {
    if (payload == null) return ""
    if (field == MessageField.TITLE) {
        DefaultStatusRules.forSource(payload.sourcePackage)
            .firstOrNull { IgnoreRuleMatcher.matches(payload, it) }?.let { return it.pattern }
    }
    val text = fieldText(payload, field)
    return text.takeIf { it.isNotBlank() && it.length <= 256 && '\n' !in it && '*' !in it } ?: ""
}

@Composable
internal fun IgnoreRuleBuilder(
    item: InboxItem,
    inbox: List<InboxItem>,
    store: InboxStore?,
    sourceName: String = item.sourcePackage,
    close: () -> Unit,
) {
    val defaultName = stringResource(R.string.ignore_rule_default_name)
    var name by rememberSaveable(item.id) { mutableStateOf(defaultName) }
    var fieldName by rememberSaveable(item.id) { mutableStateOf(MessageField.TITLE.name) }
    var pattern by rememberSaveable(item.id) { mutableStateOf(suggestedIgnorePattern(item.payload, MessageField.TITLE)) }
    var patternEdited by rememberSaveable(item.id) { mutableStateOf(false) }
    var ignoreCase by rememberSaveable(item.id) { mutableStateOf(true) }
    var sameAppOnly by rememberSaveable(item.id) { mutableStateOf(true) }
    var previewCount by rememberSaveable(item.id) { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var retryLocked by rememberSaveable(item.id) { mutableStateOf(false) }
    var frozenCommand by remember { mutableStateOf<LedgerCommand.PutIgnoreRule?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val id = rememberSaveable(item.id) { UUID.randomUUID().toString() }
    val operationId = rememberSaveable(item.id) { UUID.randomUUID().toString() }
    val field = MessageField.valueOf(fieldName)
    val rule = IgnoreRule(id, name.trim(), item.sourcePackage, field, pattern, ignoreCase, sameAppOnly = sameAppOnly)
    val errors = IgnoreRuleMatcher.validate(rule)
    val scope = rememberCoroutineScope()
    val saveFailed = stringResource(R.string.ignore_rule_save_failed)
    val saveUnknown = stringResource(R.string.ignore_rule_save_unknown)
    val editable = !busy && !retryLocked
    fun changed() { previewCount = null; error = null }

    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.ignore_similar_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ignore_similar_explanation))
                Text(stringResource(R.string.ignore_rule_app, sourceName), style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(stringResource(R.string.ignore_rule_same_app), Modifier.weight(1f))
                    Switch(sameAppOnly, { sameAppOnly = it; changed() }, enabled = editable, modifier = Modifier.testTag("ignore_rule_same_app"))
                }
                if (!sameAppOnly) Text(stringResource(R.string.ignore_rule_all_apps_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(name, { name = it; changed() }, label = { Text(stringResource(R.string.ignore_rule_name)) }, singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth().testTag("ignore_rule_name"))
                Choice(
                    stringResource(R.string.ignore_rule_field), fieldName,
                    MessageField.entries.map { it.name to stringResource(ignoreFieldLabel(it)) },
                    enabled = editable,
                ) { fieldName = it; if (!patternEdited) pattern = suggestedIgnorePattern(item.payload, MessageField.valueOf(it)); changed() }
                OutlinedTextField(
                    pattern, { pattern = it; patternEdited = true; changed() },
                    label = { Text(stringResource(R.string.ignore_rule_pattern)) },
                    supportingText = { Text(stringResource(R.string.ignore_rule_pattern_hint)) },
                    enabled = editable, modifier = Modifier.fillMaxWidth().testTag("ignore_rule_pattern"),
                )
                Text(stringResource(R.string.ignore_rule_draft_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.ignore_rule_current_text), style = MaterialTheme.typography.labelLarge)
                val currentText = item.payload?.let { fieldText(it, field) }.orEmpty()
                SelectionContainer {
                    Text(currentText.ifBlank { stringResource(R.string.ignore_rule_empty_field) }, modifier = Modifier.testTag("ignore_rule_current_message"))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.ignore_rule_ignore_case), Modifier.weight(1f))
                    Switch(ignoreCase, { ignoreCase = it; changed() }, enabled = editable, modifier = Modifier.testTag("ignore_rule_case"))
                }
                if (errors.isNotEmpty() && pattern.isNotEmpty()) Text(errors.joinToString(" "), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ignore_rule_error"))
                OutlinedButton(
                    enabled = editable && errors.isEmpty(),
                    onClick = {
                        val pending = setOf(InboxStatus.UNRECOGNIZED, InboxStatus.READY, InboxStatus.REVIEW_NEEDED)
                        previewCount = inbox.count { row -> row.status in pending && row.payload?.let { IgnoreRuleMatcher.matches(it, rule) } == true }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("preview_ignore_rule"),
                ) { Text(stringResource(R.string.preview_ignore_rule)) }
                previewCount?.let { count -> Text(pluralStringResource(R.plurals.ignore_rule_match_count, count, count), modifier = Modifier.testTag("ignore_rule_preview")) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ignore_rule_save_error")) }
                Text(stringResource(R.string.ignore_rule_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(
                enabled = store != null && !busy && (retryLocked || previewCount != null && errors.isEmpty()),
                modifier = Modifier.testTag("save_ignore_rule"),
                onClick = {
                    val command = frozenCommand ?: LedgerCommand.PutIgnoreRule(rule, operationId = operationId).also { frozenCommand = it }
                    busy = true
                    scope.launch {
                        try {
                            val result = store?.execute(command)
                            if (result?.applied == true) close()
                            else {
                                error = result?.message ?: saveFailed
                                frozenCommand = null
                                retryLocked = false
                            }
                        } catch (_: Exception) {
                            error = saveUnknown
                            retryLocked = true
                        } finally { busy = false }
                    }
                },
            ) { Text(stringResource(if (retryLocked) R.string.retry_save_rule else R.string.save_rule)) }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun IgnoreRuleSettings(rules: List<IgnoreRule>, execute: (LedgerCommand) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var deleteRule by remember { mutableStateOf<IgnoreRule?>(null) }
    OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("ignore_rule_settings")) {
        Text(stringResource(R.string.ignore_rules))
    }
    if (expanded) {
        Text(stringResource(R.string.ignore_rules_explanation), style = MaterialTheme.typography.bodySmall)
        if (rules.isEmpty()) Text(stringResource(R.string.no_ignore_rules))
        rules.sortedBy { it.name }.forEach { rule ->
            Surface(Modifier.fillMaxWidth().testTag("ignore_rule_${rule.id}"), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(12.dp)) {
                    Text(rule.name, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.ignore_rule_summary, if (rule.sameAppOnly) rule.sourcePackage else stringResource(R.string.ignore_rule_all_apps), stringResource(ignoreFieldLabel(rule.field)), rule.pattern), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Switch(rule.enabled, { execute(LedgerCommand.SetIgnoreRuleEnabled(rule.id, rule.revision, it)) }, modifier = Modifier.testTag("toggle_ignore_rule_${rule.id}"))
                        TextButton(onClick = { deleteRule = rule }) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
        }
    }
    deleteRule?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleteRule = null },
            title = { Text(stringResource(R.string.delete_ignore_rule_title)) },
            text = { Text(stringResource(R.string.delete_ignore_rule_explanation)) },
            confirmButton = { TextButton(onClick = { execute(LedgerCommand.DeleteIgnoreRule(rule.id, rule.revision)); deleteRule = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleteRule = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun ignoreFieldLabel(field: MessageField) = when (field) {
    MessageField.TITLE -> R.string.notification_title
    MessageField.BODY -> R.string.notification_body
    MessageField.BIG_TEXT -> R.string.ignore_field_expanded
    MessageField.SUB_TEXT -> R.string.ignore_field_subtext
    MessageField.LINES -> R.string.ignore_field_lines
}
