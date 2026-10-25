package com.fishese.moneyfly.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notification_slots")
data class NotificationSlotRow(@PrimaryKey val slotKey: String, val episodeId: String, val active: Boolean)
@Entity(tableName = "inbox_receipts", indices = [Index(value = ["episodeId", "fingerprint"], unique = true), Index("entryId")])
data class ReceiptRow(@PrimaryKey val id: String, val episodeId: String, val fingerprint: String,
    val sourcePackage: String, val postedAt: String, val observedAt: String, val contentRevision: Int,
    val revision: Long, val settlement: String?, val entryId: String?, val removed: Boolean)
@Entity(tableName = "inbox_raw")
data class RawRow(@PrimaryKey val receiptId: String, val payload: String, val capturedAt: Long, val byteCount: Long)
@Entity(tableName = "capture_sources")
data class SourceRow(@PrimaryKey val sourcePackage: String, val ignored: Boolean, val parsingEnabled: Boolean, val revision: Long)
@Entity(tableName = "capture_settings")
data class CaptureSettingsRow(@PrimaryKey val id: Int = 1, val consent: Boolean, val revision: Long)
/** No source, timestamps or user content: only suppresses re-reading an expired active revision. */
@Entity(tableName = "expired_active_revisions", primaryKeys = ["episodeId", "fingerprint"])
data class ExpiredRevisionRow(val episodeId: String, val fingerprint: String, val contentRevision: Int)

@Dao
interface InboxDao {
    @Query("SELECT * FROM inbox_receipts") fun receipts(): Flow<List<ReceiptRow>>
    @Query("SELECT * FROM inbox_raw") fun raw(): Flow<List<RawRow>>
    @Query("SELECT * FROM capture_sources ORDER BY sourcePackage") fun sources(): Flow<List<SourceRow>>
    @Query("SELECT * FROM capture_settings WHERE id = 1") fun settings(): Flow<CaptureSettingsRow?>
    @Query("SELECT * FROM capture_settings WHERE id = 1") suspend fun settingsOnce(): CaptureSettingsRow?
    @Query("SELECT * FROM capture_sources WHERE sourcePackage = :source") suspend fun source(source: String): SourceRow?
    @Query("SELECT * FROM notification_slots WHERE slotKey = :key") suspend fun slot(key: String): NotificationSlotRow?
    @Query("SELECT * FROM inbox_receipts WHERE id = :id") suspend fun receipt(id: String): ReceiptRow?
    @Query("SELECT * FROM inbox_receipts WHERE episodeId = :episodeId ORDER BY contentRevision") suspend fun episodeReceipts(episodeId: String): List<ReceiptRow>
    @Query("SELECT * FROM expired_active_revisions WHERE episodeId = :episodeId") suspend fun expiredRevisions(episodeId: String): List<ExpiredRevisionRow>
    @Query("SELECT COUNT(*) FROM notification_slots WHERE episodeId = :episodeId AND active = 1") suspend fun activeEpisodeCount(episodeId: String): Int
    @Query("SELECT * FROM inbox_raw WHERE receiptId = :id") suspend fun rawOnce(id: String): RawRow?
    @Query("SELECT * FROM inbox_raw ORDER BY capturedAt, receiptId") suspend fun allRaw(): List<RawRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSlot(row: NotificationSlotRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putReceipt(row: ReceiptRow)
    @Insert suspend fun putRaw(row: RawRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSource(row: SourceRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSettings(row: CaptureSettingsRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putExpiredRevision(row: ExpiredRevisionRow)
    @Query("DELETE FROM inbox_raw WHERE receiptId = :id") suspend fun deleteRaw(id: String)
    @Query("UPDATE inbox_raw SET byteCount = :bytes WHERE receiptId = :id") suspend fun updateRawBytes(id: String, bytes: Long)
    @Query("DELETE FROM inbox_receipts WHERE id = :id") suspend fun deleteReceipt(id: String)
    @Query("DELETE FROM expired_active_revisions WHERE episodeId = :episodeId") suspend fun deleteExpiredRevisions(episodeId: String)
    @Query("UPDATE inbox_receipts SET removed = :removed, revision = revision + 1 WHERE entryId = :entryId AND removed != :removed") suspend fun markEntryRemoved(entryId: String, removed: Boolean)
}
