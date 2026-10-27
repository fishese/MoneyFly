package com.fishese.moneyfly.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fishese.moneyfly.ledger.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountIconMigrationTest {
    @Test fun upgradePreservesAccountsAndReplayAndStoresUnicodeIcon() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val filename = "synthetic-account-icon-migration.db"
        context.deleteDatabase(filename)
        var db = Room.databaseBuilder(context, LedgerDatabase::class.java, filename).build()
        val original = LedgerCommand.PutAccount("card", "Synthetic card", AccountKind.CARD, operationId = "old-account")
        assertTrue(RoomLedgerStore(db).execute(original).applied)
        db.close()
        context.openOrCreateDatabase(filename, Context.MODE_PRIVATE, null).use { it.execSQL("ALTER TABLE account_labels DROP COLUMN icon"); it.version = 8 }
        db = Room.databaseBuilder(context, LedgerDatabase::class.java, filename).addMigrations(LEDGER_MIGRATION_8_9).build()
        try {
            val store = RoomLedgerStore(db)
            assertTrue(store.execute(original).replayed)
            assertNull(store.accounts.first().single().icon)
            val change = LedgerCommand.PutAccount("card", "Synthetic card", AccountKind.CARD, 1, "icon-save", "👨‍👩‍👧")
            assertTrue(store.execute(change).applied)
            assertEquals("👨‍👩‍👧", store.accounts.first().single().icon)
            assertTrue(store.execute(change).replayed)
            assertEquals(CommandStatus.INVALID, store.execute(LedgerCommand.PutAccount("card", "Synthetic card", AccountKind.CARD, 2, icon = "x".repeat(33))).status)
            assertTrue(store.execute(LedgerCommand.PutAccount("card", "Synthetic card", AccountKind.CARD, 2, icon = "匯")).applied)
            assertEquals("匯", store.accounts.first().single().icon)
        } finally { db.close(); context.deleteDatabase(filename) }
    }
}
