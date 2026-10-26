package com.fishese.moneyfly.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.fishese.moneyfly.ledger.*
import com.fishese.moneyfly.recognition.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class TemplateDetailsTest {
    @get:Rule val compose = createComposeRule()
    private fun template() = TemplateDefinition("synthetic-template", "Synthetic purchase", "example.synthetic",
        patterns = listOf(TemplateEngine.parsePattern(MessageField.BODY, "Paid HKD {amount} at {merchant}.")),
        predicates = listOf(TemplatePredicate(MessageField.TITLE, PredicateOperation.EQUALS, "Synthetic completed")),
        fixedCurrency = "HKD", fixedCurrencyEvidence = FixedCurrencyEvidence.EXPLICIT_MATCHED_LITERAL, enabled = false, revision = 4)

    @Test fun viewerShowsStoredPatternConditionsAndRevision() {
        val definition = template()
        compose.setContent { MaterialTheme { TemplateDetails(definition, listOf(definition), {}) } }
        compose.onNodeWithText("Template ID: synthetic-template · Revision 4").assertExists()
        compose.onNodeWithText("Disabled").assertExists()
        compose.onNodeWithTag("template_pattern_BODY", useUnmergedTree = true).performScrollTo().assertTextEquals("Paid HKD {amount} at {merchant}.")
        compose.onNodeWithText("Synthetic completed", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun settingsShowsIdenticalCopiesAndPersistedApplyCounts() {
        val definition = template()
        val store = FakeStore()
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            TemplateSettings(listOf(definition, definition.copy(id = "second-copy")), store) { _, done -> done() }
        } } }
        compose.onNodeWithTag("template_settings").performClick()
        compose.onAllNodesWithText("Identical copies: 1").assertCountEquals(2)
        compose.onNodeWithTag("view_template_synthetic-template").assertExists()
        compose.onNodeWithTag("apply_template_synthetic-template").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_apply_template", useUnmergedTree = true).performClick()
        compose.waitUntil { store.command != null }
        compose.onNodeWithTag("template_report_synthetic-template").performScrollTo().assertTextContains("Recorded: 2", substring = true)
        compose.runOnIdle {
            assertEquals("synthetic-template", store.command!!.templateId)
            assertEquals(4L, store.command!!.expectedRevision)
        }
    }

    private class FakeStore : LedgerStore {
        override val entries = MutableStateFlow(emptyList<LedgerEntry>())
        override val categories = MutableStateFlow(emptyList<Category>())
        override val accounts = MutableStateFlow(emptyList<AccountLabel>())
        override val aliases = MutableStateFlow(emptyList<MerchantAlias>())
        override val rules = MutableStateFlow(emptyList<CategoryRule>())
        var command: LedgerCommand.ApplyTemplateToPending? = null
        override suspend fun execute(command: LedgerCommand): CommandResult {
            this.command = command as LedgerCommand.ApplyTemplateToPending
            return CommandResult(CommandStatus.APPLIED, templateReport = TemplateApplyReport(3, 2, 2, 0, 1))
        }
    }
}
