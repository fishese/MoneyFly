package com.fishese.moneyfly.workflow

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fishese.moneyfly.capture.*
import com.fishese.moneyfly.ledger.*
import com.fishese.moneyfly.recognition.*
import com.fishese.moneyfly.storage.*
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Invented full workflow. This establishes no real provider's notification format. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationWorkflowTest {
    private val now = Instant.parse("2026-10-25T03:00:00Z")
    private val digester = ReceiptDigester { input ->
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32) { 23 }, "HmacSHA256"))
        mac.doFinal(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test fun missedReceiptThenTeachFormatThenSaveNextVariedMerchantExactlyOnce() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val store = RoomLedgerStore(database, digester, { now })
            assertTrue(store.execute(LedgerCommand.SetCaptureConsent(true, 0)).applied)
            fun payload(key: String, amount: String, merchant: String) = NotificationPayload(
                "example.synthetic.payments", "synthetic-user", key, now, now,
                title = "Synthetic completed purchase",
                body = "Paid HKD $amount at $merchant.",
            )
            store.execute(LedgerCommand.CaptureNotification(payload("first", "28.50", "SAMPLE CAFE")))
            val missed = store.inbox.first().single()
            assertEquals(InboxStatus.UNRECOGNIZED, missed.status)
            assertTrue(store.execute(LedgerCommand.SaveCaptured(missed.id, missed.revision,
                EntryDraft("28.50", merchant = "SAMPLE CAFE", notes = "Synthetic manual correction", occurredAt = now))).applied)

            val template = TemplateDefinition(
                id = "synthetic-format", name = "Invented payment layout",
                sourcePackage = "example.synthetic.payments",
                patterns = listOf(FieldPattern(MessageField.BODY, listOf(
                    PatternSegment.Literal("Paid HKD "), PatternSegment.Slot(SlotName.AMOUNT, 32),
                    PatternSegment.Literal(" at "), PatternSegment.Slot(SlotName.MERCHANT, 256),
                    PatternSegment.Literal("."),
                ))),
                predicates = listOf(TemplatePredicate(MessageField.TITLE, PredicateOperation.EQUALS, "Synthetic completed purchase")),
                fixedCurrency = "HKD", fixedCurrencyEvidence = FixedCurrencyEvidence.EXPLICIT_MATCHED_LITERAL,
                enabled = true,
            )
            assertTrue(store.execute(LedgerCommand.PutTemplate(template)).applied)
            // Teaching is independent: it does not change or add a booked amount.
            assertEquals(1, store.entries.first().size)
            val capture = LedgerCommand.CaptureNotification(payload("second", "137.20", "SAMPLE MARKET"), "synthetic-auto-capture")
            val receiptId = store.execute(capture).entityId
            val ready = store.inbox.first().single { it.id == receiptId }
            assertEquals(InboxStatus.SAVED, ready.status)
            assertEquals("137.20", ready.draft!!.amount)
            assertEquals("SAMPLE MARKET", ready.draft!!.merchant)
            assertNull(ready.draft!!.accountId)
            assertEquals(TimeOrigin.NOTIFICATION_POSTED, ready.draft!!.timeOrigin)
            assertEquals(TimePrecision.UNKNOWN, ready.draft!!.timePrecision)
            assertTrue(store.execute(capture).replayed)
            assertEquals(CommandStatus.CONFLICT, store.execute(LedgerCommand.SaveCaptured(ready.id, ready.revision, ready.draft!!)).status)
            assertEquals(2, store.entries.first().size)
            val filtered = store.entries.first().filter(LedgerFilter(keyword = "market", unassignedOnly = true)::matches)
            assertEquals(1, filtered.size)
            assertEquals("137.20", ledgerTotals(filtered).single().expense.toPlainString())

            val savedTemplate = store.templates.first().single()
            assertTrue(store.execute(LedgerCommand.SetTemplateEnabled(savedTemplate.id, savedTemplate.revision, false)).applied)
            store.execute(LedgerCommand.CaptureNotification(payload("third", "8.10", "SAMPLE BAKERY")))
            assertEquals(1, store.inbox.first().count { it.status == InboxStatus.UNRECOGNIZED })
            assertEquals(2, store.entries.first().size)
        } finally { database.close() }
    }
}
