package com.fishese.moneyfly.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fishese.moneyfly.capture.DefaultIgnoredSources
import com.fishese.moneyfly.recognition.DefaultStatusRules
import com.fishese.moneyfly.recognition.StarterTemplates
import com.fishese.moneyfly.recognition.TemplateEngine
import com.fishese.moneyfly.recognition.RecognitionStatus

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
    for (source in listOf("android", "com.android.systemui")) for (rule in DefaultStatusRules.forSource(source)) {
        db.execSQL("INSERT OR IGNORE INTO ignore_rule_revisions (id, revision, name, sourcePackage, field, pattern, ignoreCase, enabled, deleted) SELECT ?, 1, ?, ?, ?, ?, 1, 1, 0 WHERE EXISTS (SELECT 1 FROM capture_sources WHERE sourcePackage = ?)",
            arrayOf(rule.id, rule.name, source, rule.field.name, rule.pattern, source))
    }
}

/** Preserve every existing source choice, including restored apps. */
internal fun seedDefaultIgnoredSources(db: SupportSQLiteDatabase) {
    for (source in DefaultIgnoredSources.packages) {
        db.execSQL("INSERT OR IGNORE INTO capture_sources (sourcePackage, ignored, parsingEnabled, revision) VALUES (?, 1, 0, 1)", arrayOf(source))
    }
}

/** Enroll only untouched sources; existing custom templates and source choices remain authoritative. */
internal fun seedStarterTemplates(db: SupportSQLiteDatabase) {
    // Disabled or deleted custom templates are choices too, even when no source row exists.
    val configuredSources = mutableSetOf<String>()
    var enabledCount = 0
    db.query("SELECT t.definition, t.enabled, t.deleted FROM template_revisions t WHERE t.revision = (SELECT MAX(revision) FROM template_revisions WHERE id = t.id)").use { cursor ->
        while (cursor.moveToNext()) {
            configuredSources += TemplateCodec.decode(cursor.getString(0)).sourcePackage
            if (cursor.getInt(1) != 0 && cursor.getInt(2) == 0) enabledCount++
        }
    }
    for ((source, definitions) in StarterTemplates.definitions.groupBy { it.sourcePackage }) {
        val existing = db.query("SELECT 1 FROM capture_sources WHERE sourcePackage = ?", arrayOf(source)).use { it.moveToFirst() }
        if (existing || source in configuredSources || enabledCount + definitions.size > 100) continue
        // A previously deleted starter must not be resurrected.
        val seen = definitions.any { definition ->
            db.query("SELECT 1 FROM template_revisions WHERE id = ?", arrayOf(definition.id)).use { it.moveToFirst() }
        }
        if (seen) continue
        check(definitions.all { definition ->
            TemplateEngine.validate(definition).isEmpty() && TemplateEngine.generatedExamples(definition).let { examples ->
                examples.size == 3 && examples.all { (it.result.status == RecognitionStatus.READY) == it.shouldMatch }
            }
        })
        for (definition in definitions) db.execSQL(
            "INSERT INTO template_revisions (id, revision, definition, enabled, deleted) VALUES (?, 1, ?, 1, 0)",
            arrayOf(definition.id, TemplateCodec.encode(definition)),
        )
        db.execSQL("INSERT INTO capture_sources (sourcePackage, ignored, parsingEnabled, revision) VALUES (?, 0, 1, 1)", arrayOf(source))
        enabledCount += definitions.size
    }
}
