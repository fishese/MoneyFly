package com.fishese.moneyfly.storage

import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import net.zetetic.database.DatabaseErrorHandler

/** Builds and verifies an encrypted copy; caller retains the original until activation succeeds. */
internal object EncryptedDatabaseMigration {
    internal val preserveOnCorruption = DatabaseErrorHandler { _, exception -> throw exception }
    fun createVerifiedCopy(source: File, destination: File, passphrase: ByteArray) {
        require(source.isFile && !destination.exists() && passphrase.isNotEmpty())
        System.loadLibrary("sqlcipher")
        var completed = false
        try {
            SQLiteDatabase.openDatabase(source.absolutePath, ByteArray(0), null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, preserveOnCorruption, null).use { plain ->
                val version = plain.version
                plain.execSQL("ATTACH DATABASE ? AS encrypted KEY ?", arrayOf(destination.absolutePath, passphrase.toString(Charsets.US_ASCII)))
                plain.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray()).use { it.moveToFirst() }
                plain.execSQL("PRAGMA encrypted.user_version = $version")
                val tables = mutableListOf<String>()
                plain.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY name", emptyArray()).use { cursor ->
                    while (cursor.moveToNext()) tables += cursor.getString(0)
                }
                val targetTables = mutableListOf<String>()
                plain.rawQuery("SELECT name FROM encrypted.sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY name", emptyArray()).use { cursor ->
                    while (cursor.moveToNext()) targetTables += cursor.getString(0)
                }
                check(tables == targetTables) { "Encrypted copy has different tables" }
                for (table in tables) {
                    val quoted = "\"" + table.replace("\"", "\"\"") + "\""
                    val sourceCount = plain.rawQuery("SELECT COUNT(*) FROM main.$quoted", emptyArray()).use { it.moveToFirst(); it.getLong(0) }
                    val targetCount = plain.rawQuery("SELECT COUNT(*) FROM encrypted.$quoted", emptyArray()).use { it.moveToFirst(); it.getLong(0) }
                    check(sourceCount == targetCount) { "Encrypted copy has different row counts" }
                    val differences = plain.rawQuery("SELECT COUNT(*) FROM (SELECT * FROM main.$quoted EXCEPT SELECT * FROM encrypted.$quoted)", emptyArray()).use { it.moveToFirst(); it.getLong(0) }
                    check(differences == 0L) { "Encrypted copy has different rows" }
                }
                plain.execSQL("DETACH DATABASE encrypted")
            }
            SQLiteDatabase.openDatabase(destination.absolutePath, passphrase, null, SQLiteDatabase.OPEN_READONLY, preserveOnCorruption, null).use { encrypted ->
                encrypted.rawQuery("PRAGMA integrity_check", emptyArray()).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getString(0) == "ok") { "Encrypted copy failed verification" }
                }
                check(encrypted.version > 0) { "Encrypted copy has no schema version" }
            }
            completed = true
        } finally {
            if (!completed) SQLiteDatabase.deleteDatabase(destination)
        }
    }
}
