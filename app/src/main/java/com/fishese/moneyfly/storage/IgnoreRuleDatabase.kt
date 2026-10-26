package com.fishese.moneyfly.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Query
import androidx.room.ColumnInfo
import com.fishese.moneyfly.ledger.*
import com.fishese.moneyfly.recognition.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "ignore_rule_revisions", primaryKeys = ["id", "revision"])
data class IgnoreRuleRow(
    val id: String, val revision: Long, val name: String, val sourcePackage: String,
    val field: String, val pattern: String, val ignoreCase: Boolean, val enabled: Boolean, val deleted: Boolean,
    @ColumnInfo(defaultValue = "1") val sameAppOnly: Boolean,
    @ColumnInfo(defaultValue = "0") val summaryOnly: Boolean,
)

@Dao
interface IgnoreRuleDao {
    @Query("SELECT * FROM ignore_rule_revisions r WHERE r.deleted = 0 AND r.revision = (SELECT MAX(revision) FROM ignore_rule_revisions WHERE id = r.id) ORDER BY name, id")
    fun rules(): Flow<List<IgnoreRuleRow>>
    @Query("SELECT * FROM ignore_rule_revisions r WHERE r.deleted = 0 AND r.revision = (SELECT MAX(revision) FROM ignore_rule_revisions WHERE id = r.id)")
    suspend fun current(): List<IgnoreRuleRow>
    @Query("SELECT * FROM ignore_rule_revisions WHERE id = :id ORDER BY revision DESC LIMIT 1")
    suspend fun latest(id: String): IgnoreRuleRow?
    @Insert suspend fun put(row: IgnoreRuleRow)
}

internal fun IgnoreRuleRow.toRule() = IgnoreRule(id, name, sourcePackage, MessageField.valueOf(field), pattern, ignoreCase, enabled, revision, sameAppOnly, summaryOnly)
internal fun IgnoreRule.toRow(revision: Long, deleted: Boolean = false) = IgnoreRuleRow(id, revision, name, sourcePackage, field.name, pattern, ignoreCase, enabled, deleted, sameAppOnly, summaryOnly)

internal class IgnoreRuleWriter(private val dao: IgnoreRuleDao) {
    suspend fun currentCompiled() = dao.current().mapNotNull { IgnoreRuleMatcher.compile(it.toRule()) }
    /** A tombstone or disabled revision counts as enrolled and is never resurrected. */
    suspend fun ensureDefaults(sourcePackage: String) {
        for (rule in DefaultStatusRules.forSource(sourcePackage)) {
            if (dao.latest(rule.id) == null) dao.put(rule.toRow(revision = 1))
        }
    }

    suspend fun execute(command: LedgerCommand): CommandResult = when (command) {
        is LedgerCommand.PutIgnoreRule -> put(command.rule, command.expectedRevision)
        is LedgerCommand.SetIgnoreRuleEnabled -> {
            val old = dao.latest(command.ruleId)
            if (old == null || old.deleted) missing() else put(old.toRule().copy(enabled = command.enabled), command.expectedRevision)
        }
        is LedgerCommand.DeleteIgnoreRule -> {
            val old = dao.latest(command.ruleId)
            when {
                old == null || old.deleted -> missing()
                old.revision != command.expectedRevision -> conflict(old.id, old.revision)
                else -> {
                    val revision = old.revision + 1
                    dao.put(old.toRule().copy(enabled = false).toRow(revision, deleted = true))
                    ok(old.id, revision)
                }
            }
        }
        else -> error("Unsupported ignore rule command")
    }

    private suspend fun put(input: IgnoreRule, expected: Long?): CommandResult {
        val old = dao.latest(input.id)
        if (old?.deleted == true) return CommandResult(CommandStatus.INVALID, message = "This ignore rule was deleted. Create a new rule instead.")
        if (old?.revision != expected) return if (old == null) missing() else conflict(old.id, old.revision)
        val errors = IgnoreRuleMatcher.validate(input)
        if (errors.isNotEmpty()) return CommandResult(CommandStatus.INVALID, message = errors.joinToString(" "))
        if (input.enabled && dao.current().count { it.enabled && it.id != input.id } >= 100)
            return CommandResult(CommandStatus.INVALID, message = "At most 100 ignore rules may be enabled.")
        val revision = (old?.revision ?: 0) + 1
        dao.put(input.toRow(revision))
        return ok(input.id, revision)
    }

    private fun ok(id: String, revision: Long) = CommandResult(CommandStatus.APPLIED, id, revision)
    private fun conflict(id: String, revision: Long) = CommandResult(CommandStatus.CONFLICT, id, revision, message = "This ignore rule changed. Review the latest version.")
    private fun missing() = CommandResult(CommandStatus.NOT_FOUND, message = "This ignore rule no longer exists.")
}
