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
class AccountRuleMigrationTest {
    @Test fun upgradeFromSevenPreservesEntriesAndOldOperationReplay() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-account-rule-upgrade.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        val command = LedgerCommand.Save(EntryDraft("12.00", merchant = "Synthetic"), "entry", "old-save")
        assertTrue(RoomLedgerStore(db).execute(command).applied)
        db.close()
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { it.execSQL("DROP TABLE account_rules"); it.version = 7 }
        db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).addMigrations(LEDGER_MIGRATION_7_8).build()
        try {
            val store = RoomLedgerStore(db)
            assertEquals("12.00", store.entries.first().single().draft.amount)
            assertTrue(store.execute(command).replayed)
            assertTrue(store.accountRules.first().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
