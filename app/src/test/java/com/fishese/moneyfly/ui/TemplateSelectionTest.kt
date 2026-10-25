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
        assertThrows(IllegalArgumentException::class.java) {
            compileObservedPatterns(payload("12.50 and 12.50"), listOf(ObservedSlot(MessageField.BODY, "12.50", SlotName.AMOUNT)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            compileObservedPatterns(payload("Shop 12.50"), listOf(ObservedSlot(MessageField.BODY, "12.50", SlotName.AMOUNT), ObservedSlot(MessageField.BODY, "Shop 12.50", SlotName.MERCHANT)))
        }
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
