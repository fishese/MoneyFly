package com.fishese.moneyfly.storage

import android.database.Cursor
import androidx.room.withTransaction
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import com.fishese.moneyfly.security.BackupCrypto

/** Consistent logical snapshot, never a raw database or WAL file. */
internal object BackupSnapshot {
    private val tables = listOf(
        "ledger_entries", "categories", "account_labels", "merchant_aliases", "category_rules", "account_rules",
        "retired_entities", "inbox_receipts", "inbox_raw", "capture_sources", "template_revisions", "receipt_recognition", "ignore_rule_revisions",
    )
    // HMAC identities are device-bound, and capture consent must be granted again on the new device.
    private val deviceLocalTables = listOf("notification_slots", "expired_active_revisions", "operation_results", "capture_settings")
    private const val MAX_ROWS = 500_000

    suspend fun snapshot(database: LedgerDatabase): ByteArray = database.withTransaction {
        val db = database.openHelper.writableDatabase
        val result = JSONObject().put("format", 1).put("schema", database.openHelper.readableDatabase.version)
        val tableData = JSONObject()
        var count = 0
        var estimatedBytes = 0L
        for (table in tables) {
            val rows = JSONArray()
            val columns = columns(db, table)
            db.query("SELECT * FROM $table").use { cursor ->
                while (cursor.moveToNext()) {
                    require(++count <= MAX_ROWS) { "Backup contains too many rows" }
                    val values = JSONArray()
                    for (index in columns.indices) {
                        estimatedBytes += when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_STRING -> cursor.getString(index).length.toLong() * 6
                            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).size.toLong() * 2
                            else -> 32L
                        }
                        require(estimatedBytes <= BackupCrypto.MAX_PLAINTEXT_BYTES) { "Backup is too large" }
                        values.put(when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                        Cursor.FIELD_TYPE_BLOB -> JSONObject().put("blob", Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP))
                        else -> error("Unknown database value")
                        })
                    }
                    rows.put(values)
                }
            }
            tableData.put(table, JSONObject().put("columns", JSONArray(columns)).put("rows", rows))
        }
        val bytes = result.put("tables", tableData).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= BackupCrypto.MAX_PLAINTEXT_BYTES) { "Backup is too large" }
        bytes
    }

    suspend fun restore(database: LedgerDatabase, bytes: ByteArray) = database.withTransaction {
        require(bytes.size <= BackupCrypto.MAX_PLAINTEXT_BYTES)
        val db = database.openHelper.writableDatabase
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("format") == 1 && root.getInt("schema") == db.version) { "Unsupported backup version" }
        val tableData = root.getJSONObject("tables")
        require(tableData.length() == tables.size && tables.all(tableData::has)) { "Incomplete backup" }
        var count = 0
        for (table in tables) {
            val info = tableData.getJSONObject(table)
            val names = info.getJSONArray("columns")
            val expected = columns(db, table)
            require(names.length() == expected.size && expected.indices.all { names.getString(it) == expected[it] }) { "Incompatible backup table" }
            val rows = info.getJSONArray("rows")
            count += rows.length()
            require(count <= MAX_ROWS)
            for (index in 0 until rows.length()) {
                val row = rows.getJSONArray(index)
                require(row.length() == expected.size)
                for (column in 0 until row.length()) {
                    val value = row.get(column)
                    require(value === JSONObject.NULL || value is String || value is Number || (value is JSONObject && value.length() == 1 && value.has("blob")))
                    if (value is JSONObject) Base64.decode(value.getString("blob"), Base64.NO_WRAP)
                }
            }
        }
        for (table in tables + deviceLocalTables) db.execSQL("DELETE FROM $table")
        for (table in tables) {
            val info = tableData.getJSONObject(table)
            val names = columns(db, table)
            val sql = "INSERT INTO $table (${names.joinToString(",") { "\"$it\"" }}) VALUES (${names.joinToString(",") { "?" }})"
            val rows = info.getJSONArray("rows")
            for (index in 0 until rows.length()) {
                val row = rows.getJSONArray(index)
                val values = Array<Any?>(names.size) { column ->
                    when (val value = row.get(column)) {
                        JSONObject.NULL -> null
                        is JSONObject -> Base64.decode(value.getString("blob"), Base64.NO_WRAP)
                        else -> value
                    }
                }
                db.execSQL(sql, values)
            }
        }
    }

    private fun columns(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): List<String> = buildList {
        db.query("PRAGMA table_info($table)").use { cursor ->
            val index = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) add(cursor.getString(index))
        }
        check(isNotEmpty()) { "Backup table missing" }
    }
}
