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
    private fun reopen() { db = Room.databaseBuilder(context, LedgerDatabase::class.java, filename).addMigrations(LEDGER_MIGRATION_1_2, LEDGER_MIGRATION_2_3, LEDGER_MIGRATION_3_4, LEDGER_MIGRATION_4_5).build(); store = RoomLedgerStore(db, digest, { now }) }
    @After fun cleanup() { db.close(); context.deleteDatabase(filename) }
    private suspend fun capture(key: String): InboxItem {
        val id = store.execute(LedgerCommand.CaptureNotification(payload(key))).entityId
        return store.inbox.first().single { it.id == id }
    }
    private suspend fun enableCapture() { assertTrue(store.execute(LedgerCommand.SetCaptureConsent(true, 0)).applied) }

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
    @Test fun recognitionIsFrozenUntilExplicitRepreviewAndNeverRewritesSavedEntry() = runBlocking {
        enableCapture(); val old = capture("before-template")
        assertEquals(InboxStatus.UNRECOGNIZED, old.status)
        store.execute(LedgerCommand.PutTemplate(definition()))
        assertEquals(InboxStatus.UNRECOGNIZED, store.inbox.first().single().status)
        val ready = capture("after-template")
        assertEquals(InboxStatus.READY, ready.status); assertEquals(1L, ready.matchTemplateRevision)
        assertEquals("SYNTHETIC SHOP", ready.draft!!.merchant)
        val saved = store.execute(LedgerCommand.SaveCaptured(ready.id, ready.revision, ready.draft!!)).entry!!
        val current = store.templates.first().single()
        store.execute(LedgerCommand.PutTemplate(current.copy(kind = EntryKind.INCOME), current.revision))
        assertEquals(EntryKind.EXPENSE, store.entries.first().single().draft.kind)
        assertEquals(CommandStatus.CONFLICT, store.execute(LedgerCommand.RepreviewReceipt(ready.id, ready.revision + 1)).status)
        val repreview = store.execute(LedgerCommand.RepreviewReceipt(old.id, old.revision))
        assertTrue(repreview.applied)
        val renewed = store.inbox.first().single { it.id == old.id }
        assertEquals(2L, renewed.revision); assertEquals(2L, renewed.matchTemplateRevision)
        assertEquals(EntryKind.INCOME, renewed.draft!!.kind)
        assertEquals(saved.id, store.entries.first().single().id)
        assertEquals(CommandStatus.CONFLICT, store.execute(LedgerCommand.RepreviewReceipt(old.id, old.revision)).status)
    }
    @Test fun savedAssumptionKeepsCompactTemplateVersionAfterRawAndDerivedDraftExpire() = runBlocking {
        enableCapture(); store.execute(LedgerCommand.PutTemplate(definition()))
        val ready = capture("saved-assumption")
        assertTrue(ready.issues.any { it.contains("assumption") })
        store.execute(LedgerCommand.SaveCaptured(ready.id, ready.revision, ready.draft!!))
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
        assertEquals(InboxStatus.READY, store.inbox.first().single { it.id == ready.id }.status)
        assertTrue(store.execute(LedgerCommand.DeleteTemplate("format", 2)).applied)
        assertTrue(store.templates.first().isEmpty()); assertNotNull(db.templateDao().historical("format", 1))
        assertEquals(CommandStatus.NOT_FOUND, store.execute(LedgerCommand.SetTemplateEnabled("format", 3, true)).status)
        assertEquals(CommandStatus.INVALID, store.execute(LedgerCommand.PutTemplate(definition())).status)
        assertTrue(store.execute(LedgerCommand.RepreviewReceipt(ready.id, ready.revision)).applied)
        assertEquals(InboxStatus.UNRECOGNIZED, store.inbox.first().single { it.id == ready.id }.status)
    }
    @Test fun repreviewPrunesImmediatelyWhenDerivedDraftWouldExceedRawByteBudget() = runBlocking {
        enableCapture(); val old = capture("unrecognized")
        val rawBytes = db.inboxDao().allRaw().single().byteCount
        store = RoomLedgerStore(db, digest, { now }, RawRetention(maxBytes = rawBytes + 1))
        store.execute(LedgerCommand.PutTemplate(definition()))
        assertTrue(store.execute(LedgerCommand.RepreviewReceipt(old.id, old.revision)).applied)
        assertTrue(db.inboxDao().allRaw().isEmpty()); assertNull(db.templateDao().recognitionOnce(old.id))
        assertTrue(store.entries.first().isEmpty())
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
    @Test fun versionTwoUpgradePreservesManualEntriesCapturedReceiptsAndOperationReplay() = runBlocking {
        enableCapture()
        val manual = LedgerCommand.Save(EntryDraft("2.00", occurredAt = now), "manual", "manual-save")
        store.execute(manual)
        val receipt = capture("retained")
        db.close()
        // The v2 tables are unchanged; remove only v3 additions to form a v2 baseline.
        context.openOrCreateDatabase(filename, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("DROP TABLE template_revisions"); old.execSQL("DROP TABLE receipt_recognition")
            old.execSQL("DROP TABLE ignore_rule_revisions"); old.version = 2
        }
        reopen()
        assertEquals("manual", store.entries.first().single().id)
        assertTrue(store.execute(manual).replayed)
        assertEquals(receipt.id, store.inbox.first().single().id)
        assertTrue(store.captureSettings.first().consent)
        assertTrue(store.execute(LedgerCommand.PutTemplate(definition())).applied)
        assertEquals(InboxStatus.READY, capture("new-ready").status)
    }
}
