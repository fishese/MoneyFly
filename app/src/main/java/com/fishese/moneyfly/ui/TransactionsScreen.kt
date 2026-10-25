package com.fishese.moneyfly.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fishese.moneyfly.R
import com.fishese.moneyfly.ledger.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun Transactions(
    entries: List<LedgerEntry>, categories: List<Category>, accounts: List<AccountLabel>,
    modifier: Modifier, pendingCount: Int = 0, onPendingInbox: () -> Unit = {}, edit: (LedgerEntry) -> Unit,
) {
    val today = LocalDate.now()
    var search by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var account by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf(today.withDayOfMonth(1).toString()) }
    var end by rememberSaveable { mutableStateOf(YearMonth.from(today).atEndOfMonth().toString()) }
    var filters by rememberSaveable { mutableStateOf(false) }
    var grouping by rememberSaveable { mutableStateOf(JournalGrouping.DATE.name) }
    var collapsed by rememberSaveable { mutableStateOf(listOf<String>()) }
    val from = journalDate(start)
    val through = journalDate(end)
    val untilExclusive = journalExclusiveEnd(through)
    val earlierMonth = journalAdjacentMonth(YearMonth.from(from ?: today), -1)
    val laterMonth = journalAdjacentMonth(YearMonth.from(from ?: today), 1)
    val dateError = (start.isNotBlank() && from == null) || (end.isNotBlank() && through == null) || (from != null && through != null && from > through)
    val filter = LedgerFilter(categoryId = category.takeIf { it.isNotEmpty() && it != "none" }, uncategorizedOnly = category == "none", accountId = account.takeIf { it.isNotEmpty() && it != "none" }, unassignedOnly = account == "none", fromDate = from, untilDateExclusive = untilExclusive, kinds = kind.takeIf { it.isNotEmpty() }?.let { setOf(EntryKind.valueOf(it)) } ?: emptySet())
    val rows = if (dateError) emptyList() else entries.filter { filter.matches(it) && (currency.isEmpty() || it.draft.currency == currency) && matchesJournalSearch(it, search, categories, accounts) }.sortedByDescending { it.draft.occurredAt }
    val groups = journalGroups(rows, JournalGrouping.valueOf(grouping), categories, accounts)
    fun month(value: YearMonth) { start = value.atDay(1).toString(); end = value.atEndOfMonth().toString() }
    fun clear() { search = ""; category = ""; account = ""; kind = ""; currency = ""; start = ""; end = "" }
    val categoryName = if (category == "none") stringResource(R.string.uncategorized) else categories.find { it.id == category }?.name.orEmpty()
    val accountName = if (account == "none") stringResource(R.string.unassigned) else accounts.find { it.id == account }?.name.orEmpty()
    val previousMonth = stringResource(R.string.journal_previous_month)
    val nextMonth = stringResource(R.string.journal_next_month)
    val clearSearch = stringResource(R.string.journal_clear_search)
    val period = when {
        start.isBlank() && end.isBlank() -> stringResource(R.string.journal_all_time)
        from != null && through != null && from.dayOfMonth == 1 && through == YearMonth.from(from).atEndOfMonth() -> from.format(DateTimeFormatter.ofPattern("LLLL yyyy"))
        else -> "$start – $end"
    }
    var groupMenu by remember { mutableStateOf(false) }
    val selectedGroupLabel = stringResource(JournalGrouping.valueOf(grouping).label)
    val standardMonth = from != null && through != null && from.dayOfMonth == 1 && through == YearMonth.from(from).atEndOfMonth()
    LazyColumn(modifier.fillMaxSize().testTag("transactions_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.transactions), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (pendingCount > 0) TextButton(onClick = onPendingInbox) { Text(stringResource(R.string.journal_pending_compact, pendingCount), style = MaterialTheme.typography.labelSmall) }
                TextButton(modifier = Modifier.testTag("clear_filters"), onClick = { clear() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.clear_all), style = MaterialTheme.typography.labelSmall) }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { earlierMonth?.let(::month) }, enabled = earlierMonth != null, modifier = Modifier.testTag("journal_previous_month").sizeIn(minWidth = 40.dp).semantics { contentDescription = previousMonth }, contentPadding = PaddingValues(4.dp)) { Text("‹", style = MaterialTheme.typography.titleLarge) }
                Text(period, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { laterMonth?.let(::month) }, enabled = laterMonth != null, modifier = Modifier.testTag("journal_next_month").sizeIn(minWidth = 40.dp).semantics { contentDescription = nextMonth }, contentPadding = PaddingValues(4.dp)) { Text("›", style = MaterialTheme.typography.titleLarge) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SuggestionChip(onClick = { month(YearMonth.from(today)) }, label = { Text(stringResource(R.string.journal_this_month), style = MaterialTheme.typography.labelMedium) })
                SuggestionChip(onClick = { month(YearMonth.from(today).minusMonths(1)) }, label = { Text(stringResource(R.string.journal_last_month), style = MaterialTheme.typography.labelMedium) })
                SuggestionChip(onClick = { start = ""; end = "" }, label = { Text(stringResource(R.string.journal_all_time), style = MaterialTheme.typography.labelMedium) })
            }
        }
        item {
            CategorySpendingOverview(rows, categories) { selectedCategory, selectedCurrency ->
                category = selectedCategory ?: "none"
                currency = selectedCurrency
                kind = EntryKind.EXPENSE.name
            }
        }
        if (rows.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ledgerTotals(rows).forEach { total ->
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.widthIn(min = 190.dp, max = 270.dp)) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.journal_spending), style = MaterialTheme.typography.labelSmall)
                            Text("${total.currency} ${total.expense.toPlainString()}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            val extra = listOfNotNull(
                                if (total.income.signum() != 0) stringResource(R.string.journal_income_total, total.income.toPlainString()) else null,
                                if (total.refund.signum() != 0) stringResource(R.string.journal_refund_total, total.refund.toPlainString()) else null,
                                if (total.transfer.signum() != 0) stringResource(R.string.journal_transfer_excluded_total, total.transfer.toPlainString()) else null,
                            )
                            if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        item {
            OutlinedTextField(search, { search = it }, placeholder = { Text(stringResource(R.string.journal_search_hint), style = MaterialTheme.typography.bodyMedium) }, modifier = Modifier.fillMaxWidth().testTag("journal_search"), singleLine = true, shape = MaterialTheme.shapes.large,
                trailingIcon = if (search.isNotEmpty()) { { TextButton(onClick = { search = "" }, modifier = Modifier.testTag("journal_clear_search").semantics { contentDescription = clearSearch }) { Text("×") } } } else null)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.journal_entry_count, rows.size, rows.size), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { filters = true }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(stringResource(R.string.filters), style = MaterialTheme.typography.labelMedium) }
                Box {
                    TextButton(onClick = { groupMenu = true }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("${stringResource(R.string.journal_group_by)}: $selectedGroupLabel", style = MaterialTheme.typography.labelMedium) }
                    DropdownMenu(groupMenu, { groupMenu = false }) {
                        JournalGrouping.entries.forEach { mode ->
                            DropdownMenuItem(text = { Text(stringResource(mode.label)) }, onClick = { grouping = mode.name; collapsed = emptyList(); groupMenu = false })
                        }
                    }
                }
            }
            if (category.isNotEmpty() || account.isNotEmpty() || kind.isNotEmpty() || currency.isNotEmpty() || (!standardMonth && (start.isNotEmpty() || end.isNotEmpty()))) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (category.isNotEmpty()) InputChip(selected = true, onClick = { category = "" }, label = { Text("$categoryName ×") })
                if (account.isNotEmpty()) InputChip(selected = true, onClick = { account = "" }, label = { Text("$accountName ×") })
                if (kind.isNotEmpty()) InputChip(selected = true, onClick = { kind = "" }, label = { Text("${kindLabel(EntryKind.valueOf(kind))} ×") })
                if (currency.isNotEmpty()) InputChip(selected = true, onClick = { currency = "" }, label = { Text("$currency ×") }, modifier = Modifier.testTag("journal_currency_filter"))
                if (!standardMonth && (start.isNotEmpty() || end.isNotEmpty())) InputChip(selected = true, onClick = { start = ""; end = "" }, label = { Text("$period ×") })
            }
            if (category.isEmpty() && categories.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { item -> FilterChip(selected = false, onClick = { category = item.id }, label = { Text(item.name, style = MaterialTheme.typography.labelMedium) }) }
            }
            if (dateError) Text(stringResource(R.string.invalid_date_range), color = MaterialTheme.colorScheme.error)
        }
        if (rows.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(if (entries.none { !it.deleted }) R.string.journal_empty_title else R.string.no_matches), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(if (entries.none { !it.deleted }) R.string.journal_empty_body else R.string.journal_no_matches_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entries.any { !it.deleted }) OutlinedButton(onClick = { clear() }) { Text(stringResource(R.string.clear_all)) }
            }
        }
        groups.forEach { group ->
            val key = "$grouping:${group.key}"
            item(key = "header:$key") {
                val title = group.title ?: stringResource(when (JournalGrouping.valueOf(grouping)) { JournalGrouping.CATEGORY -> R.string.uncategorized; JournalGrouping.ACCOUNT -> R.string.unassigned; else -> R.string.no_merchant })
                val groupState = stringResource(if (key in collapsed) R.string.journal_collapsed else R.string.journal_expanded)
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("journal_group_$key").semantics { stateDescription = groupState }.clickable { collapsed = if (key in collapsed) collapsed - key else collapsed + key }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Text(pluralStringResource(R.plurals.journal_entry_count, group.entries.size, group.entries.size), Modifier.weight(0.65f).testTag("journal_group_count_$key"), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                        val spending = ledgerTotals(group.entries).filter { it.expense.signum() != 0 }
                        Text(if (spending.isEmpty()) stringResource(R.string.journal_no_spending) else spending.joinToString("\n") { "${it.currency} ${it.expense.toPlainString()}" }, Modifier.weight(1f).testTag("journal_group_total_$key"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                    }
                }
            }
            if (key !in collapsed) items(group.entries, key = { it.id }) { entry -> JournalRow(entry, categories, accounts, edit) }
        }
    }
    if (filters) ModalBottomSheet(onDismissRequest = { filters = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.filters), style = MaterialTheme.typography.headlineSmall)
            Choice(stringResource(R.string.category), category, listOf("" to stringResource(R.string.all), "none" to stringResource(R.string.uncategorized)) + categories.map { it.id to it.name }) { category = it }
            Choice(stringResource(R.string.account), account, listOf("" to stringResource(R.string.all), "none" to stringResource(R.string.unassigned)) + accounts.map { it.id to it.name }) { account = it }
            Choice(stringResource(R.string.kind), kind, listOf("" to stringResource(R.string.all)) + EntryKind.entries.map { it.name to kindLabel(it) }) { kind = it }
            Choice(stringResource(R.string.currency), currency, listOf("" to stringResource(R.string.all)) + entries.filter { !it.deleted }.map { it.draft.currency }.distinct().sorted().map { it to it }) { currency = it }
            DateField(stringResource(R.string.from_date), start, allowBlank = true) { start = it }
            DateField(stringResource(R.string.to_date), end, allowBlank = true) { end = it }
            if (dateError) Text(stringResource(R.string.invalid_date_range), color = MaterialTheme.colorScheme.error)
            Button(onClick = { filters = false }, enabled = !dateError, modifier = Modifier.fillMaxWidth()) { Text(pluralStringResource(R.plurals.journal_show_results_count, rows.size, rows.size)) }
        }
    }
}

@Composable
private fun JournalRow(entry: LedgerEntry, categories: List<Category>, accounts: List<AccountLabel>, edit: (LedgerEntry) -> Unit) {
    val draft = entry.draft
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("transaction_${entry.id}").clickable { edit(entry) }.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Text(draft.canonicalMerchant?.takeIf { it.isNotBlank() } ?: draft.merchant.ifBlank { stringResource(R.string.no_merchant) }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text("${draft.currency} ${draft.amount}", Modifier.widthIn(max = 170.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (draft.kind == EntryKind.INCOME || draft.kind == EntryKind.REFUND) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        Text("${kindLabel(draft.kind)} · ${categories.find { it.id == draft.categoryId }?.name ?: stringResource(R.string.uncategorized)} · ${accounts.find { it.id == draft.accountId }?.name ?: stringResource(R.string.unassigned)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(draft.occurredAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (draft.notes.isNotBlank()) Text(draft.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}
