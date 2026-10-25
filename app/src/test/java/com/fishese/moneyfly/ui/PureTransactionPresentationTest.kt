package com.fishese.moneyfly.ui

import com.fishese.moneyfly.ledger.*
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class PureTransactionPresentationTest {
    @Test fun journalSearchComposesLiteralTermsAcrossSavedFields() {
        val categories = listOf(Category("food", "午餐 Food", 1))
        val accounts = listOf(AccountLabel("card", "Travel Card", AccountKind.CARD, 1))
        val saved = entry("search", EntryDraft(amount = "28.50", currency = "HKD", merchant = "茶餐廳", canonicalMerchant = "Corner Cafe", notes = "Lunch 10% bonus", categoryId = "food", accountId = "card"))
        assertTrue(matchesJournalSearch(saved, "hkd 28.50 lunch", categories, accounts))
        assertTrue(matchesJournalSearch(saved, "茶餐廳　午餐\tCARD", categories, accounts))
        assertTrue(matchesJournalSearch(saved, "cAfE Food travel", categories, accounts))
        assertTrue(matchesJournalSearch(saved, "%", categories, accounts))
        assertFalse(matchesJournalSearch(saved, ".*", categories, accounts))
        assertFalse(matchesJournalSearch(saved, "_", categories, accounts))
        assertFalse(matchesJournalSearch(saved, "HKD 28.50 dinner", categories, accounts))
        assertFalse(matchesJournalSearch(saved, "USD 28.50", categories, accounts))
        assertFalse(matchesJournalSearch(saved, "HKD 2850", categories, accounts))
        assertTrue(matchesJournalSearch(saved, "  \t", categories, accounts))
        val otherCurrency = saved.copy(draft = saved.draft.copy(currency = "USD"))
        assertEquals(listOf(saved), listOf(saved, otherCurrency).filter { matchesJournalSearch(it, "HKD 28.50", categories, accounts) })
    }

    @Test fun typedExtremeDatesAndMonthBoundariesNeverOverflow() {
        assertNull(journalDate("+999999999-12-31"))
        assertNull(journalDate("-999999999-01-01"))
        assertNull(journalDate("0000-01-01"))
        assertNull(journalDate("+10000-01-01"))
        assertNull(journalDate("2026-02-30"))
        assertEquals(LocalDate.of(1, 1, 1), journalDate("0001-01-01"))
        assertEquals(LocalDate.of(10000, 1, 1), journalExclusiveEnd(journalDate("9999-12-31")))
        assertNull(journalExclusiveEnd(LocalDate.MAX))
        assertNull(journalAdjacentMonth(YearMonth.of(1, 1), -1))
        assertNull(journalAdjacentMonth(YearMonth.of(9999, 12), 1))
        assertEquals(YearMonth.of(2026, 9), journalAdjacentMonth(YearMonth.of(2026, 10), -1))
    }

    private fun entry(id: String, draft: EntryDraft): LedgerEntry {
        val now = Instant.parse("2026-10-24T12:00:00Z")
        return LedgerEntry(id, 1, draft.copy(occurredAt = now), createdAt = now, updatedAt = now, lastOperationId = id)
    }

    @Test fun groupingPreservesExactSeparateCurrencyAndKindTotals() {
        val rows = listOf(
            entry("a", EntryDraft(amount = "0.10", merchant = "Cafe", categoryId = "food")),
            entry("b", EntryDraft(amount = "0.20", merchant = "Cafe", categoryId = "food")),
            entry("c", EntryDraft(amount = "9.00", currency = "USD", kind = EntryKind.INCOME)),
            entry("d", EntryDraft(amount = "2.00", kind = EntryKind.REFUND)),
            entry("e", EntryDraft(amount = "25.00", kind = EntryKind.TRANSFER)),
        )
        for (mode in JournalGrouping.entries) {
            val grouped = journalGroups(rows, mode, listOf(Category("food", "Food", 1)), emptyList(), ZoneId.of("UTC"))
            assertEquals(rows.map { it.id }.toSet(), grouped.flatMap { it.entries }.map { it.id }.toSet())
            val totals = ledgerTotals(grouped.flatMap { it.entries })
            assertEquals("0.30", totals.first { it.currency == "HKD" }.expense.toPlainString())
            assertEquals("2.00", totals.first { it.currency == "HKD" }.refund.toPlainString())
            assertEquals("25.00", totals.first { it.currency == "HKD" }.transfer.toPlainString())
            assertEquals("9.00", totals.first { it.currency == "USD" }.income.toPlainString())
        }
    }

    @Test fun unassignedGroupsRemainFindableAndDeletedEntriesStayHidden() {
        val unassigned = entry("a", EntryDraft(amount = "5.00", notes = "Lunch"))
        val assigned = entry("b", EntryDraft(amount = "8.00", accountId = "card", categoryId = "food"))
        val rows = listOf(unassigned, assigned, unassigned.copy(id = "deleted", deleted = true))
        assertEquals(listOf(unassigned), journalGroups(rows, JournalGrouping.ACCOUNT, emptyList(), emptyList()).first { it.key.isEmpty() }.entries)
        assertEquals(listOf(unassigned), rows.filter(LedgerFilter(unassignedOnly = true, keyword = "lunch")::matches))
        assertEquals(listOf(unassigned), journalGroups(rows, JournalGrouping.CATEGORY, emptyList(), emptyList()).first { it.key.isEmpty() }.entries)
    }
}
