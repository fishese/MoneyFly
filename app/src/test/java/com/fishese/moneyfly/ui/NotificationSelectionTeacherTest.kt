package com.fishese.moneyfly.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import com.fishese.moneyfly.capture.NotificationPayload
import com.fishese.moneyfly.ledger.TimeOrigin
import com.fishese.moneyfly.ledger.TimePrecision
import com.fishese.moneyfly.recognition.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class NotificationSelectionTeacherTest {
    @get:Rule val compose = createComposeRule()
    private fun payload(body: String) = NotificationPayload("synthetic.app", "synthetic", "synthetic", Instant.EPOCH, Instant.EPOCH, "Synthetic store", body)

    @Test fun selectsAndAdjustsOneCharacterWithoutTouchingOtherAssignments() {
        val assignments = mutableStateMapOf<SlotName, TaughtSpan>()
        val text = "Paid 12.34 at synthetic shop."
        compose.setContent { MaterialTheme { NotificationSelectionTeacher(payload(text), assignments, true, { slot, span -> assignments[slot] = span }, { assignments.remove(it) }) } }
        compose.onNodeWithTag("teacher_text_BODY").performTextInputSelection(TextRange(5, 10))
        compose.onNodeWithTag("teacher_assigned_value").assertTextEquals("12.34")
        compose.onNodeWithTag("teacher_end_back").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("teacher_assigned_value").assertTextEquals("12.3")
        compose.runOnIdle { assertEquals(TaughtSpan(MessageField.BODY, 5, 9), assignments[SlotName.AMOUNT]) }
    }

    @Test fun selectingCurrencyAndNumberMarksOnlyAmount() {
        val assignments = mutableStateMapOf<SlotName, TaughtSpan>()
        val text = "Paid HKD72.50 to synthetic merchant"
        compose.setContent { MaterialTheme { NotificationSelectionTeacher(payload(text), assignments, true, { slot, span -> assignments[slot] = span }, { assignments.remove(it) }) } }
        compose.onNodeWithTag("teacher_text_BODY").performTextInputSelection(TextRange(5, 13))
        compose.onNodeWithTag("teacher_assigned_value").assertTextEquals("72.50")
        compose.runOnIdle { assertEquals(TaughtSpan(MessageField.BODY, 8, 13), assignments[SlotName.AMOUNT]) }
    }

    @Test fun selectedValueIsColoredAfterPauseEvenWhileFieldHasFocus() {
        val assignments = mutableStateMapOf<SlotName, TaughtSpan>()
        compose.setContent { MaterialTheme { NotificationSelectionTeacher(payload("Paid HKD72.50."), assignments, true, { slot, span -> assignments[slot] = span }, { assignments.remove(it) }) } }
        val field = compose.onNodeWithTag("teacher_text_BODY")
        field.performClick().performTextInputSelection(TextRange(5, 13))
        compose.waitUntil(5000) {
            val value = field.fetchSemanticsNode().config[SemanticsProperties.EditableText]
            value.spanStyles.any { it.start == 8 && it.end == 13 }
        }
        compose.onNodeWithTag("teacher_assigned_value").assertTextEquals("72.50")
    }

    @Test fun amountTrimmingRequiresRecognizedCurrencyAndOnlyNumber() {
        fun selected(text: String): String {
            val span = amountSelection(text, TaughtSpan(MessageField.BODY, 0, text.length))
            return text.substring(span.start, span.end)
        }
        assertEquals("1,234.50", selected("HK$1,234.50"))
        assertEquals("72.50", selected("72.50 HKD"))
        assertEquals("72.50", selected("72.50"))
        assertEquals("XYZ72.50", selected("XYZ72.50"))
        assertEquals("HKD72.50 at a shop", selected("HKD72.50 at a shop"))
    }

    @Test fun expandingLongExpandedBodyPreservesSelection() {
        val assignments = mutableStateMapOf<SlotName, TaughtSpan>()
        val longText = "Synthetic line\n".repeat(20) + "Amount 12.34"
        val item = payload("Short message").copy(bigText = longText)
        compose.setContent { MaterialTheme { androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
            NotificationSelectionTeacher(item, assignments, true, { slot, span -> assignments[slot] = span }, { assignments.remove(it) })
        } } }
        compose.onNodeWithTag("teacher_extra_fields").performScrollTo().performClick()
        val field = compose.onNodeWithTag("teacher_text_BIG_TEXT")
        field.performScrollTo().performTextInputSelection(TextRange(longText.length - 5, longText.length))
        val compactHeight = field.getUnclippedBoundsInRoot().let { it.bottom - it.top }
        compose.onNodeWithTag("teacher_resize_BIG_TEXT").performScrollTo().performClick()
        assertTrue(field.getUnclippedBoundsInRoot().let { it.bottom - it.top } > compactHeight)
        compose.runOnIdle { assertEquals(TaughtSpan(MessageField.BIG_TEXT, longText.length - 5, longText.length), assignments[SlotName.AMOUNT]) }
        compose.onNodeWithTag("teacher_resize_BIG_TEXT").performScrollTo().performClick()
        assertEquals(compactHeight, field.getUnclippedBoundsInRoot().let { it.bottom - it.top })
    }

    @Test fun repeatedValuesShowWarningAndSectionStillWorks() {
        val assignments = mutableStateMapOf<SlotName, TaughtSpan>()
        val sections = mutableStateMapOf<MessageField, TaughtSpan>()
        val active = mutableStateOf<SlotName?>(SlotName.AMOUNT)
        val text = "Header Paid 12.34; total 12.34. Footer"
        compose.setContent { MaterialTheme { androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
            NotificationSelectionTeacher(payload(text), assignments, true, { slot, span -> assignments[slot] = span }, { assignments.remove(it) }, sections = sections,
                onSection = { field, section -> if (section == null) sections.remove(field) else sections[field] = section }, active = active.value)
        } } }
        compose.onNodeWithTag("teacher_text_BODY").performScrollTo().performTextInputSelection(TextRange(12, 17))
        compose.onNodeWithTag("teacher_repeated_warning").performScrollTo().assertTextContains("appears 2 times", substring = true)
        compose.onNodeWithTag("teacher_link_repeats").assertDoesNotExist()
        compose.runOnIdle { active.value = null }
        compose.onNodeWithTag("teacher_text_BODY").performScrollTo().performTextInputSelection(TextRange(7, 30))
        compose.runOnIdle { assertEquals(TaughtSpan(MessageField.BODY, 7, 30), sections[MessageField.BODY]) }
    }

    @Test fun explicitOffsetsDisambiguateRepeatedValuesAndRejectOverlap() {
        val payload = payload("Paid 12.34; balance 12.34")
        val first = ObservedSlot(MessageField.BODY, "12.34", SlotName.AMOUNT, 5)
        val pattern = compileObservedPatterns(payload, listOf(first)).single()
        assertEquals(2, pattern.segments.filterIsInstance<PatternSegment.Slot>().size)
        assertTrue(pattern.segments.filterIsInstance<PatternSegment.Slot>().all { it.linked })
        assertEquals(pattern, compileObservedPatterns(payload, listOf(first.copy(start = null))).single())
        assertThrows(IllegalArgumentException::class.java) { compileObservedPatterns(payload, listOf(first, ObservedSlot(MessageField.BODY, "2.3", SlotName.MERCHANT, 6))) }
    }

    @Test fun dateExtractionIsStrictAndUsesDatePrecision() {
        val pattern = FieldPattern(MessageField.BODY, listOf(PatternSegment.Literal("Paid "), PatternSegment.Slot(SlotName.AMOUNT), PatternSegment.Literal(" on "), PatternSegment.Slot(SlotName.DATE)))
        val template = TemplateDefinition("synthetic", "Synthetic", "synthetic.app", listOf(pattern), fixedCurrency = "HKD", fixedCurrencyEvidence = FixedCurrencyEvidence.USER_CONFIRMED_ASSUMPTION, enabled = true)
        val valid = payload("Paid 12.34 on 2026-02-03")
        val preview = TemplateEngine.preview(template, valid)
        assertTrue(preview.canEnable)
        val draft = preview.current.draft!!
        assertEquals(TimeOrigin.EXTRACTED, draft.timeOrigin)
        assertEquals(TimePrecision.DATE, draft.timePrecision)
        assertEquals(LocalDate.of(2026, 2, 3), draft.occurredAt.atZone(ZoneId.systemDefault()).toLocalDate())
        val invalid = TemplateEngine.recognize(payload("Paid 12.34 on 03/02/2026"), listOf(template))
        assertEquals(RecognitionStatus.REVIEW_NEEDED, invalid.status)
        assertTrue(invalid.issues.any { it.contains("YYYY-MM-DD") })
    }

    @Test fun boundaryAdjustmentMovesAcrossWholeUnicodeCharacters() {
        assertEquals(1, moveTextBoundary("A😀B", 3, -1))
        assertEquals(3, moveTextBoundary("A😀B", 1, 1))
    }
}
