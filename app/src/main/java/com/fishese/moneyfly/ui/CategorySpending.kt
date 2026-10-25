package com.fishese.moneyfly.ui

import com.fishese.moneyfly.ledger.EntryKind
import com.fishese.moneyfly.ledger.LedgerEntry
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

internal data class CategoryExpense(val categoryId: String?, val amount: BigDecimal, val entries: List<LedgerEntry>)
internal data class CurrencyCategorySpending(val currency: String, val total: BigDecimal, val categories: List<CategoryExpense>) {
    fun fraction(category: CategoryExpense): Double = if (total.signum() <= 0) 0.0 else category.amount.divide(total, MathContext.DECIMAL64).toDouble().coerceIn(0.0, 1.0)
    fun percentage(category: CategoryExpense): String = if (total.signum() <= 0) "0.0" else category.amount.multiply(BigDecimal(100)).divide(total, 1, RoundingMode.HALF_UP).toPlainString()
}

/** Gross saved expenses only. Every addition stays decimal and currencies never mix. */
internal fun categorySpending(entries: List<LedgerEntry>): List<CurrencyCategorySpending> = entries
    .filter { !it.deleted && it.draft.kind == EntryKind.EXPENSE }
    .mapNotNull { entry -> entry.draft.amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.let { entry to it } }
    .groupBy { it.first.draft.currency }.toSortedMap().map { (currency, rows) ->
        val categories = rows.groupBy { it.first.draft.categoryId }.map { (id, values) ->
            CategoryExpense(id, values.fold(BigDecimal.ZERO) { sum, row -> sum + row.second }, values.map { it.first }.sortedByDescending { it.draft.occurredAt })
        }.sortedWith(compareByDescending<CategoryExpense> { it.amount }.thenBy { it.categoryId.orEmpty() })
        CurrencyCategorySpending(currency, categories.fold(BigDecimal.ZERO) { sum, category -> sum + category.amount }, categories)
    }

/** Clockwise angle from twelve o'clock; shares the same ordering as the rendered chart. */
internal fun categoryAtAngle(spending: CurrencyCategorySpending, angle: Double): CategoryExpense? {
    if (!angle.isFinite() || spending.total.signum() <= 0) return null
    val normalized = ((angle % 360.0) + 360.0) % 360.0
    var end = 0.0
    return spending.categories.firstOrNull { category -> end += spending.fraction(category) * 360.0; normalized < end }
        ?: spending.categories.lastOrNull()
}
