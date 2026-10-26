package com.fishese.moneyfly.fx

import com.fishese.moneyfly.ledger.LedgerEntry
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency

fun supportedCurrency(code: String): Boolean = code.matches(Regex("[A-Z]{3}")) &&
    runCatching { Currency.getInstance(code).defaultFractionDigits >= 0 }.getOrDefault(false)

/** Direction is always: reporting currency units for ONE original currency unit. */
fun validatedEntryRate(rate: String?, target: String?): String? {
    require((rate == null) == (target == null)) { "An exchange rate needs its target currency." }
    if (rate == null) return null
    require(target != null && supportedCurrency(target)) { "Choose a supported target currency." }
    require(rate.length <= 64 && rate.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) { "Enter a positive decimal exchange rate." }
    val value = BigDecimal(rate)
    require(value.signum() > 0 && value.precision() <= 30 && value.stripTrailingZeros().scale() <= 18) { "Exchange rate must be positive and have at most 18 decimal places." }
    return value.stripTrailingZeros().toPlainString()
}

data class FxQuote(val date: LocalDate, val source: String, val target: String, val rate: String,
    val rateDate: LocalDate, val fetchedAt: Long) {
    val key: String get() = "$date:$source:$target"
}

data class FxConversion(val amount: BigDecimal, val rate: BigDecimal, val rateDate: LocalDate, val manual: Boolean)

data class FxSnapshot(
    val currency: String = "HKD", val initialized: Boolean = false, val separate: Boolean = false,
    val quotes: Map<String, FxQuote> = emptyMap(), val refreshing: Boolean = false,
    val lastChecked: Long? = null, val failed: Boolean = false,
) {
    fun convert(entry: LedgerEntry, zone: ZoneId = ZoneId.systemDefault()): FxConversion? {
        val draft = entry.draft
        val date = draft.occurredAt.atZone(zone).toLocalDate()
        val manual = draft.exchangeRate != null && draft.exchangeRateCurrency == currency && draft.currency != currency
        val quote = quotes["$date:${draft.currency}:$currency"]
        val rate = when {
            draft.currency == currency -> BigDecimal.ONE
            manual -> draft.exchangeRate?.toBigDecimalOrNull()
            else -> quote?.takeIf { it.rateDate <= date }?.rate?.toBigDecimalOrNull()
        }?.takeIf { it.signum() > 0 } ?: return null
        val amount = draft.amount.toBigDecimalOrNull()?.multiply(rate)
            ?.setScale(Currency.getInstance(currency).defaultFractionDigits, RoundingMode.HALF_UP) ?: return null
        return FxConversion(amount, rate, if (manual || draft.currency == currency) date else quote!!.rateDate, manual)
    }

    /** Read-only analytics copies. Callers must keep original entries for editing and currency filters. */
    fun project(entries: List<LedgerEntry>): List<LedgerEntry> = entries.mapNotNull { entry ->
        convert(entry)?.let { entry.copy(draft = entry.draft.copy(amount = it.amount.toPlainString(), currency = currency, exchangeRate = null, exchangeRateCurrency = null)) }
    }
}
