package com.fishese.moneyfly.recognition

import java.time.Instant
import com.fishese.moneyfly.capture.NotificationPayload
import com.fishese.moneyfly.ledger.EntryKind
import org.junit.Assert.*
import org.junit.Test

class SuggestedTemplatesTest {
    private fun payload(source: String, title: String = "", body: String = "") =
        NotificationPayload(source, "synthetic", "synthetic", Instant.EPOCH, Instant.EPOCH, title, body)

    @Test fun catalogIsStrictlySourceScopedAndContainsNoObservedValues() {
        assertEquals(1, SuggestedTemplates.availableTemplates(SuggestedTemplates.GOOGLE_WALLET).size)
        assertEquals(2, SuggestedTemplates.availableTemplates(SuggestedTemplates.PAYME).size)
        assertTrue(SuggestedTemplates.availableTemplates("unknown.synthetic").isEmpty())
    }

    @Test fun walletCandidateExtractsChangingMerchantAndAmountAndRequiresExactStructure() {
        val p = payload(SuggestedTemplates.GOOGLE_WALLET, "SYNTHETIC SHOP", "HK$12.34 with INVENTED CARD")
        val suggestion = SuggestedTemplates.matchingSuggestions(p).single()
        assertTrue(suggestion.preview.canEnable)
        assertFalse(suggestion.definition.enabled)
        assertEquals("12.34", suggestion.preview.current.draft!!.amount)
        assertEquals("SYNTHETIC SHOP", suggestion.preview.current.draft!!.merchant)
        assertTrue(SuggestedTemplates.matchingSuggestions(p.copy(body = "Possible payment HK$12.34")).isEmpty())
    }

    @Test fun octopusUsesDebitInTitleAndNeverMistakesRemainingBalanceForAmount() {
        val p = payload(SuggestedTemplates.SAMSUNG_WALLET, "Smart Octopus HK$7.8 SYNTHETIC TRANSIT", "Remaining value HK$123.4")
        val suggestion = SuggestedTemplates.matchingSuggestions(p).single()
        assertEquals("7.80", suggestion.preview.current.draft!!.amount)
        assertEquals("SYNTHETIC TRANSIT", suggestion.preview.current.draft!!.merchant)
    }

    @Test fun incomingTransferDirectionIsExplicitAndWhitespaceVariantsAreDerivedFromCurrentShape() {
        val p = payload(SuggestedTemplates.PAYME, body = "SYNTHETIC PERSON 向您 轉賬 港 幣 82.75 元， 轉賬訊息為 INVENTED MEMO 。")
        val suggestion = SuggestedTemplates.matchingSuggestions(p).single()
        assertEquals(EntryKind.INCOME, suggestion.preview.current.draft!!.kind)
        assertEquals("82.75", suggestion.preview.current.draft!!.amount)
        assertTrue(suggestion.preview.canEnable)
    }

    @Test fun exactCompletedBankSmsAndAutomaticDebitFormatsStayReviewFirst() {
        val bank = payload(SuggestedTemplates.HSBC, body = "SYNTHETIC SENDER sent HKD2,187.65 to your account INVENTED-ACCOUNT. Tap to view the details.")
        val bankSuggestion = SuggestedTemplates.matchingSuggestions(bank).single()
        assertEquals(EntryKind.INCOME, bankSuggestion.preview.current.draft!!.kind)
        assertEquals("2187.65", bankSuggestion.preview.current.draft!!.amount)
        assertFalse(bankSuggestion.definition.enabled)

        val alipay = payload(SuggestedTemplates.ALIPAY, body = "於 10 月 25 日 12:34 成功通過 SYNTHETIC SERVICE 授權自動付款支付 HKD23.45。點擊查閱付款詳情>")
        val alipaySuggestion = SuggestedTemplates.matchingSuggestions(alipay).single()
        assertEquals(EntryKind.EXPENSE, alipaySuggestion.preview.current.draft!!.kind)
        assertEquals("23.45", alipaySuggestion.preview.current.draft!!.amount)

        val sms = payload(SuggestedTemplates.MESSAGES, title = "Amex", body = "AE: A Card not present transaction of JPY 2,468 from SYNTHETIC RAIL was made on your Card ending with INVENTED on 10/25/2026. If in doubt, call 20000000")
        val smsSuggestion = SuggestedTemplates.matchingSuggestions(sms).single()
        assertEquals("2468", smsSuggestion.preview.current.draft!!.amount)
        assertEquals("SYNTHETIC RAIL", smsSuggestion.preview.current.draft!!.merchant)
        assertTrue(smsSuggestion.preview.canEnable)
    }

    @Test fun aeonAlertVariesMerchantGroupedAmountCardAndDateWithoutFreezingThem() {
        val p = payload(SuggestedTemplates.MESSAGES, title = "Invented sender", body = "AEON: We received a transaction SYNTHETIC SHOP at amount JPY1,250.00 with your credit card **0000 on 25/10/2026 12:34. SYNTHETIC NOTICE")
        val suggestion = SuggestedTemplates.matchingSuggestions(p).single()
        assertTrue(suggestion.preview.canEnable)
        assertEquals("1250", suggestion.preview.current.draft!!.amount)
        assertEquals("SYNTHETIC SHOP", suggestion.preview.current.draft!!.merchant)
        val changed = p.copy(body = "AEON: We received a transaction INVENTED MARKET at amount JPY2,350 with your credit card **1111 on 26/10/2026 18:40. INVENTED NOTICE")
        val result = TemplateEngine.recognize(changed, listOf(suggestion.definition.copy(enabled = true)))
        assertEquals(RecognitionStatus.READY, result.status)
        assertEquals("INVENTED MARKET", result.draft!!.merchant)
        assertEquals("2350", result.draft!!.amount)
        assertTrue(suggestion.definition.patterns.flatMap { it.segments }.filterIsInstance<PatternSegment.Literal>().none { it.text.contains("0000") || it.text.contains("2026") || it.text.contains("SYNTHETIC") })
        assertTrue(SuggestedTemplates.matchingSuggestions(p.copy(sourcePackage = "unknown.synthetic")).isEmpty())
        assertTrue(SuggestedTemplates.matchingSuggestions(p.copy(body = p.body + " verification code")).isEmpty())
    }

    @Test fun bankApprovalAndNonAmexMessagesAreNotSuggested() {
        assertTrue(SuggestedTemplates.matchingSuggestions(payload(SuggestedTemplates.HSBC, "action needed", "Tap to approve a payment of HKD12.34")).isEmpty())
        val body = "AE: A Card not present transaction of JPY 2,468 from SYNTHETIC RAIL was made on your Card ending with INVENTED on 10/25/2026. If in doubt, call 20000000"
        assertTrue(SuggestedTemplates.matchingSuggestions(payload(SuggestedTemplates.MESSAGES, "Unknown sender", body)).isEmpty())
    }

    @Test fun securityApprovalWithdrawalAndGenericAmountsNeverBecomeSuggestions() {
        val source = SuggestedTemplates.GOOGLE_WALLET
        assertTrue(SuggestedTemplates.matchingSuggestions(payload(source, "action needed", "HK$12.34 with INVENTED CARD")).isEmpty())
        assertTrue(SuggestedTemplates.matchingSuggestions(payload(source, "需要批准付款", "HK$12.34 with INVENTED CARD")).isEmpty())
        assertTrue(SuggestedTemplates.matchingSuggestions(payload(source, "SYNTHETIC", "HK$12.34 with INVENTED CARD").copy(textLines = listOf("驗證碼"))).isEmpty())
        assertTrue(SuggestedTemplates.matchingSuggestions(payload(source, "SYNTHETIC", "Possible charge HK$12.34")).isEmpty())
        assertTrue(SuggestedTemplates.matchingSuggestions(payload(source, "SYNTHETIC", "HK$12.34 with INVENTED CARD").copy(truncated = true)).isEmpty())
        assertTrue(SuggestedTemplates.matchingSuggestions(payload(source, "SYNTHETIC", "HK$12.34 with " + "X".repeat(17_000))).isEmpty())
    }
}
