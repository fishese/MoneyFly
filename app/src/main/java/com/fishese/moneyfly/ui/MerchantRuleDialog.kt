package com.fishese.moneyfly.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fishese.moneyfly.R
import com.fishese.moneyfly.ledger.*
import java.util.UUID
import kotlinx.coroutines.launch

/** An explicit action: selecting a category in the editor alone never creates a rule. */
@Composable
internal fun MerchantRuleDialog(store: LedgerStore, rules: List<CategoryRule>, merchant: String, categoryId: String, categoryName: String, targetEntry: LedgerEntry? = null, dismiss: () -> Unit) {
    val existing = rules.firstOrNull { it.field == RuleField.MERCHANT && it.match == LiteralMatch.EQUALS && it.priority == 0 && it.sourcePackage == null && it.accountId == null && Categorization.normalize(it.literal) == Categorization.normalize(merchant) }
    val id = rememberSaveable { existing?.id ?: UUID.randomUUID().toString() }
    val operation = rememberSaveable { UUID.randomUUID().toString() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var entryAssigned by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(stringResource(R.string.category_rule_create_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.category_rule_create_detail, merchant, categoryName))
                if (existing != null) Text(stringResource(R.string.category_rule_replace), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.category_rule_create_hint), style = MaterialTheme.typography.bodySmall)
                if (error) Text(stringResource(if (entryAssigned) R.string.category_rule_partial_failure else R.string.category_rule_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(enabled = !busy, modifier = Modifier.testTag("confirm_merchant_rule"), onClick = {
            busy = true
            scope.launch {
                try {
                    if (targetEntry != null && !entryAssigned) {
                        val edit = LedgerCommand.Edit(targetEntry.id, targetEntry.revision, targetEntry.draft.copy(categoryId = categoryId, categoryOrigin = CategoryOrigin.EXPLICIT))
                        if (!store.execute(edit).applied) { error = true; return@launch }
                        entryAssigned = true
                    }
                    val rule = CategoryRule(id, RuleField.MERCHANT, LiteralMatch.EQUALS, merchant.trim(), categoryId, revision = existing?.revision ?: 0)
                    val result = store.execute(LedgerCommand.PutRule(rule, existing?.revision, operation))
                    if (result.applied) dismiss() else error = true
                } catch (_: Exception) { error = true }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.category_rule_save)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
}
