package com.fishese.moneyfly.recognition

import com.fishese.moneyfly.capture.NotificationPayload
import com.fishese.moneyfly.ledger.EntryKind
import java.util.Locale

enum class SuggestionEvidence { OBSERVED_MESSAGE, HISTORICAL_FORMAT }

data class SuggestedFormat(
    val id: String,
    val sourcePackage: String,
    val label: String,
    val evidence: SuggestionEvidence = SuggestionEvidence.HISTORICAL_FORMAT,
)

data class TemplateSuggestion(
    val format: SuggestedFormat,
    val definition: TemplateDefinition,
    val preview: TemplatePreview,
)

/**
 * Privacy-safe, opt-in candidates. This catalog contains format structure only:
 * it never embeds an observed merchant, amount, account label, or message sample.
 */
object SuggestedTemplates {
    const val GOOGLE_WALLET = "com.google.android.apps.walletnfcrel"
    const val SAMSUNG_WALLET = "com.samsung.android.spay"
    const val PAYME = "hk.com.hsbc.paymefromhsbc"
    const val HSBC = "hk.com.hsbc.hsbchkmobilebanking"
    const val ALIPAY = "hk.alipay.wallet"
    const val MESSAGES = "com.google.android.apps.messaging"

    private val formats = listOf(
        SuggestedFormat("google-wallet-payment", GOOGLE_WALLET, "Wallet payment"),
        SuggestedFormat("smart-octopus-debit", SAMSUNG_WALLET, "Smart Octopus debit"),
        SuggestedFormat("payme-incoming", PAYME, "Incoming transfer"),
        SuggestedFormat("payme-completed-payment", PAYME, "Completed payment"),
        SuggestedFormat("hsbc-incoming", HSBC, "Incoming bank transfer"),
        SuggestedFormat("alipay-completed-debit", ALIPAY, "Completed automatic payment"),
        SuggestedFormat("amex-card-sms", MESSAGES, "Card transaction SMS"),
        SuggestedFormat("aeon-card-sms", MESSAGES, "AEON transaction alert"),
    )

    fun availableTemplates(sourcePackage: String): List<SuggestedFormat> =
        formats.filter { it.sourcePackage == sourcePackage }

    fun matchingSuggestions(payload: NotificationPayload): List<TemplateSuggestion> {
        if (payload.redacted || payload.truncated || payload.groupSummary || inputBytes(payload) > 16_384 || excluded(payload)) return emptyList()
        val candidates = when (payload.sourcePackage) {
            GOOGLE_WALLET -> google(payload)
            SAMSUNG_WALLET -> octopus(payload)
            PAYME -> payMe(payload)
            HSBC -> hsbcIncoming(payload)
            ALIPAY -> alipayCompleted(payload)
            MESSAGES -> amexSms(payload) + aeonSms(payload)
            else -> emptyList()
        }
        return candidates.mapNotNull { (format, definition) ->
            val preview = TemplateEngine.preview(definition, payload)
            if (preview.canEnable) TemplateSuggestion(format.copy(evidence = SuggestionEvidence.OBSERVED_MESSAGE), definition, preview) else null
        }
    }

    private fun google(p: NotificationPayload): List<Pair<SuggestedFormat, TemplateDefinition>> {
        if (p.title.isBlank()) return emptyList()
        val match = Regex("^(HK\\$|HKD|USD|JPY|SGD)\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s+with\\s+(.+)$").matchEntire(p.body) ?: return emptyList()
        val token = match.groupValues[1]
        val currency = if (token == "HK$") "HKD" else token
        val amountStart = match.groups[2]!!.range.first
        val accountStart = match.groups[3]!!.range.first
        val definition = TemplateDefinition(
            id = "suggested-google-wallet-${currency.lowercase(Locale.ROOT)}", name = "Wallet payment ($currency)", sourcePackage = GOOGLE_WALLET,
            patterns = listOf(
                FieldPattern(MessageField.TITLE, listOf(PatternSegment.Slot(SlotName.MERCHANT))),
                FieldPattern(MessageField.BODY, listOf(PatternSegment.Literal(p.body.substring(0, amountStart)), PatternSegment.Slot(SlotName.AMOUNT, 64), PatternSegment.Literal(p.body.substring(match.groups[2]!!.range.last + 1, accountStart)), PatternSegment.Slot(SlotName.ACCOUNT_HINT))),
            ),
            fixedCurrency = currency, fixedCurrencyEvidence = FixedCurrencyEvidence.EXPLICIT_MATCHED_LITERAL,
            numberConvention = NumberConvention.DECIMAL_DOT_GROUP_COMMA,
        )
        return listOf(formats.first { it.id == "google-wallet-payment" } to definition)
    }

    private fun octopus(p: NotificationPayload): List<Pair<SuggestedFormat, TemplateDefinition>> {
        val match = Regex("^Smart Octopus\\s+(HK\\$|HKD)\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s+(.+)$").matchEntire(p.title) ?: return emptyList()
        val currency = "HKD"
        val amount = match.groups[2]!!
        val merchant = match.groups[3]!!
        val definition = TemplateDefinition(
            id = "suggested-smart-octopus-hkd", name = "Smart Octopus debit", sourcePackage = SAMSUNG_WALLET,
            patterns = listOf(FieldPattern(MessageField.TITLE, listOf(
                PatternSegment.Literal(p.title.substring(0, amount.range.first)), PatternSegment.Slot(SlotName.AMOUNT, 64, numericOnly = true),
                PatternSegment.Literal(p.title.substring(amount.range.last + 1, merchant.range.first)), PatternSegment.Slot(SlotName.MERCHANT),
            ))), fixedCurrency = currency, fixedCurrencyEvidence = FixedCurrencyEvidence.EXPLICIT_MATCHED_LITERAL,
            numberConvention = NumberConvention.DECIMAL_DOT_GROUP_COMMA,
        )
        return listOf(formats.first { it.id == "smart-octopus-debit" } to definition)
    }

    private fun payMe(p: NotificationPayload): List<Pair<SuggestedFormat, TemplateDefinition>> {
        incomingPayMe(p)?.let { return listOf(formats.first { it.id == "payme-incoming" } to it) }
        completedPayMe(p)?.let { return listOf(formats.first { it.id == "payme-completed-payment" } to it) }
        return emptyList()
    }

    private fun hsbcIncoming(p: NotificationPayload): List<Pair<SuggestedFormat, TemplateDefinition>> {
        val match = Regex("^(.+?)(\\s+sent\\s+)(HKD|USD|JPY|SGD)\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)(\\s+to your account\\s+)(.+?)(\\.\\s+Tap to view the details\\.)$").matchEntire(p.body) ?: return emptyList()
        val merchant = match.groups[1]!!; val amount = match.groups[4]!!; val account = match.groups[6]!!
        val currency = match.groupValues[3]
        val definition = TemplateDefinition(
            id = "suggested-hsbc-incoming-${currency.lowercase(Locale.ROOT)}", name = "Incoming bank transfer", sourcePackage = HSBC,
            patterns = listOf(FieldPattern(MessageField.BODY, listOf(
                PatternSegment.Slot(SlotName.MERCHANT), PatternSegment.Literal(p.body.substring(merchant.range.last + 1, amount.range.first)),
                PatternSegment.Slot(SlotName.AMOUNT, 64, numericOnly = true), PatternSegment.Literal(p.body.substring(amount.range.last + 1, account.range.first)),
                PatternSegment.Slot(SlotName.ACCOUNT_HINT), PatternSegment.Literal(p.body.substring(account.range.last + 1)),
            ))), kind = EntryKind.INCOME, fixedCurrency = currency, fixedCurrencyEvidence = FixedCurrencyEvidence.EXPLICIT_MATCHED_LITERAL,
            numberConvention = NumberConvention.DECIMAL_DOT_GROUP_COMMA,
        )
        return listOf(formats.first { it.id == "hsbc-incoming" } to definition)
    }

    private fun alipayCompleted(p: NotificationPayload): List<Pair<SuggestedFormat, TemplateDefinition>> {
        val match = Regex("^(於\\s*)(.+?)(\\s*成功\\s*通過\\s*)(.+?)(\\s*授權\\s*自動\\s*付款\\s*支付\\s*)(HKD|USD|JPY|SGD)\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)(\\s*。\\s*點擊\\s*查閱\\s*付款\\s*詳情\\s*>?)$").matchEntire(p.body) ?: return emptyList()
        val notes = match.groups[2]!!; val merchant = match.groups[4]!!; val amount = match.groups[7]!!
        val currency = match.groupValues[6]
        val definition = TemplateDefinition(
            id = "suggested-alipay-completed-${currency.lowercase(Locale.ROOT)}", name = "Completed automatic payment", sourcePackage = ALIPAY,
            patterns = listOf(FieldPattern(MessageField.BODY, listOf(
                PatternSegment.Literal(p.body.substring(0, notes.range.first)), PatternSegment.Slot(SlotName.NOTES),
                PatternSegment.Literal(p.body.substring(notes.range.last + 1, merchant.range.first)), PatternSegment.Slot(SlotName.MERCHANT),
                PatternSegment.Literal(p.body.substring(merchant.range.last + 1, amount.range.first)), PatternSegment.Slot(SlotName.AMOUNT, 64, numericOnly = true),
                PatternSegment.Literal(p.body.substring(amount.range.last + 1)),
            ))), kind = EntryKind.EXPENSE, fixedCurrency = currency, fixedCurrencyEvidence = FixedCurrencyEvidence.EXPLICIT_MATCHED_LITERAL,
            numberConvention = NumberConvention.DECIMAL_DOT_GROUP_COMMA,
        )
        return listOf(formats.first { it.id == "alipay-completed-debit" } to definition)
    }

    private fun aeonSms(p: NotificationPayload): List<Pair<SuggestedFormat, TemplateDefinition>> {
        val match = Regex("^(AEON:\\s*We received a transaction\\s+)(.+?)(\\s+at amount\\s+)(JPY|HKD|USD|SGD)\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)(\\s+with your credit card\\s+)(.+?)(\\s+on\\s+)(.+)$").matchEntire(p.body) ?: return emptyList()
        val merchant = match.groups[2]!!; val amount = match.groups[5]!!; val account = match.groups[7]!!; val date = match.groups[9]!!
        val currency = match.groupValues[4]
        val definition = TemplateDefinition(
            id = "suggested-aeon-card-${currency.lowercase(Locale.ROOT)}", name = "AEON transaction alert", sourcePackage = MESSAGES,
            patterns = listOf(FieldPattern(MessageField.BODY, listOf(
                PatternSegment.Literal(p.body.substring(0, merchant.range.first)), PatternSegment.Slot(SlotName.MERCHANT),
                PatternSegment.Literal(p.body.substring(merchant.range.last + 1, amount.range.first)), PatternSegment.Slot(SlotName.AMOUNT, 64, numericOnly = true),
                PatternSegment.Literal(p.body.substring(amount.range.last + 1, account.range.first)), PatternSegment.Slot(SlotName.ACCOUNT_HINT),
                PatternSegment.Literal(p.body.substring(account.range.last + 1, date.range.first)), PatternSegment.Slot(SlotName.VARIABLE, 1024),
            ))), fixedCurrency = currency, fixedCurrencyEvidence = FixedCurrencyEvidence.EXPLICIT_MATCHED_LITERAL,
            numberConvention = NumberConvention.DECIMAL_DOT_GROUP_COMMA,
        )
        return listOf(formats.first { it.id == "aeon-card-sms" } to definition)
    }

    private fun amexSms(p: NotificationPayload): List<Pair<SuggestedFormat, TemplateDefinition>> {
        if (p.title != "Amex") return emptyList()
        val match = Regex("^(AE:\\s*A\\s+Card not present transaction of\\s+)(JPY|HKD|USD|SGD)\\s+([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)(\\s+from\\s+)(.+?)(\\s+was made on your Card ending with\\s+)(.+?)(\\s+on\\s+)(.+?)(\\.\\s+If in doubt,\\s+call\\s+)([0-9 +()\\-]+)$").matchEntire(p.body) ?: return emptyList()
        val amount = match.groups[3]!!; val merchant = match.groups[5]!!; val account = match.groups[7]!!; val date = match.groups[9]!!; val hotline = match.groups[11]!!
        val currency = match.groupValues[2]
        val definition = TemplateDefinition(
            id = "suggested-amex-card-${currency.lowercase(Locale.ROOT)}", name = "Card transaction SMS", sourcePackage = MESSAGES,
            patterns = listOf(FieldPattern(MessageField.BODY, listOf(
                PatternSegment.Literal(p.body.substring(0, amount.range.first)), PatternSegment.Slot(SlotName.AMOUNT, 64, numericOnly = true),
                PatternSegment.Literal(p.body.substring(amount.range.last + 1, merchant.range.first)), PatternSegment.Slot(SlotName.MERCHANT),
                PatternSegment.Literal(p.body.substring(merchant.range.last + 1, account.range.first)), PatternSegment.Slot(SlotName.ACCOUNT_HINT),
                PatternSegment.Literal(p.body.substring(account.range.last + 1, date.range.first)), PatternSegment.Slot(SlotName.NOTES),
                PatternSegment.Literal(p.body.substring(date.range.last + 1, hotline.range.first)), PatternSegment.Slot(SlotName.VARIABLE),
            ))), predicates = listOf(TemplatePredicate(MessageField.TITLE, PredicateOperation.EQUALS, "Amex")),
            kind = EntryKind.EXPENSE, fixedCurrency = currency, fixedCurrencyEvidence = FixedCurrencyEvidence.EXPLICIT_MATCHED_LITERAL,
            numberConvention = NumberConvention.DECIMAL_DOT_GROUP_COMMA,
        )
        return listOf(formats.first { it.id == "amex-card-sms" } to definition)
    }

    private fun incomingPayMe(p: NotificationPayload): TemplateDefinition? {
        val match = Regex("^(.+?)(\\s*向您\\s*轉賬\\s*港\\s*幣\\s*)([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)(\\s*元\\s*[，,]\\s*轉賬\\s*訊\\s*息\\s*為\\s*)(.+?)(\\s*。)?$").matchEntire(p.body) ?: return null
        return payMeDefinition(p, match, "suggested-payme-incoming", "Incoming transfer", EntryKind.INCOME)
    }

    private fun completedPayMe(p: NotificationPayload): TemplateDefinition? {
        val match = Regex("^(您已(?:成功)?\\s*(?:向|付款給)\\s*)(.+?)(\\s*(?:支付|轉賬|付款)\\s*港\\s*幣\\s*)([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)(\\s*元\\s*。?)$").matchEntire(p.body) ?: return null
        val merchant = match.groups[2]!!; val amount = match.groups[4]!!
        return TemplateDefinition(
            id = "suggested-payme-completed", name = "Completed payment", sourcePackage = PAYME,
            patterns = listOf(FieldPattern(MessageField.BODY, listOf(
                PatternSegment.Literal(p.body.substring(0, merchant.range.first)), PatternSegment.Slot(SlotName.MERCHANT),
                PatternSegment.Literal(p.body.substring(merchant.range.last + 1, amount.range.first)), PatternSegment.Slot(SlotName.AMOUNT, 64),
                PatternSegment.Literal(p.body.substring(amount.range.last + 1)),
            ))), fixedCurrency = "HKD", fixedCurrencyEvidence = FixedCurrencyEvidence.EXPLICIT_MATCHED_LITERAL,
            kind = EntryKind.EXPENSE, numberConvention = NumberConvention.DECIMAL_DOT_GROUP_COMMA,
        )
    }

    private fun payMeDefinition(p: NotificationPayload, match: MatchResult, id: String, name: String, kind: EntryKind): TemplateDefinition {
        val merchant = match.groups[1]!!; val amount = match.groups[3]!!; val notes = match.groups[5]!!
        val segments = mutableListOf<PatternSegment>(
            PatternSegment.Slot(SlotName.MERCHANT), PatternSegment.Literal(p.body.substring(merchant.range.last + 1, amount.range.first)),
            PatternSegment.Slot(SlotName.AMOUNT, 64), PatternSegment.Literal(p.body.substring(amount.range.last + 1, notes.range.first)),
        )
        segments += PatternSegment.Slot(SlotName.NOTES)
        match.groups[6]?.let { segments += PatternSegment.Literal(p.body.substring(it.range.first)) }
        return TemplateDefinition(id, name, PAYME, listOf(FieldPattern(MessageField.BODY, segments)), kind = kind,
            fixedCurrency = "HKD", fixedCurrencyEvidence = FixedCurrencyEvidence.EXPLICIT_MATCHED_LITERAL,
            numberConvention = NumberConvention.DECIMAL_DOT_GROUP_COMMA)
    }

    private fun excluded(p: NotificationPayload): Boolean {
        val text = listOf(p.title, p.body, p.bigText, p.subText, p.textLines.joinToString(" ")).joinToString(" ").lowercase(Locale.ROOT)
        return listOf(
            "action needed", "approve a payment", "authorization", "verification code", "one-time password", "otp", "withdrawn", "payment due",
            "授權請求", "請授權", "需要授權", "批准付款", "驗證", "一次性密碼", "提款",
        ).any(text::contains)
    }

    private fun inputBytes(p: NotificationPayload): Int =
        listOf(p.title, p.body, p.bigText, p.subText, p.textLines.joinToString("\n")).sumOf { it.toByteArray(Charsets.UTF_8).size }
}
