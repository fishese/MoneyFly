package com.fishese.moneyfly.storage

import android.content.Context
import com.fishese.moneyfly.security.DeviceDatabaseKey
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Crash-recoverable opt-in conversion, performed before opening any Room connection. */
internal object EncryptedStorage {
    const val PLAIN_NAME = "moneyfly_next_ledger_v1.db"
    const val ENCRYPTED_NAME = "moneyfly_encrypted_v1.db"
    private const val PREFS = "encrypted_storage_v1"
    private const val PENDING = "pending"
    private const val ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)
    fun requestMigration(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        check(!prefs.getBoolean(ENABLED, false))
        check(prefs.edit().putBoolean(PENDING, true).commit()) { "Could not schedule encryption" }
    }

    fun canCancelPending(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(PENDING, false) && !prefs.getBoolean(ENABLED, false) && context.getDatabasePath(PLAIN_NAME).isFile
    }

    @Synchronized fun cancelPending(context: Context) {
        check(canCancelPending(context))
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PENDING).commit())
    }

    @Synchronized fun prepare(context: Context): Pair<String, SupportOpenHelperFactory?> {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getBoolean(PENDING, false)
        val enabled = prefs.getBoolean(ENABLED, false)
        if (!pending && !enabled) return PLAIN_NAME to null
        System.loadLibrary("sqlcipher")
        val plain = app.getDatabasePath(PLAIN_NAME)
        val encrypted = app.getDatabasePath(ENCRYPTED_NAME)
        val key = DeviceDatabaseKey(app).getOrCreate(existingEncryptedDatabase = enabled || encrypted.exists())
        try {
            if (!enabled) {
                check(plain.isFile) { "The original database is unavailable" }
                // A previous attempt may have left a partial target. The original remains authoritative.
                if (encrypted.exists()) check(SQLiteDatabase.deleteDatabase(encrypted)) { "Could not clear incomplete encrypted copy" }
                EncryptedDatabaseMigration.createVerifiedCopy(plain, encrypted, key)
                check(prefs.edit().putBoolean(ENABLED, true).remove(PENDING).commit()) { "Could not activate encrypted storage" }
            }
            check(encrypted.isFile) { "The encrypted database is unavailable" }
            // Confirm access before cleaning up the original, including after interruption.
            SQLiteDatabase.openDatabase(encrypted.absolutePath, key, null, SQLiteDatabase.OPEN_READONLY, EncryptedDatabaseMigration.preserveOnCorruption, null).use { db ->
                db.rawQuery("PRAGMA integrity_check", emptyArray()).use { cursor -> check(cursor.moveToFirst() && cursor.getString(0) == "ok") }
            }
            val oldFiles = listOf(plain, java.io.File(plain.path + "-wal"), java.io.File(plain.path + "-shm"), java.io.File(plain.path + "-journal"))
            if (oldFiles.any { it.exists() }) {
                app.deleteDatabase(PLAIN_NAME)
                check(oldFiles.none { it.exists() }) { "Could not remove the unencrypted database" }
            }
            return ENCRYPTED_NAME to SupportOpenHelperFactory(key.copyOf())
        } finally { key.fill(0) }
    }
}
