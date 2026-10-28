package com.fishese.moneyfly.recognition

import com.fishese.moneyfly.capture.NotificationPayload
import com.fishese.moneyfly.ledger.*
import kotlinx.coroutines.flow.Flow

enum class MessageField { TITLE, BODY, BIG_TEXT, SUB_TEXT, LINES }
enum class SlotName { AMOUNT, CURRENCY, MERCHANT, REFERENCE, ACCOUNT_HINT, NOTES, VARIABLE, DATE }
sealed interface PatternSegment {
    data class Literal(val text: String) : PatternSegment
    /** numericOnly is used by reviewed catalog formats whose amount is followed by free text. */
    data class Slot(val name: SlotName, val maxLength: Int = 256, val numericOnly: Boolean = false, val linked: Boolean = false) : PatternSegment
}
data class FieldPattern(val field: MessageField, val segments: List<PatternSegment>, val section: Boolean = false)
enum class PredicateOperation { EQUALS, CONTAINS, STARTS_WITH, ENDS_WITH }
data class TemplatePredicate(val field: MessageField, val operation: PredicateOperation, val literal: String, val ignoreCase: Boolean = false)
enum class NumberConvention { DECIMAL_DOT_GROUP_COMMA, DECIMAL_COMMA_GROUP_DOT, DECIMAL_DOT_NO_GROUPING, INTEGER_NO_GROUPING }
enum class FixedCurrencyEvidence { EXPLICIT_MATCHED_LITERAL, USER_CONFIRMED_ASSUMPTION }
data class CurrencyToken(val token: String, val currency: String, val userConfirmedAmbiguous: Boolean = false)
/** Only unambiguous, explicit currency markers are included by default. */
val commonCurrencyTokens = listOf(
    CurrencyToken("HKD", "HKD"), CurrencyToken("HK$", "HKD"), CurrencyToken("USD", "USD"), CurrencyToken("US$", "USD"),
    CurrencyToken("SGD", "SGD"), CurrencyToken("S$", "SGD"), CurrencyToken("GBP", "GBP"), CurrencyToken("£", "GBP"),
    CurrencyToken("EUR", "EUR"), CurrencyToken("€", "EUR"), CurrencyToken("JPY", "JPY"), CurrencyToken("JP¥", "JPY"),
    CurrencyToken("CNY", "CNY"), CurrencyToken("RMB", "CNY"), CurrencyToken("CN¥", "CNY"), CurrencyToken("TWD", "TWD"), CurrencyToken("NT$", "TWD"),
)

fun currencyTokenOptions(selected: CurrencyToken): List<CurrencyToken> =
    listOf(selected) + commonCurrencyTokens.filter { it.token != selected.token }

/** Small native contract, not an importer for the portable schema. No examples or source samples are persisted. */
data class TemplateDefinition(
    val id: String, val name: String, val sourcePackage: String, val patterns: List<FieldPattern>,
    val predicates: List<TemplatePredicate> = emptyList(), val kind: EntryKind = EntryKind.EXPENSE,
    val fixedCurrency: String? = null, val fixedCurrencyEvidence: FixedCurrencyEvidence? = null,
    val fixedMerchant: String? = null, val currencyTokens: List<CurrencyToken> = emptyList(),
    val numberConvention: NumberConvention = NumberConvention.DECIMAL_DOT_GROUP_COMMA,
    val enabled: Boolean = false, val revision: Long = 0,
)
enum class RecognitionStatus { NO_MATCH, READY, REVIEW_NEEDED }

/** Ignore display identity and enablement when comparing the actual matching instructions. */
fun TemplateDefinition.hasSameContent(other: TemplateDefinition): Boolean =
    copy(id = "", name = "", revision = 0, enabled = false) == other.copy(id = "", name = "", revision = 0, enabled = false)
data class RecognitionResult(val status: RecognitionStatus, val draft: EntryDraft? = null, val issues: List<String> = emptyList(),
    val templateId: String? = null, val templateRevision: Long? = null)
data class SyntheticPreview(val name: String, val payload: NotificationPayload, val shouldMatch: Boolean, val result: RecognitionResult)
data class TemplatePreview(val current: RecognitionResult, val examples: List<SyntheticPreview>, val issues: List<String>) {
    val canEnable: Boolean get() = current.status == RecognitionStatus.READY && issues.isEmpty() && examples.size == 3 &&
        examples.all { (it.result.status == RecognitionStatus.READY) == it.shouldMatch }
}
interface TemplateStore {
    val templates: Flow<List<TemplateDefinition>>
    suspend fun execute(command: LedgerCommand): CommandResult
}
