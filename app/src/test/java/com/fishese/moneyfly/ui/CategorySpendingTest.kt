package com.fishese.moneyfly.ui

import com.fishese.moneyfly.ledger.*
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CategorySpendingTest {
    private fun entry(id: String, amount: String, currency: String = "HKD", category: String? = "food", kind: EntryKind = EntryKind.EXPENSE, deleted: Boolean = false): LedgerEntry {
        val now = Instant.parse("2026-10-24T12:00:00Z")
        return LedgerEntry(id, 1, EntryDraft(amount = amount, currency = currency, categoryId = category, kind = kind, occurredAt = now), deleted, now, now, id)
    }

    @Test fun grossCategoryExpensesRemainExactAndSeparateByCurrency() {
        val rows = listOf(entry("a", "0.10"), entry("b", "0.20"), entry("c", "0.70", category = null),
            entry("usd", "9.99", "USD"), entry("income", "50.00", kind = EntryKind.INCOME),
            entry("refund", "2.00", kind = EntryKind.REFUND), entry("transfer", "99.00", kind = EntryKind.TRANSFER), entry("deleted", "100.00", deleted = true))
        val totals = categorySpending(rows)
        assertEquals(listOf("HKD", "USD"), totals.map { it.currency })
        val hkd = totals.first()
        assertEquals("1.00", hkd.total.toPlainString())
        assertEquals(listOf(null, "food"), hkd.categories.map { it.categoryId })
        assertEquals("0.30", hkd.categories.first { it.categoryId == "food" }.amount.toPlainString())
        assertEquals(2, hkd.categories.first { it.categoryId == "food" }.entries.size)
        assertEquals("30.0", hkd.percentage(hkd.categories.first { it.categoryId == "food" }))
        assertEquals("9.99", totals.last().total.toPlainString())
        assertEquals(setOf("a", "b", "c", "usd"), totals.flatMap { it.categories }.flatMap { it.entries }.map { it.id }.toSet())
    }

    @Test fun hugeDecimalValuesHaveFiniteRenderingRatiosWithoutDoubleSumOverflow() {
        val huge = BigDecimal.TEN.pow(1000).toPlainString()
        val total = categorySpending(listOf(entry("a", huge, category = "a"), entry("b", huge, category = "b"))).single()
        assertEquals(BigDecimal(huge).multiply(BigDecimal(2)), total.total)
        total.categories.forEach { assertEquals(0.5, total.fraction(it), 0.0); assertEquals("50.0", total.percentage(it)) }
        assertEquals("a", categoryAtAngle(total, 0.0)?.categoryId)
        assertEquals("b", categoryAtAngle(total, 180.0)?.categoryId)
        assertEquals("b", categoryAtAngle(total, -90.0)?.categoryId)
        assertNull(categoryAtAngle(total, Double.NaN))
    }

    @Test fun emptyZeroAndManyCategoriesStayDeterministic() {
        assertTrue(categorySpending(emptyList()).isEmpty())
        assertTrue(categorySpending(listOf(entry("zero", "0"), entry("bad", "invalid"))).isEmpty())
        val rows = (0 until 100).map { entry("id$it", "1.00", category = "category-${it.toString().padStart(3, '0')}") }
        val forward = categorySpending(rows).single()
        val reversed = categorySpending(rows.reversed()).single()
        assertEquals(100, forward.categories.size)
        assertEquals(forward.categories.map { it.categoryId }, reversed.categories.map { it.categoryId })
        assertEquals("100.00", forward.total.toPlainString())
        assertEquals("category-000", categoryAtAngle(forward, 0.0)?.categoryId)
        assertEquals("category-099", categoryAtAngle(forward, 359.999)?.categoryId)
    }
}
