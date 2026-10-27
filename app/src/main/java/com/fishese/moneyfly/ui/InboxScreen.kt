package com.fishese.moneyfly.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fishese.moneyfly.R
import com.fishese.moneyfly.capture.*
import com.fishese.moneyfly.ledger.*
import com.fishese.moneyfly.recognition.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
internal fun InboxScreen(
    store: InboxStore?, controller: CaptureController?, inbox: List<InboxItem>, sources: List<SourcePreference>,
    entries: List<LedgerEntry>, modifier: Modifier, hasTemplates: Boolean,
    execute: (LedgerCommand, (CommandResult) -> Unit) -> Unit,
    record: (InboxItem, Boolean) -> Unit, viewEntry: (LedgerEntry) -> Unit, createTemplate: (InboxItem) -> Unit,
) {
    val settingsFlow = remember(store) { store?.captureSettings ?: flowOf(CaptureSettings()) }
    val connectionFlow = remember(controller) { controller?.connection ?: flowOf(CaptureConnection()) }
    val settings by settingsFlow.collectAsStateWithLifecycle(CaptureSettings())
    val connection by connectionFlow.collectAsStateWithLifecycle(CaptureConnection())
    var consentDialog by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("pending") }
    var app by rememberSaveable { mutableStateOf("") }
    var keyword by rememberSaveable { mutableStateOf("") }
    var controlsExpanded by rememberSaveable { mutableStateOf(false) }
    var appMenu by remember { mutableStateOf(false) }
    var ignoreSource by rememberSaveable { mutableStateOf<String?>(null) }
    var similarItem by remember { mutableStateOf<InboxItem?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ignored = stringResource(R.string.receipt_ignored)
    val undo = stringResource(R.string.undo)
    val query = keyword.trim()
    val sourceNames = remember(controller, sources, inbox) {
        (sources.map { it.sourcePackage } + inbox.map { it.sourcePackage }).distinct()
            .associateWith { controller?.sourceName(it) ?: it }
    }
    val rows = inbox.filter { item ->
        (when (filter) {
            "pending" -> item.status in setOf(InboxStatus.READY, InboxStatus.REVIEW_NEEDED, InboxStatus.UNRECOGNIZED)
            "ready" -> item.status == InboxStatus.READY
            "unrecognized" -> item.status == InboxStatus.UNRECOGNIZED
            "review" -> item.status == InboxStatus.REVIEW_NEEDED
            "saved" -> item.status == InboxStatus.SAVED || item.status == InboxStatus.LINKED_DUPLICATE
            "ignored" -> item.status == InboxStatus.IGNORED
            else -> item.status != InboxStatus.IGNORED
        }) && (app.isEmpty() || app == item.sourcePackage) &&
            (query.isEmpty() || listOf(item.sourcePackage, sourceNames[item.sourcePackage].orEmpty(), item.draft?.merchant.orEmpty(), item.draft?.amount.orEmpty(), item.draft?.currency.orEmpty(), item.payload?.title.orEmpty(), item.payload?.body.orEmpty(), item.payload?.bigText.orEmpty(), item.payload?.subText.orEmpty(), item.payload?.textLines?.joinToString("\n").orEmpty()).any { it.contains(query, ignoreCase = true) })
    }.sortedByDescending { it.observedAt }
    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("inbox_list"), contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.inbox), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.inbox_ui_counts, inbox.count { it.status == InboxStatus.READY }, inbox.count { it.status == InboxStatus.REVIEW_NEEDED }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (inbox.any { it.status == InboxStatus.READY }) Text(stringResource(R.string.inbox_ready_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val pendingCount = inbox.count { it.status in setOf(InboxStatus.READY, InboxStatus.REVIEW_NEEDED, InboxStatus.UNRECOGNIZED) }
                    FilterChip(selected = filter == "pending", onClick = { filter = if (filter == "pending") "all" else "pending" }, label = { Text(stringResource(R.string.inbox_pending_view, pendingCount)) }, modifier = Modifier.testTag("inbox_filter_pending"))
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    CompactCaptureBar(settings.consent, connection, store != null && controller != null, controlsExpanded,
                        refresh = { scope.launch { controller?.refresh() } },
                        setup = { controlsExpanded = !controlsExpanded }, enable = { consentDialog = true })
                    if (controlsExpanded) {
                    Text(stringResource(R.string.capture_deferred), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.retention_limits), style = MaterialTheme.typography.bodySmall)
                    Text(connection.lastRefresh?.let { stringResource(R.string.last_refresh, localTime(it)) } ?: stringResource(R.string.never_refreshed), style = MaterialTheme.typography.bodySmall)
                    connection.problem?.let { problem -> Text(stringResource(when (problem) {
                        CaptureProblem.ACCESS_DENIED -> R.string.access_needed
                        CaptureProblem.DISCONNECTED -> R.string.listener_disconnected
                        CaptureProblem.INTAKE_BUSY -> R.string.capture_busy
                        CaptureProblem.CAPTURE_FAILED -> R.string.capture_failed
                        CaptureProblem.SETTINGS_UNAVAILABLE -> R.string.settings_unavailable
                    }), color = MaterialTheme.colorScheme.error) }
                    if (settings.consent) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { execute(LedgerCommand.SetCaptureConsent(false, settings.revision)) {} }, modifier = Modifier.testTag("pause_capture")) { Text(stringResource(R.string.pause_capture)) }
                            if (!connection.accessGranted) OutlinedButton(onClick = { controller?.openAccessSettings() }) { Text(stringResource(R.string.open_access_settings)) }
                            else if (!connection.connected) OutlinedButton(onClick = { controller?.requestReconnect() }) { Text(stringResource(R.string.reconnect)) }
                        }
                    }
                    }
                    }
                    }
                    OutlinedTextField(keyword, { keyword = it }, label = { Text(stringResource(R.string.inbox_ui_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("inbox_search"), shape = MaterialTheme.shapes.large,
                        trailingIcon = if (keyword.isNotEmpty()) { { IconButton(onClick = { keyword = "" }, modifier = Modifier.testTag("inbox_clear_search")) { Icon(Icons.Default.Close, stringResource(R.string.journal_clear_search)) } } } else null)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("all" to stringResource(R.string.all), "ready" to stringResource(R.string.ready), "review" to stringResource(R.string.review_needed), "unrecognized" to stringResource(R.string.unrecognized), "saved" to stringResource(R.string.saved), "ignored" to stringResource(R.string.ignored)).forEach { (value, label) ->
                            FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp).testTag("inbox_filter_$value"))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            TextButton(onClick = { appMenu = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("inbox_app_filter")) { Text(if (app.isEmpty()) stringResource(R.string.inbox_ui_all_apps) else sourceNames[app] ?: app) }
                            DropdownMenu(expanded = appMenu, onDismissRequest = { appMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.inbox_ui_all_apps)) }, onClick = { app = ""; appMenu = false })
                                sourceNames.toList().sortedBy { it.second }.forEach { (source, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { app = source; appMenu = false }) }
                            }
                        }
                        if (keyword.isNotEmpty() || filter != "pending" || app.isNotEmpty()) TextButton(onClick = { keyword = ""; filter = "pending"; app = "" }, modifier = Modifier.heightIn(min = 48.dp).testTag("inbox_clear_filters")) { Text(stringResource(R.string.clear_all)) }
                    }
                    if (keyword.isNotEmpty() || filter != "all" || app.isNotEmpty()) Text(stringResource(R.string.inbox_showing_count, rows.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("inbox_showing_count"))
                }
            }
            if (rows.isEmpty()) item {
                Text(stringResource(when {
                    filter == "pending" && keyword.isBlank() && app.isEmpty() && settings.consent && connection.accessGranted && connection.connected -> R.string.inbox_pending_empty
                    filter == "pending" && keyword.isBlank() && app.isEmpty() && !settings.consent -> R.string.paused_empty
                    filter == "pending" && keyword.isBlank() && app.isEmpty() && (!connection.accessGranted || !connection.connected) -> R.string.disconnected_empty
                    filter == "ready" -> R.string.ready_empty
                    app.isNotEmpty() && sources.any { it.sourcePackage == app && it.ignored } && filter != "ignored" -> R.string.hidden_source_empty
                    keyword.isNotBlank() || app.isNotEmpty() || filter != "all" -> R.string.inbox_no_matches
                    !settings.consent -> R.string.paused_empty
                    !connection.accessGranted || !connection.connected -> R.string.disconnected_empty
                    else -> R.string.inbox_empty
                }), Modifier.padding(vertical = 20.dp))
            }
            items(rows, key = { it.id }) { item ->
                val saveOperationId = rememberSaveable(item.id, item.revision) { java.util.UUID.randomUUID().toString() }
                val readyEntryId = rememberSaveable(item.id, item.revision) { java.util.UUID.randomUUID().toString() }
                val ignoreItem: () -> Unit = {
                    execute(LedgerCommand.IgnoreReceipt(item.id, item.revision)) { result ->
                        val revision = result.revision
                        if (revision != null) scope.launch {
                            if (snackbar.showSnackbar(ignored, undo, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) execute(LedgerCommand.ReopenReceipt(item.id, revision)) {}
                        }
                    }
                }
                PendingReceiptSwipe(item, sourceNames[item.sourcePackage] ?: item.sourcePackage,
                    ignoreThis = ignoreItem,
                    ignoreSimilar = { similarItem = item },
                    ignoreApp = { ignoreSource = item.sourcePackage },
                    recordEntry = { record(item, false) },
                    recordTemplate = { createTemplate(item) }) {
                ReceiptCard(item, sourceNames[item.sourcePackage] ?: item.sourcePackage, sources.any { it.sourcePackage == item.sourcePackage && it.ignored }, entries, record, viewEntry,
                    ignore = ignoreItem,
                    ignoreSimilar = { similarItem = item },
                    reopen = { execute(LedgerCommand.ReopenReceipt(item.id, item.revision)) {} },
                    restoreApp = { sources.find { it.sourcePackage == item.sourcePackage }?.let { source -> execute(LedgerCommand.SetIgnoredSource(source.sourcePackage, false, source.revision)) {} } },
                    ignoreApp = { ignoreSource = item.sourcePackage },
                    link = { target -> execute(LedgerCommand.LinkDuplicate(item.id, item.revision, target.id, target.revision)) {} },
                    saveReady = { item.draft?.let { draft -> execute(LedgerCommand.SaveCaptured(item.id, item.revision, draft, entryId = readyEntryId, operationId = saveOperationId)) {} } },
                    createTemplate = { createTemplate(item) },
                    repreview = if (hasTemplates) { { execute(LedgerCommand.RepreviewReceipt(item.id, item.revision)) {} } } else null)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 90.dp))
    }
    if (consentDialog) AlertDialog(onDismissRequest = { consentDialog = false }, title = { Text(stringResource(R.string.consent_title)) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(R.string.consent_explanation)) } }, confirmButton = {
        TextButton(modifier = Modifier.testTag("confirm_capture_consent"), onClick = {
            execute(LedgerCommand.SetCaptureConsent(true, settings.revision)) {
                consentDialog = false
                controller?.openAccessSettings()
                controller?.requestReconnect()
            }
        }) { Text(stringResource(R.string.agree_and_continue)) }
    }, dismissButton = { TextButton(onClick = { consentDialog = false }) { Text(stringResource(R.string.cancel)) } })
    ignoreSource?.let { source ->
        val sourceName = sourceNames[source] ?: source
        AlertDialog(onDismissRequest = { ignoreSource = null }, title = { Text(stringResource(R.string.ignore_app_named, sourceName)) }, text = { Text(stringResource(R.string.ignore_app_explanation, sourceName)) }, confirmButton = {
            TextButton(modifier = Modifier.testTag("confirm_ignore_app"), onClick = { execute(LedgerCommand.SetIgnoredSource(source, true, sources.find { it.sourcePackage == source }?.revision ?: 0)) { ignoreSource = null } }) { Text(stringResource(R.string.ignore_app)) }
        }, dismissButton = { TextButton(onClick = { ignoreSource = null }) { Text(stringResource(R.string.cancel)) } })
    }
    similarItem?.let { item ->
        IgnoreRuleBuilder(item = item, inbox = inbox, store = store, sourceName = sourceNames[item.sourcePackage] ?: item.sourcePackage, close = { similarItem = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactCaptureBar(consent: Boolean, connection: CaptureConnection, available: Boolean, expanded: Boolean, refresh: () -> Unit, setup: () -> Unit, enable: () -> Unit) {
    val status = stringResource(when {
        !consent -> R.string.inbox_ui_paused
        !connection.accessGranted -> R.string.inbox_ui_access
        !connection.connected -> R.string.inbox_ui_disconnected
        else -> R.string.inbox_ui_connected
    })
    val actions: @Composable () -> Unit = {
        IconButton(onClick = refresh, enabled = consent && connection.accessGranted && connection.connected && !connection.refreshing, modifier = Modifier.size(48.dp).testTag("refresh_inbox")) {
            Icon(Icons.Default.Refresh, contentDescription = stringResource(if (connection.refreshing) R.string.refreshing else R.string.refresh))
        }
        TextButton(onClick = setup, modifier = Modifier.heightIn(min = 48.dp).testTag("capture_controls")) { Text(stringResource(if (expanded) R.string.inbox_ui_close else R.string.inbox_ui_setup)) }
        if (!consent) Button(onClick = enable, enabled = available, modifier = Modifier.heightIn(min = 48.dp).testTag("enable_capture")) { Text(stringResource(R.string.inbox_ui_enable)) }
    }
    if (LocalDensity.current.fontScale > 1.3f) {
        Column {
            Text(status, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            FlowRow(verticalArrangement = Arrangement.Center) { actions() }
        }
    } else Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(status, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        actions()
    }
}

/** Swipes expose choices only. They never mutate the Inbox as a side effect of a gesture. */
@Composable
private fun PendingReceiptSwipe(
    item: InboxItem,
    sourceName: String,
    ignoreThis: () -> Unit,
    ignoreSimilar: () -> Unit,
    ignoreApp: () -> Unit,
    recordEntry: () -> Unit,
    recordTemplate: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (item.status != InboxStatus.UNRECOGNIZED && item.status != InboxStatus.REVIEW_NEEDED && item.status != InboxStatus.READY) {
        content()
        return
    }
    key(item.id, item.revision) {
        val state = rememberSwipeToDismissBoxState()
        var choices by remember { mutableStateOf<SwipeChoices?>(null) }
        LaunchedEffect(state.currentValue) {
            when (state.currentValue) {
                SwipeToDismissBoxValue.EndToStart -> choices = SwipeChoices.IGNORE
                SwipeToDismissBoxValue.StartToEnd -> choices = SwipeChoices.RECORD
                else -> Unit
            }
            if (state.currentValue != SwipeToDismissBoxValue.Settled) state.reset()
        }
        SwipeToDismissBox(state = state, modifier = Modifier.clip(MaterialTheme.shapes.medium).testTag("swipe_${item.id}"),
            backgroundContent = {
                val ignoring = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
                Surface(Modifier.fillMaxSize(), color = if (ignoring) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
                    Box(
                        Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        contentAlignment = if (ignoring) androidx.compose.ui.Alignment.CenterEnd else androidx.compose.ui.Alignment.CenterStart,
                    ) {
                        Text(stringResource(if (ignoring) R.string.swipe_ignore else R.string.swipe_record), fontWeight = FontWeight.SemiBold)
                    }
                }
            }) { content() }
        choices?.let { selected ->
            SwipeActionSheet(title = stringResource(if (selected == SwipeChoices.IGNORE) R.string.ignore_heading else R.string.record_heading), dismiss = { choices = null }) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (selected == SwipeChoices.IGNORE) {
                            TextButton(onClick = { choices = null; ignoreThis() }, modifier = Modifier.fillMaxWidth().testTag("swipe_ignore_this_${item.id}")) { Text(stringResource(R.string.ignore_choice_this)) }
                            TextButton(onClick = { choices = null; ignoreSimilar() }, modifier = Modifier.fillMaxWidth().testTag("swipe_ignore_similar_${item.id}")) { Text(stringResource(R.string.ignore_choice_similar)) }
                            TextButton(onClick = { choices = null; ignoreApp() }, modifier = Modifier.fillMaxWidth().testTag("swipe_ignore_app_${item.id}")) { Text(stringResource(R.string.ignore_choice_app, sourceName)) }
                        } else {
                            TextButton(onClick = { choices = null; recordEntry() }, modifier = Modifier.fillMaxWidth().testTag("swipe_record_entry_${item.id}")) { Text(stringResource(R.string.record_choice_entry)) }
                            TextButton(onClick = { choices = null; recordTemplate() }, modifier = Modifier.fillMaxWidth().testTag("swipe_record_template_${item.id}")) { Text(stringResource(R.string.record_choice_template)) }
                        }
                    }
            }
        }
    }
}

private enum class SwipeChoices { IGNORE, RECORD }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReceiptCard(item: InboxItem, sourceName: String, sourceIgnored: Boolean, entries: List<LedgerEntry>, record: (InboxItem, Boolean) -> Unit, viewEntry: (LedgerEntry) -> Unit, ignore: () -> Unit, ignoreSimilar: () -> Unit, reopen: () -> Unit, restoreApp: () -> Unit, ignoreApp: () -> Unit, link: (LedgerEntry) -> Unit, saveReady: () -> Unit, createTemplate: () -> Unit, repreview: (() -> Unit)?) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    var more by rememberSaveable(item.id) { mutableStateOf(false) }
    var chooseDuplicate by rememberSaveable(item.id) { mutableStateOf(false) }
    val duplicateIds = item.draft?.let { possibleDuplicateEntries(it, entries).map { entry -> entry.id } }.orEmpty()
    val candidateIds = (item.relatedEntryIds + duplicateIds).distinct()
    var targetId by rememberSaveable(item.id) { mutableStateOf(candidateIds.firstOrNull() ?: entries.firstOrNull { !it.deleted }?.id.orEmpty()) }
    var showAllEntries by rememberSaveable(item.id) { mutableStateOf(candidateIds.isEmpty()) }
    val saved = item.status == InboxStatus.SAVED || item.status == InboxStatus.LINKED_DUPLICATE
    val ignored = item.status == InboxStatus.IGNORED
    val needsDecision = item.requiresRevisionDecision || candidateIds.isNotEmpty()
    Card(Modifier.fillMaxWidth().testTag("receipt_${item.id}"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(sourceName, Modifier.widthIn(max = 160.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.postedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)), style = MaterialTheme.typography.labelSmall)
            val statusLabel = stringResource(when (item.status) { InboxStatus.UNRECOGNIZED -> R.string.unrecognized; InboxStatus.READY -> R.string.ready; InboxStatus.REVIEW_NEEDED -> R.string.review_needed; InboxStatus.SAVED -> R.string.saved; InboxStatus.LINKED_DUPLICATE -> R.string.linked_duplicate; InboxStatus.IGNORED -> R.string.ignored; InboxStatus.REMOVED -> R.string.entry_removed })
            if (saved || ignored) {
                Surface(color = if (saved) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                    Text(statusLabel, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = if (saved) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else Text(statusLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (item.status == InboxStatus.REVIEW_NEEDED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            item.draft?.let {
                if (it.merchant.isNotBlank()) Text(it.merchant, style = MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${it.currency} ${it.amount}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(kindLabel(it.kind), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (item.status != InboxStatus.READY || expanded) NotificationContent(item, expanded)
            if (item.payload?.groupSummary == true) {
                Text(stringResource(R.string.inbox_summary_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                Text(stringResource(R.string.inbox_summary_explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item.issues.forEach { issue ->
                val label = when (issue) {
                    "Date uses notification posting time; original transaction time is unknown." -> stringResource(R.string.inbox_ui_notification_time)
                    "Merchant was not provided; add it while reviewing this entry." -> stringResource(R.string.template_no_merchant_hint)
                    else -> issue
                }
                Text(label, style = MaterialTheme.typography.bodySmall, color = if (item.status == InboxStatus.REVIEW_NEEDED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (saved) {
                val entry = entries.find { it.id == item.entryId && !it.deleted }
                if (entry != null) OutlinedButton(onClick = { viewEntry(entry) }) { Text(stringResource(R.string.view_entry)) }
                else Text(stringResource(R.string.saved_entry_unavailable))
            } else if (ignored) {
                if (sourceIgnored) OutlinedButton(onClick = restoreApp, modifier = Modifier.testTag("restore_app_${item.id}")) { Text(stringResource(R.string.restore_app)) }
                else if (item.ignoreRuleId == null) OutlinedButton(onClick = reopen, modifier = Modifier.testTag("reopen_${item.id}")) { Text(stringResource(R.string.restore_item)) }
                else Text(stringResource(R.string.ignored_by_similar_rule), style = MaterialTheme.typography.bodySmall)
            } else if (item.status == InboxStatus.REMOVED) {
                Text(stringResource(R.string.removed_entry_explanation))
            } else {
                if (needsDecision) {
                    Text(stringResource(if (duplicateIds.isNotEmpty()) R.string.possible_duplicate else R.string.updated_receipt_decision), color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = { record(item, true) }, modifier = Modifier.testTag("record_${item.id}")) { Text(stringResource(R.string.separate_purchase)) }
                } else if (item.status == InboxStatus.READY && item.draft != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = saveReady, enabled = runCatching { Money.normalize(item.draft.amount, item.draft.currency) }.isSuccess, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("save_ready_${item.id}")) { Text(stringResource(R.string.save)) }
                    OutlinedButton(onClick = { record(item, false) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("record_${item.id}")) { Text(stringResource(R.string.edit_entry)) }
                    }
                } else Button(onClick = { record(item, false) }, modifier = Modifier.testTag("record_${item.id}")) { Text(stringResource(R.string.record_entry)) }
            }
            TextButton(onClick = { more = !more }, modifier = Modifier.heightIn(min = 48.dp).testTag("more_${item.id}")) { Text(stringResource(if (more) R.string.inbox_ui_fewer_actions else R.string.inbox_ui_more_actions)) }
            if (more) {
                HorizontalDivider()
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp).testTag("details_${item.id}")) { Text(stringResource(if (expanded) R.string.less_details else R.string.details)) }
                if (!saved && !ignored && item.status != InboxStatus.REMOVED) {
                if (entries.any { !it.deleted }) OutlinedButton(onClick = { chooseDuplicate = !chooseDuplicate }, modifier = Modifier.testTag("link_${item.id}")) { Text(stringResource(R.string.link_existing)) }
                if (chooseDuplicate) {
                    val options = entries.filter { !it.deleted && (showAllEntries || it.id in candidateIds) }
                    Choice(stringResource(R.string.existing_entry), targetId, options.map { it.id to "${it.draft.currency} ${it.draft.amount} · ${it.draft.merchant} · ${localTime(it.draft.occurredAt)}" }) { targetId = it }
                    TextButton(onClick = { showAllEntries = !showAllEntries }) { Text(stringResource(if (showAllEntries) R.string.only_suggested else R.string.choose_any_entry)) }
                    val target = options.find { it.id == targetId }
                    Button(enabled = target != null, onClick = { if (target != null) link(target) }, modifier = Modifier.testTag("confirm_link_${item.id}")) { Text(stringResource(R.string.confirm_link)) }
                }
                TextButton(onClick = ignore, modifier = Modifier.testTag("ignore_${item.id}")) { Text(stringResource(R.string.ignore_item)) }
                if (repreview != null) TextButton(onClick = repreview) { Text(stringResource(R.string.repreview_item)) }
                }
            if (item.payload != null) {
                OutlinedButton(onClick = createTemplate, modifier = Modifier.testTag("template_${item.id}")) { Text(stringResource(R.string.create_template)) }
                TextButton(onClick = ignoreSimilar, modifier = Modifier.testTag("similar_${item.id}")) { Text(stringResource(R.string.ignore_similar)) }
            }
            TextButton(onClick = ignoreApp, modifier = Modifier.testTag("ignore_app_${item.id}")) { Text(stringResource(R.string.ignore_app_named, sourceName)) }
            }
        }
    }
}

@Composable
internal fun NotificationContent(item: InboxItem, expanded: Boolean) {
    val payload = item.payload
    if (payload == null) Text(stringResource(R.string.content_expired))
    else {
        if (payload.title.isNotBlank()) Text(payload.title, style = MaterialTheme.typography.titleMedium)
        if (payload.body.isNotBlank()) Text(payload.body)
        if (payload.title.isBlank() && payload.body.isBlank()) Text(stringResource(R.string.empty_notification_text))
        if (expanded) {
            if (payload.bigText.isNotBlank() && payload.bigText != payload.body) Text(payload.bigText)
            payload.textLines.filter { it.isNotBlank() && it != payload.body }.forEach { Text(it) }
            if (payload.subText.isNotBlank()) Text(payload.subText)
            if (payload.redacted) Text(stringResource(R.string.redacted_content))
            if (payload.truncated) Text(stringResource(R.string.truncated_content))
            if (payload.groupSummary) Text(stringResource(R.string.group_summary))
            Text(stringResource(R.string.observed_time, localTime(item.observedAt)), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.content_revision, item.contentRevision), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourceSettings(sources: List<SourcePreference>, sourceName: (String) -> String = { it }, execute: (LedgerCommand) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("all") }
    OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("source_settings")) { Text(stringResource(R.string.notification_sources)) }
    if (expanded) {
        Text(stringResource(R.string.source_settings_explanation), style = MaterialTheme.typography.bodySmall)
        if (sources.isEmpty()) Text(stringResource(R.string.no_sources))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to R.string.all, "captured" to R.string.source_captured, "ignored" to R.string.ignored).forEach { (value, label) ->
                FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("source_filter_$value"))
            }
        }
        val shown = sources.filter { filter == "all" || (filter == "captured" && !it.ignored) || (filter == "ignored" && it.ignored) }
            .sortedWith(compareBy<SourcePreference> { it.ignored }.thenBy { sourceName(it.sourcePackage).lowercase(java.util.Locale.ROOT) }.thenBy { it.sourcePackage })
        if (shown.isEmpty() && sources.isNotEmpty()) Text(stringResource(R.string.source_filter_empty), style = MaterialTheme.typography.bodySmall)
        shown.forEach { source ->
            val name = sourceName(source.sourcePackage).ifBlank { source.sourcePackage }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Text(if (name == source.sourcePackage) name else "$name [${source.sourcePackage}]", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.source_capture_toggle), Modifier.weight(1f))
                Switch(checked = !source.ignored, onCheckedChange = { execute(LedgerCommand.SetIgnoredSource(source.sourcePackage, !it, source.revision)) }, modifier = Modifier.testTag("restore_source_${source.sourcePackage}"))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.parsing_source), Modifier.weight(1f))
                Switch(checked = source.parsingEnabled, onCheckedChange = { execute(LedgerCommand.SetParsingSource(source.sourcePackage, it, source.revision)) }, enabled = !source.ignored, modifier = Modifier.testTag("parse_source_${source.sourcePackage}"))
            }
        }
    }
}

private fun localTime(instant: java.time.Instant): String = instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))
