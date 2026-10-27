package com.fishese.moneyfly.storage

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.fishese.moneyfly.security.DeviceDatabaseKey
import androidx.room.Room
import com.fishese.moneyfly.capture.ReceiptDigester
import com.fishese.moneyfly.ledger.EntryDraft
import com.fishese.moneyfly.ledger.LedgerCommand
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseMigrationTest {
    @Test fun migrationFailureLeavesOriginalUntouchedAndDoesNotActivateEncryption() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "synthetic-failed-encryption-test").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(directory, name)
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("synthetic-failed-$name", mode)
            override fun deleteDatabase(name: String): Boolean = android.database.sqlite.SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        }
        val source = context.getDatabasePath(EncryptedStorage.PLAIN_NAME)
        val encrypted = context.getDatabasePath(EncryptedStorage.ENCRYPTED_NAME)
        context.getSharedPreferences("encrypted_storage_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("database_key_v1", Context.MODE_PRIVATE).edit().clear().commit()
        android.database.sqlite.SQLiteDatabase.deleteDatabase(source); android.database.sqlite.SQLiteDatabase.deleteDatabase(encrypted)
        try {
            source.writeText("synthetic invalid database")
            EncryptedStorage.requestMigration(context)
            try { EncryptedStorage.prepare(context); fail("Invalid database was accepted") } catch (_: Exception) { }
            assertEquals("synthetic invalid database", source.readText())
            assertFalse(encrypted.exists())
            assertFalse(EncryptedStorage.isEnabled(context))
        } finally {
            context.getSharedPreferences("encrypted_storage_v1", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("database_key_v1", Context.MODE_PRIVATE).edit().clear().commit()
            android.database.sqlite.SQLiteDatabase.deleteDatabase(source); android.database.sqlite.SQLiteDatabase.deleteDatabase(encrypted)
            directory.delete()
        }
    }

    @Test fun roomOpensConvertedDatabaseWithItsExistingData() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "synthetic-room-encryption-test").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(directory, name)
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("synthetic-room-$name", mode)
            override fun deleteDatabase(name: String): Boolean = android.database.sqlite.SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        }
        val source = context.getDatabasePath(EncryptedStorage.PLAIN_NAME)
        val encrypted = context.getDatabasePath(EncryptedStorage.ENCRYPTED_NAME)
        context.getSharedPreferences("encrypted_storage_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("database_key_v1", Context.MODE_PRIVATE).edit().clear().commit()
        android.database.sqlite.SQLiteDatabase.deleteDatabase(source); android.database.sqlite.SQLiteDatabase.deleteDatabase(encrypted)
        try {
            val original = Room.databaseBuilder(context, LedgerDatabase::class.java, EncryptedStorage.PLAIN_NAME).build()
            val now = Instant.parse("2026-01-01T00:00:00Z")
            val store = RoomLedgerStore(original, ReceiptDigester { it.reversed() }, { now })
            assertTrue(store.execute(LedgerCommand.Save(EntryDraft("4.50", occurredAt = now, merchant = "Invented merchant"), "synthetic", "synthetic-operation")).applied)
            original.close()
            EncryptedStorage.requestMigration(context)
            val (name, factory) = EncryptedStorage.prepare(context)
            val converted = Room.databaseBuilder(context, LedgerDatabase::class.java, name).openHelperFactory(factory!!).build()
            try {
                val restored = RoomLedgerStore(converted, ReceiptDigester { it.reversed() }, { now })
                assertEquals("Invented merchant", restored.entries.first().single().draft.merchant)
                assertEquals("4.50", restored.entries.first().single().draft.amount)
            } finally { converted.close() }
        } finally {
            context.getSharedPreferences("encrypted_storage_v1", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("database_key_v1", Context.MODE_PRIVATE).edit().clear().commit()
            android.database.sqlite.SQLiteDatabase.deleteDatabase(source); android.database.sqlite.SQLiteDatabase.deleteDatabase(encrypted)
            directory.delete()
        }
    }

    @Test fun activationRecoversAnInterruptedCopyAndPreservesTheDatabase() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "synthetic-activation-test").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(directory, name)
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("synthetic-test-$name", mode)
            override fun deleteDatabase(name: String): Boolean = android.database.sqlite.SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        }
        val source = context.getDatabasePath(EncryptedStorage.PLAIN_NAME)
        val encrypted = context.getDatabasePath(EncryptedStorage.ENCRYPTED_NAME)
        context.getSharedPreferences("synthetic-test-unused", Context.MODE_PRIVATE)
        context.getSharedPreferences("encrypted_storage_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("database_key_v1", Context.MODE_PRIVATE).edit().clear().commit()
        android.database.sqlite.SQLiteDatabase.deleteDatabase(source)
        android.database.sqlite.SQLiteDatabase.deleteDatabase(encrypted)
        try {
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(source, null).use { db ->
                db.enableWriteAheadLogging()
                db.execSQL("CREATE TABLE synthetic (value TEXT)")
                db.execSQL("INSERT INTO synthetic VALUES ('invented activation')")
                db.version = 9
            }
            DeviceDatabaseKey(context).getOrCreate(false).fill(0)
            encrypted.writeText("incomplete encrypted copy")
            EncryptedStorage.requestMigration(context)
            val (name, factory) = EncryptedStorage.prepare(context)
            assertEquals(EncryptedStorage.ENCRYPTED_NAME, name); assertNotNull(factory)
            assertFalse(source.exists()); assertTrue(encrypted.exists())
            val key = DeviceDatabaseKey(context).getOrCreate(true)
            SQLiteDatabase.openDatabase(encrypted.absolutePath, key, null, SQLiteDatabase.OPEN_READONLY, null).use { db ->
                db.rawQuery("SELECT value FROM synthetic", emptyArray()).use { c -> assertTrue(c.moveToFirst()); assertEquals("invented activation", c.getString(0)) }
            }
            key.fill(0)
            assertTrue(EncryptedStorage.isEnabled(context))
            assertEquals(EncryptedStorage.ENCRYPTED_NAME, EncryptedStorage.prepare(context).first)
        } finally {
            context.getSharedPreferences("encrypted_storage_v1", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("database_key_v1", Context.MODE_PRIVATE).edit().clear().commit()
            android.database.sqlite.SQLiteDatabase.deleteDatabase(source)
            android.database.sqlite.SQLiteDatabase.deleteDatabase(encrypted)
            directory.delete()
        }
    }

    @Test fun encryptedCopyPreservesRowsAndOriginalAndRejectsWrongKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "synthetic-encryption-test").apply { mkdirs() }
        val source = File(directory, "plain.db")
        val encrypted = File(directory, "encrypted.db")
        android.database.sqlite.SQLiteDatabase.deleteDatabase(source)
        android.database.sqlite.SQLiteDatabase.deleteDatabase(encrypted)
        try {
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(source, null).use { db ->
                db.execSQL("CREATE TABLE synthetic (value TEXT NOT NULL)")
                db.execSQL("INSERT INTO synthetic VALUES ('invented sample')")
                db.version = 9
            }
            val passphrase = "synthetic-test-key".toByteArray()
            EncryptedDatabaseMigration.createVerifiedCopy(source, encrypted, passphrase)
            assertTrue(source.isFile)
            assertFalse(encrypted.inputStream().use { it.readNBytes(16) }.toString(Charsets.US_ASCII).startsWith("SQLite format 3"))
            SQLiteDatabase.openDatabase(encrypted.absolutePath, passphrase, null, SQLiteDatabase.OPEN_READONLY, null).use { db ->
                assertEquals(9, db.version)
                db.rawQuery("SELECT value FROM synthetic", emptyArray()).use { cursor ->
                    assertTrue(cursor.moveToFirst()); assertEquals("invented sample", cursor.getString(0))
                }
            }
            try {
                SQLiteDatabase.openDatabase(encrypted.absolutePath, "wrong-key".toByteArray(), null, SQLiteDatabase.OPEN_READONLY, null).use { db ->
                    db.rawQuery("SELECT value FROM synthetic", emptyArray()).use { it.moveToFirst() }
                }
                fail("Incorrect key opened database")
            } catch (_: android.database.sqlite.SQLiteException) { }
        } finally {
            android.database.sqlite.SQLiteDatabase.deleteDatabase(source)
            android.database.sqlite.SQLiteDatabase.deleteDatabase(encrypted)
            directory.delete()
        }
    }
}
