package com.fishese.moneyfly.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val LEDGER_MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS notification_slots (slotKey TEXT NOT NULL PRIMARY KEY, episodeId TEXT NOT NULL, active INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS inbox_receipts (id TEXT NOT NULL PRIMARY KEY, episodeId TEXT NOT NULL, fingerprint TEXT NOT NULL, sourcePackage TEXT NOT NULL, postedAt TEXT NOT NULL, observedAt TEXT NOT NULL, contentRevision INTEGER NOT NULL, revision INTEGER NOT NULL, settlement TEXT, entryId TEXT, removed INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_inbox_receipts_episodeId_fingerprint ON inbox_receipts (episodeId, fingerprint)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_inbox_receipts_entryId ON inbox_receipts (entryId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS inbox_raw (receiptId TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL, capturedAt INTEGER NOT NULL, byteCount INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS capture_sources (sourcePackage TEXT NOT NULL PRIMARY KEY, ignored INTEGER NOT NULL, parsingEnabled INTEGER NOT NULL, revision INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS capture_settings (id INTEGER NOT NULL PRIMARY KEY, consent INTEGER NOT NULL, revision INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS expired_active_revisions (episodeId TEXT NOT NULL, fingerprint TEXT NOT NULL, contentRevision INTEGER NOT NULL, PRIMARY KEY(episodeId, fingerprint))")
    }
}

val LEDGER_MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS template_revisions (id TEXT NOT NULL, revision INTEGER NOT NULL, definition TEXT NOT NULL, enabled INTEGER NOT NULL, deleted INTEGER NOT NULL, PRIMARY KEY(id, revision))")
        db.execSQL("CREATE TABLE IF NOT EXISTS receipt_recognition (receiptId TEXT NOT NULL PRIMARY KEY, result TEXT, templateId TEXT, templateRevision INTEGER)")
    }
}

val LEDGER_MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS ignore_rule_revisions (id TEXT NOT NULL, revision INTEGER NOT NULL, name TEXT NOT NULL, sourcePackage TEXT NOT NULL, field TEXT NOT NULL, pattern TEXT NOT NULL, ignoreCase INTEGER NOT NULL, enabled INTEGER NOT NULL, deleted INTEGER NOT NULL, PRIMARY KEY(id, revision))")
        seedDefaultStatusRules(db)
    }
}

val LEDGER_MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ignore_rule_revisions ADD COLUMN sameAppOnly INTEGER NOT NULL DEFAULT 1")
    }
}

/** Idempotent for upgraded databases and for a v4 build opened before defaults were introduced. */
val LEDGER_MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ignore_rule_revisions ADD COLUMN summaryOnly INTEGER NOT NULL DEFAULT 0")
    }
}

val LEDGER_MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ledger_entries ADD COLUMN exchangeRate TEXT")
        db.execSQL("ALTER TABLE ledger_entries ADD COLUMN exchangeRateCurrency TEXT")
    }
}

val LEDGER_MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS account_rules (id TEXT NOT NULL PRIMARY KEY, merchant TEXT, sourcePackage TEXT, accountId TEXT NOT NULL, revision INTEGER NOT NULL)")
    }
}
val LEDGER_MIGRATION_8_9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE account_labels ADD COLUMN icon TEXT")
    }
}

internal fun seedDefaultStatusRules(db: SupportSQLiteDatabase) {
    db.execSQL("INSERT OR IGNORE INTO ignore_rule_revisions (id, revision, name, sourcePackage, field, pattern, ignoreCase, enabled, deleted) SELECT 'default-status:' || sourcePackage || ':charging-full', 1, 'Charging status', sourcePackage, 'TITLE', 'Charging*', 1, 1, 0 FROM capture_sources WHERE sourcePackage IN ('android', 'com.android.systemui')")
    db.execSQL("INSERT OR IGNORE INTO ignore_rule_revisions (id, revision, name, sourcePackage, field, pattern, ignoreCase, enabled, deleted) SELECT 'default-status:android:zone-launcher', 1, 'Zone Launcher status', sourcePackage, 'TITLE', 'Zone Launcher *', 1, 1, 0 FROM capture_sources WHERE sourcePackage = 'android'")
}
