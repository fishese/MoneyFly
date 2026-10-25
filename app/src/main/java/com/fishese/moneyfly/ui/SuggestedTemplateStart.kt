package com.fishese.moneyfly.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.fishese.moneyfly.capture.InboxStatus
import com.fishese.moneyfly.ledger.*
import com.fishese.moneyfly.recognition.*
import java.util.UUID
import kotlinx.coroutines.launch

/** Suggestion enrollment is separate from recording the current financial entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemplateStart(
    receipt: InboxItem,
    store: LedgerStore,
    close: () -> Unit,
    recordEnabled: Boolean = true,
    recordExisting: Boolean = false,
    recordEntry: () -> Unit,
) {
    val suggestions = remember(receipt.id, receipt.contentRevision, receipt.payload) {
        receipt.payload?.let(SuggestedTemplates::matchingSuggestions).orEmpty()
    }
    var custom by rememberSaveable(receipt.id) { mutableStateOf(suggestions.isEmpty()) }
    if (custom) {
        TemplateBuilder(receipt, store, close, recordEnabled, recordExisting, recordEntry)
        return
    }

    var selectedId by rememberSaveable(receipt.id) { mutableStateOf(suggestions.first().format.id) }
    val selected = suggestions.firstOrNull { it.format.id == selectedId } ?: suggestions.first()
    val templateId = rememberSaveable(receipt.id, selected.format.id) { UUID.randomUUID().toString() }
    val putOperationId = rememberSaveable(receipt.id, selected.format.id) { UUID.randomUUID().toString() }
    val previewOperationId = rememberSaveable(receipt.id, receipt.revision, selected.format.id) { UUID.randomUUID().toString() }
    val frozen = remember(selected.definition, templateId) { selected.definition.copy(id = templateId, enabled = true) }
    var busy by rememberSaveable(receipt.id) { mutableStateOf(false) }
    var error by rememberSaveable(receipt.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val saveFailed = stringResource(R.string.suggested_template_save_failed)
    val pending = receipt.status in setOf(InboxStatus.READY, InboxStatus.UNRECOGNIZED, InboxStatus.REVIEW_NEEDED)

    Dialog(onDismissRequest = { if (!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DialogSystemBars()
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                TopAppBar(title = { Text(stringResource(R.string.suggested_template_title)) }, navigationIcon = {
                    TextButton(onClick = close, enabled = !busy) { Text(stringResource(R.string.cancel)) }
                })
            }) { padding ->
                Column(
                    Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.suggested_template_match_explanation))
                    Text(stringResource(R.string.suggested_template_scope, receipt.sourcePackage), style = MaterialTheme.typography.labelMedium)
                    suggestions.forEach { suggestion ->
                        val draft = suggestion.preview.current.draft ?: return@forEach
                        Surface(
                            onClick = { if (!busy) selectedId = suggestion.format.id },
                            selected = selectedId == suggestion.format.id,
                            shape = MaterialTheme.shapes.large,
                            color = if (selectedId == suggestion.format.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth().testTag("suggested_format_${suggestion.format.id}"),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(suggestion.definition.name, style = MaterialTheme.typography.titleMedium)
                                Text("${draft.currency} ${draft.amount}", style = MaterialTheme.typography.headlineSmall)
                                if (draft.merchant.isNotBlank()) Text(draft.merchant) else Text(stringResource(R.string.template_no_merchant_hint))
                                Text(kindLabel(draft.kind), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    Text(stringResource(R.string.suggested_template_review_only), style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("suggested_template_error")) }
                    Button(
                        enabled = !busy && selected.preview.canEnable,
                        modifier = Modifier.fillMaxWidth().testTag("use_suggested_template"),
                        onClick = {
                            busy = true
                            error = null
                            val put = LedgerCommand.PutTemplate(frozen, expectedRevision = null, operationId = putOperationId)
                            scope.launch {
                                try {
                                    val saved = store.execute(put)
                                    if (!saved.applied) error = saved.message ?: saveFailed
                                    else if (pending) {
                                        // The template is already safely committed. Refresh is best effort: a
                                        // concurrent receipt revision can be reviewed from Inbox without
                                        // presenting the successful template save as a failure.
                                        try { store.execute(LedgerCommand.RepreviewReceipt(receipt.id, receipt.revision, previewOperationId)) } catch (_: Exception) {}
                                        close()
                                    } else close()
                                } catch (_: Exception) { error = saveFailed }
                                finally { busy = false }
                            }
                        },
                    ) { Text(stringResource(if (busy) R.string.saving else R.string.suggested_template_use)) }
                    OutlinedButton(onClick = { custom = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("custom_template")) {
                        Text(stringResource(R.string.suggested_template_custom))
                    }
                    OutlinedButton(onClick = recordEntry, enabled = !busy && recordEnabled, modifier = Modifier.fillMaxWidth().testTag("suggested_record_entry")) {
                        Text(stringResource(if (recordExisting) R.string.view_entry else R.string.record_entry))
                    }
                }
            }
        }
    }
}
