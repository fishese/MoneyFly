package com.fishese.moneyfly.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "template_revisions", primaryKeys = ["id", "revision"])
data class TemplateRow(val id: String, val revision: Long, val definition: String, val enabled: Boolean, val deleted: Boolean)
@Entity(tableName = "receipt_recognition")
data class RecognitionRow(@PrimaryKey val receiptId: String, val result: String?, val templateId: String?, val templateRevision: Long?)
@Dao
interface TemplateDao {
    @Query("SELECT * FROM template_revisions t WHERE t.deleted = 0 AND t.revision = (SELECT MAX(revision) FROM template_revisions WHERE id = t.id) ORDER BY t.id") fun templates(): Flow<List<TemplateRow>>
    @Query("SELECT * FROM template_revisions t WHERE t.deleted = 0 AND t.revision = (SELECT MAX(revision) FROM template_revisions WHERE id = t.id)") suspend fun current(): List<TemplateRow>
    @Query("SELECT * FROM template_revisions WHERE id = :id ORDER BY revision DESC LIMIT 1") suspend fun latest(id: String): TemplateRow?
    @Query("SELECT * FROM template_revisions WHERE id = :id AND revision = :revision") suspend fun historical(id: String, revision: Long): TemplateRow?
    @Query("SELECT * FROM receipt_recognition") fun recognition(): Flow<List<RecognitionRow>>
    @Query("SELECT * FROM receipt_recognition WHERE receiptId = :id") suspend fun recognitionOnce(id: String): RecognitionRow?
    @Insert suspend fun putTemplate(row: TemplateRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRecognition(row: RecognitionRow)
    @Query("DELETE FROM receipt_recognition WHERE receiptId = :id") suspend fun clearRecognition(id: String)
}
