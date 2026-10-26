package com.fishese.moneyfly.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fishese.moneyfly.capture.ReceiptDigester
import com.fishese.moneyfly.ledger.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FxEntryStoreTest {
    @Test fun versionSixMigrationKeepsLedgerAndReplayThenPersistsOverrideAndUndo() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-fx-migration.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        val now = Instant.parse("2025-10-25T12:00:00Z")
        var store = RoomLedgerStore(db, ReceiptDigester { "synthetic" }, { now })
        val save = LedgerCommand.Save(EntryDraft("1250", "JPY", occurredAt = now), "synthetic-entry", "before-fx")
        assertTrue(store.execute(save).applied)
        db.close()
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use {
            it.execSQL("ALTER TABLE ledger_entries DROP COLUMN exchangeRate")
            it.execSQL("ALTER TABLE ledger_entries DROP COLUMN exchangeRateCurrency")
            it.version = 6
        }
        db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).addMigrations(LEDGER_MIGRATION_6_7).build()
        try {
            store = RoomLedgerStore(db, ReceiptDigester { "synthetic" }, { now })
            assertTrue(store.execute(save).replayed)
            assertNull(store.entries.first().single().draft.exchangeRate)
            val edit = LedgerCommand.Edit("synthetic-entry", 1, save.draft.copy(exchangeRate = "0.05300", exchangeRateCurrency = "HKD"), "card-rate")
            assertTrue(store.execute(edit).applied)
            assertEquals("0.053", store.entries.first().single().draft.exchangeRate)
            assertEquals(CommandStatus.INVALID, store.execute(edit.copy(draft = edit.draft.copy(exchangeRate = "0.06"))).status)
            assertTrue(store.execute(LedgerCommand.Undo("card-rate", 2)).applied)
            assertNull(store.entries.first().single().draft.exchangeRate)
            assertEquals("1250", store.entries.first().single().draft.amount)
            assertEquals(CommandStatus.INVALID, store.execute(LedgerCommand.Edit("synthetic-entry", 3, save.draft.copy(exchangeRate = "0"))).status)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
