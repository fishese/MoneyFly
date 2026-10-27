package com.fishese.moneyfly.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fishese.moneyfly.R
import com.fishese.moneyfly.ledger.*
import java.util.UUID
import kotlinx.coroutines.launch

internal enum class TransactionAction { CATEGORY, ACCOUNT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransactionSwipe(entry: LedgerEntry, selecting: Boolean, onAction: (LedgerEntry, TransactionAction) -> Unit, content: @Composable () -> Unit) {
    if (selecting) { content(); return }
    key(entry.id, entry.revision) {
        val state = rememberSwipeToDismissBoxState()
        LaunchedEffect(state.currentValue) {
            val direction = state.currentValue
            if (direction != SwipeToDismissBoxValue.Settled) {
                onAction(entry, if (direction == SwipeToDismissBoxValue.EndToStart) TransactionAction.CATEGORY else TransactionAction.ACCOUNT)
                state.reset()
            }
        }
        SwipeToDismissBox(state, modifier = Modifier.testTag("transaction_swipe_${entry.id}"), backgroundContent = {
            if (state.dismissDirection != SwipeToDismissBoxValue.Settled) {
                val category = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(Modifier.padding(horizontal = 16.dp), contentAlignment = if (category) Alignment.CenterEnd else Alignment.CenterStart) {
                        Text(stringResource(if (category) R.string.category else R.string.account), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }) { content() }
    }
}

/** Rules are explicit and target one merchant or one notification source. */
@Composable
internal fun AccountRuleDialog(store: LedgerStore, rules: List<AccountRule>, entry: LedgerEntry, accountId: String, accountName: String, dismiss: () -> Unit) {
    val merchant = (entry.draft.canonicalMerchant ?: entry.draft.merchant).trim()
    val source = entry.draft.sourcePackage
    var byApp by rememberSaveable { mutableStateOf(merchant.isBlank() && source != null) }
    val existing = rules.firstOrNull { r -> r.sourcePackage == (if (byApp) source else null) &&
        if (byApp) r.merchant == null else r.merchant?.let { Categorization.normalize(it) == Categorization.normalize(merchant) } == true }
    val id = remember(existing?.id, byApp) { existing?.id ?: UUID.randomUUID().toString() }
    val operation = remember(id, accountId) { UUID.randomUUID().toString() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(stringResource(R.string.account_rule_title)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.account_rule_target, accountName))
            if (merchant.isNotBlank()) FilterChip(selected = !byApp, onClick = { byApp = false; error = false }, label = { Text(stringResource(R.string.account_rule_merchant, merchant)) })
            if (source != null) FilterChip(selected = byApp, onClick = { byApp = true; error = false }, label = { Text(stringResource(R.string.account_rule_app, source)) })
            if (existing != null) Text(stringResource(R.string.account_rule_replace), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.account_rule_hint), style = MaterialTheme.typography.bodySmall)
            if (error) Text(stringResource(R.string.category_rule_failed), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton(enabled = !busy && (merchant.isNotBlank() || source != null), modifier = Modifier.testTag("confirm_account_rule"), onClick = {
            busy = true
            scope.launch {
                try {
                    val rule = AccountRule(id, if (byApp) null else merchant, if (byApp) source else null, accountId, existing?.revision ?: 0)
                    val result = store.execute(LedgerCommand.PutAccountRule(rule, existing?.revision, operation))
                    if (result.applied) dismiss() else error = true
                } catch (_: Exception) { error = true } finally { busy = false }
            }
        }) { Text(stringResource(R.string.category_rule_save)) }
    }, dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun AccountRuleSettings(rules: List<AccountRule>, accounts: List<AccountLabel>, sourceName: (String) -> String, execute: (LedgerCommand) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AccountRule?>(null) }
    OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("account_rule_settings")) { Text(stringResource(R.string.account_rules)) }
    if (expanded) {
        Text(stringResource(R.string.account_rules_manage_hint), style = MaterialTheme.typography.bodySmall)
        if (rules.isEmpty()) Text(stringResource(R.string.account_rules_empty), style = MaterialTheme.typography.bodySmall)
        rules.forEach { rule ->
            val match = rule.merchant ?: rule.sourcePackage?.let(sourceName).orEmpty()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$match → ${accounts.find { it.id == rule.accountId }?.name.orEmpty()}", Modifier.weight(1f))
                TextButton(onClick = { deleting = rule }, modifier = Modifier.testTag("delete_account_rule_${rule.id}")) { Text(stringResource(R.string.delete)) }
            }
        }
    }
    deleting?.let { rule ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete_account_rule_title)) },
            text = { Text(stringResource(R.string.delete_account_rule_hint)) }, confirmButton = { TextButton(onClick = { execute(LedgerCommand.DeleteAccountRule(rule.id, rule.revision)); deleting = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
