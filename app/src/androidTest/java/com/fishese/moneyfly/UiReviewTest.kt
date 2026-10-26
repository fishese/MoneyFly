package com.fishese.moneyfly

import android.graphics.Bitmap
import android.content.Intent
import android.os.Build
import org.junit.Assume.assumeTrue
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fishese.moneyfly.capture.NotificationPayload
import com.fishese.moneyfly.ledger.*
import com.fishese.moneyfly.recognition.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import kotlinx.coroutines.flow.first
import org.junit.runner.RunWith

/** Run ONLY on the isolated moneyfly-ui-review AVD. All contents are invented. */
@RunWith(AndroidJUnit4::class)
class UiReviewTest {
    @Test fun captureInventedJournalScreens() {
        assumeTrue("Synthetic review is emulator-only", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MoneyFlyApplication
        val store = app.ledgerStore
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val posted = today.atTime(12, 30).atZone(zone).toInstant()
        runBlocking {
            for ((id, name) in listOf("food" to "Food & drinks", "groceries" to "Groceries", "transport" to "Transport", "shopping" to "Shopping")) {
                store.execute(LedgerCommand.PutCategory("ui-$id", name, operationId = "ui-category-$id"))
            }
            store.execute(LedgerCommand.PutAccount("ui-card", "Everyday card", AccountKind.CARD, operationId = "ui-account"))
            data class Row(val id: String, val merchant: String, val amount: String, val category: String, val days: Long, val currency: String = "HKD", val kind: EntryKind = EntryKind.EXPENSE)
            val rows = listOf(
                Row("coffee", "SAMPLE Coffee", "32.00", "food", 0),
                Row("lunch", "SAMPLE Noodle House", "68.50", "food", 0),
                Row("train", "SAMPLE Metro", "12.40", "transport", 0),
                Row("market", "SAMPLE Market", "186.20", "groceries", 1),
                Row("bakery", "SAMPLE Bakery", "26.00", "food", 1),
                Row("books", "SAMPLE Bookshop", "118.00", "shopping", 2),
                Row("usd", "SAMPLE Online Store", "19.90", "shopping", 2, "USD"),
                Row("income", "SAMPLE Income", "1200.00", "", 3, kind = EntryKind.INCOME),
            )
            for ((index, row) in rows.withIndex()) {
                val date = today.minusDays(row.days).atTime(12, 30 - index).atZone(zone).toInstant()
                store.execute(LedgerCommand.Save(EntryDraft(row.amount, row.currency, row.kind, date, row.merchant,
                    notes = if (row.id == "lunch") "Invented lunch with friends" else "",
                    categoryId = row.category.takeIf(String::isNotBlank)?.let { "ui-$it" }, accountId = "ui-card"),
                    entryId = "ui-${row.id}", operationId = "ui-save-${row.id}"))
            }
            store.execute(LedgerCommand.SetCaptureConsent(true, 0, "ui-consent-enable"))
            val oldId = store.execute(LedgerCommand.CaptureNotification(NotificationPayload("example.synthetic.receipts", "synthetic", "ui-old", posted.minusSeconds(120), posted.minusSeconds(120),
                title = "SAMPLE earlier purchase", body = "Paid HKD 43.70 at SAMPLE CORNER SHOP."), "ui-old-capture")).entityId
            val template = TemplateDefinition("ui-format", "Invented purchase layout", "example.synthetic.receipts",
                listOf(FieldPattern(MessageField.BODY, listOf(PatternSegment.Literal("Paid HKD "), PatternSegment.Slot(SlotName.AMOUNT, 32), PatternSegment.Literal(" at "), PatternSegment.Slot(SlotName.MERCHANT, 256), PatternSegment.Literal(".")))),
                fixedCurrency = "HKD", fixedCurrencyEvidence = FixedCurrencyEvidence.EXPLICIT_MATCHED_LITERAL, enabled = true)
            store.execute(LedgerCommand.PutTemplate(template, operationId = "ui-template"))
            assertEquals(com.fishese.moneyfly.capture.InboxStatus.SAVED, store.inbox.first().single { it.id == oldId }.status)
            val automatic = LedgerCommand.CaptureNotification(NotificationPayload("example.synthetic.receipts", "synthetic", "ui-ready", posted, posted,
                title = "SAMPLE purchase", body = "Paid HKD 25.40 at SAMPLE BAKERY."), "ui-ready-capture")
            val receiptId = store.execute(automatic).entityId
            assertTrue(store.execute(automatic).replayed)
            assertEquals(com.fishese.moneyfly.capture.InboxStatus.SAVED, store.inbox.first().single { it.id == receiptId }.status)
            assertEquals(1, store.entries.first().count { it.draft.merchant == "SAMPLE BAKERY" })
            store.execute(LedgerCommand.CaptureNotification(NotificationPayload("example.synthetic.chat", "synthetic", "ui-summary", posted, posted,
                title = "SAMPLE Chat", body = "6 messages from 3 chats", groupSummary = true), "ui-summary-capture"))
            store.execute(LedgerCommand.CaptureNotification(NotificationPayload("example.synthetic.receipts", "synthetic", "ui-unknown", posted.minusSeconds(60), posted.minusSeconds(60),
                title = "SAMPLE receipt details", body = "An invented unsupported notification. Record it manually if it is an expense."), "ui-unknown-capture"))
            store.execute(LedgerCommand.SetCaptureConsent(false, 1, "ui-consent-pause"))
        }
        InstrumentationRegistry.getInstrumentation().startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        capture("transactions")
    }

    private fun capture(name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(300)
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
