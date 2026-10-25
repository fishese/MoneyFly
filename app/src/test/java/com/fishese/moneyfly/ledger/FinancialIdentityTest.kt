package com.fishese.moneyfly.ledger

import com.fishese.moneyfly.capture.ReceiptDigester
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class FinancialIdentityTest {
    private val digest = ReceiptDigester { value -> Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256")); doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
    } }
    private val evidence = CompleteEmailEvidence("synthetic@example.invalid", "Invented receipt", "Sample\r\nvalue: 12.34",
        Instant.parse("2026-10-01T12:00:00.123Z"), ExactTimeOrigin.ORIGINAL_MESSAGE, ExactTimePrecision.MILLISECOND,
        "UTC", true, true, true)
    @Test fun completeCopyIdentityIgnoresOnlyDocumentedLineTransport() {
        assertNotNull(FinancialIdentity.emailCopy(evidence, digest))
        assertEquals(FinancialIdentity.emailCopy(evidence, digest), FinancialIdentity.emailCopy(evidence.copy(fullContent = "Sample\nvalue: 12.34"), digest))
        assertNotEquals(FinancialIdentity.emailCopy(evidence, digest), FinancialIdentity.emailCopy(evidence.copy(fullContent = "Sample\nvalue: 12.35"), digest))
        assertNotEquals(FinancialIdentity.emailCopy(evidence, digest), FinancialIdentity.emailCopy(evidence.copy(fullContent = "sample\nvalue: 12.34"), digest))
        // Mailbox, delivery time, Android key and capture time are deliberately absent from this API.
    }
    @Test fun imprecisePartialAmbiguousOrDifferentTimeContextCannotAutoIdentify() {
        assertNull(FinancialIdentity.emailCopy(evidence.copy(fullContentAvailable = false), digest))
        assertNull(FinancialIdentity.emailCopy(evidence.copy(canonicalBoundariesCertain = false), digest))
        assertNull(FinancialIdentity.emailCopy(evidence.copy(eventUnambiguous = false), digest))
        val key = FinancialIdentity.emailCopy(evidence, digest)
        assertNotEquals(key, FinancialIdentity.emailCopy(evidence.copy(timeOrigin = ExactTimeOrigin.TRANSACTION), digest))
        assertNotEquals(key, FinancialIdentity.emailCopy(evidence.copy(precision = ExactTimePrecision.SECOND), digest))
        assertNotEquals(key, FinancialIdentity.emailCopy(evidence.copy(zoneContext = "Asia/Hong_Kong"), digest))
        assertNotEquals(key, FinancialIdentity.emailCopy(evidence.copy(eventTime = evidence.eventTime.plusMillis(1)), digest))
        assertNotNull(FinancialIdentity.emailCopy(evidence.copy(eventTime = Instant.parse("2026-10-01T12:00:00Z"), precision = ExactTimePrecision.SECOND), digest))
    }
    @Test fun similarEntriesAreOnlyReviewCandidatesAndKnownAccountOrDirectionConflictsExcludeThem() {
        val draft = EntryDraft("12.34", occurredAt = evidence.eventTime, merchant = "Synthetic shop", accountId = "account-one")
        fun entry(id: String, value: EntryDraft) = LedgerEntry(id, 1, value, false, evidence.eventTime, evidence.eventTime, "synthetic-op")
        val entries = listOf(entry("same", draft), entry("unknown-account", draft.copy(accountId = null)),
            entry("different-account", draft.copy(accountId = "account-two")), entry("income", draft.copy(kind = EntryKind.INCOME)),
            entry("other-currency", draft.copy(currency = "USD")), entry("distant", draft.copy(occurredAt = draft.occurredAt.minusSeconds(3 * 86400))))
        assertEquals(listOf("same", "unknown-account"), possibleDuplicateEntries(draft, entries).map { it.id })
    }
}
