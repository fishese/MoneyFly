package com.fishese.moneyfly.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fishese.moneyfly.capture.*
import com.fishese.moneyfly.ledger.*
import com.fishese.moneyfly.recognition.*
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TemplateStoreTest {
    private lateinit var context: Context
    private lateinit var db: LedgerDatabase
    private lateinit var store: RoomLedgerStore
    private var now = Instant.parse("2026-10-01T12:00:00Z")
    private val filename = "template-writer-test.db"
    private val digest = ReceiptDigester { text -> Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(ByteArray(32) { 11 }, "HmacSHA256")); doFinal(text.toByteArray()).joinToString("") { "%02x".format(it) }
    } }
    private fun definition() = TemplateDefinition("format", "Synthetic changing payment", "invalid.synthetic",
        listOf(TemplateEngine.parsePattern(MessageField.BODY, "Paid {amount} at {merchant}.")), fixedCurrency = "HKD",
        fixedCurrencyEvidence = FixedCurrencyEvidence.USER_CONFIRMED_ASSUMPTION, enabled = true)
    private fun payload(key: String) = NotificationPayload("invalid.synthetic", "synthetic-user", key, now, now, "Synthetic title", "Paid 12.34 at SYNTHETIC SHOP.")
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(filename); reopen() }
    private fun reopen() { db = Room.databaseBuilder(context, LedgerDatabase::class.java, filename).addMigrations(LEDGER_MIGRATION_1_2, LEDGER_MIGRATION_2_3, LEDGER_MIGRATION_3_4, LEDGER_MIGRATION_4_5, LEDGER_MIGRATION_5_6, LEDGER_MIGRATION_6_7, LEDGER_MIGRATION_7_8).build(); store = RoomLedgerStore(db, digest, { now }) }
    @After fun cleanup() { db.close(); context.deleteDatabase(filename) }
    private suspend fun capture(key: String): InboxItem {
        val id = store.execute(LedgerCommand.CaptureNotification(payload(key))).entityId
        return store.inbox.first().single { it.id == id }
    }
    private suspend fun enableCapture() { assertTrue(store.execute(LedgerCommand.SetCaptureConsent(true, 0)).applied) }

    @Test fun clearMatchAutomaticallySavesOnceAndChangedMatchStillChecksForDuplicates() = runBlocking {
        enableCapture(); store.execute(LedgerCommand.PutTemplate(definition()))
        val command = LedgerCommand.CaptureNotification(payload("automatic"), "automatic-operation")
        assertTrue(store.execute(command).applied)
        assertTrue(store.execute(command).replayed)
        store.execute(LedgerCommand.CaptureNotification(payload("automatic")))
        assertEquals(1, store.entries.first().size)
        assertEquals(InboxStatus.SAVED, store.inbox.first().single().status)
        assertEquals("invalid.synthetic", store.entries.first().single().draft.sourcePackage)
        val duplicate = capture("another-key")
        assertEquals(InboxStatus.REVIEW_NEEDED, duplicate.status)
        assertTrue(duplicate.issues.any { it.contains("duplicate") })
        val changed = payload("automatic").copy(body = "Paid 23.45 at SYNTHETIC SHOP.")
        store.execute(LedgerCommand.CaptureNotification(changed))
        assertEquals(InboxStatus.SAVED, store.inbox.first().single { it.contentRevision == 2 }.status)
        assertEquals(2, store.entries.first().size)
        db.close(); reopen()
        assertEquals(2, store.entries.first().size)
        assertTrue(store.execute(command).replayed)
    }

    @Test fun recurringFareAutomaticallyRecordsAcrossDaysAndForEveningRideOnReusedSlot() = runBlocking {
        enableCapture(); store.execute(LedgerCommand.PutTemplate(definition()))
        assertEquals(InboxStatus.SAVED, capture("recurring-slot").status)
        now = now.plusSeconds(86400)
        assertEquals(InboxStatus.SAVED, capture("recurring-slot").status)
        now = now.plusSeconds(8 * 3600)
        assertEquals(InboxStatus.SAVED, capture("recurring-slot").status)
        assertEquals(3, store.entries.first().size)
        // Re-delivery of the exact snapshot stays idempotent despite the relaxed financial window.
        capture("recurring-slot")
        assertEquals(3, store.entries.first().size)
        now = now.plusSeconds(60)
        assertEquals(InboxStatus.REVIEW_NEEDED, capture("recurring-slot").status)
        assertEquals(3, store.entries.first().size)
    }

    @Test fun automaticallyRecordedTemplateUsesCategoryRule() = runBlocking {
        enableCapture()
        store.execute(LedgerCommand.PutCategory("synthetic-category", "Synthetic category"))
        store.execute(LedgerCommand.PutRule(CategoryRule("synthetic-rule", RuleField.MERCHANT, LiteralMatch.EQUALS, "SYNTHETIC SHOP", "synthetic-category")))
        store.execute(LedgerCommand.PutTemplate(definition()))
        assertEquals(InboxStatus.SAVED, capture("categorized-notification").status)
        val draft = store.entries.first().single().draft
        assertEquals("synthetic-category", draft.categoryId)
        assertEquals(CategoryOrigin.RULE, draft.categoryOrigin)
    }

    @Test fun automaticallyRecordedTemplateUsesAppAccountRule() = runBlocking {
        enableCapture()
        store.execute(LedgerCommand.PutAccount("synthetic-card", "Synthetic card", AccountKind.CARD))
        store.execute(LedgerCommand.PutAccountRule(AccountRule("synthetic-app-rule", null, "invalid.synthetic", "synthetic-card")))
        store.execute(LedgerCommand.PutTemplate(definition()))
        assertEquals(InboxStatus.SAVED, capture("account-notification").status)
        assertEquals("synthetic-card", store.entries.first().single().draft.accountId)
    }

    @Test fun applyExistingRecordsDailyFareButKeepsNearbyRepostForReview() = runBlocking {
        enableCapture(); capture("recurring-backfill")
        now = now.plusSeconds(86400); capture("recurring-backfill")
        now = now.plusSeconds(60); capture("recurring-backfill")
        store.execute(LedgerCommand.PutTemplate(definition().copy(enabled = false)))
        val result = store.execute(LedgerCommand.ApplyTemplateToPending("format", 1))
        assertEquals(TemplateApplyReport(3, 3, 2, 1, 0), result.templateReport)
        assertEquals(2, store.entries.first().size)
    }

    @Test fun deletedFareDoesNotBlockNextDaysRideButStillProtectsNearbyRepost() = runBlocking {
        enableCapture(); store.execute(LedgerCommand.PutTemplate(definition()))
        capture("deleted-recurring")
        val saved = store.entries.first().single()
        store.execute(LedgerCommand.Delete(saved.id, saved.revision))
        now = now.plusSeconds(60)
        assertEquals(InboxStatus.REVIEW_NEEDED, capture("deleted-recurring").status)
        now = now.plusSeconds(86400)
        assertEquals(InboxStatus.SAVED, capture("deleted-recurring").status)
        assertEquals(1, store.entries.first().size)
    }

    @Test fun missingMerchantFallbackAlsoAllowsDailyPurchases() = runBlocking {
        enableCapture()
        store.execute(LedgerCommand.PutTemplate(definition().copy(
            patterns = listOf(TemplateEngine.parsePattern(MessageField.BODY, "Paid {amount} at SYNTHETIC SHOP.")))))
        assertEquals(InboxStatus.SAVED, capture("unknown-merchant").status)
        now = now.plusSeconds(60)
        assertEquals(InboxStatus.REVIEW_NEEDED, capture("unknown-merchant").status)
        now = now.plusSeconds(86400)
        assertEquals(InboxStatus.SAVED, capture("unknown-merchant").status)
        assertEquals(2, store.entries.first().size)
    }

    @Test fun ignoresAndUnclearMatchesNeverAutomaticallySave() = runBlocking {
        enableCapture(); store.execute(LedgerCommand.PutTemplate(definition()))
        store.execute(LedgerCommand.PutIgnoreRule(IgnoreRule("ignore", "Synthetic ignore", "invalid.synthetic", MessageField.TITLE, "Synthetic*")))
        assertEquals(InboxStatus.IGNORED, capture("ignored").status)
        store.execute(LedgerCommand.SetIgnoreRuleEnabled("ignore", 1, false))
        for ((key, value) in listOf("summary" to payload("summary").copy(groupSummary = true),
            "truncated" to payload("truncated").copy(truncated = true), "redacted" to payload("redacted").copy(redacted = true))) {
            val result = store.execute(LedgerCommand.CaptureNotification(value))
            assertEquals(InboxStatus.REVIEW_NEEDED, store.inbox.first().single { it.id == result.entityId }.status)
        }
        assertTrue(store.entries.first().isEmpty())
        store.execute(LedgerCommand.PutTemplate(definition().copy(id = "conflicting", kind = EntryKind.INCOME)))
        assertEquals(InboxStatus.REVIEW_NEEDED, capture("conflict").status)
        assertTrue(store.entries.first().isEmpty())
    }

    @Test fun automaticEntryAndReceiptRollBackTogetherOnCommitFailure() = runBlocking {
        enableCapture(); store.execute(LedgerCommand.PutTemplate(definition()))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_capture_operation BEFORE INSERT ON operation_results BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        val command = LedgerCommand.CaptureNotification(payload("rollback"), "rollback-capture")
        try { store.execute(command); fail("Expected synthetic commit failure") } catch (_: android.database.sqlite.SQLiteException) { }
        assertTrue(store.entries.first().isEmpty()); assertTrue(store.inbox.first().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_capture_operation")
        assertTrue(store.execute(command).applied)
        assertEquals(1, store.entries.first().size)
        assertEquals(InboxStatus.SAVED, store.inbox.first().single().status)
    }

    @Test fun templateSaveAtomicallyEnrollsSourceAndRevisionsDoNotBookMoney() = runBlocking {
        val command = LedgerCommand.PutTemplate(definition(), operationId = "template-save")
        assertTrue(store.execute(command).applied); assertTrue(store.execute(command).replayed)
        assertTrue(store.sources.first().single().parsingEnabled)
        assertTrue(store.entries.first().isEmpty()); assertTrue(store.inbox.first().isEmpty())
        val initial = store.templates.first().single()
        assertEquals(1L, initial.revision)
        assertTrue(store.execute(LedgerCommand.PutTemplate(initial.copy(kind = EntryKind.INCOME), 1)).applied)
        assertEquals(2L, store.templates.first().single().revision)
        assertEquals(EntryKind.EXPENSE, TemplateCodec.decode(db.templateDao().historical("format", 1)!!.definition).kind)
        assertEquals(CommandStatus.CONFLICT, store.execute(LedgerCommand.PutTemplate(initial.copy(name = "Stale"), 1)).status)
    }
    @Test fun templateSaveBackfillsRetainedMatchesAndNeverRewritesSavedEntries() = runBlocking {
        enableCapture(); val old = capture("before-template")
        val second = store.execute(LedgerCommand.CaptureNotification(payload("another-old").copy(body = "Paid 23.45 at SYNTHETIC MARKET."))).entityId
        assertEquals(InboxStatus.UNRECOGNIZED, old.status)
        val command = LedgerCommand.PutTemplate(definition(), operationId = "backfill-template")
        assertTrue(store.execute(command).applied)
        assertTrue(store.execute(command).replayed)
        val saved = store.inbox.first().single { it.id == old.id }
        assertEquals(InboxStatus.SAVED, saved.status)
        assertEquals(InboxStatus.SAVED, store.inbox.first().single { it.id == second }.status)
        assertEquals(2, store.entries.first().size)
        assertEquals(1L, saved.matchTemplateRevision)
        assertEquals(CommandStatus.CONFLICT, store.execute(LedgerCommand.SaveCaptured(old.id, old.revision, saved.draft!!)).status)
        val current = store.templates.first().single()
        store.execute(LedgerCommand.PutTemplate(current.copy(kind = EntryKind.INCOME), current.revision))
        assertTrue(store.entries.first().all { it.draft.kind == EntryKind.EXPENSE })
        assertEquals(1L, store.inbox.first().single { it.id == old.id }.matchTemplateRevision)
        assertEquals(CommandStatus.CONFLICT, store.execute(LedgerCommand.RepreviewReceipt(saved.id, saved.revision)).status)
        db.close(); reopen()
        assertEquals(2, store.entries.first().size)
        assertTrue(store.execute(command).replayed)
    }
    @Test fun backfillIncludesHistoricalRevisionsSkipsIgnoredAndStillChecksDuplicatesWhenCapturePaused() = runBlocking {
        enableCapture()
        val first = capture("first")
        val duplicate = capture("duplicate")
        val ignored = capture("ignored")
        store.execute(LedgerCommand.IgnoreReceipt(ignored.id, ignored.revision))
        val earlierRevision = capture("changed")
        store.execute(LedgerCommand.CaptureNotification(payload("changed").copy(body = "Paid 45.67 at SYNTHETIC CHANGED.")))
        store.execute(LedgerCommand.SetCaptureConsent(false, 1))
        store.execute(LedgerCommand.PutTemplate(definition()))
        assertEquals(2, store.entries.first().size)
        val items = store.inbox.first()
        assertEquals(1, items.count { it.id in setOf(first.id, duplicate.id, earlierRevision.id) && it.status == InboxStatus.SAVED })
        assertEquals(2, items.count { it.id in setOf(first.id, duplicate.id, earlierRevision.id) && it.status == InboxStatus.REVIEW_NEEDED })
        assertEquals(InboxStatus.IGNORED, items.single { it.id == ignored.id }.status)
        val changedEntry = items.single { it.payload?.body?.contains("CHANGED") == true }
        assertTrue(changedEntry.requiresRevisionDecision)
        assertEquals(InboxStatus.SAVED, changedEntry.status)
    }

    @Test fun explicitApplyRecoversOlderPurchasesEvenWhenLatestReusedNotificationDoesNotMatch() = runBlocking {
        enableCapture()
        capture("reused-slot")
        now = now.plusSeconds(600)
        store.execute(LedgerCommand.CaptureNotification(payload("reused-slot").copy(body = "Paid 21.56 at SYNTHETIC MARKET.")))
        now = now.plusSeconds(600)
        store.execute(LedgerCommand.CaptureNotification(payload("reused-slot").copy(body = "Synthetic non-purchase status")))
        store.execute(LedgerCommand.SetCaptureConsent(false, 1))
        store.execute(LedgerCommand.PutTemplate(definition().copy(enabled = false)))
        val command = LedgerCommand.ApplyTemplateToPending("format", 1, "historical-apply")
        val result = store.execute(command)
        assertTrue(result.applied)
        assertEquals(TemplateApplyReport(3, 2, 2, 0, 1), result.templateReport)
        assertEquals(setOf("12.34", "21.56"), store.entries.first().map { it.draft.amount }.toSet())
        assertFalse(store.templates.first().single().enabled)
        db.close(); reopen()
        val retry = store.execute(command)
        assertTrue(retry.replayed); assertEquals(result.templateReport, retry.templateReport)
        assertEquals(2, store.entries.first().size)
    }

    @Test fun historicalUpdatesOfOnePurchaseDoNotBookTwice() = runBlocking {
        enableCapture(); capture("same-purchase")
        now = now.plusSeconds(60)
        store.execute(LedgerCommand.CaptureNotification(payload("same-purchase").copy(subText = "Synthetic status changed")))
        store.execute(LedgerCommand.PutTemplate(definition().copy(enabled = false)))
        val result = store.execute(LedgerCommand.ApplyTemplateToPending("format", 1))
        assertEquals(TemplateApplyReport(2, 2, 1, 1, 0), result.templateReport)
        assertEquals(1, store.entries.first().size)
        assertTrue(store.inbox.first().single { it.status == InboxStatus.REVIEW_NEEDED }.issues.any { it.contains("duplicate") })
        val saved = store.entries.first().single()
        store.execute(LedgerCommand.Delete(saved.id, saved.revision))
        val afterDelete = store.execute(LedgerCommand.ApplyTemplateToPending("format", 1))
        assertEquals(0, afterDelete.templateReport!!.recorded)
        assertTrue(store.entries.first().isEmpty())
        assertTrue(store.inbox.first().single { it.status == InboxStatus.REVIEW_NEEDED }.issues.any { it.contains("deleted") })
    }

    @Test fun applyingUnrelatedTemplatePreservesUsefulPreviewAndReportsNoMatch() = runBlocking {
        enableCapture(); store.execute(LedgerCommand.PutTemplate(definition()))
        capture("saved"); val pending = capture("duplicate-for-review")
        val other = definition().copy(id = "other", enabled = false,
            patterns = listOf(TemplateEngine.parsePattern(MessageField.BODY, "Received {amount} from {merchant}.")))
        store.execute(LedgerCommand.PutTemplate(other))
        val result = store.execute(LedgerCommand.ApplyTemplateToPending("other", 1))
        assertEquals(TemplateApplyReport(1, 0, 0, 0, 1), result.templateReport)
        val preserved = store.inbox.first().single { it.id == pending.id }
        assertEquals(pending.revision, preserved.revision)
        assertEquals(pending.draft, preserved.draft)
        assertEquals("format", preserved.matchTemplateId)
        assertEquals(1, store.entries.first().size)
    }

    @Test fun enrollingIdenticalTemplateReusesItAndRestoresExplicitParsingEnrollment() = runBlocking {
        store.execute(LedgerCommand.PutTemplate(definition()))
        val source = store.sources.first().single()
        store.execute(LedgerCommand.SetParsingSource(source.sourcePackage, false, source.revision))
        val reused = store.execute(LedgerCommand.PutTemplate(definition().copy(id = "duplicate-copy", name = "Another display name")))
        assertEquals("format", reused.entityId)
        assertEquals(1, store.templates.first().size)
        assertTrue(store.sources.first().single().parsingEnabled)
        assertFalse(definition().hasSameContent(definition().copy(kind = EntryKind.INCOME)))
    }
    @Test fun savedAssumptionKeepsCompactTemplateVersionAfterRawAndDerivedDraftExpire() = runBlocking {
        enableCapture(); store.execute(LedgerCommand.PutTemplate(definition()))
        val ready = capture("saved-assumption")
        assertTrue(ready.issues.any { it.contains("assumption") })
        assertEquals(InboxStatus.SAVED, ready.status)
        now = now.plus(Duration.ofDays(8))
        val hiddenRaw = store.inbox.first().single()
        assertTrue(hiddenRaw.contentExpired); assertNull(hiddenRaw.draft); assertTrue(hiddenRaw.issues.isEmpty())
        assertEquals("format", hiddenRaw.matchTemplateId); assertEquals(1L, hiddenRaw.matchTemplateRevision)
        store.execute(LedgerCommand.PruneInbox())
        val provenance = db.templateDao().recognitionOnce(ready.id)!!
        assertNull(provenance.result); assertEquals("format", provenance.templateId); assertEquals(1L, provenance.templateRevision)
        assertTrue(db.inboxDao().allRaw().isEmpty())
        assertEquals(FixedCurrencyEvidence.USER_CONFIRMED_ASSUMPTION, TemplateCodec.decode(db.templateDao().historical("format", 1)!!.definition).fixedCurrencyEvidence)
        assertEquals("12.34", store.entries.first().single().draft.amount)
    }
    @Test fun disableAndDeleteAffectFutureCapturesWhileHistoricalRevisionsRemain() = runBlocking {
        enableCapture(); store.execute(LedgerCommand.PutTemplate(definition()))
        val ready = capture("ready")
        assertTrue(store.execute(LedgerCommand.SetTemplateEnabled("format", 1, false)).applied)
        assertEquals(InboxStatus.UNRECOGNIZED, capture("paused").status)
        assertEquals(InboxStatus.SAVED, store.inbox.first().single { it.id == ready.id }.status)
        assertTrue(store.execute(LedgerCommand.DeleteTemplate("format", 2)).applied)
        assertTrue(store.templates.first().isEmpty()); assertNotNull(db.templateDao().historical("format", 1))
        assertEquals(CommandStatus.NOT_FOUND, store.execute(LedgerCommand.SetTemplateEnabled("format", 3, true)).status)
        assertEquals(CommandStatus.INVALID, store.execute(LedgerCommand.PutTemplate(definition())).status)
        assertEquals(CommandStatus.CONFLICT, store.execute(LedgerCommand.RepreviewReceipt(ready.id, ready.revision)).status)
        assertEquals(InboxStatus.SAVED, store.inbox.first().single { it.id == ready.id }.status)
    }
    @Test fun backfillKeepsSavedEntryWhenDerivedDraftExceedsRawByteBudget() = runBlocking {
        enableCapture(); val old = capture("unrecognized")
        val rawBytes = db.inboxDao().allRaw().single().byteCount
        store = RoomLedgerStore(db, digest, { now }, RawRetention(maxBytes = rawBytes + 1))
        store.execute(LedgerCommand.PutTemplate(definition()))
        assertEquals(CommandStatus.NOT_FOUND, store.execute(LedgerCommand.RepreviewReceipt(old.id, old.revision)).status)
        assertTrue(db.inboxDao().allRaw().isEmpty()); assertNull(db.templateDao().recognitionOnce(old.id)!!.result)
        assertEquals(1, store.entries.first().size)
    }
    @Test fun templateAndSourceRollBackTogetherWhenOperationCommitFails() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_template_operation BEFORE INSERT ON operation_results BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        val command = LedgerCommand.PutTemplate(definition(), operationId = "template-operation")
        var failed = false
        try { store.execute(command) } catch (_: Exception) { failed = true }
        assertTrue(failed); assertTrue(store.templates.first().isEmpty()); assertTrue(store.sources.first().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_template_operation")
        assertTrue(store.execute(command).applied)
        val encoded = db.templateDao().latest("format")!!.definition
        assertFalse(encoded.contains("synthetic-0")); assertFalse(encoded.contains("A SYNTHETIC SHOP"))
    }
    @Test fun backfillAndTemplateRollBackTogetherAndRetryBooksRetainedMatchesOnce() = runBlocking {
        enableCapture(); val old = capture("retained-rollback")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_backfill_operation BEFORE INSERT ON operation_results BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        val command = LedgerCommand.PutTemplate(definition(), operationId = "backfill-rollback")
        try { store.execute(command); fail("Expected commit failure") } catch (_: android.database.sqlite.SQLiteException) { }
        assertTrue(store.entries.first().isEmpty()); assertTrue(store.templates.first().isEmpty())
        assertEquals(InboxStatus.UNRECOGNIZED, store.inbox.first().single().status)
        assertEquals(old.revision, store.inbox.first().single().revision)
        assertFalse(store.sources.first().single().parsingEnabled)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_backfill_operation")
        assertTrue(store.execute(command).applied); assertTrue(store.execute(command).replayed)
        assertEquals(InboxStatus.SAVED, store.inbox.first().single().status)
        assertEquals(1, store.entries.first().size)
    }
    @Test fun versionTwoUpgradePreservesManualEntriesCapturedReceiptsAndOperationReplay() = runBlocking {
        enableCapture()
        val manual = LedgerCommand.Save(EntryDraft("2.00", occurredAt = now), "manual", "manual-save")
        store.execute(manual)
        val receipt = capture("retained")
        db.close()
        // The v2 tables are unchanged; remove only v3 additions to form a v2 baseline.
        context.openOrCreateDatabase(filename, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("DROP TABLE template_revisions"); old.execSQL("DROP TABLE receipt_recognition")
            old.execSQL("DROP TABLE ignore_rule_revisions"); old.execSQL("DROP TABLE account_rules"); old.execSQL("ALTER TABLE ledger_entries DROP COLUMN exchangeRate"); old.execSQL("ALTER TABLE ledger_entries DROP COLUMN exchangeRateCurrency"); old.version = 2
        }
        reopen()
        assertEquals("manual", store.entries.first().single().id)
        assertTrue(store.execute(manual).replayed)
        assertEquals(receipt.id, store.inbox.first().single().id)
        assertTrue(store.captureSettings.first().consent)
        assertTrue(store.execute(LedgerCommand.PutTemplate(definition())).applied)
        assertEquals(InboxStatus.REVIEW_NEEDED, capture("new-ready").status)
        assertEquals(2, store.entries.first().size)
    }
}
