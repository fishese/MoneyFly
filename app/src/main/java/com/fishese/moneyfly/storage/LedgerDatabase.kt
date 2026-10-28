package com.fishese.moneyfly.storage

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "ledger_entries", indices = [Index("categoryId"), Index("accountId")])
data class EntryRow(
    @PrimaryKey val id: String, val revision: Long, val amount: String, val currency: String,
    val kind: String, val occurredAt: String, val merchant: String, val notes: String,
    val categoryId: String?, val accountId: String?, val canonicalMerchant: String?, val sourcePackage: String?,
    val timeOrigin: String, val timePrecision: String, val categoryOrigin: String,
    val deleted: Boolean, val createdAt: String, val updatedAt: String, val lastOperationId: String,
    val exchangeRate: String? = null, val exchangeRateCurrency: String? = null,
)
@Entity(tableName = "categories")
data class CategoryRow(@PrimaryKey val id: String, val name: String, val revision: Long)
@Entity(tableName = "account_labels")
data class AccountRow(@PrimaryKey val id: String, val name: String, val kind: String, val revision: Long, val icon: String? = null)
@Entity(tableName = "merchant_aliases", indices = [Index(value = ["normalized"], unique = true)])
data class AliasRow(@PrimaryKey val id: String, val spelling: String, val normalized: String, val canonicalName: String, val revision: Long)
@Entity(tableName = "category_rules")
data class RuleRow(@PrimaryKey val id: String, val field: String, val match: String, val literal: String,
    val categoryId: String, val priority: Int, val sourcePackage: String?, val accountId: String?, val revision: Long)
@Entity(tableName = "account_rules")
data class AccountRuleRow(@PrimaryKey val id: String, val merchant: String?, val sourcePackage: String?, val accountId: String, val revision: Long)
@Entity(tableName = "operation_results")
data class OperationRow(@PrimaryKey val id: String, val fingerprint: String, val result: String, val previousEntry: String?)
@Entity(tableName = "retired_entities", primaryKeys = ["kind", "id"])
data class RetiredEntityRow(val kind: String, val id: String)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM ledger_entries WHERE deleted = 0 ORDER BY occurredAt DESC, id") fun entries(): Flow<List<EntryRow>>
    @Query("SELECT * FROM categories ORDER BY name, id") fun categories(): Flow<List<CategoryRow>>
    @Query("SELECT * FROM account_labels ORDER BY name, id") fun accounts(): Flow<List<AccountRow>>
    @Query("SELECT * FROM merchant_aliases ORDER BY spelling, id") fun aliases(): Flow<List<AliasRow>>
    @Query("SELECT * FROM category_rules ORDER BY priority DESC, id") fun rules(): Flow<List<RuleRow>>
    @Query("SELECT * FROM account_rules ORDER BY id") fun accountRules(): Flow<List<AccountRuleRow>>
    @Query("SELECT * FROM ledger_entries WHERE id = :id") suspend fun entry(id: String): EntryRow?
    @Query("SELECT * FROM categories WHERE id = :id") suspend fun category(id: String): CategoryRow?
    @Query("SELECT * FROM account_labels WHERE id = :id") suspend fun account(id: String): AccountRow?
    @Query("SELECT * FROM merchant_aliases WHERE id = :id") suspend fun alias(id: String): AliasRow?
    @Query("SELECT * FROM merchant_aliases WHERE normalized = :normalized AND id != :exceptId") suspend fun aliasWithSpelling(normalized: String, exceptId: String): AliasRow?
    @Query("SELECT * FROM category_rules WHERE id = :id") suspend fun rule(id: String): RuleRow?
    @Query("SELECT * FROM account_rules WHERE id = :id") suspend fun accountRule(id: String): AccountRuleRow?
    @Query("SELECT id FROM account_rules WHERE accountId = :id") suspend fun accountAssignmentRuleIds(id: String): List<String>
    @Query("SELECT * FROM operation_results WHERE id = :id") suspend fun operation(id: String): OperationRow?
    @Query("SELECT * FROM retired_entities WHERE kind = :kind AND id = :id") suspend fun retired(kind: String, id: String): RetiredEntityRow?
    @Query("SELECT id FROM category_rules WHERE categoryId = :id") suspend fun categoryRuleIds(id: String): List<String>
    @Query("SELECT id FROM category_rules WHERE accountId = :id") suspend fun accountRuleIds(id: String): List<String>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putEntry(row: EntryRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCategory(row: CategoryRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAccount(row: AccountRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAlias(row: AliasRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRule(row: RuleRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAccountRule(row: AccountRuleRow)
    @Insert suspend fun putOperation(row: OperationRow)
    @Query("DELETE FROM operation_results WHERE fingerprint LIKE 'hmac:%' AND rowid NOT IN (SELECT rowid FROM operation_results WHERE fingerprint LIKE 'hmac:%' ORDER BY rowid DESC LIMIT 1000)") suspend fun trimCaptureOperations()
    @Insert suspend fun retire(row: RetiredEntityRow)
    @Query("DELETE FROM categories WHERE id = :id") suspend fun deleteCategory(id: String)
    @Query("DELETE FROM account_labels WHERE id = :id") suspend fun deleteAccount(id: String)
    @Query("DELETE FROM merchant_aliases WHERE id = :id") suspend fun deleteAlias(id: String)
    @Query("DELETE FROM category_rules WHERE id = :id") suspend fun deleteRule(id: String)
    @Query("DELETE FROM category_rules WHERE categoryId = :id") suspend fun deleteCategoryRules(id: String)
    @Query("DELETE FROM category_rules WHERE accountId = :id") suspend fun deleteAccountRules(id: String)
    @Query("DELETE FROM account_rules WHERE id = :id") suspend fun deleteAccountRule(id: String)
    @Query("DELETE FROM account_rules WHERE accountId = :id") suspend fun deleteAccountRulesForAccount(id: String)
    @Query("UPDATE ledger_entries SET categoryId = NULL, categoryOrigin = 'UNASSIGNED', revision = revision + 1, updatedAt = :now, lastOperationId = :operationId WHERE categoryId = :id") suspend fun clearCategory(id: String, now: String, operationId: String)
    @Query("UPDATE ledger_entries SET accountId = NULL, revision = revision + 1, updatedAt = :now, lastOperationId = :operationId WHERE accountId = :id") suspend fun clearAccount(id: String, now: String, operationId: String)
}

@Database(entities = [EntryRow::class, CategoryRow::class, AccountRow::class, AliasRow::class, RuleRow::class, OperationRow::class, RetiredEntityRow::class,
    NotificationSlotRow::class, ReceiptRow::class, RawRow::class, SourceRow::class, CaptureSettingsRow::class, ExpiredRevisionRow::class,
    TemplateRow::class, RecognitionRow::class, IgnoreRuleRow::class, AccountRuleRow::class], version = 9, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
    abstract fun inboxDao(): InboxDao
    abstract fun templateDao(): TemplateDao
    abstract fun ignoreRuleDao(): IgnoreRuleDao
}
