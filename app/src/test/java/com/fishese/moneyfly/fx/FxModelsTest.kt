package com.fishese.moneyfly.fx

import com.fishese.moneyfly.ledger.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FxModelsTest {
    private val day = LocalDate.parse("2025-10-25")
    private fun entry(draft: EntryDraft) = LedgerEntry("synthetic", 1, draft, createdAt = draft.occurredAt, updatedAt = draft.occurredAt, lastOperationId = "synthetic")
    private fun draft() = EntryDraft("1250", "JPY", occurredAt = day.atStartOfDay(ZoneOffset.UTC).toInstant())

    @Test fun convertsExactlyByEntryDateAndKeepsOriginals() {
        val row = entry(draft())
        val quote = FxQuote(day, "JPY", "HKD", "0.051234", day, 1)
        val state = FxSnapshot(quotes = mapOf(quote.key to quote))
        assertEquals("64.04", state.convert(row, ZoneOffset.UTC)!!.amount.toPlainString())
        assertEquals("HKD", state.project(listOf(row)).single().draft.currency)
        assertEquals("JPY", row.draft.currency); assertEquals("1250", row.draft.amount)
        assertNull(state.convert(entry(draft().copy(occurredAt = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())), ZoneOffset.UTC))
    }

    @Test fun customRateWinsAndRemainsBoundToItsTargetCurrency() {
        val row = entry(draft().copy(exchangeRate = "0.06", exchangeRateCurrency = "HKD"))
        val quote = FxQuote(day, "JPY", "HKD", "0.05", day, 1)
        assertEquals("75.00", FxSnapshot(quotes = mapOf(quote.key to quote)).convert(row, ZoneOffset.UTC)!!.amount.toPlainString())
        assertTrue(FxSnapshot().convert(row, ZoneOffset.UTC)!!.manual)
        assertNull(FxSnapshot(currency = "USD").convert(row, ZoneOffset.UTC))
        assertEquals("1250", FxSnapshot(currency = "JPY").convert(row, ZoneOffset.UTC)!!.amount.toPlainString())
    }

    @Test fun missingRatesAreExcludedInsteadOfBeingSummedOrCountedAsZero() {
        val native = entry(draft().copy(currency = "HKD", amount = "12.00"))
        val foreign = entry(draft()).copy(id = "foreign")
        val report = FxSnapshot().project(listOf(native, foreign))
        assertEquals(1, report.size)
        assertEquals("12.00", ledgerTotals(report).single().expense.toPlainString())
    }

    @Test fun providerGapsUseOnlyPriorDailyRatesAndRejectFutureData() {
        val json = """[{"date":"2025-10-24","base":"JPY","quote":"HKD","rate":0.052},{"date":"2025-10-27","base":"JPY","quote":"HKD","rate":0.059}]"""
        val quote = parseRateResponse(json, "JPY", "HKD", setOf(day), 1).single()
        assertEquals(LocalDate.parse("2025-10-24"), quote.rateDate)
        assertEquals("0.052", quote.rate)
        assertTrue(parseRateResponse(json, "JPY", "HKD", setOf(LocalDate.parse("2025-10-23")), 1).isEmpty())
    }

    @Test fun validatesPositiveDecimalOverridesAndCompleteCurrencyBinding() {
        assertEquals("0.052", validatedEntryRate("0.05200", "HKD"))
        assertNull(validatedEntryRate(null, null))
        for ((rate, target) in listOf("0" to "HKD", "-1" to "HKD", "NaN" to "HKD", "1e3" to "HKD", "1" to "XYZ", "1" to null, null to "HKD")) {
            assertTrue("Invalid binding accepted", runCatching { validatedEntryRate(rate, target) }.isFailure)
        }
    }
}
