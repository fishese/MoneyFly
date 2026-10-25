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
class InboxMigrationTest {
    @Test fun versionFourIgnoreRulesMigrateAsSameAppOnly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val filename = "synthetic-v4-ignore-rule-migration.db"
        context.deleteDatabase(filename)
        val latest = Room.databaseBuilder(context, LedgerDatabase::class.java, filename).build()
        latest.openHelper.writableDatabase // Room opens lazily; materialize the v5 fixture before reshaping it.
        latest.close()
        context.openOrCreateDatabase(filename, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE ignore_rule_revisions_v4 (id TEXT NOT NULL, revision INTEGER NOT NULL, name TEXT NOT NULL, sourcePackage TEXT NOT NULL, field TEXT NOT NULL, pattern TEXT NOT NULL, ignoreCase INTEGER NOT NULL, enabled INTEGER NOT NULL, deleted INTEGER NOT NULL, PRIMARY KEY(id, revision))")
            old.execSQL("INSERT INTO ignore_rule_revisions_v4 VALUES ('old-rule', 1, 'Old synthetic rule', 'synthetic.app', 'TITLE', 'Synthetic*', 1, 1, 0)")
            old.execSQL("DROP TABLE ignore_rule_revisions")
            old.execSQL("ALTER TABLE ignore_rule_revisions_v4 RENAME TO ignore_rule_revisions")
            old.version = 4
        }
        val upgraded = Room.databaseBuilder(context, LedgerDatabase::class.java, filename).addMigrations(LEDGER_MIGRATION_4_5).build()
        try {
            val rule = RoomLedgerStore(upgraded, ReceiptDigester { "synthetic" }).ignoreRules.first().single()
            assertEquals("old-rule", rule.id)
            assertTrue(rule.sameAppOnly)
            assertEquals(5, upgraded.openHelper.readableDatabase.version)
        } finally { upgraded.close(); context.deleteDatabase(filename) }
    }

    @Test fun versionOneManualLedgerAndCommittedRetrySurviveNonDestructiveUpgrade() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val filename = "synthetic-v1-migration.db"
        context.deleteDatabase(filename)
        val instant = Instant.parse("2026-10-01T12:00:00Z")
        val command = LedgerCommand.Save(EntryDraft("12.34", occurredAt = instant, merchant = "Synthetic merchant"), "entry", "old-save")
        val entry = LedgerEntry("entry", 1, command.draft, false, instant, instant, command.operationId)
        // Frozen version-one SQL fixture: no capture tables and no destructive fallback.
        context.openOrCreateDatabase(filename, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE ledger_entries (id TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, amount TEXT NOT NULL, currency TEXT NOT NULL, kind TEXT NOT NULL, occurredAt TEXT NOT NULL, merchant TEXT NOT NULL, notes TEXT NOT NULL, categoryId TEXT, accountId TEXT, canonicalMerchant TEXT, sourcePackage TEXT, timeOrigin TEXT NOT NULL, timePrecision TEXT NOT NULL, categoryOrigin TEXT NOT NULL, deleted INTEGER NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, lastOperationId TEXT NOT NULL)")
            old.execSQL("CREATE INDEX index_ledger_entries_categoryId ON ledger_entries(categoryId)")
            old.execSQL("CREATE INDEX index_ledger_entries_accountId ON ledger_entries(accountId)")
            old.execSQL("CREATE TABLE categories (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, revision INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE account_labels (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, revision INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE merchant_aliases (id TEXT NOT NULL PRIMARY KEY, spelling TEXT NOT NULL, normalized TEXT NOT NULL, canonicalName TEXT NOT NULL, revision INTEGER NOT NULL)")
            old.execSQL("CREATE UNIQUE INDEX index_merchant_aliases_normalized ON merchant_aliases(normalized)")
            old.execSQL("CREATE TABLE category_rules (id TEXT NOT NULL PRIMARY KEY, field TEXT NOT NULL, `match` TEXT NOT NULL, literal TEXT NOT NULL, categoryId TEXT NOT NULL, priority INTEGER NOT NULL, sourcePackage TEXT, accountId TEXT, revision INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE operation_results (id TEXT NOT NULL PRIMARY KEY, fingerprint TEXT NOT NULL, result TEXT NOT NULL, previousEntry TEXT)")
            old.execSQL("CREATE TABLE retired_entities (kind TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY(kind, id))")
            old.execSQL("INSERT INTO ledger_entries VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf("entry", 1, "12.34", "HKD", "EXPENSE", instant.toString(), "Synthetic merchant", "", null, null, null, null, "USER", "EXACT", "UNASSIGNED", 0, instant.toString(), instant.toString(), "old-save"))
            old.execSQL("INSERT INTO categories VALUES ('category', 'Synthetic label', 1)")
            old.execSQL("INSERT INTO operation_results VALUES (?, ?, ?, NULL)", arrayOf("old-save", CommandFingerprint.of(command), LedgerCodec.result(CommandResult(CommandStatus.APPLIED, "entry", 1, entry))))
            old.version = 1
        }
        val upgraded = Room.databaseBuilder(context, LedgerDatabase::class.java, filename).addMigrations(LEDGER_MIGRATION_1_2, LEDGER_MIGRATION_2_3, LEDGER_MIGRATION_3_4, LEDGER_MIGRATION_4_5).build()
        try {
            val store = RoomLedgerStore(upgraded, ReceiptDigester { "synthetic-digest" }, { instant })
            assertEquals(entry, store.entries.first().single())
            assertEquals("Synthetic label", store.categories.first().single().name)
            assertTrue(store.execute(command).replayed)
            assertEquals(1, store.entries.first().size)
            assertFalse(store.captureSettings.first().consent)
            assertTrue(store.ignoreRules.first().isEmpty())
            assertTrue(store.execute(LedgerCommand.SetCaptureConsent(true, 0)).applied)
            assertEquals(5, upgraded.openHelper.readableDatabase.version)
        } finally { upgraded.close(); context.deleteDatabase(filename) }
    }
}
