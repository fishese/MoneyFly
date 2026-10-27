package com.fishese.moneyfly.ledger

import com.fishese.moneyfly.capture.ReceiptDigester
import java.time.Instant

enum class ExactTimeOrigin { ORIGINAL_MESSAGE, TRANSACTION }
enum class ExactTimePrecision { SECOND, MILLISECOND }
/** A trusted adapter must establish these facts; Android delivery timestamps cannot fill this record. */
data class CompleteEmailEvidence(
    val canonicalSender: String, val subject: String, val fullContent: String,
    val eventTime: Instant, val timeOrigin: ExactTimeOrigin, val precision: ExactTimePrecision,
    val zoneContext: String, val fullContentAvailable: Boolean, val canonicalBoundariesCertain: Boolean,
    val eventUnambiguous: Boolean,
)
object FinancialIdentity {
    fun canonicalText(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
    fun emailCopy(evidence: CompleteEmailEvidence, digester: ReceiptDigester): String? {
        if (!evidence.fullContentAvailable || !evidence.canonicalBoundariesCertain || !evidence.eventUnambiguous ||
            evidence.canonicalSender.isBlank() || evidence.fullContent.isEmpty() || evidence.zoneContext.isBlank()) return null
        if (evidence.precision == ExactTimePrecision.SECOND && evidence.eventTime.nano != 0) return null
        if (evidence.precision == ExactTimePrecision.MILLISECOND && evidence.eventTime.nano % 1_000_000 != 0) return null
        val fields = listOf("email-copy-v1", evidence.canonicalSender, canonicalText(evidence.subject), canonicalText(evidence.fullContent),
            evidence.eventTime.toString(), evidence.timeOrigin.name, evidence.precision.name, evidence.zoneContext)
        return digester.digest(fields.joinToString("") { "${it.length}:$it;" })
    }
}

/** Notification updates usually arrive close together; recurring purchases are independent events. */
val POSSIBLE_DUPLICATE_WINDOW: java.time.Duration = java.time.Duration.ofMinutes(15)

/** Review suggestions only. A match never authorizes linking or saving. */
fun possibleDuplicateEntries(draft: EntryDraft, entries: List<LedgerEntry>): List<LedgerEntry> {
    val amount = try { java.math.BigDecimal(Money.normalize(draft.amount, draft.currency)) } catch (_: IllegalArgumentException) { return emptyList() }
    val merchant = Categorization.normalize(draft.canonicalMerchant ?: draft.merchant)
    if (merchant.isBlank()) return emptyList()
    return entries.filter { entry ->
        val other = entry.draft
        !entry.deleted && other.currency == draft.currency && other.kind == draft.kind &&
            java.math.BigDecimal(other.amount).compareTo(amount) == 0 &&
            Categorization.normalize(other.canonicalMerchant ?: other.merchant) == merchant &&
            (draft.accountId == null || other.accountId == null || draft.accountId == other.accountId) &&
            java.time.Duration.between(other.occurredAt, draft.occurredAt).abs() <= POSSIBLE_DUPLICATE_WINDOW
    }.sortedBy { it.id }
}
