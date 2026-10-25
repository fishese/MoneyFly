package com.fishese.moneyfly.recognition

import com.fishese.moneyfly.capture.NotificationPayload
import com.fishese.moneyfly.ledger.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class TemplateEngineTest {
    private val time = Instant.parse("2026-10-01T12:00:00Z")
    private fun payload(body: String = "Paid HKD 12.34 at SYNTHETIC SHOP.", title: String = "Synthetic purchase") =
        NotificationPayload("invalid.synthetic", "synthetic", "key", time, time, title, body)
    private fun definition() = TemplateDefinition("synthetic", "Synthetic changing format", "invalid.synthetic",
        listOf(TemplateEngine.parsePattern(MessageField.BODY, "Paid HKD {amount} at {merchant}.")),
        listOf(TemplatePredicate(MessageField.TITLE, PredicateOperation.EQUALS, "Synthetic purchase")),
        fixedCurrency = "HKD", fixedCurrencyEvidence = FixedCurrencyEvidence.EXPLICIT_MATCHED_LITERAL, enabled = true, revision = 1)

    @Test fun currentAndGeneratedVariationsChangeAmountAndMerchantWithoutBooking() {
        val preview = TemplateEngine.preview(definition(), payload())
        assertTrue(preview.issues.toString(), preview.canEnable)
        assertEquals(3, preview.examples.size)
        val positives = preview.examples.filter { it.shouldMatch }.map { it.result.draft!! }
        assertNotEquals(positives[0].amount, positives[1].amount)
        assertNotEquals(positives[0].merchant, positives[1].merchant)
        assertEquals(RecognitionStatus.NO_MATCH, preview.examples.last().result.status)
        val changed = TemplateEngine.recognize(payload("Paid HKD 99.10 at 測試商店."), listOf(definition()))
        assertEquals("99.10", changed.draft!!.amount); assertEquals("測試商店", changed.draft!!.merchant)
        assertEquals(TimeOrigin.NOTIFICATION_POSTED, changed.draft!!.timeOrigin)
        assertEquals(TimePrecision.UNKNOWN, changed.draft!!.timePrecision)
    }
    @Test fun patternsAnchorEntireFieldsAndKeepTitleAndBodyBoundariesSeparate() {
        val d = definition().copy(patterns = listOf(TemplateEngine.parsePattern(MessageField.TITLE, "Paid HKD {amount}"),
            TemplateEngine.parsePattern(MessageField.BODY, "Merchant: {merchant}.")), predicates = emptyList())
        assertEquals(RecognitionStatus.READY, TemplateEngine.recognize(payload("Merchant: SYNTHETIC.", "Paid HKD 12.34"), listOf(d)).status)
        assertEquals(RecognitionStatus.NO_MATCH, TemplateEngine.recognize(payload("Merchant: SYNTHETIC. trailing", "Paid HKD 12.34"), listOf(d)).status)
        assertEquals(RecognitionStatus.NO_MATCH, TemplateEngine.recognize(payload("Paid HKD 12.34 Merchant: SYNTHETIC.", "Missing"), listOf(d)).status)
    }
    @Test fun repeatedDelimitersNeverChooseTheFirstAssignment() {
        val result = TemplateEngine.recognize(payload("Paid HKD 12.34 at A at B."), listOf(definition()))
        assertEquals(RecognitionStatus.REVIEW_NEEDED, result.status)
        assertNull(result.draft)
    }
    @Test fun conflictingTemplatesRequireReviewWhileAgreementIsDeterministic() {
        val a = definition()
        assertEquals(RecognitionStatus.REVIEW_NEEDED, TemplateEngine.recognize(payload(), listOf(a, a.copy(id = "other", kind = EntryKind.INCOME))).status)
        val agreed = TemplateEngine.recognize(payload(), listOf(a.copy(id = "z"), a.copy(id = "a")))
        assertEquals(RecognitionStatus.READY, agreed.status); assertEquals("a", agreed.templateId)
    }
    @Test fun invalidDefinitionsAndUnicodeByteBudgetsAreRejected() {
        val d = definition()
        assertTrue(TemplateEngine.validate(d.copy(patterns = listOf(FieldPattern(MessageField.BODY, listOf(PatternSegment.Slot(SlotName.AMOUNT), PatternSegment.Slot(SlotName.MERCHANT)))))).isNotEmpty())
        assertTrue(TemplateEngine.validate(d.copy(patterns = d.patterns + d.patterns)).isNotEmpty())
        assertTrue(TemplateEngine.validate(d.copy(patterns = listOf(TemplateEngine.parsePattern(MessageField.BODY, "{amount}/{amount}")))).isNotEmpty())
        val oversized = d.copy(patterns = listOf(FieldPattern(MessageField.BODY, listOf(PatternSegment.Literal("字".repeat(1400)), PatternSegment.Slot(SlotName.AMOUNT)))))
        assertTrue(TemplateEngine.validate(oversized).any { it.contains("4 KiB") })
        assertEquals(RecognitionStatus.REVIEW_NEEDED, TemplateEngine.recognize(payload(title = "字".repeat(5500)), listOf(d)).status)
        assertThrows(IllegalArgumentException::class.java) { TemplateEngine.parsePattern(MessageField.BODY, "字".repeat(5500)) }
        assertEquals(RecognitionStatus.REVIEW_NEEDED, TemplateEngine.recognize(payload(), List(101) { d.copy(id = "template-$it") }).status)
    }
    @Test fun fixedCurrencyNeedsRealTokenBoundariesAndAmbiguousSymbolsNeedConfirmation() {
        val embedded = definition().copy(patterns = listOf(TemplateEngine.parsePattern(MessageField.BODY, "XHKDY {amount} at {merchant}.")))
        assertTrue(TemplateEngine.validate(embedded).isNotEmpty())
        val dynamic = definition().copy(patterns = listOf(TemplateEngine.parsePattern(MessageField.BODY, "Paid {currency} {amount} at {merchant}.")), fixedCurrency = null, fixedCurrencyEvidence = null)
        assertEquals(RecognitionStatus.REVIEW_NEEDED, TemplateEngine.recognize(payload("Paid $ 12.34 at SAMPLE."), listOf(dynamic)).status)
        assertTrue(TemplateEngine.validate(dynamic.copy(currencyTokens = listOf(CurrencyToken("$", "HKD")))).isNotEmpty())
        assertTrue(TemplateEngine.validate(dynamic.copy(currencyTokens = listOf(CurrencyToken("HKD", "USD")))).isNotEmpty())
        val confirmed = dynamic.copy(currencyTokens = listOf(CurrencyToken("$", "HKD", true)))
        val ready = TemplateEngine.recognize(payload("Paid $ 12.34 at SAMPLE."), listOf(confirmed))
        assertEquals(RecognitionStatus.READY, ready.status); assertTrue(ready.issues.any { it.contains("assumption") })
        assertEquals("HKD", TemplateEngine.recognize(payload("Paid HK$ 12.34 at SAMPLE."), listOf(dynamic)).draft!!.currency)
    }
    @Test fun numberConventionsValidateGroupingAndCurrencyScaleWithoutRounding() {
        val commaGrouping = definition().copy(numberConvention = NumberConvention.DECIMAL_DOT_GROUP_COMMA)
        assertEquals("1234.50", TemplateEngine.recognize(payload("Paid HKD 1,234.50 at SAMPLE."), listOf(commaGrouping)).draft!!.amount)
        assertEquals(RecognitionStatus.REVIEW_NEEDED, TemplateEngine.recognize(payload("Paid HKD 1,23.50 at SAMPLE."), listOf(commaGrouping)).status)
        val decimalComma = definition().copy(numberConvention = NumberConvention.DECIMAL_COMMA_GROUP_DOT)
        assertEquals("1234.50", TemplateEngine.recognize(payload("Paid HKD 1.234,50 at SAMPLE."), listOf(decimalComma)).draft!!.amount)
        assertEquals(RecognitionStatus.REVIEW_NEEDED, TemplateEngine.recognize(payload("Paid HKD 1.001 at SAMPLE."), listOf(definition())).status)
        val jpy = definition().copy(patterns = listOf(TemplateEngine.parsePattern(MessageField.BODY, "Paid JPY {amount} at {merchant}.")), fixedCurrency = "JPY")
        assertEquals(RecognitionStatus.REVIEW_NEEDED, TemplateEngine.recognize(payload("Paid JPY 1.1 at SAMPLE."), listOf(jpy)).status)
        val kwd = definition().copy(patterns = listOf(TemplateEngine.parsePattern(MessageField.BODY, "Paid KWD {amount} at {merchant}.")), fixedCurrency = "KWD")
        assertEquals("1.001", TemplateEngine.recognize(payload("Paid KWD 1.001 at SAMPLE."), listOf(kwd)).draft!!.amount)
    }
    @Test fun fixedMerchantRestrictionAndStaticTitleSyntheticPredicatesStayConsistent() {
        val d = definition().copy(patterns = listOf(TemplateEngine.parsePattern(MessageField.BODY, "Paid HKD {amount}.")), fixedMerchant = "Synthetic Store",
            predicates = listOf(TemplatePredicate(MessageField.TITLE, PredicateOperation.EQUALS, "Synthetic Store purchase"),
                TemplatePredicate(MessageField.TITLE, PredicateOperation.CONTAINS, "Synthetic Store")))
        assertTrue(TemplateEngine.preview(d, payload("Paid HKD 12.34.", "Synthetic Store purchase")).canEnable)
        assertTrue(TemplateEngine.validate(d.copy(predicates = emptyList())).isNotEmpty())
        val containsOnly = d.copy(predicates = d.predicates.drop(1))
        assertEquals(RecognitionStatus.NO_MATCH, TemplateEngine.recognize(payload("Paid HKD 12.34.", "Synthetic StoreTwo purchase"), listOf(containsOnly)).status)
    }
    @Test fun incompleteRawContentNeverBecomesReadyAndWorkBudgetIsShared() {
        listOf(payload().copy(groupSummary = true), payload().copy(redacted = true), payload().copy(truncated = true)).forEach {
            assertEquals(RecognitionStatus.REVIEW_NEEDED, TemplateEngine.recognize(it, listOf(definition())).status)
        }
        val longMerchant = "A".repeat(3000)
        val d = definition().copy(patterns = listOf(FieldPattern(MessageField.BODY, listOf(PatternSegment.Literal("Paid HKD "), PatternSegment.Slot(SlotName.AMOUNT, 64), PatternSegment.Literal(" at "), PatternSegment.Slot(SlotName.MERCHANT, 4096), PatternSegment.Literal(".")))))
        val result = TemplateEngine.recognize(payload("Paid HKD 12.34 at $longMerchant."), List(100) { d.copy(id = "id-$it") })
        assertEquals(RecognitionStatus.REVIEW_NEEDED, result.status)
        assertTrue(result.issues.any { it.contains("work limit") })
    }
}
