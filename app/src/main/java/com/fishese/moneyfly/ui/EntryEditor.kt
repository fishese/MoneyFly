package com.fishese.moneyfly.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fishese.moneyfly.R
import com.fishese.moneyfly.ledger.*
import com.fishese.moneyfly.capture.*
import com.fishese.moneyfly.recognition.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryEditor(id: String, expectedRevision: Long, original: LedgerEntry?, categories: List<Category>, accounts: List<AccountLabel>, aliases: List<MerchantAlias>, rules: List<CategoryRule>, store: LedgerStore, close: () -> Unit, saved: (LedgerCommand, CommandResult) -> Unit, delete: () -> Unit, receipt: InboxItem? = null, receiptRevision: Long = 0, separatePurchase: Boolean = false, recentEntries: List<LedgerEntry> = emptyList(), reportingCurrency: String = "HKD", fx: com.fishese.moneyfly.fx.FxSnapshot? = null) {
    key(id) {
        val initial = remember(id) { original?.draft ?: receipt?.draft ?: receipt?.let { EntryDraft(currency = reportingCurrency, occurredAt = it.postedAt, sourcePackage = it.sourcePackage, timeOrigin = TimeOrigin.NOTIFICATION_POSTED, timePrecision = TimePrecision.UNKNOWN) } ?: EntryDraft(currency = reportingCurrency) }
        val originalReceipt = remember(id) { receipt }
        val zone = ZoneId.systemDefault()
        var amount by rememberSaveable { mutableStateOf(initial.amount) }
        var currency by rememberSaveable { mutableStateOf(initial.currency) }
        var customRate by rememberSaveable { mutableStateOf(initial.exchangeRate != null) }
        var entryRate by rememberSaveable { mutableStateOf(initial.exchangeRate.orEmpty()) }
        var rateCurrency by rememberSaveable { mutableStateOf(initial.exchangeRateCurrency ?: reportingCurrency) }
        val validRate = !customRate || runCatching { com.fishese.moneyfly.fx.validatedEntryRate(entryRate, rateCurrency) }.isSuccess
        var kind by rememberSaveable { mutableStateOf(initial.kind.name) }
        var merchant by rememberSaveable { mutableStateOf(initial.merchant) }
        var canonicalMerchant by rememberSaveable { mutableStateOf(initial.canonicalMerchant) }
        var notes by rememberSaveable { mutableStateOf(initial.notes) }
        var category by rememberSaveable { mutableStateOf(initial.categoryId.orEmpty()) }
        var account by rememberSaveable { mutableStateOf(initial.accountId.orEmpty()) }
        var categoryTouched by rememberSaveable { mutableStateOf(initial.categoryId != null) }
        var accountTouched by rememberSaveable { mutableStateOf(initial.accountId != null) }
        var selectedRecentId by rememberSaveable { mutableStateOf<String?>(null) }
        var moreKinds by rememberSaveable { mutableStateOf(initial.kind in setOf(EntryKind.TRANSFER, EntryKind.REFUND)) }
        var moreDate by rememberSaveable { mutableStateOf(false) }
        var categoryPicker by remember { mutableStateOf(false) }
        var createCategoryRule by remember { mutableStateOf(false) }
        var accountPicker by remember { mutableStateOf(false) }
        var notificationExpanded by remember { mutableStateOf(false) }
        var date by rememberSaveable { mutableStateOf(initial.occurredAt.atZone(zone).toLocalDate().toString()) }
        var time by rememberSaveable { mutableStateOf(initial.occurredAt.atZone(zone).toLocalTime().withSecond(0).withNano(0).toString()) }
        var operationId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
        val entryId = rememberSaveable { UUID.randomUUID().toString() }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var retryLocked by rememberSaveable { mutableStateOf(false) }
        var confirmDelete by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val focus = LocalFocusManager.current
        val amountFocus = remember { FocusRequester() }
        val merchantFocus = remember { FocusRequester() }
        val notesFocus = remember { FocusRequester() }
        val editable = !busy && !retryLocked
        val today = LocalDate.now(zone)
        val recents = recentMerchantChoices(recentEntries, merchant)
        val previous = recentEntries.find { it.id == selectedRecentId && !it.deleted }
        val previousCategory = categories.find { it.id == previous?.draft?.categoryId }
        val previousAccount = accounts.find { it.id == previous?.draft?.accountId }
        val recentCategories = recentEntries.filterNot { it.deleted }.sortedByDescending { it.draft.occurredAt }.mapNotNull { row -> categories.find { it.id == row.draft.categoryId } }.distinctBy { it.id }.take(5)
        val invalid = stringResource(R.string.invalid_entry)
        val conflict = stringResource(R.string.conflict)
        val failed = stringResource(R.string.retry_save)
        val normalized = runCatching { Money.normalize(amount, currency) }.getOrNull()
        val timeResolution = resolveEditorTime(date, time, zone, initial, preserveOriginal = original != null || receipt != null)
        val instant = timeResolution.instant
        val unchangedTime = timeResolution.preservesOriginal
        val dateProblem = when (timeResolution.issue) {
            EditorTimeIssue.GAP -> stringResource(R.string.editor_time_gap, zone.id)
            EditorTimeIssue.OVERLAP -> stringResource(R.string.editor_time_overlap, zone.id)
            EditorTimeIssue.INVALID -> stringResource(R.string.editor_time_invalid)
            null -> null
        }
        LaunchedEffect(timeResolution.issue) { if (timeResolution.issue in setOf(EditorTimeIssue.GAP, EditorTimeIssue.OVERLAP)) moreDate = true }
        val missingCategory = category.isNotEmpty() && categories.none { it.id == category }
        val missingAccount = account.isNotEmpty() && accounts.none { it.id == account }
        val suggestion = Categorization.suggest(initial.copy(merchant = merchant, canonicalMerchant = canonicalMerchant, notes = notes, accountId = account.ifEmpty { null }, categoryId = category.ifEmpty { null }, categoryOrigin = if (category.isEmpty()) CategoryOrigin.UNASSIGNED else CategoryOrigin.EXPLICIT), aliases, rules)
        val automaticCategory = suggestion.categoryId.takeIf { original == null && kind == EntryKind.EXPENSE.name && !categoryTouched && category.isEmpty() }
        val displayedCategory = category.ifEmpty { automaticCategory.orEmpty() }
        androidx.compose.ui.window.Dialog(onDismissRequest = { if (!busy) close() }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            DialogSystemBars()
            Surface(Modifier.fillMaxSize()) {
                val keyboard = LocalSoftwareKeyboardController.current
                LaunchedEffect(id) {
                    if (original == null && initial.amount.isBlank() && originalReceipt?.draft == null && !retryLocked) {
                        // The dialog must finish its first layout before it can accept focus.
                        withFrameNanos { }
                        amountFocus.requestFocus()
                        keyboard?.show()
                    }
                }
                Scaffold(modifier = Modifier.imePadding(), topBar = { TopAppBar(title = { Text(stringResource(if (original == null) R.string.add_entry else R.string.edit_entry)) }, navigationIcon = { TextButton(modifier = Modifier.testTag("cancel_entry"), onClick = close, enabled = !busy) { Text(stringResource(R.string.cancel)) } }, actions = { if (original != null) IconButton(onClick = { confirmDelete = true }, enabled = !busy && !retryLocked) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) } }) }, bottomBar = {
                    Surface(tonalElevation = 3.dp) {
                        Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp).testTag("entry_error")) }
                            dateProblem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp).testTag("entry_time_error")) }
                            if (missingCategory || missingAccount) Text(stringResource(R.string.editor_missing_label_save), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp).testTag("entry_label_error"))
                            Button(enabled = !busy && normalized != null && instant != null && !missingCategory && !missingAccount && validRate, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("save_entry"), onClick = {
                                if (normalized == null || instant == null || missingCategory || missingAccount || !validRate) { error = invalid; return@Button }
                                val draft = initial.copy(amount = normalized, currency = currency, kind = EntryKind.valueOf(kind), merchant = merchant, canonicalMerchant = canonicalMerchant, notes = notes, categoryId = category.ifEmpty { null }, accountId = account.ifEmpty { null }, occurredAt = instant, timeOrigin = timeResolution.origin!!, timePrecision = timeResolution.precision!!, categoryOrigin = if (categoryTouched) CategoryOrigin.EXPLICIT else if (category.isEmpty()) CategoryOrigin.UNASSIGNED else initial.categoryOrigin, exchangeRate = if (customRate) entryRate else null, exchangeRateCurrency = if (customRate) rateCurrency else null)
                                val command: LedgerCommand = when {
                                    originalReceipt != null -> LedgerCommand.SaveCaptured(originalReceipt.id, receiptRevision, draft, if (separatePurchase) SaveDecision.SEPARATE_PURCHASE else SaveDecision.NORMAL, entryId, operationId)
                                    id == "new" -> LedgerCommand.Save(draft, entryId, operationId)
                                    else -> LedgerCommand.Edit(id, expectedRevision, draft, operationId)
                                }
                                busy = true
                                focus.clearFocus()
                                scope.launch {
                                    try {
                                        val result = store.execute(command)
                                        if (result.applied) saved(command, result) else {
                                            error = if (result.status == CommandStatus.CONFLICT) conflict else invalid
                                            retryLocked = false
                                            operationId = UUID.randomUUID().toString()
                                        }
                                    } catch (_: Exception) { error = failed; retryLocked = true }
                                    finally { busy = false }
                                }
                            }) { Text(stringResource(if (busy) R.string.saving else R.string.save)) }
                        }
                    }
                }) { padding ->
                    Column(Modifier.padding(padding).testTag("entry_form").verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        originalReceipt?.let { item ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(item.sourcePackage, style = MaterialTheme.typography.labelSmall)
                                    if (notificationExpanded) NotificationContent(item, expanded = true)
                                    else if (item.payload != null) {
                                        if (item.payload.title.isNotBlank()) Text(item.payload.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                        if (item.payload.body.isNotBlank()) Text(item.payload.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    } else Text(stringResource(R.string.content_expired))
                                    TextButton(onClick = { notificationExpanded = !notificationExpanded }) { Text(stringResource(if (notificationExpanded) R.string.less_details else R.string.editor_notification_details)) }
                                    if (item.draft == null) Text(stringResource(R.string.currency_assumption, reportingCurrency), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(EntryKind.EXPENSE, EntryKind.INCOME).forEach { direction -> FilterChip(selected = kind == direction.name, enabled = editable, onClick = { kind = direction.name }, label = { Text(kindLabel(direction)) }, modifier = Modifier.testTag("direction_${direction.name}")) }
                            if (moreKinds) listOf(EntryKind.TRANSFER, EntryKind.REFUND).forEach { direction -> FilterChip(selected = kind == direction.name, enabled = editable, onClick = { kind = direction.name }, label = { Text(kindLabel(direction)) }, modifier = Modifier.testTag("direction_${direction.name}")) }
                            else TextButton(onClick = { moreKinds = true }, enabled = editable) { Text(stringResource(R.string.editor_more_types)) }
                        }
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(amount, { amount = it }, enabled = editable, label = { Text(stringResource(R.string.amount)) }, placeholder = { Text("0.00") }, textStyle = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { merchantFocus.requestFocus() }), singleLine = true, modifier = Modifier.weight(1f).focusRequester(amountFocus).testTag("entry_amount"), isError = amount.isNotEmpty() && normalized == null)
                                OutlinedTextField(currency, { currency = it.uppercase(java.util.Locale.ROOT).take(3); customRate = false; entryRate = ""; rateCurrency = reportingCurrency }, enabled = editable, label = { Text(stringResource(R.string.editor_currency)) }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { merchantFocus.requestFocus() }), textStyle = MaterialTheme.typography.titleMedium, singleLine = true, modifier = Modifier.width(100.dp).testTag("entry_currency"))
                            }
                        }
                        if (amount.isNotEmpty() && normalized == null) Text(stringResource(R.string.editor_amount_help), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(merchant, { merchant = it; canonicalMerchant = Categorization.canonicalMerchant(it, aliases); selectedRecentId = null }, enabled = editable, label = { Text(stringResource(R.string.merchant)) }, placeholder = { Text(stringResource(R.string.editor_merchant_optional)) }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { notesFocus.requestFocus() }), singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(merchantFocus).testTag("entry_merchant"))
                        if (recents.isNotEmpty()) {
                            Text(stringResource(R.string.editor_recent_merchants), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                recents.forEach { row -> AssistChip(enabled = editable, onClick = { merchant = row.draft.merchant; canonicalMerchant = Categorization.canonicalMerchant(merchant, aliases); selectedRecentId = row.id }, label = { Text(row.draft.canonicalMerchant ?: row.draft.merchant) }, modifier = Modifier.testTag("recent_merchant_${row.id}")) }
                            }
                        }
                        canonicalMerchant?.let { Text(stringResource(R.string.alias_preview, it), style = MaterialTheme.typography.bodySmall) }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.category), style = MaterialTheme.typography.titleSmall)
                            if (missingCategory) {
                                Text(stringResource(R.string.editor_missing_category), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { category = ""; categoryTouched = true }, enabled = editable, modifier = Modifier.testTag("clear_missing_category")) { Text(stringResource(R.string.editor_clear_category)) }
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(displayedCategory.isEmpty(), { category = ""; categoryTouched = true }, enabled = editable, label = { Text(stringResource(R.string.uncategorized)) }, modifier = Modifier.testTag("category_none"))
                                val shown = (listOfNotNull(categories.find { it.id == displayedCategory }) + recentCategories).distinctBy { it.id }
                                shown.forEach { label -> FilterChip(displayedCategory == label.id, { category = label.id; categoryTouched = true }, enabled = editable, label = { Text(label.name) }, modifier = Modifier.testTag("quick_category_${label.id}")) }
                            }
                            OutlinedButton(onClick = { categoryPicker = true }, enabled = editable, modifier = Modifier.testTag("choose_category")) { Text(stringResource(R.string.editor_all_categories)) }
                            if (!categoryTouched && suggestion.needsChoice) Text(stringResource(R.string.rule_conflict, suggestion.conflictingCategoryIds.mapNotNull { candidate -> categories.find { it.id == candidate }?.name }.joinToString(", ")), color = MaterialTheme.colorScheme.error)
                            else if (!categoryTouched && category.isEmpty() && suggestion.categoryId != null) {
                                if (original == null && kind == EntryKind.EXPENSE.name) Text(stringResource(R.string.category_rule_auto_preview, categories.find { it.id == suggestion.categoryId }?.name.orEmpty()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                else TextButton(onClick = { category = suggestion.categoryId; categoryTouched = true }, enabled = editable, modifier = Modifier.testTag("apply_rule_category")) { Text(stringResource(R.string.use_suggestion, categories.find { it.id == suggestion.categoryId }?.name.orEmpty())) }
                            }
                            if (kind == EntryKind.EXPENSE.name && merchant.isNotBlank() && category.isNotEmpty() && !missingCategory) TextButton(onClick = { createCategoryRule = true }, enabled = editable, modifier = Modifier.testTag("create_merchant_rule")) { Text(stringResource(R.string.category_rule_from_entry)) }
                            if (!categoryTouched && previousCategory != null && !suggestion.needsChoice && suggestion.categoryId == null) SuggestionChip(onClick = { category = previousCategory.id; categoryTouched = true }, enabled = editable, label = { Text(stringResource(R.string.editor_previous_category, previousCategory.name)) }, modifier = Modifier.testTag("apply_recent_category"))
                        }
                        if (currency != reportingCurrency || customRate) {
                            EntryRateFields(currency, if (customRate) rateCurrency else reportingCurrency, entryRate, customRate, editable,
                                fx?.quotes?.get("${instant?.atZone(zone)?.toLocalDate()}:$currency:$reportingCurrency"),
                                { customRate = it }, { entryRate = it }, { rateCurrency = it })
                        }
                        OutlinedTextField(notes, { notes = it }, enabled = editable, label = { Text(stringResource(R.string.notes)) }, placeholder = { Text(stringResource(R.string.editor_notes_hint)) }, minLines = 1, maxLines = 4, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }), modifier = Modifier.fillMaxWidth().focusRequester(notesFocus).testTag("entry_notes"))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(date == today.toString(), { date = today.toString() }, enabled = editable, label = { Text(stringResource(R.string.editor_today)) }, modifier = Modifier.testTag("date_today"))
                            FilterChip(date == today.minusDays(1).toString(), { date = today.minusDays(1).toString() }, enabled = editable, label = { Text(stringResource(R.string.editor_yesterday)) }, modifier = Modifier.testTag("date_yesterday"))
                            OutlinedButton(onClick = { moreDate = !moreDate }, enabled = editable, modifier = Modifier.testTag("more_date_time")) { Text(runCatching { LocalDate.parse(date).format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)) }.getOrDefault(date) + " · " + time) }
                        }
                        if (moreDate) {
                            if (editable) DateField(stringResource(R.string.date), date) { date = it }
                            OutlinedTextField(time, { time = it }, enabled = editable, label = { Text(stringResource(R.string.time)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), trailingIcon = { TextButton(enabled = editable, onClick = { val t = runCatching { LocalTime.parse(time) }.getOrDefault(LocalTime.now()); TimePickerDialog(context, { _, h, m -> time = LocalTime.of(h, m).toString() }, t.hour, t.minute, true).show() }) { Text(stringResource(R.string.choose)) } })
                            Text(zone.id, style = MaterialTheme.typography.labelSmall)
                        }
                        if (unchangedTime && initial.timeOrigin in setOf(TimeOrigin.NOTIFICATION_POSTED, TimeOrigin.CAPTURED)) Text(stringResource(R.string.fallback_time), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { accountPicker = true }, enabled = editable, modifier = Modifier.fillMaxWidth().testTag("choose_account")) { Text(stringResource(R.string.editor_optional_account, accounts.find { it.id == account }?.name ?: stringResource(if (missingAccount) R.string.editor_removed_label else R.string.unassigned))) }
                        if (missingAccount) {
                            Text(stringResource(R.string.editor_missing_account), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { account = ""; accountTouched = true }, enabled = editable, modifier = Modifier.testTag("clear_missing_account")) { Text(stringResource(R.string.editor_clear_account)) }
                        }
                        if (!accountTouched && previousAccount != null) SuggestionChip(onClick = { account = previousAccount.id; accountTouched = true }, enabled = editable, label = { Text(stringResource(R.string.editor_previous_account, previousAccount.name)) }, modifier = Modifier.testTag("apply_recent_account"))
                    }
                }
                if (categoryPicker) EditorLabelPicker(stringResource(R.string.category), categories.map { it.id to it.name }, stringResource(R.string.uncategorized), category, dismiss = { categoryPicker = false }, categoryStore = store) { category = it; categoryTouched = true; categoryPicker = false }
                if (accountPicker) EditorLabelPicker(stringResource(R.string.account), accounts.map { it.id to it.name }, stringResource(R.string.unassigned), account, dismiss = { accountPicker = false }) { account = it; accountTouched = true; accountPicker = false }
                if (createCategoryRule) MerchantRuleDialog(store, rules, canonicalMerchant ?: merchant, category, categories.find { it.id == category }?.name.orEmpty()) { createCategoryRule = false }
            }
        }
        if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text(stringResource(R.string.delete_entry)) }, text = { Text(stringResource(R.string.delete_hint)) }, confirmButton = { TextButton(onClick = { confirmDelete = false; delete() }) { Text(stringResource(R.string.delete)) } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } })
    }
}
