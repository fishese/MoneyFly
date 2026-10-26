package com.fishese.moneyfly.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fishese.moneyfly.capture.*
import com.fishese.moneyfly.ledger.*
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
class InboxStoreTest {
    private lateinit var context: Context
    private lateinit var db: LedgerDatabase
    private lateinit var store: RoomLedgerStore
    private var now = Instant.parse("2026-10-01T12:00:00Z")
    private val name = "inbox-writer-test.db"
    private val digest = ReceiptDigester { value -> Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(ByteArray(32) { 3 }, "HmacSHA256")); doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
    } }
    private val draft = EntryDraft("12.34", occurredAt = Instant.parse("2026-10-01T12:00:00Z"), notes = "Synthetic user correction")
    private fun payload(key: String = "synthetic-key", source: String = "invalid.synthetic") = NotificationPayload(source, "synthetic-user", key, now, now,
        "Synthetic title", "Invented notification content", bigText = "Invented expanded content", textLines = listOf("Invented line"))
    @Before fun open() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(name); reopen() }
    private fun reopen(retention: RawRetention = RawRetention()) {
        db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).addMigrations(LEDGER_MIGRATION_1_2, LEDGER_MIGRATION_2_3, LEDGER_MIGRATION_3_4, LEDGER_MIGRATION_4_5, LEDGER_MIGRATION_5_6).build()
        store = RoomLedgerStore(db, digest, { now }, retention)
    }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    private suspend fun enable() { assertTrue(store.execute(LedgerCommand.SetCaptureConsent(true, 0)).applied) }
    private suspend fun capture(p: NotificationPayload = payload()): InboxItem {
        val result = store.execute(LedgerCommand.CaptureNotification(p))
        assertTrue(result.message, result.applied)
        return store.inbox.first().single { it.id == result.entityId }
    }

    @Test fun consentReplayAndRefreshUseKeyedIdentityWithoutRawNotificationKeys() = runBlocking {
        val p = payload()
        assertEquals(CommandStatus.INVALID, store.execute(LedgerCommand.CaptureNotification(p)).status)
        assertTrue(store.inbox.first().isEmpty())
        enable()
        val command = LedgerCommand.CaptureNotification(p, "capture")
        val first = store.execute(command)
        assertTrue(first.applied)
        db.close(); reopen()
        assertTrue(store.captureSettings.first().consent)
        assertTrue(store.execute(command).replayed)
        assertEquals(first.entityId, store.execute(LedgerCommand.CaptureNotification(p.copy(observedAt = now.plusSeconds(4)))).entityId)
        assertEquals(1, store.inbox.first().size)
        val raw = db.inboxDao().allRaw().single().payload
        assertFalse(raw.contains(p.notificationKey)); assertFalse(raw.contains(p.userScope))
        assertTrue(db.ledgerDao().operation("capture")!!.fingerprint.startsWith("hmac:"))
        val item = store.inbox.first().single()
        assertEquals("", item.payload!!.notificationKey)
        assertEquals(InboxStatus.UNRECOGNIZED, item.status)
    }
    @Test fun competingSaveCommandsCommitOneEntryAndSettlementTogether() = runBlocking {
        enable(); val receipt = capture()
        val a = LedgerCommand.SaveCaptured(receipt.id, receipt.revision, draft, entryId = "one", operationId = "save-one")
        val b = a.copy(entryId = "two", operationId = "save-two")
        val results = coroutineScope { listOf(a, b).map { async { store.execute(it) } }.map { it.await() } }
        assertEquals(1, results.count { it.applied }); assertEquals(1, results.count { it.status == CommandStatus.CONFLICT })
        assertEquals(1, store.entries.first().size)
        assertEquals(InboxStatus.SAVED, store.inbox.first().single().status)
        assertEquals("Synthetic user correction", store.entries.first().single().draft.notes)
        assertNull(store.entries.first().single().draft.accountId)
        val winningCommand = if (results[0].applied) a else b
        assertTrue(store.execute(winningCommand).replayed)
    }
    @Test fun explicitSameEntryAndSeparatePurchaseBothSurviveRefresh() = runBlocking {
        enable()
        val first = capture(payload("one"))
        val saved = store.execute(LedgerCommand.SaveCaptured(first.id, first.revision, draft, entryId = "entry")).entry!!
        val copy = capture(payload("copy", "invalid.other"))
        assertEquals(1, store.entries.first().size)
        val link = LedgerCommand.LinkDuplicate(copy.id, copy.revision, saved.id, saved.revision, "link")
        assertTrue(store.execute(link).applied); assertTrue(store.execute(link).replayed)
        val repeatedPurchase = capture(payload("separate"))
        assertTrue(store.execute(LedgerCommand.SaveCaptured(repeatedPurchase.id, repeatedPurchase.revision, draft, SaveDecision.SEPARATE_PURCHASE, "separate-entry")).applied)
        assertEquals(2, store.entries.first().size)
        store.execute(LedgerCommand.CaptureNotification(payload("copy", "invalid.other")))
        assertEquals(3, store.inbox.first().size)
        assertEquals(InboxStatus.LINKED_DUPLICATE, store.inbox.first().single { it.id == copy.id }.status)
    }
    @Test fun changedImmutableRevisionNeedsExplicitResolutionAndReusedKeyStartsNewEpisode() = runBlocking {
        enable(); val p = payload(); val first = capture(p)
        val entry = store.execute(LedgerCommand.SaveCaptured(first.id, first.revision, draft, entryId = "entry")).entry!!
        val changed = capture(p.copy(body = "Invented completed variant", observedAt = now.plusSeconds(1)))
        assertEquals(2, changed.contentRevision); assertTrue(changed.requiresRevisionDecision)
        assertEquals(listOf(entry.id), changed.relatedEntryIds)
        assertEquals(CommandStatus.CONFLICT, store.execute(LedgerCommand.SaveCaptured(changed.id, changed.revision, draft)).status)
        assertTrue(store.execute(LedgerCommand.LinkDuplicate(changed.id, changed.revision, entry.id, entry.revision)).applied)
        assertEquals(1, store.entries.first().size)
        store.execute(LedgerCommand.NotificationRemoved(p.sourcePackage, p.userScope, p.notificationKey))
        val nextEpisode = capture(p)
        assertEquals(1, nextEpisode.contentRevision); assertNotEquals(first.id, nextEpisode.id)
        assertTrue(store.execute(LedgerCommand.SaveCaptured(nextEpisode.id, nextEpisode.revision, draft)).applied)
        assertEquals(2, store.entries.first().size)
    }
    @Test fun individualAndAppIgnoreRestoreRemainDistinctAndNeverDeleteLedger() = runBlocking {
        enable(); val a = capture(payload("a")); val b = capture(payload("b")); val c = capture(payload("c"))
        store.execute(LedgerCommand.SaveCaptured(c.id, c.revision, draft))
        val ignored = store.execute(LedgerCommand.IgnoreReceipt(a.id, a.revision))
        assertTrue(ignored.applied)
        val source = store.sources.first().single()
        assertTrue(store.execute(LedgerCommand.SetIgnoredSource(source.sourcePackage, true, source.revision)).applied)
        assertTrue(store.execute(LedgerCommand.CaptureNotification(payload("future"))).applied)
        assertEquals(3, store.inbox.first().size)
        assertEquals(InboxStatus.IGNORED, store.inbox.first().single { it.id == b.id }.status)
        assertEquals(InboxStatus.SAVED, store.inbox.first().single { it.id == c.id }.status)
        store.execute(LedgerCommand.SetIgnoredSource(source.sourcePackage, false, source.revision + 1))
        assertEquals(InboxStatus.IGNORED, store.inbox.first().single { it.id == a.id }.status)
        assertEquals(InboxStatus.UNRECOGNIZED, store.inbox.first().single { it.id == b.id }.status)
        assertTrue(store.execute(LedgerCommand.ReopenReceipt(a.id, ignored.revision!!)).applied)
        assertEquals(1, store.entries.first().size)
    }
    @Test fun ledgerDeleteAndConditionalUndoUpdateAllLinkedReceiptsWithoutResurrection() = runBlocking {
        enable(); val p = payload(); val a = capture(p)
        val entry = store.execute(LedgerCommand.SaveCaptured(a.id, a.revision, draft, entryId = "entry")).entry!!
        val b = capture(payload("other-key")); store.execute(LedgerCommand.LinkDuplicate(b.id, b.revision, entry.id, entry.revision))
        val delete = LedgerCommand.Delete(entry.id, entry.revision, "delete")
        assertTrue(store.execute(delete).applied)
        assertTrue(store.inbox.first().all { it.status == InboxStatus.REMOVED })
        store.execute(LedgerCommand.CaptureNotification(p))
        assertTrue(store.entries.first().isEmpty())
        assertTrue(store.execute(LedgerCommand.Undo("delete", 2, "undo")).applied)
        assertEquals(setOf(InboxStatus.SAVED, InboxStatus.LINKED_DUPLICATE), store.inbox.first().map { it.status }.toSet())
        assertEquals(1, store.entries.first().size)
    }
    @Test fun retentionBoundsRawAndCompactsPendingWhileActiveRefreshCannotResurrect() = runBlocking {
        db.close(); reopen(RawRetention(maxItems = 2)); enable()
        val p = payload("expired-active"); val first = capture(p)
        now = now.plusSeconds(1); val second = capture(payload("saved"))
        store.execute(LedgerCommand.SaveCaptured(second.id, second.revision, draft))
        now = now.plusSeconds(1); capture(payload("third"))
        assertNull(db.inboxDao().receipt(first.id)); assertEquals(2, db.inboxDao().allRaw().size)
        assertNull(store.execute(LedgerCommand.CaptureNotification(p)).entityId)
        assertEquals(2, store.inbox.first().size)
        now = now.plus(Duration.ofDays(8))
        assertEquals(1, store.inbox.first().size) // Read-side expiry works before a worker runs.
        assertTrue(store.inbox.first().single().contentExpired)
        store.execute(LedgerCommand.PruneInbox())
        assertTrue(db.inboxDao().allRaw().isEmpty())
        assertEquals(1, store.entries.first().size)
        val changed = capture(p.copy(body = "Invented changed content", observedAt = now))
        assertEquals(2, changed.contentRevision)
    }
    @Test fun rawByteLimitAndInactiveExpiryDoNotKeepUnprocessedMetadata() = runBlocking {
        db.close(); reopen(RawRetention(maxBytes = 1)); enable()
        val p = payload()
        assertTrue(store.execute(LedgerCommand.CaptureNotification(p)).applied)
        assertTrue(db.inboxDao().allRaw().isEmpty()); assertTrue(store.inbox.first().isEmpty())
        store.execute(LedgerCommand.NotificationRemoved(p.sourcePackage, p.userScope, p.notificationKey))
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM expired_active_revisions").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.close(); reopen(RawRetention(maxAge = Duration.ofSeconds(1)))
        val item = capture(payload("inactive"))
        store.execute(LedgerCommand.NotificationRemoved(item.sourcePackage, "synthetic-user", "inactive"))
        now = now.plusSeconds(2); store.execute(LedgerCommand.PruneInbox())
        assertNull(db.inboxDao().receipt(item.id))
    }
    @Test fun failedOperationCommitRollsBackReceiptRawSourceAndSettlement() = runBlocking {
        enable()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_capture_operation BEFORE INSERT ON operation_results BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        var failed = false
        try { store.execute(LedgerCommand.CaptureNotification(payload())) } catch (_: Exception) { failed = true }
        assertTrue(failed); assertTrue(store.inbox.first().isEmpty()); assertTrue(store.sources.first().isEmpty())
        assertTrue(db.inboxDao().allRaw().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_capture_operation")
        val receipt = capture()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_settlement BEFORE UPDATE ON inbox_receipts BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        // Room REPLACE is an insert, so fail its insert instead.
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_settlement_insert BEFORE INSERT ON inbox_receipts WHEN NEW.settlement IS NOT NULL BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        failed = false
        try { store.execute(LedgerCommand.SaveCaptured(receipt.id, receipt.revision, draft)) } catch (_: Exception) { failed = true }
        assertTrue(failed); assertTrue(store.entries.first().isEmpty())
        assertEquals(InboxStatus.UNRECOGNIZED, store.inbox.first().single().status)
    }
}
