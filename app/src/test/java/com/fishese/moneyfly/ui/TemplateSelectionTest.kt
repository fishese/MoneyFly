package com.fishese.moneyfly.ui

import com.fishese.moneyfly.capture.NotificationPayload
import com.fishese.moneyfly.recognition.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class TemplateSelectionTest {
    private fun payload(body: String) = NotificationPayload("synthetic.app", "synthetic", "synthetic", Instant.EPOCH, Instant.EPOCH, title = "Paid 12.50", body = body)

    @Test fun selectedSpansStayInTheirOwnCompleteNotificationField() {
        val patterns = compileObservedPatterns(payload("At Example Shop."), listOf(ObservedSlot(MessageField.TITLE, "12.50", SlotName.AMOUNT), ObservedSlot(MessageField.BODY, "Example Shop", SlotName.MERCHANT)))
        assertEquals(listOf(PatternSegment.Literal("Paid "), PatternSegment.Slot(SlotName.AMOUNT)), patterns.single { it.field == MessageField.TITLE }.segments)
        assertEquals(listOf(PatternSegment.Literal("At "), PatternSegment.Slot(SlotName.MERCHANT), PatternSegment.Literal(".")), patterns.single { it.field == MessageField.BODY }.segments)
    }

    @Test fun ambiguousAndOverlappingValuesCannotCreateASilentlyDifferentTemplate() {
        val repeated = compileObservedPatterns(payload("12.50 and 12.50"), listOf(ObservedSlot(MessageField.BODY, "12.50", SlotName.AMOUNT)))
        val slots = repeated.single().segments.filterIsInstance<PatternSegment.Slot>()
        assertEquals(2, slots.size)
        assertTrue(slots.all { it.linked })
        assertThrows(IllegalArgumentException::class.java) {
            compileObservedPatterns(payload("Shop 12.50"), listOf(ObservedSlot(MessageField.BODY, "12.50", SlotName.AMOUNT), ObservedSlot(MessageField.BODY, "Shop 12.50", SlotName.MERCHANT)))
        }
    }

    @Test fun automaticallyRepeatedAmountChangesTogetherAndDisagreementNeedsReview() {
        val original = payload("Paid 12.50; total 12.50.")
        val patterns = compileObservedPatterns(original, listOf(ObservedSlot(MessageField.BODY, "12.50", SlotName.AMOUNT)))
        val template = TemplateDefinition("repeated", "Synthetic", "synthetic.app", patterns, fixedCurrency = "HKD",
            fixedCurrencyEvidence = FixedCurrencyEvidence.USER_CONFIRMED_ASSUMPTION, enabled = true)
        assertTrue(TemplateEngine.preview(template, original).canEnable)
        assertEquals(RecognitionStatus.READY, TemplateEngine.recognize(payload("Paid 23.45; total 23.45."), listOf(template)).status)
        assertEquals(RecognitionStatus.REVIEW_NEEDED, TemplateEngine.recognize(payload("Paid 23.45; total 67.89."), listOf(template)).status)
    }

    @Test fun adjacentRepeatedCurrencyAndAmountCanBeTaughtSafely() {
        val original = payload("Paid HKD72.50; total HKD72.50.")
        val patterns = compileObservedPatterns(original, listOf(
            ObservedSlot(MessageField.BODY, "HKD", SlotName.CURRENCY),
            ObservedSlot(MessageField.BODY, "72.50", SlotName.AMOUNT)))
        val slots = patterns.single().segments.filterIsInstance<PatternSegment.Slot>()
        assertEquals(4, slots.size)
        assertTrue(slots.filter { it.name == SlotName.AMOUNT }.all { it.numericOnly && it.linked })
        val template = TemplateDefinition("adjacent", "Synthetic", "synthetic.app", patterns,
            currencyTokens = listOf(CurrencyToken("HKD", "HKD", true)), enabled = true)
        assertTrue(TemplateEngine.preview(template, original).canEnable)
        assertEquals(RecognitionStatus.REVIEW_NEEDED, TemplateEngine.recognize(payload("Paid HKD10.00; total HKD20.00."), listOf(template)).status)
    }

    @Test fun selectedReferenceAndAccountHintVaryInGeneratedPositivePreviews() {
        val original = payload("Paid HKD 12.50 at Example Shop; ref REF-001; account CARD-1234.")
        val patterns = compileObservedPatterns(original, listOf(
            ObservedSlot(MessageField.BODY, "12.50", SlotName.AMOUNT),
            ObservedSlot(MessageField.BODY, "Example Shop", SlotName.MERCHANT),
            ObservedSlot(MessageField.BODY, "REF-001", SlotName.REFERENCE),
            ObservedSlot(MessageField.BODY, "CARD-1234", SlotName.ACCOUNT_HINT),
        ))
        val definition = TemplateDefinition("synthetic", "Synthetic varying identifiers", "synthetic.app", patterns,
            fixedCurrency = "HKD", fixedCurrencyEvidence = FixedCurrencyEvidence.USER_CONFIRMED_ASSUMPTION)
        val report = TemplateEngine.preview(definition, original)
        assertTrue(report.canEnable)
        val positives = report.examples.filter { it.shouldMatch }
        assertEquals(2, positives.size)
        assertTrue(positives.all { it.result.status == RecognitionStatus.READY })
        assertNotEquals(positives[0].payload.body, positives[1].payload.body)
        assertTrue(positives[0].payload.body.contains("A-SYNTHETIC"))
        assertTrue(positives[1].payload.body.contains("B-SYNTHETIC"))
        assertTrue(positives[0].payload.body.contains("A LABEL"))
        assertTrue(positives[1].payload.body.contains("B LABEL"))
        assertTrue(positives.all { it.result.draft?.accountId == null })
    }

    @Test fun expandedBodyTemplateDoesNotFreezeDuplicatedDynamicShortText() {
        val original = payload("Paid 12.50 at Example Shop…").copy(title = "Example Shop · 12.50", bigText = "Paid HKD 12.50 at Example Shop.")
        val selections = listOf(ObservedSlot(MessageField.BIG_TEXT, "12.50", SlotName.AMOUNT), ObservedSlot(MessageField.BIG_TEXT, "Example Shop", SlotName.MERCHANT))
        val patterns = compileObservedPatterns(original, selections)
        val predicates = compileStaticRestrictions(original, selections, patterns)
        assertEquals(MessageField.BIG_TEXT, patterns.single().field)
        assertTrue(predicates.isEmpty())
        val definition = TemplateDefinition("expanded", "Synthetic expanded format", "synthetic.app", patterns, predicates,
            fixedCurrency = "HKD", fixedCurrencyEvidence = FixedCurrencyEvidence.USER_CONFIRMED_ASSUMPTION, enabled = true)
        assertTrue(TemplateEngine.preview(definition, original).canEnable)
        val changed = original.copy(title = "Other Market · 99.50", body = "Paid 99.50 at Other Market…", bigText = "Paid HKD 99.50 at Other Market.")
        val recognized = TemplateEngine.recognize(changed, listOf(definition))
        assertEquals(RecognitionStatus.READY, recognized.status)
        assertEquals("99.50", recognized.draft?.amount)
        assertEquals("Other Market", recognized.draft?.merchant)
        assertEquals(RecognitionStatus.NO_MATCH, TemplateEngine.recognize(changed.copy(bigText = "Prefix " + changed.bigText), listOf(definition)).status)
    }
}
