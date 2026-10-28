package com.fishese.moneyfly.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.fishese.moneyfly.capture.NotificationPayload
import com.fishese.moneyfly.ledger.*
import com.fishese.moneyfly.recognition.*
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class TemplateEditorTest {
    @get:Rule val compose = createComposeRule()
    private fun template() = TemplateDefinition("synthetic-template", "Synthetic payment", "synthetic.app",
        listOf(TemplateEngine.parsePattern(MessageField.BODY, "Paid HKD{amount} at {merchant}.")),
        fixedCurrency = "HKD", fixedCurrencyEvidence = FixedCurrencyEvidence.EXPLICIT_MATCHED_LITERAL, enabled = true, revision = 4)
    private fun payload(body: String) = NotificationPayload("synthetic.app", "synthetic", "synthetic", Instant.EPOCH, Instant.EPOCH, body = body)

    @Test fun convertsFixedCurrencyWithoutAnOriginalNotificationAndKeepsChecks() {
        val converted = useCurrencyFromPattern(template(), "HKD", "HKD")
        assertNull(converted.fixedCurrency)
        assertTrue(converted.patterns.single().segments.filterIsInstance<PatternSegment.Slot>().any { it.name == SlotName.AMOUNT && it.numericOnly })
        assertTrue(TemplateEngine.validate(converted).isEmpty())
        assertEquals(RecognitionStatus.READY, TemplateEngine.recognize(payload("Paid HKD1,200.00 at EXAMPLE STORE."), listOf(converted)).status)
        assertEquals("GBP", TemplateEngine.recognize(payload("Paid £1,200.00 at EXAMPLE STORE."), listOf(converted)).draft!!.currency)
        assertEquals("EUR", TemplateEngine.recognize(payload("Paid €1,200.00 at EXAMPLE STORE."), listOf(converted)).draft!!.currency)
        assertEquals("HKD", TemplateEngine.recognize(payload("Paid HK$1,200.00 at EXAMPLE STORE."), listOf(converted)).draft!!.currency)
        assertNotEquals(RecognitionStatus.READY, TemplateEngine.recognize(payload("Paid $1,200.00 at EXAMPLE STORE."), listOf(converted)).status)
        assertThrows(IllegalArgumentException::class.java) { useCurrencyFromPattern(template(), "USD", "USD") }
    }

    @Test fun editScreenChecksBeforeSavingAndUsesExistingRevision() {
        val store = Store()
        compose.setContent { MaterialTheme { TemplateEditor(template(), store) {} } }
        compose.onNodeWithTag("edit_use_currency").performScrollTo().performClick()
        compose.onNodeWithTag("edit_template_save").assertIsNotEnabled()
        compose.onNodeWithTag("edit_template_check").performClick()
        compose.onNodeWithTag("edit_template_save").assertIsEnabled().performClick()
        compose.waitUntil { store.command != null }
        compose.runOnIdle {
            assertEquals(4L, store.command!!.expectedRevision)
            assertNull(store.command!!.definition.fixedCurrency)
            assertTrue(store.command!!.definition.enabled)
        }
    }

    @Test fun commaAndDotIsTheDefaultAndRecognizesUngroupedAmounts() {
        assertEquals(NumberConvention.DECIMAL_DOT_GROUP_COMMA, suggestedNumberConvention("72.50"))
        assertEquals(NumberConvention.DECIMAL_DOT_GROUP_COMMA, TemplateDefinition("a", "b", "c", emptyList()).numberConvention)
    }

    private class Store : LedgerStore {
        override val entries = MutableStateFlow(emptyList<LedgerEntry>())
        override val categories = MutableStateFlow(emptyList<Category>())
        override val accounts = MutableStateFlow(emptyList<AccountLabel>())
        override val aliases = MutableStateFlow(emptyList<MerchantAlias>())
        override val rules = MutableStateFlow(emptyList<CategoryRule>())
        var command: LedgerCommand.PutTemplate? = null
        override suspend fun execute(command: LedgerCommand): CommandResult {
            this.command = command as LedgerCommand.PutTemplate
            return CommandResult(CommandStatus.APPLIED)
        }
    }
}
