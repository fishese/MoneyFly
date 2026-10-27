package com.fishese.moneyfly.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fishese.moneyfly.R
import com.fishese.moneyfly.ledger.*
import com.fishese.moneyfly.capture.*
import com.fishese.moneyfly.recognition.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneyFlyApp(store: LedgerStore, captureController: CaptureController? = null, fxRepository: com.fishese.moneyfly.fx.FxRepository? = null) {
    MoneyFlyTheme {
        val entries by store.entries.collectAsStateWithLifecycle(emptyList())
        val fxFlow = remember(fxRepository) { fxRepository?.state ?: flowOf(com.fishese.moneyfly.fx.FxSnapshot()) }
        val fxState by fxFlow.collectAsStateWithLifecycle(com.fishese.moneyfly.fx.FxSnapshot())
        LaunchedEffect(fxRepository, entries, fxState.currency) {
            fxRepository?.initialize(entries)
            fxRepository?.refresh(entries)
        }
        val categories by store.categories.collectAsStateWithLifecycle(emptyList())
        val accounts by store.accounts.collectAsStateWithLifecycle(emptyList())
        val aliases by store.aliases.collectAsStateWithLifecycle(emptyList())
        val rules by store.rules.collectAsStateWithLifecycle(emptyList())
        val accountRules by store.accountRules.collectAsStateWithLifecycle(emptyList())
        val inboxStore = store as? InboxStore
        val inboxFlow = remember(inboxStore) { inboxStore?.inbox ?: flowOf(emptyList()) }
        val sourceFlow = remember(inboxStore) { inboxStore?.sources ?: flowOf(emptyList()) }
        val inbox by inboxFlow.collectAsStateWithLifecycle(emptyList())
        val sources by sourceFlow.collectAsStateWithLifecycle(emptyList())
        val ignoreRuleFlow = remember(inboxStore) { inboxStore?.ignoreRules ?: flowOf(emptyList()) }
        val ignoreRules by ignoreRuleFlow.collectAsStateWithLifecycle(emptyList())
        val templateStore = store as? TemplateStore
        val templateFlow = remember(templateStore) { templateStore?.templates ?: flowOf(emptyList()) }
        val templates by templateFlow.collectAsStateWithLifecycle(emptyList())
        val pendingCount = inbox.count { it.status == InboxStatus.READY || it.status == InboxStatus.REVIEW_NEEDED }
        var destination by rememberSaveable { mutableIntStateOf(0) }
        var editorId by rememberSaveable { mutableStateOf<String?>(null) }
        var editingRevision by rememberSaveable { mutableLongStateOf(0) }
        var receiptId by rememberSaveable { mutableStateOf<String?>(null) }
        var receiptRevision by rememberSaveable { mutableLongStateOf(0) }
        var separatePurchase by rememberSaveable { mutableStateOf(false) }
        var templateReceiptId by rememberSaveable { mutableStateOf<String?>(null) }
        var selecting by rememberSaveable { mutableStateOf(false) }
        var selectedIds by rememberSaveable { mutableStateOf(listOf<String>()) }
        var swipeEntry by remember { mutableStateOf<LedgerEntry?>(null) }
        var swipeAction by remember { mutableStateOf<TransactionAction?>(null) }
        var pickerMode by remember { mutableStateOf<String?>(null) }
        var ruleCategory by remember { mutableStateOf<String?>(null) }
        var ruleAccount by remember { mutableStateOf<String?>(null) }
        var bulkSnapshot by remember { mutableStateOf<List<LedgerEntry>>(emptyList()) }
        var confirmBulkRemove by remember { mutableStateOf(false) }
        var bulkRunning by remember { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val undo = stringResource(R.string.undo)
        val changed = stringResource(R.string.entry_changed)
        val failed = stringResource(R.string.operation_failed)
        val conflict = stringResource(R.string.conflict)
        val addExpenseLabel = stringResource(R.string.shell_add_expense)
        val bulkResult = stringResource(R.string.bulk_result)
        val bulkUndoResult = stringResource(R.string.bulk_undo_result)
        fun applyBulk(snapshot: List<LedgerEntry>, action: String, value: String? = null) {
            if (bulkRunning || snapshot.isEmpty()) return
            bulkRunning = true
            scope.launch {
                val succeeded = mutableListOf<Pair<String, Long>>()
                var failedCount = 0
                for (entry in snapshot) {
                    val command: LedgerCommand = when (action) {
                        "delete" -> LedgerCommand.Delete(entry.id, entry.revision)
                        "category" -> LedgerCommand.Edit(entry.id, entry.revision, entry.draft.copy(categoryId = value, categoryOrigin = if (value == null) CategoryOrigin.UNASSIGNED else CategoryOrigin.EXPLICIT))
                        else -> LedgerCommand.Edit(entry.id, entry.revision, entry.draft.copy(accountId = value))
                    }
                    try {
                        val result = store.execute(command)
                        if (result.applied && result.revision != null) succeeded += command.operationId to result.revision else failedCount++
                    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failedCount++ }
                }
                selecting = false; selectedIds = emptyList(); bulkRunning = false
                if (snackbar.showSnackbar(bulkResult.format(succeeded.size, failedCount), if (succeeded.isNotEmpty()) undo else null, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                    var undoFailed = 0
                    for ((operation, revision) in succeeded) {
                        try { if (!store.execute(LedgerCommand.Undo(operation, revision)).applied) undoFailed++ }
                        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { undoFailed++ }
                    }
                    if (undoFailed > 0) snackbar.showSnackbar(bulkUndoResult.format(undoFailed))
                }
            }
        }
        fun run(command: LedgerCommand, offerUndo: Boolean = false, done: () -> Unit = {}, onApplied: (CommandResult) -> Unit = {}) {
            scope.launch {
                try {
                    val result = store.execute(command)
                    if (result.applied) {
                        done()
                        onApplied(result)
                        if (offerUndo && result.revision != null) {
                            if (snackbar.showSnackbar(changed, undo, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                                val reversed = store.execute(LedgerCommand.Undo(command.operationId, result.revision))
                                if (!reversed.applied) snackbar.showSnackbar(conflict)
                            }
                        }
                    } else snackbar.showSnackbar(if (result.status == CommandStatus.CONFLICT) conflict else failed)
                } catch (_: Exception) { snackbar.showSnackbar(failed) }
            }
        }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(painterResource(R.drawable.moneyfly_art), null, Modifier.size(30.dp), tint = Color.Unspecified)
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                    }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background), actions = {
                    if (destination == 1) FilledTonalIconButton(modifier = Modifier.testTag("add_entry"), onClick = { editorId = "new"; editingRevision = 0 }) {
                        Icon(Icons.Default.Add, addExpenseLabel)
                    }
                    else if (pendingCount > 0) IconButton(onClick = { destination = 1 }) {
                        BadgedBox(badge = { Badge { Text(if (pendingCount > 99) "99+" else pendingCount.toString()) } }) {
                            Icon(Icons.Default.Email, stringResource(R.string.shell_review_inbox))
                        }
                    }
                })
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                    listOf(R.string.transactions, R.string.inbox, R.string.settings).forEachIndexed { index, title ->
                        NavigationBarItem(modifier = Modifier.testTag("destination_$index"), selected = destination == index, onClick = { destination = index },
                            icon = { Icon(when(index) { 1 -> Icons.Default.Email; 2 -> Icons.Default.Settings; else -> Icons.AutoMirrored.Filled.List }, null) },
                            label = { Text(stringResource(title)) })
                    }
                }
            },
            floatingActionButton = { if (destination == 0 && !selecting) ExtendedFloatingActionButton(modifier = Modifier.testTag("add_entry").semantics { contentDescription = addExpenseLabel }, onClick = { editorId = "new"; editingRevision = 0 }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(addExpenseLabel) }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) },
        ) { padding ->
            when (destination) {
                0 -> Transactions(entries, categories, accounts, Modifier.padding(padding), pendingCount = pendingCount, onPendingInbox = { destination = 1 },
                    fx = if (fxRepository == null) null else fxState,
                    changeDisplay = { separate -> scope.launch { fxRepository?.setSeparate(separate) } },
                    refreshRates = { scope.launch { fxRepository?.refresh(entries, force = true) } }, selecting = selecting, selectionBusy = bulkRunning, selectedIds = selectedIds.toSet(),
                    startSelection = { selecting = true; selectedIds = emptyList() }, cancelSelection = { selecting = false; selectedIds = emptyList() },
                    toggleSelection = { id -> selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id },
                    selectVisible = { ids -> selectedIds = (selectedIds + ids).distinct() },
                    bulkCategory = { bulkSnapshot = entries.filter { it.id in selectedIds }; pickerMode = "bulk-category" },
                    bulkAccount = { bulkSnapshot = entries.filter { it.id in selectedIds }; pickerMode = "bulk-account" },
                    bulkRemove = { bulkSnapshot = entries.filter { it.id in selectedIds }; confirmBulkRemove = true },
                    swipe = { entry, action ->
                        swipeEntry = entry
                        if (action == TransactionAction.CATEGORY) { swipeAction = null; pickerMode = "single-category" }
                        else swipeAction = action
                    }) { editorId = it.id; editingRevision = it.revision }
                1 -> InboxScreen(inboxStore, captureController, inbox, sources, entries, Modifier.padding(padding), templates.isNotEmpty(), execute = { command, applied -> run(command, onApplied = applied) },
                    record = { receipt, separate -> receiptId = receipt.id; receiptRevision = receipt.revision; separatePurchase = separate },
                    viewEntry = { entry -> editorId = entry.id; editingRevision = entry.revision }, createTemplate = { templateReceiptId = it.id })
                2 -> Settings(categories, accounts, aliases, rules, accountRules, sources, templates, ignoreRules, Modifier.padding(padding), store, fxRepository, fxState, entries, captureController) { command, done -> run(command, done = done) }
            }
        }
        editorId?.let { id ->
            val entry = entries.find { it.id == id }
            if (id != "new" && entry == null) return@let
            EntryEditor(id, editingRevision, entry, categories, accounts, aliases, rules, store,
                close = { editorId = null },
                saved = { command, result ->
                    editorId = null
                    if (command is LedgerCommand.Edit && result.revision != null) {
                        scope.launch {
                            if (snackbar.showSnackbar(changed, undo, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                                run(LedgerCommand.Undo(command.operationId, result.revision))
                            }
                        }
                    }
                },
                delete = { if (entry != null) run(LedgerCommand.Delete(entry.id, editingRevision), true, done = { editorId = null }) }, recentEntries = entries, reportingCurrency = fxState.currency, fx = fxState, accountRules = accountRules)
        }
        receiptId?.let { id ->
            val receipt = inbox.find { it.id == id }
            if (receipt != null) EntryEditor("receipt-$id", 0, null, categories, accounts, aliases, rules, store,
                close = { receiptId = null }, saved = { _, _ -> receiptId = null }, delete = {},
                receipt = receipt, receiptRevision = receiptRevision, separatePurchase = separatePurchase, recentEntries = entries, reportingCurrency = fxState.currency, fx = fxState, accountRules = accountRules)
        }
        templateReceiptId?.let { id ->
            val item = inbox.find { it.id == id }
            val existing = entries.find { it.id == item?.entryId && !it.deleted }
            val pending = item?.status in setOf(InboxStatus.UNRECOGNIZED, InboxStatus.READY, InboxStatus.REVIEW_NEEDED)
            if (item?.payload != null) TemplateStart(item, store, close = { templateReceiptId = null }, recordEnabled = pending || existing != null, recordExisting = existing != null, recordEntry = {
                templateReceiptId = null
                if (existing != null) { editorId = existing.id; editingRevision = existing.revision }
                else {
                    receiptId = item.id; receiptRevision = item.revision
                    separatePurchase = item.requiresRevisionDecision || item.relatedEntryIds.isNotEmpty()
                }
            })
        }
        val actionEntry = swipeEntry
        if (actionEntry != null && swipeAction == TransactionAction.ACCOUNT) SwipeActionSheet(stringResource(R.string.account), dismiss = { swipeAction = null; swipeEntry = null }) {
            TextButton(onClick = { pickerMode = "single-account"; swipeAction = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("swipe_assign_account")) { Text(stringResource(R.string.assign_account)) }
            TextButton(onClick = { pickerMode = "rule-account"; swipeAction = null }, enabled = actionEntry.draft.merchant.isNotBlank() || actionEntry.draft.sourcePackage != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("swipe_rule_account")) { Text(stringResource(R.string.create_account_rule)) }
        }
        pickerMode?.let { mode ->
            val categoryPicker = mode.endsWith("category")
            EditorLabelPicker(stringResource(if (categoryPicker) R.string.category else R.string.account),
                if (categoryPicker) categories.map { it.id to it.name } else accounts.map { it.id to listOfNotNull(it.icon, it.name).joinToString(" ") },
                stringResource(if (categoryPicker) R.string.uncategorized else R.string.unassigned),
                if (mode.startsWith("single") || mode.startsWith("rule")) (if (categoryPicker) actionEntry?.draft?.categoryId else actionEntry?.draft?.accountId).orEmpty() else "",
                dismiss = { pickerMode = null; swipeEntry = null }, categoryStore = if (categoryPicker) store else null,
                confirmCategory = mode == "single-category",
                createVendorRule = if (mode == "single-category" && actionEntry?.draft?.merchant?.isNotBlank() == true) { chosen -> pickerMode = null; ruleCategory = chosen } else null) { chosen ->
                pickerMode = null
                when (mode) {
                    "bulk-category" -> applyBulk(bulkSnapshot, "category", chosen.ifEmpty { null })
                    "bulk-account" -> applyBulk(bulkSnapshot, "account", chosen.ifEmpty { null })
                    "single-category" -> actionEntry?.let { run(LedgerCommand.Edit(it.id, it.revision, it.draft.copy(categoryId = chosen.ifEmpty { null }, categoryOrigin = if (chosen.isEmpty()) CategoryOrigin.UNASSIGNED else CategoryOrigin.EXPLICIT)), true) }
                    "single-account" -> actionEntry?.let { run(LedgerCommand.Edit(it.id, it.revision, it.draft.copy(accountId = chosen.ifEmpty { null })), true) }
                    "rule-account" -> if (chosen.isNotEmpty()) ruleAccount = chosen else swipeEntry = null
                }
                if (mode.startsWith("single")) swipeEntry = null
            }
        }
        if (actionEntry != null && ruleCategory != null) MerchantRuleDialog(store, rules, actionEntry.draft.canonicalMerchant?.takeIf { it.isNotBlank() } ?: actionEntry.draft.merchant, ruleCategory!!, categories.find { it.id == ruleCategory }?.name.orEmpty(), targetEntry = actionEntry) { ruleCategory = null; swipeEntry = null }
        if (actionEntry != null && ruleAccount != null) AccountRuleDialog(store, accountRules, actionEntry, ruleAccount!!, accounts.find { it.id == ruleAccount }?.name.orEmpty()) { ruleAccount = null; swipeEntry = null }
        if (confirmBulkRemove) AlertDialog(onDismissRequest = { confirmBulkRemove = false }, title = { Text(stringResource(R.string.bulk_remove_title)) }, text = { Text(stringResource(R.string.bulk_remove_confirm, bulkSnapshot.size)) },
            confirmButton = { TextButton(onClick = { confirmBulkRemove = false; applyBulk(bulkSnapshot, "delete") }, modifier = Modifier.testTag("confirm_bulk_remove")) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmBulkRemove = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
internal fun kindLabel(kind: EntryKind): String = stringResource(when(kind) { EntryKind.EXPENSE -> R.string.expense; EntryKind.INCOME -> R.string.income; EntryKind.TRANSFER -> R.string.transfer; EntryKind.REFUND -> R.string.refund })

@Composable
internal fun Choice(label: String, selected: String, choices: List<Pair<String, String>>, enabled: Boolean = true, changed: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("$label: ${choices.find { it.first == selected }?.second.orEmpty()}") }
        DropdownMenu(expanded && enabled, { expanded = false }) { choices.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { changed(value); expanded = false }) } }
    }
}

@Composable
internal fun DateField(label: String, value: String, allowBlank: Boolean = false, changed: (String) -> Unit) {
    val context = LocalContext.current
    OutlinedTextField(value, changed, label = { Text(label) }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth(), trailingIcon = {
        TextButton(onClick = {
            val date = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
            DatePickerDialog(context, { _, y, m, d -> changed(LocalDate.of(y, m + 1, d).toString()) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }) { Text(stringResource(R.string.choose)) }
    }, supportingText = if (allowBlank) { { Text(stringResource(R.string.blank_any_date)) } } else null)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Settings(categories: List<Category>, accounts: List<AccountLabel>, aliases: List<MerchantAlias>, rules: List<CategoryRule>, accountRules: List<AccountRule>, sources: List<SourcePreference>, templates: List<TemplateDefinition>, ignoreRules: List<IgnoreRule>, modifier: Modifier, store: LedgerStore, fxRepository: com.fishese.moneyfly.fx.FxRepository?, fxState: com.fishese.moneyfly.fx.FxSnapshot, entries: List<LedgerEntry>, captureController: CaptureController?, execute: (LedgerCommand, () -> Unit) -> Unit) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var confirmApplyCategories by remember { mutableStateOf(false) }
    var applyingCategories by remember { mutableStateOf(false) }
    var categoryApplyResult by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val applyScope = rememberCoroutineScope()
    val settingsListState = rememberLazyListState()
    val headings = listOf(R.string.categories, R.string.accounts, R.string.aliases, R.string.rules)
    val ids = when(section) {
        0 -> categories.filter { it.name.contains(search, true) }.map { it.id }
        1 -> accounts.filter { it.name.contains(search, true) }.map { it.id }
        2 -> aliases.filter { it.spelling.contains(search, true) || it.canonicalName.contains(search, true) }.map { it.id }
        else -> rules.filter { it.literal.contains(search, true) || categories.find { c -> c.id == it.categoryId }?.name.orEmpty().contains(search, true) }.map { it.id }
    }
    LazyColumn(modifier.fillMaxSize().testTag("settings_list"), state = settingsListState, contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "settings_shortcuts") {
            Column {
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (fxRepository != null) AssistChip(onClick = { applyScope.launch { settingsListState.animateScrollToItem(1) } }, label = { Text(stringResource(R.string.settings_currency_shortcut)) }, modifier = Modifier.testTag("settings_jump_currency"))
                    AssistChip(onClick = { section = 0; search = ""; applyScope.launch { settingsListState.animateScrollToItem(2) } }, label = { Text(stringResource(R.string.categories)) }, modifier = Modifier.testTag("settings_jump_categories"))
                    AssistChip(onClick = { section = 3; search = ""; applyScope.launch { settingsListState.animateScrollToItem(2) } }, label = { Text(stringResource(R.string.settings_rules_shortcut)) }, modifier = Modifier.testTag("settings_jump_rules"))
                    AssistChip(onClick = { applyScope.launch { settingsListState.animateScrollToItem(3 + maxOf(ids.size, 1)) } }, label = { Text(stringResource(R.string.settings_notifications_shortcut)) }, modifier = Modifier.testTag("settings_jump_notifications"))
                }
            }
        }
        item(key = "settings_currency") {
            if (fxRepository != null) {
                FxSettings(fxRepository, fxState, entries)
                Spacer(Modifier.height(20.dp))
            }
        }
        item(key = "settings_organize") {
            Text(stringResource(R.string.shell_organize), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.shell_organize_hint), Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                headings.forEachIndexed { index, title ->
                    FilterChip(selected = section == index, onClick = { section = index; search = "" }, label = { Text(stringResource(title)) })
                }
            }
            OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.shell_search_labels)) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("settings_search"), singleLine = true)
            FilledTonalButton(onClick = { editId = "new" }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(when(section) { 0 -> R.string.shell_new_category; 1 -> R.string.shell_new_account; 2 -> R.string.shell_new_alias; else -> R.string.shell_new_rule }))
            }
            if (section == 0) {
                Spacer(Modifier.height(16.dp))
                ExpenseCategorySuggestions(categories, store, customCategory = { editId = "new" })
            }
            if (section == 1) AccountRuleSettings(accountRules, accounts, { captureController?.sourceName(it) ?: it }) { command -> execute(command) {} }
            if (section == 3) {
                Text(stringResource(R.string.rule_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { confirmApplyCategories = true }, enabled = !applyingCategories && rules.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("apply_category_rules")) { Text(stringResource(if (applyingCategories) R.string.category_apply_running else R.string.category_apply_existing)) }
                categoryApplyResult?.let { (updated, failed) -> Text(stringResource(R.string.category_apply_result, updated, failed), style = MaterialTheme.typography.bodySmall) }
            }
        }
        items(ids) { id ->
            val title = when(section) { 0 -> categories.first { it.id == id }.name; 1 -> accounts.first { it.id == id }.let { listOfNotNull(it.icon, it.name).joinToString(" ") }; 2 -> aliases.first { it.id == id }.let { "${it.spelling} → ${it.canonicalName}" }; else -> rules.first { it.id == id }.let { "${it.literal} → ${categories.find { c -> c.id == it.categoryId }?.name.orEmpty()} (${it.priority})" } }
            Surface(Modifier.fillMaxWidth().testTag("settings_label_$id").clickable { editId = id }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(16.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.Default.Edit, stringResource(R.string.edit_entry), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (ids.isEmpty()) item { Text(stringResource(if (search.isBlank()) R.string.no_labels else R.string.shell_no_label_matches), Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item(key = "settings_notifications") {
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.shell_notifications), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.shell_notifications_hint), Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            SourceSettings(sources, templates, sourceName = { captureController?.sourceName(it) ?: it }) { command -> execute(command) {} }
            IgnoreRuleSettings(ignoreRules) { command -> execute(command) {} }
            TemplateSettings(templates, store, execute)
            Text(stringResource(R.string.local_journal), Modifier.padding(vertical = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirmApplyCategories) AlertDialog(onDismissRequest = { confirmApplyCategories = false }, title = { Text(stringResource(R.string.category_apply_existing)) }, text = { Text(stringResource(R.string.category_apply_confirm)) },
        confirmButton = { TextButton(onClick = {
            confirmApplyCategories = false; applyingCategories = true; categoryApplyResult = null
            applyScope.launch {
                var updated = 0; var failed = 0
                try {
                    val currentRules = store.rules.first()
                    val currentAliases = store.aliases.first()
                    val candidates = store.entries.first().filter { !it.deleted && it.draft.kind == EntryKind.EXPENSE && it.draft.categoryId == null }
                    for (entry in candidates) {
                        val target = Categorization.suggest(entry.draft, currentAliases, currentRules).categoryId ?: continue
                        try {
                            val result = store.execute(LedgerCommand.Edit(entry.id, entry.revision, entry.draft.copy(categoryId = target, categoryOrigin = CategoryOrigin.RULE)))
                            if (result.applied) updated++ else failed++
                        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failed++ }
                    }
                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failed++ }
                categoryApplyResult = updated to failed; applyingCategories = false
            }
        }, modifier = Modifier.testTag("confirm_apply_category_rules")) { Text(stringResource(R.string.category_apply_existing)) } },
        dismissButton = { TextButton(onClick = { confirmApplyCategories = false }) { Text(stringResource(R.string.cancel)) } })
    editId?.let { id ->
        val c = categories.find { it.id == id }; val a = accounts.find { it.id == id }; val alias = aliases.find { it.id == id }; val rule = rules.find { it.id == id }
        var name by remember(id, section) { mutableStateOf(c?.name ?: a?.name ?: alias?.spelling ?: rule?.literal.orEmpty()) }
        var canonical by remember(id, section) { mutableStateOf(alias?.canonicalName.orEmpty()) }
        var category by remember(id, section) { mutableStateOf(rule?.categoryId ?: categories.firstOrNull()?.id.orEmpty()) }
        var account by remember(id, section) { mutableStateOf(rule?.accountId.orEmpty()) }
        var priority by remember(id, section) { mutableStateOf((rule?.priority ?: 0).toString()) }
        var field by remember(id, section) { mutableStateOf((rule?.field ?: RuleField.MERCHANT).name) }
        var match by remember(id, section) { mutableStateOf((rule?.match ?: LiteralMatch.EQUALS).name) }
        var source by remember(id, section) { mutableStateOf(rule?.sourcePackage.orEmpty()) }
        var accountKind by remember(id, section) { mutableStateOf((a?.kind ?: AccountKind.ACCOUNT).name) }
        var accountIcon by remember(id, section) { mutableStateOf(a?.icon.orEmpty()) }
        val actualId = remember(id) { if (id == "new") UUID.randomUUID().toString() else id }
        AlertDialog(onDismissRequest = { editId = null }, title = { Text(stringResource(headings[section])) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(if (section == 2) R.string.spelling else if (section == 3) R.string.match_text else R.string.name)) })
                if (section == 1) {
                    Choice(stringResource(R.string.account_kind), accountKind, listOf(AccountKind.ACCOUNT.name to stringResource(R.string.account), AccountKind.CARD.name to stringResource(R.string.card), AccountKind.OTHER.name to stringResource(R.string.other))) { accountKind = it }
                    OutlinedTextField(accountIcon, { accountIcon = it }, singleLine = true, label = { Text(stringResource(R.string.account_icon_label)) }, supportingText = { Text(stringResource(R.string.account_icon_hint)) }, isError = accountIcon.length > 32 || accountIcon.any { it.isISOControl() }, modifier = Modifier.fillMaxWidth().testTag("account_icon_input"))
                }
                if (section == 2) OutlinedTextField(canonical, { canonical = it }, label = { Text(stringResource(R.string.canonical)) })
                if (section == 3) {
                    Choice(stringResource(R.string.match_field), field, listOf(RuleField.MERCHANT.name to stringResource(R.string.merchant), RuleField.NOTES.name to stringResource(R.string.notes))) { field = it }
                    Choice(stringResource(R.string.match_mode), match, listOf(LiteralMatch.EQUALS.name to stringResource(R.string.exact_match), LiteralMatch.CONTAINS.name to stringResource(R.string.contains_match), LiteralMatch.WHOLE_TOKEN.name to stringResource(R.string.token_match))) { match = it }
                    Choice(stringResource(R.string.category), category, categories.map { it.id to it.name }) { category = it }
                    OutlinedTextField(priority, { priority = it }, label = { Text(stringResource(R.string.priority)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Choice(stringResource(R.string.account_scope), account, listOf("" to stringResource(R.string.all)) + accounts.map { it.id to it.name }) { account = it }
                    OutlinedTextField(source, { source = it }, label = { Text(stringResource(R.string.source_scope)) })
                }
                if (id != "new") Text(stringResource(R.string.delete_label_hint), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {
            TextButton(enabled = name.isNotBlank() && (section != 1 || (accountIcon.length <= 32 && accountIcon.none { it.isISOControl() })) && (section != 2 || canonical.isNotBlank()) && (section != 3 || (category.isNotEmpty() && priority.toIntOrNull() != null)), onClick = {
                val command = when(section) {
                    0 -> LedgerCommand.PutCategory(actualId, name, c?.revision)
                    1 -> LedgerCommand.PutAccount(actualId, name, AccountKind.valueOf(accountKind), a?.revision, icon = accountIcon.trim().ifEmpty { null })
                    2 -> LedgerCommand.PutAlias(actualId, name, canonical, alias?.revision)
                    else -> LedgerCommand.PutRule(CategoryRule(actualId, RuleField.valueOf(field), LiteralMatch.valueOf(match), name, category, priority.toInt(), source.ifBlank { null }, account.ifBlank { null }, rule?.revision ?: 0), rule?.revision)
                }
                execute(command) { editId = null }
            }) { Text(stringResource(R.string.save)) }
        }, dismissButton = {
            Row {
                if (id != "new") TextButton(onClick = {
                    val command = when(section) { 0 -> LedgerCommand.DeleteCategory(id, c!!.revision); 1 -> LedgerCommand.DeleteAccount(id, a!!.revision); 2 -> LedgerCommand.DeleteAlias(id, alias!!.revision); else -> LedgerCommand.DeleteRule(id, rule!!.revision) }
                    execute(command) { editId = null }
                }) { Text(stringResource(R.string.delete)) }
                TextButton(onClick = { editId = null }) { Text(stringResource(R.string.cancel)) }
            }
        })
    }
}
