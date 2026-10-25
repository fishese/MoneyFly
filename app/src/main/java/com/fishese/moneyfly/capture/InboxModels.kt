package com.fishese.moneyfly.capture

import com.fishese.moneyfly.ledger.CommandResult
import com.fishese.moneyfly.ledger.LedgerCommand
import com.fishese.moneyfly.ledger.EntryDraft
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.fishese.moneyfly.recognition.IgnoreRule

/** The adapter reports only OS-visible fields; postedAt is delivery evidence, never event identity. */
data class NotificationPayload(
    val sourcePackage: String, val userScope: String, val notificationKey: String,
    val postedAt: Instant, val observedAt: Instant,
    val title: String = "", val body: String = "", val bigText: String = "",
    val textLines: List<String> = emptyList(), val subText: String = "",
    val groupSummary: Boolean = false, val redacted: Boolean = false, val truncated: Boolean = false,
)
enum class InboxStatus { UNRECOGNIZED, READY, REVIEW_NEEDED, SAVED, LINKED_DUPLICATE, IGNORED, REMOVED }
data class InboxItem(
    val id: String, val revision: Long, val sourcePackage: String, val postedAt: Instant, val observedAt: Instant,
    val contentRevision: Int, val status: InboxStatus, val payload: NotificationPayload?,
    val entryId: String? = null, val relatedEntryIds: List<String> = emptyList(),
    val requiresRevisionDecision: Boolean = false,
    val draft: EntryDraft? = null, val issues: List<String> = emptyList(),
    val matchTemplateId: String? = null, val matchTemplateRevision: Long? = null,
    val ignoreRuleId: String? = null, val ignoreRuleRevision: Long? = null,
) { val contentExpired: Boolean get() = payload == null }
data class SourcePreference(val sourcePackage: String, val ignored: Boolean = false, val parsingEnabled: Boolean = false, val revision: Long = 0)
data class CaptureSettings(val consent: Boolean = false, val revision: Long = 0)
enum class SaveDecision { NORMAL, SEPARATE_PURCHASE }

interface InboxStore {
    val inbox: Flow<List<InboxItem>>
    val sources: Flow<List<SourcePreference>>
    val captureSettings: Flow<CaptureSettings>
    val ignoreRules: Flow<List<IgnoreRule>> get() = flowOf(emptyList())
    suspend fun execute(command: LedgerCommand): CommandResult
}

/** Injection boundary: implementations keep this device-local secret outside export/DB. */
fun interface ReceiptDigester { fun digest(canonical: String): String }
