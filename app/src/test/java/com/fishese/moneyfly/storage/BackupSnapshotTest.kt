package com.fishese.moneyfly.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fishese.moneyfly.capture.ReceiptDigester
import com.fishese.moneyfly.ledger.*
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupSnapshotTest {
    @Test fun snapshotRestoresLedgerAndRollsBackInvalidInput() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val now = Instant.parse("2026-01-01T00:00:00Z")
            val store = RoomLedgerStore(database, ReceiptDigester { it.reversed() }, { now })
            assertTrue(store.execute(LedgerCommand.PutCategory("category", "Synthetic", operationId = "category-op")).applied)
            assertTrue(store.execute(LedgerCommand.Save(EntryDraft("2.00", occurredAt = now, categoryId = "category"), "entry", "save-op")).applied)
            assertTrue(store.execute(LedgerCommand.SetCaptureConsent(true, 0)).applied)
            val encrypted = store.createEncryptedBackup("invented password".toCharArray())
            val entry = store.entries.first().single()
            assertTrue(store.execute(LedgerCommand.Edit(entry.id, entry.revision, entry.draft.copy(amount = "3.00"))).applied)
            try { BackupSnapshot.restore(database, "{}".toByteArray()); fail("Invalid backup accepted") } catch (_: Exception) { }
            assertEquals("3.00", store.entries.first().single().draft.amount)
            try { store.restoreEncryptedBackup(encrypted, "wrong password".toCharArray()); fail("Wrong password accepted") } catch (_: Exception) { }
            assertEquals("3.00", store.entries.first().single().draft.amount)
            store.restoreEncryptedBackup(encrypted, "invented password".toCharArray())
            assertEquals("2.00", store.entries.first().single().draft.amount)
            assertEquals("Synthetic", store.categories.first().single().name)
            assertFalse(store.captureSettings.first().consent)
        } finally { database.close() }
    }
}
