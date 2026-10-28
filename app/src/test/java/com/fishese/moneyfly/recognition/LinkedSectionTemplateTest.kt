package com.fishese.moneyfly.recognition

import com.fishese.moneyfly.capture.NotificationPayload
import com.fishese.moneyfly.storage.TemplateCodec
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkedSectionTemplateTest {
    private fun payload(body: String) = NotificationPayload("synthetic.app", "test", "test", Instant.EPOCH, Instant.EPOCH, body = body)
    private fun definition(pattern: FieldPattern) = TemplateDefinition("test", "Test", "synthetic.app", listOf(pattern), fixedCurrency = "HKD", fixedCurrencyEvidence = FixedCurrencyEvidence.USER_CONFIRMED_ASSUMPTION, enabled = true)
    private fun literal(value: String) = PatternSegment.Literal(value)
    private val amount = PatternSegment.Slot(SlotName.AMOUNT, linked = true)

    @Test fun linkedAmountsMustAgreeAndAreVariedInPreview() {
        val template = definition(FieldPattern(MessageField.BODY, listOf(literal("Paid "), amount, literal("; total "), amount, literal("."))))
        assertTrue(TemplateEngine.preview(template, payload("Paid 12.34; total 12.34.")).canEnable)
        val changed = TemplateEngine.recognize(payload("Paid 50.00; total 50.00."), listOf(template))
        assertEquals(RecognitionStatus.READY, changed.status)
        assertEquals("50.00", changed.draft!!.amount)
        val disagreement = TemplateEngine.recognize(payload("Paid 50.00; total 70.00."), listOf(template))
        assertEquals(RecognitionStatus.REVIEW_NEEDED, disagreement.status)
        assertTrue(disagreement.issues.any { it.contains("disagree") })
        assertEquals(template, TemplateCodec.decode(TemplateCodec.encode(template)))
    }

    @Test fun adjacentConfirmedCurrencyAndAmountHaveSafeBoundaries() {
        val currency = PatternSegment.Slot(SlotName.CURRENCY, linked = true)
        val number = PatternSegment.Slot(SlotName.AMOUNT, numericOnly = true, linked = true)
        val pattern = FieldPattern(MessageField.BODY, listOf(literal("Paid "), currency, number, literal("; total "), currency, number, literal(".")))
        val template = TemplateDefinition("adjacent", "Synthetic", "synthetic.app", listOf(pattern), currencyTokens = listOf(CurrencyToken("HKD", "HKD", true)), enabled = true)
        val message = payload("Paid HKD72.50; total HKD72.50.")
        assertTrue(TemplateEngine.preview(template, message).canEnable)
        val changed = TemplateEngine.recognize(payload("Paid HKD18.25; total HKD18.25."), listOf(template))
        assertEquals(RecognitionStatus.READY, changed.status)
        assertEquals("18.25", changed.draft!!.amount)
        assertEquals(com.fishese.moneyfly.ledger.TimeOrigin.NOTIFICATION_POSTED, changed.draft!!.timeOrigin)
        assertEquals(message.postedAt, changed.draft!!.occurredAt)
        assertEquals(RecognitionStatus.REVIEW_NEEDED, TemplateEngine.recognize(payload("Paid HKD18.25; total HKD22.25."), listOf(template)).status)
        assertEquals(RecognitionStatus.NO_MATCH, TemplateEngine.recognize(payload("Paid USD18.25; total USD18.25."), listOf(template)).status)
        assertTrue(TemplateEngine.validate(template.copy(currencyTokens = emptyList())).any { it.contains("Separate extracted") })
        assertTrue(TemplateEngine.validate(template.copy(currencyTokens = listOf(CurrencyToken("HKD1", "HKD", true)))).any { it.contains("Separate extracted") })
    }

    @Test fun longContextWithRepeatedFieldsDoesNotExhaustWorkBudget() {
        val account = PatternSegment.Slot(SlotName.ACCOUNT_HINT, linked = true)
        val merchant = PatternSegment.Slot(SlotName.MERCHANT, linked = true)
        val currency = PatternSegment.Slot(SlotName.CURRENCY, linked = true)
        val number = PatternSegment.Slot(SlotName.AMOUNT, numericOnly = true, linked = true)
        val middle = " synthetic fixed context ".repeat(14)
        val segments = listOf(literal("Synthetic header ".repeat(12)), account, literal(" merchant: "), merchant,
            literal(" amount: "), currency, number, literal(middle), account, literal(" merchant: "), merchant,
            literal(" amount: "), currency, number, literal(" synthetic ending".repeat(9)))
        val pattern = FieldPattern(MessageField.BODY, segments)
        val template = TemplateDefinition("long", "Synthetic", "synthetic.app", listOf(pattern), currencyTokens = listOf(CurrencyToken("HKD", "HKD", true)), enabled = true)
        val text = "Synthetic header ".repeat(12) + "A LABEL merchant: A SYNTHETIC SHOP amount: HKD12.34" + middle +
            "A LABEL merchant: A SYNTHETIC SHOP amount: HKD12.34" + " synthetic ending".repeat(9)
        val result = TemplateEngine.preview(template, payload(text))
        assertTrue(result.current.issues.joinToString(), result.canEnable)
        val different = text.replaceFirst("HKD12.34", "HKD67.89")
        assertEquals(RecognitionStatus.REVIEW_NEEDED, TemplateEngine.recognize(payload(different), listOf(template)).status)
    }

    @Test fun linkedValuesAcrossFieldsMustAgree() {
        val title = FieldPattern(MessageField.TITLE, listOf(literal("Total "), amount, literal(".")))
        val body = FieldPattern(MessageField.BODY, listOf(literal("Paid "), amount, literal(".")))
        val template = definition(body).copy(patterns = listOf(title, body))
        val same = payload("Paid 12.34.").copy(title = "Total 12.34.")
        assertEquals(RecognitionStatus.READY, TemplateEngine.recognize(same, listOf(template)).status)
        val conflict = same.copy(title = "Total 56.78.")
        assertEquals(RecognitionStatus.REVIEW_NEEDED, TemplateEngine.recognize(conflict, listOf(template)).status)
    }

    @Test fun unlinkedRepeatedSlotsAreRejected() {
        val unlinked = PatternSegment.Slot(SlotName.AMOUNT)
        val template = definition(FieldPattern(MessageField.BODY, listOf(literal("Paid "), unlinked, literal("; total "), unlinked, literal("."))))
        assertTrue(TemplateEngine.validate(template).any { it.contains("Link repeated") })
    }

    @Test fun selectedSectionMustMatchUniquelyAndHaveBothBoundaries() {
        val pattern = FieldPattern(MessageField.BODY, listOf(literal("Purchase: "), PatternSegment.Slot(SlotName.AMOUNT), literal(" confirmed.")), section = true)
        val template = definition(pattern)
        assertTrue(TemplateEngine.preview(template, payload("header Purchase: 12.34 confirmed. footer")).canEnable)
        assertEquals(RecognitionStatus.READY, TemplateEngine.recognize(payload("Changed header Purchase: 56.78 confirmed. changed footer"), listOf(template)).status)
        assertEquals(RecognitionStatus.REVIEW_NEEDED, TemplateEngine.recognize(payload("Purchase: 12.34 confirmed. Purchase: 56.78 confirmed."), listOf(template)).status)
        assertEquals(template, TemplateCodec.decode(TemplateCodec.encode(template)))
        val missingEnd = definition(pattern.copy(segments = listOf(literal("Purchase: "), PatternSegment.Slot(SlotName.AMOUNT))))
        assertTrue(TemplateEngine.validate(missingEnd).any { it.contains("before and after") })
        val weak = definition(pattern.copy(segments = listOf(literal("("), PatternSegment.Slot(SlotName.AMOUNT), literal(")"))))
        assertTrue(TemplateEngine.validate(weak).any { it.contains("identifying text") })
    }
}
