package com.fishese.moneyfly.fx

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fishese.moneyfly.ledger.*
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FxRepositoryTest {
    private lateinit var context: Context
    private var now = Instant.parse("2025-10-26T12:00:00Z").toEpochMilli()
    private fun row(id: String, currency: String, imported: Boolean = true, created: Instant = Instant.parse("2025-10-25T12:00:00Z")) =
        LedgerEntry(id, 1, EntryDraft("100", currency, occurredAt = Instant.parse("2025-10-25T12:00:00Z"), sourcePackage = if (imported) "example.synthetic" else null), createdAt = created, updatedAt = created, lastOperationId = id)
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("reporting-currency-and-rates", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private class Client(private val clock: () -> Long = { Instant.parse("2025-10-26T12:00:00Z").toEpochMilli() }) : FxRateClient {
        val calls = mutableListOf<Set<LocalDate>>()
        var fail = false
        override suspend fun fetch(source: String, target: String, dates: Set<LocalDate>): List<FxQuote> {
            calls += dates
            if (fail) error("Synthetic offline")
            return dates.map { FxQuote(it, source, target, "0.05", it, clock()) }
        }
    }

    @Test fun firstImportedEntrySetsDefaultOnceAndExplicitChoiceSurvivesRestart() = runBlocking {
        val repo = FxRepository(context, Client()) { now }
        assertEquals("HKD", repo.state.value.currency)
        repo.initialize(listOf(row("manual", "USD", false)))
        assertEquals("HKD", repo.state.value.currency)
        repo.initialize(listOf(row("first", "JPY")))
        assertEquals("JPY", repo.state.value.currency)
        repo.setCurrency("HKD"); repo.setSeparate(true)
        repo.initialize(listOf(row("later", "USD")))
        val reopened = FxRepository(context, Client()) { now }
        assertEquals("HKD", reopened.state.value.currency)
        assertTrue(reopened.state.value.separate)
    }

    @Test fun cachesHistoryRefreshesDailyAndManualRefreshRechecksHistoricalDates() = runBlocking {
        val client = Client { now }
        val repo = FxRepository(context, client) { now }
        repo.setCurrency("HKD")
        val rows = listOf(row("foreign", "JPY"))
        assertTrue(repo.refresh(rows)); assertEquals(1, client.calls.size)
        assertTrue(repo.refresh(rows)); assertEquals(1, client.calls.size)
        now += 86_400_000
        assertTrue(repo.refresh(rows)); assertEquals(setOf(LocalDate.parse("2025-10-27")), client.calls.last())
        assertTrue(repo.refresh(rows, force = true))
        assertEquals(setOf(LocalDate.parse("2025-10-25"), LocalDate.parse("2025-10-27")), client.calls.last())
        client.fail = true
        assertFalse(repo.refresh(rows, force = true)); assertTrue(repo.state.value.failed)
        val reopened = FxRepository(context, client) { now }
        assertEquals("5.00", reopened.state.value.convert(rows.single())!!.amount.toPlainString())
    }

    @Test fun yesterdayRateIsRecheckedOnceAfterItsPublishingDayEnds() = runBlocking {
        val client = Client { now }
        val repo = FxRepository(context, client) { now }
        repo.setCurrency("HKD")
        val date = Instant.ofEpochMilli(now)
        val todayEntry = row("today", "JPY").let { it.copy(draft = it.draft.copy(occurredAt = date)) }
        repo.refresh(listOf(todayEntry))
        now += 86_400_000
        repo.refresh(listOf(todayEntry))
        assertEquals(setOf(LocalDate.parse("2025-10-26"), LocalDate.parse("2025-10-27")), client.calls.last())
        repo.refresh(listOf(todayEntry))
        assertEquals(2, client.calls.size)
    }
}
