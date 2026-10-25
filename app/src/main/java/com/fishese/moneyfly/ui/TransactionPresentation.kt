package com.fishese.moneyfly.ui

import com.fishese.moneyfly.R
import com.fishese.moneyfly.ledger.AccountLabel
import com.fishese.moneyfly.ledger.Category
import com.fishese.moneyfly.ledger.LedgerEntry
import java.time.ZoneId
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal enum class JournalGrouping(val label: Int) {
    DATE(R.string.journal_group_date), CATEGORY(R.string.category), MERCHANT(R.string.merchant), ACCOUNT(R.string.account)
}

internal data class JournalGroup(val key: String, val title: String?, val entries: List<LedgerEntry>)

/** Keep editable dates within the four-digit range supported by the date controls. */
internal fun journalDate(value: String): LocalDate? = runCatching {
    LocalDate.parse(value).takeIf { it.year in 1..9999 }
}.getOrNull()

internal fun journalExclusiveEnd(value: LocalDate?): LocalDate? = value?.let {
    runCatching { it.plusDays(1) }.getOrNull()
}

internal fun journalAdjacentMonth(value: YearMonth, offset: Long): YearMonth? = runCatching {
    value.plusMonths(offset).takeIf { it.year in 1..9999 }
}.getOrNull()

/** Literal AND search across saved entry fields; no notification content or pattern syntax. */
internal fun matchesJournalSearch(entry: LedgerEntry, query: String, categories: List<Category>, accounts: List<AccountLabel>): Boolean {
    val terms = query.trim().lowercase(Locale.ROOT).split(Regex("[\\s\\p{Z}]+")).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return true
    val draft = entry.draft
    val fields = listOf(draft.merchant, draft.canonicalMerchant.orEmpty(), draft.notes, draft.amount, draft.currency,
        categories.find { it.id == draft.categoryId }?.name.orEmpty(), accounts.find { it.id == draft.accountId }?.name.orEmpty()
    ).map { it.lowercase(Locale.ROOT) }
    return terms.all { term -> fields.any { field -> field.contains(term) } }
}

/** Grouping is presentation only and never changes a saved entry. */
internal fun journalGroups(entries: List<LedgerEntry>, grouping: JournalGrouping, categories: List<Category>, accounts: List<AccountLabel>, zone: ZoneId = ZoneId.systemDefault()): List<JournalGroup> {
    val sorted = entries.filterNot { it.deleted }.sortedByDescending { it.draft.occurredAt }
    fun key(entry: LedgerEntry): String = when (grouping) {
        JournalGrouping.DATE -> entry.draft.occurredAt.atZone(zone).toLocalDate().toString()
        JournalGrouping.CATEGORY -> entry.draft.categoryId.orEmpty()
        JournalGrouping.ACCOUNT -> entry.draft.accountId.orEmpty()
        JournalGrouping.MERCHANT -> (entry.draft.canonicalMerchant?.takeIf { it.isNotBlank() } ?: entry.draft.merchant).trim().lowercase(Locale.ROOT)
    }
    return sorted.groupBy(::key).map { (key, rows) ->
        val title = when (grouping) {
            JournalGrouping.DATE -> rows.first().draft.occurredAt.atZone(zone).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
            JournalGrouping.CATEGORY -> categories.find { it.id == key }?.name
            JournalGrouping.ACCOUNT -> accounts.find { it.id == key }?.name
            JournalGrouping.MERCHANT -> (rows.first().draft.canonicalMerchant?.takeIf { it.isNotBlank() } ?: rows.first().draft.merchant).trim().takeIf { it.isNotBlank() }
        }
        JournalGroup(key, title, rows)
    }.let { groups -> if (grouping == JournalGrouping.DATE) groups else groups.sortedBy { it.title.orEmpty().lowercase(Locale.ROOT) } }
}
