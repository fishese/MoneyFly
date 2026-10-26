package com.fishese.moneyfly.ledger

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import com.fishese.moneyfly.capture.NotificationPayload
import com.fishese.moneyfly.capture.SaveDecision
import com.fishese.moneyfly.recognition.TemplateDefinition
import com.fishese.moneyfly.recognition.IgnoreRule

enum class EntryKind { EXPENSE, INCOME, TRANSFER, REFUND }
enum class TimeOrigin { USER, NOTIFICATION_POSTED, CAPTURED, EXTRACTED }
enum class TimePrecision { EXACT, MINUTE, DATE, UNKNOWN }
enum class CategoryOrigin { EXPLICIT, RULE, UNASSIGNED }
enum class AccountKind { ACCOUNT, CARD, OTHER }

data class EntryDraft(
    val amount: String = "", val currency: String = "HKD", val kind: EntryKind = EntryKind.EXPENSE,
    val occurredAt: Instant = Instant.now(), val merchant: String = "", val notes: String = "",
    val categoryId: String? = null, val accountId: String? = null,
    val canonicalMerchant: String? = null, val sourcePackage: String? = null,
    val timeOrigin: TimeOrigin = TimeOrigin.USER, val timePrecision: TimePrecision = TimePrecision.EXACT,
    val categoryOrigin: CategoryOrigin = if (categoryId == null) CategoryOrigin.UNASSIGNED else CategoryOrigin.EXPLICIT,
    val exchangeRate: String? = null, val exchangeRateCurrency: String? = null,
)

data class LedgerEntry(
    val id: String, val revision: Long, val draft: EntryDraft, val deleted: Boolean = false,
    val createdAt: Instant, val updatedAt: Instant, val lastOperationId: String,
)
data class Category(val id: String, val name: String, val revision: Long)
data class AccountLabel(val id: String, val name: String, val kind: AccountKind, val revision: Long)
data class MerchantAlias(val id: String, val spelling: String, val canonicalName: String, val revision: Long)
enum class RuleField { MERCHANT, NOTES }
enum class LiteralMatch { EQUALS, CONTAINS, WHOLE_TOKEN }
data class CategoryRule(
    val id: String, val field: RuleField, val match: LiteralMatch, val literal: String,
    val categoryId: String, val priority: Int = 0, val sourcePackage: String? = null,
    val accountId: String? = null, val revision: Long = 0,
)

sealed interface LedgerCommand {
    val operationId: String
    data class Save(val draft: EntryDraft, val entryId: String = UUID.randomUUID().toString(), override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class Edit(val entryId: String, val expectedRevision: Long, val draft: EntryDraft, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class Delete(val entryId: String, val expectedRevision: Long, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    /** Reverses the named edit/delete only if its resulting revision is still current. */
    data class Undo(val targetOperationId: String, val expectedRevision: Long, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class PutCategory(val id: String, val name: String, val expectedRevision: Long? = null, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class DeleteCategory(val id: String, val expectedRevision: Long, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class PutAccount(val id: String, val name: String, val kind: AccountKind = AccountKind.ACCOUNT, val expectedRevision: Long? = null, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class DeleteAccount(val id: String, val expectedRevision: Long, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class PutAlias(val id: String, val spelling: String, val canonicalName: String, val expectedRevision: Long? = null, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class DeleteAlias(val id: String, val expectedRevision: Long, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class PutRule(val rule: CategoryRule, val expectedRevision: Long? = null, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class DeleteRule(val id: String, val expectedRevision: Long, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class CaptureNotification(val payload: NotificationPayload, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class NotificationRemoved(val sourcePackage: String, val userScope: String, val notificationKey: String, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class SetCaptureConsent(val enabled: Boolean, val expectedRevision: Long, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class SetIgnoredSource(val sourcePackage: String, val ignored: Boolean, val expectedRevision: Long, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class SetParsingSource(val sourcePackage: String, val enabled: Boolean, val expectedRevision: Long, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class SaveCaptured(val receiptId: String, val expectedRevision: Long, val draft: EntryDraft,
        val decision: SaveDecision = SaveDecision.NORMAL, val entryId: String = UUID.randomUUID().toString(), override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class LinkDuplicate(val receiptId: String, val expectedRevision: Long, val targetEntryId: String, val expectedEntryRevision: Long, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class IgnoreReceipt(val receiptId: String, val expectedRevision: Long, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class ReopenReceipt(val receiptId: String, val expectedRevision: Long, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class PruneInbox(override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class PutTemplate(val definition: TemplateDefinition, val expectedRevision: Long? = null, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class SetTemplateEnabled(val templateId: String, val expectedRevision: Long, val enabled: Boolean, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class DeleteTemplate(val templateId: String, val expectedRevision: Long, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class RepreviewReceipt(val receiptId: String, val expectedRevision: Long, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class ApplyTemplateToPending(val templateId: String, val expectedRevision: Long, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class PutIgnoreRule(val rule: IgnoreRule, val expectedRevision: Long? = null, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class SetIgnoreRuleEnabled(val ruleId: String, val expectedRevision: Long, val enabled: Boolean, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
    data class DeleteIgnoreRule(val ruleId: String, val expectedRevision: Long, override val operationId: String = UUID.randomUUID().toString()) : LedgerCommand
}
enum class CommandStatus { APPLIED, CONFLICT, INVALID, NOT_FOUND }
data class TemplateApplyReport(val checked: Int, val matched: Int, val recorded: Int, val review: Int, val unmatched: Int)
data class CommandResult(
    val status: CommandStatus, val entityId: String? = null, val revision: Long? = null,
    val entry: LedgerEntry? = null, val message: String? = null, val replayed: Boolean = false,
    val templateReport: TemplateApplyReport? = null,
) { val applied: Boolean get() = status == CommandStatus.APPLIED }

interface LedgerStore {
    val entries: Flow<List<LedgerEntry>>
    val categories: Flow<List<Category>>
    val accounts: Flow<List<AccountLabel>>
    val aliases: Flow<List<MerchantAlias>>
    val rules: Flow<List<CategoryRule>>
    suspend fun execute(command: LedgerCommand): CommandResult
}

object Money {
    /** Exact decimal normalization. Excess nonzero fractional digits are rejected, never rounded. */
    fun normalize(amount: String, currency: String): String {
        require(amount.length <= 64) { "Amount is too long." }
        require(currency.matches(Regex("[A-Z]{3}"))) { "Choose a three-letter currency code." }
        val scale = try { Currency.getInstance(currency).defaultFractionDigits } catch (_: IllegalArgumentException) { -1 }
        require(scale >= 0) { "Unsupported currency." }
        require(amount.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) { "Enter a positive decimal amount." }
        val value = BigDecimal(amount)
        require(value.signum() > 0) { "Amount must be greater than zero." }
        require(value.precision() <= 30 && value.stripTrailingZeros().scale() <= scale) { "Amount has too many digits for this currency." }
        return value.setScale(scale).toPlainString()
    }
}

data class LedgerFilter(
    val categoryId: String? = null, val uncategorizedOnly: Boolean = false,
    val accountId: String? = null, val unassignedOnly: Boolean = false,
    val fromDate: LocalDate? = null, val untilDateExclusive: LocalDate? = null,
    val kinds: Set<EntryKind> = emptySet(), val keyword: String = "", val zone: ZoneId = ZoneId.systemDefault(),
) {
    fun matches(entry: LedgerEntry): Boolean {
        val d = entry.draft
        val date = d.occurredAt.atZone(zone).toLocalDate()
        val query = keyword.trim().lowercase(Locale.ROOT)
        return !entry.deleted && (categoryId == null || d.categoryId == categoryId) &&
            (!uncategorizedOnly || d.categoryId == null) && (accountId == null || d.accountId == accountId) &&
            (!unassignedOnly || d.accountId == null) && (fromDate == null || date >= fromDate) &&
            (untilDateExclusive == null || date < untilDateExclusive) && (kinds.isEmpty() || d.kind in kinds) &&
            (query.isEmpty() || d.merchant.lowercase(Locale.ROOT).contains(query) ||
                d.canonicalMerchant.orEmpty().lowercase(Locale.ROOT).contains(query) || d.notes.lowercase(Locale.ROOT).contains(query))
    }
}
data class CurrencyTotal(val currency: String, val expense: BigDecimal, val income: BigDecimal, val refund: BigDecimal, val transfer: BigDecimal)
fun ledgerTotals(entries: List<LedgerEntry>): List<CurrencyTotal> = entries.filterNot { it.deleted }.groupBy { it.draft.currency }.toSortedMap().map { (currency, rows) ->
    fun sum(kind: EntryKind) = rows.filter { it.draft.kind == kind }.fold(BigDecimal.ZERO) { total, e -> total + BigDecimal(e.draft.amount) }
    CurrencyTotal(currency, sum(EntryKind.EXPENSE), sum(EntryKind.INCOME), sum(EntryKind.REFUND), sum(EntryKind.TRANSFER))
}
