package com.fishese.moneyfly.ledger

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LedgerDomainTest {
    private fun entry(id: String, draft: EntryDraft, deleted: Boolean = false) = LedgerEntry(id, 1, draft, deleted, draft.occurredAt, draft.occurredAt, "op-$id")
    @Test fun exactMoneyAndCurrencyScales() {
        assertEquals("1234567890123456.78", Money.normalize("1234567890123456.78", "HKD"))
        assertEquals("12", Money.normalize("12", "JPY"))
        assertEquals("1.234", Money.normalize("1.234", "KWD"))
        listOf("1.001" to "HKD", "1.1" to "JPY", "0" to "HKD", "-2" to "HKD", "2e3" to "HKD", "1" to "ZZZ").forEach { (amount, currency) ->
            assertThrows(IllegalArgumentException::class.java) { Money.normalize(amount, currency) }
        }
        assertThrows(IllegalArgumentException::class.java) { Money.normalize("1".repeat(65), "HKD") }
    }
    @Test fun composedFiltersUseLiteralUnicodeSearchAndDisplayZone() {
        val draft = EntryDraft("12.00", occurredAt = Instant.parse("2026-10-01T23:30:00Z"), merchant = "測試商店", notes = "100% _literal", categoryId = "food")
        val e = entry("one", draft)
        val filter = LedgerFilter(categoryId = "food", unassignedOnly = true, fromDate = LocalDate.parse("2026-10-02"),
            untilDateExclusive = LocalDate.parse("2026-10-03"), keyword = "% _literal", zone = ZoneId.of("Asia/Hong_Kong"))
        assertTrue(filter.matches(e))
        assertFalse(filter.copy(accountId = "card").matches(e))
        assertTrue(filter.copy(keyword = "商店").matches(e))
        assertFalse(filter.copy(untilDateExclusive = LocalDate.parse("2026-10-02")).matches(e))
    }
    @Test fun totalsKeepCurrenciesAndDirectionsSeparateAndExcludeDeleted() {
        val totals = ledgerTotals(listOf(entry("1", EntryDraft("0.10")), entry("2", EntryDraft("0.20")),
            entry("3", EntryDraft("9.00", kind = EntryKind.TRANSFER)), entry("4", EntryDraft("2.00", kind = EntryKind.REFUND)),
            entry("5", EntryDraft("3.00", "USD", EntryKind.INCOME)), entry("6", EntryDraft("200.00"), deleted = true)))
        val hkd = totals.first { it.currency == "HKD" }
        assertEquals(BigDecimal("0.30"), hkd.expense)
        assertEquals(BigDecimal("9.00"), hkd.transfer)
        assertEquals(BigDecimal("2.00"), hkd.refund)
        assertEquals(BigDecimal.ZERO, hkd.income)
        assertEquals(BigDecimal("3.00"), totals.first { it.currency == "USD" }.income)
    }
    @Test fun aliasPriorityConflictExplicitChoiceAndUnicodeBoundaries() {
        val alias = MerchantAlias("alias", "  Example   SHOP ", "Sample Store", 1)
        val rules = listOf(CategoryRule("a", RuleField.MERCHANT, LiteralMatch.EQUALS, "sample store", "food", 10),
            CategoryRule("b", RuleField.NOTES, LiteralMatch.WHOLE_TOKEN, "午餐", "other", 10))
        val draft = EntryDraft("1", merchant = "example shop", notes = "午餐！")
        assertTrue(Categorization.suggest(draft, listOf(alias), rules).needsChoice)
        assertEquals("food", Categorization.suggest(draft, listOf(alias), rules.map { if (it.id == "a") it.copy(priority = 11) else it }).categoryId)
        assertEquals("chosen", Categorization.suggest(draft.copy(categoryId = "chosen", categoryOrigin = CategoryOrigin.EXPLICIT), listOf(alias), rules).categoryId)
        assertFalse(Categorization.matches("午餐費", "午餐", LiteralMatch.WHOLE_TOKEN))
        assertFalse(Categorization.matches("a_tea", "tea", LiteralMatch.WHOLE_TOKEN))
        assertFalse(Categorization.matches("teacher", "tea", LiteralMatch.WHOLE_TOKEN))
        assertTrue(Categorization.matches("(tea)", "tea", LiteralMatch.WHOLE_TOKEN))
        assertTrue(Categorization.matches("paid .* literally", ".*", LiteralMatch.CONTAINS))
    }
}
