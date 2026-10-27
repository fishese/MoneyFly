package com.fishese.moneyfly.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fishese.moneyfly.ledger.*
import com.fishese.moneyfly.storage.LedgerDatabase
import com.fishese.moneyfly.storage.RoomLedgerStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransportRuleSeedTest {
    @Test fun defaultsApplyAndDeletedRulesStayDeleted() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("expense_category_rules_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val store = RoomLedgerStore(db)
            store.execute(LedgerCommand.PutCategory("transport", "Transportation"))
            assertTrue(seedTransportRules(context, store))
            assertEquals(2, store.rules.first().size)
            for (merchant in listOf("Minibus", "MTR Corporation Limited")) {
                val saved = store.execute(LedgerCommand.Save(EntryDraft("3.00", merchant = merchant))).entry!!
                assertEquals("transport", saved.draft.categoryId)
            }
            val rule = store.rules.first().first()
            store.execute(LedgerCommand.DeleteRule(rule.id, rule.revision))
            assertTrue(seedTransportRules(context, store))
            assertEquals(1, store.rules.first().size)
        } finally { db.close() }
    }
}
