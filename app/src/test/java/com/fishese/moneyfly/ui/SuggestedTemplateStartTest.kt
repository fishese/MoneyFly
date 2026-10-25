package com.fishese.moneyfly.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.fishese.moneyfly.capture.*
import com.fishese.moneyfly.ledger.*
import com.fishese.moneyfly.recognition.SuggestedTemplates
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
class SuggestedTemplateStartTest {
    @get:Rule val compose = createComposeRule()

    private fun receipt(source: String = SuggestedTemplates.GOOGLE_WALLET, body: String = "HK$12.34 with INVENTED CARD") = InboxItem(
        id = "synthetic-receipt", revision = 3, sourcePackage = source,
        postedAt = Instant.EPOCH, observedAt = Instant.EPOCH, contentRevision = 1,
        status = InboxStatus.UNRECOGNIZED,
        payload = NotificationPayload(source, "synthetic", "synthetic", Instant.EPOCH, Instant.EPOCH, "SYNTHETIC SHOP", body),
    )

    private fun show(store: FakeStore, item: InboxItem = receipt(), closed: androidx.compose.runtime.MutableState<Boolean> = mutableStateOf(false)) {
        compose.setContent { MaterialTheme { if (!closed.value) TemplateStart(item, store, { closed.value = true }, recordEntry = {}) } }
    }

    @Test fun explicitUseCreatesEnabledTemplateThenRepreviewsWithoutBookingMoney() {
        val store = FakeStore()
        val closed = mutableStateOf(false)
        show(store, closed = closed)
        compose.onNodeWithTag("use_suggested_template").assertIsDisplayed().performClick()
        compose.waitUntil { closed.value }
        compose.runOnIdle {
            assertEquals(2, store.commands.size)
            val put = store.commands[0] as LedgerCommand.PutTemplate
            assertTrue(put.definition.enabled)
            assertNotEquals("suggested-google-wallet-hkd", put.definition.id)
            val refresh = store.commands[1] as LedgerCommand.RepreviewReceipt
            assertEquals("synthetic-receipt", refresh.receiptId)
            assertEquals(3, refresh.expectedRevision)
            assertTrue(store.commands.none { it is LedgerCommand.SaveCaptured || it is LedgerCommand.Save })
        }
    }

    @Test fun unknownFailureRetriesTheSameFrozenTemplateAndOperation() {
        val store = FakeStore(failFirst = true)
        val closed = mutableStateOf(false)
        show(store, closed = closed)
        compose.onNodeWithTag("use_suggested_template").performClick()
        compose.waitUntil { store.commands.size == 1 }
        compose.onNodeWithTag("suggested_template_error").assertExists()
        compose.waitForIdle()
        compose.onNodeWithTag("use_suggested_template").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil { closed.value }
        compose.runOnIdle {
            assertEquals(3, store.commands.size)
            val first = store.commands[0] as LedgerCommand.PutTemplate
            val retry = store.commands[1] as LedgerCommand.PutTemplate
            assertEquals(first.operationId, retry.operationId)
            assertEquals(first.definition, retry.definition)
            assertTrue(store.commands[2] is LedgerCommand.RepreviewReceipt)
        }
    }

    @Test fun unsupportedMessageGoesStraightToTheExistingCustomBuilder() {
        val store = FakeStore()
        show(store, receipt("unknown.synthetic", "An unsupported synthetic notification"))
        compose.onNodeWithTag("template_name", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("use_suggested_template").assertDoesNotExist()
    }

    @Test fun customTemplateCanExplicitlyOmitMerchantAndSuggestGroupedNumberFormat() {
        val store = FakeStore()
        val closed = mutableStateOf(false)
        show(store, receipt("unknown.synthetic", "Paid HKD 1,234.50."), closed)
        compose.onNodeWithTag("template_name").performTextInput("Invented debit")
        compose.onNodeWithTag("template_amount").performScrollTo().performTextInput("1,234.50")
        compose.onNodeWithTag("template_no_merchant").performScrollTo().performClick()
        compose.onNodeWithTag("template_merchant").assertDoesNotExist()
        compose.onNodeWithTag("confirm_template_currency").performScrollTo().performClick()
        compose.onNodeWithTag("preview_template").performScrollTo().performClick()
        compose.onNodeWithTag("save_template").assertIsEnabled().performClick()
        compose.waitUntil { closed.value }
        compose.runOnIdle {
            val command = store.commands.single() as LedgerCommand.PutTemplate
            assertEquals(com.fishese.moneyfly.recognition.NumberConvention.DECIMAL_DOT_GROUP_COMMA, command.definition.numberConvention)
            assertNull(command.definition.fixedMerchant)
            assertTrue(command.definition.patterns.flatMap { it.segments }.filterIsInstance<com.fishese.moneyfly.recognition.PatternSegment.Slot>().none { it.name == com.fishese.moneyfly.recognition.SlotName.MERCHANT })
        }
    }

    private class FakeStore(private val failFirst: Boolean = false) : LedgerStore {
        override val entries = MutableStateFlow(emptyList<LedgerEntry>())
        override val categories = MutableStateFlow(emptyList<Category>())
        override val accounts = MutableStateFlow(emptyList<AccountLabel>())
        override val aliases = MutableStateFlow(emptyList<MerchantAlias>())
        override val rules = MutableStateFlow(emptyList<CategoryRule>())
        val commands = mutableListOf<LedgerCommand>()
        private var failed = false
        override suspend fun execute(command: LedgerCommand): CommandResult {
            commands += command
            if (failFirst && !failed) { failed = true; throw IllegalStateException("synthetic unknown result") }
            return CommandResult(CommandStatus.APPLIED, revision = 1)
        }
    }
}
