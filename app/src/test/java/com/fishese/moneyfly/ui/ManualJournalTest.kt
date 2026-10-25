package com.fishese.moneyfly.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.fishese.moneyfly.ledger.*
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
class ManualJournalTest {
    @get:Rule val compose = createComposeRule()

    @Test fun manualEntryRetainsMerchantWithoutAccountOrCategory() {
        val store = RecordingStore()
        compose.setContent { MoneyFlyApp(store) }
        compose.onNodeWithTag("add_entry", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("entry_amount").performTextInput("28.50")
        compose.onNodeWithTag("entry_merchant").performScrollTo().performTextInput("茶餐廳")
        compose.onNodeWithTag("save_entry", useUnmergedTree = true).assertIsEnabled().performClick()
        compose.waitUntil { store.commands.isNotEmpty() }
        compose.runOnIdle {
            val command = store.commands.single() as LedgerCommand.Save
            assertEquals("28.50", command.draft.amount)
            assertEquals("茶餐廳", command.draft.merchant)
            assertNull(command.draft.accountId)
            assertNull(command.draft.categoryId)
        }
        val savedId = (store.commands.single() as LedgerCommand.Save).entryId
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("transaction_$savedId"))
        compose.onNodeWithText("茶餐廳", useUnmergedTree = true).assertExists()
    }

    @Test fun cancelAndInvalidAmountNeverCommit() {
        val store = RecordingStore()
        compose.setContent { MoneyFlyApp(store) }
        compose.onNodeWithTag("add_entry", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("save_entry", useUnmergedTree = true).assertIsNotEnabled()
        compose.onNodeWithTag("entry_amount").performTextInput("0")
        compose.onNodeWithTag("save_entry", useUnmergedTree = true).assertIsNotEnabled()
        compose.onNodeWithTag("entry_amount").performTextReplacement("10")
        compose.onNodeWithTag("save_entry", useUnmergedTree = true).assertIsEnabled()
        compose.onNodeWithTag("cancel_entry", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertTrue(store.commands.isEmpty()) }
    }

    @Test fun interruptedSaveRetriesSameOperationAndEntry() {
        val store = RecordingStore(failFirst = true)
        compose.setContent { MoneyFlyApp(store) }
        compose.onNodeWithTag("add_entry", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("entry_amount").performTextInput("19.20")
        compose.onNodeWithTag("save_entry", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("entry_error", useUnmergedTree = true).assertTextContains("Save was interrupted", substring = true)
        compose.onNodeWithTag("save_entry", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.size == 2 }
        compose.runOnIdle {
            val first = store.commands[0] as LedgerCommand.Save
            val second = store.commands[1] as LedgerCommand.Save
            assertEquals(first, second)
        }
    }

    @Test fun unrelatedEditPreservesOriginalInstantAndTimeEvidence() {
        val store = RecordingStore()
        val occurred = Instant.parse("2026-10-24T12:30:45.123456789Z")
        val original = EntryDraft(amount = "12.00", merchant = "Corner shop", occurredAt = occurred, timeOrigin = TimeOrigin.NOTIFICATION_POSTED, timePrecision = TimePrecision.UNKNOWN)
        store.entries.value = listOf(LedgerEntry("existing", 1, original, createdAt = occurred, updatedAt = occurred, lastOperationId = "initial"))
        compose.setContent { MoneyFlyApp(store) }
        // Clear the default current-month range, so this fixture stays findable in future runs.
        compose.onNodeWithTag("clear_filters", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("transaction_existing"))
        compose.onNodeWithTag("transaction_existing", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("entry_merchant").performScrollTo().performTextReplacement("Updated shop")
        compose.onNodeWithTag("save_entry", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.isNotEmpty() }
        compose.runOnIdle {
            val draft = (store.commands.single() as LedgerCommand.Edit).draft
            assertEquals(occurred, draft.occurredAt)
            assertEquals(TimeOrigin.NOTIFICATION_POSTED, draft.timeOrigin)
            assertEquals(TimePrecision.UNKNOWN, draft.timePrecision)
        }
    }

    private class RecordingStore(val failFirst: Boolean = false) : LedgerStore {
        override val entries = MutableStateFlow<List<LedgerEntry>>(emptyList())
        override val categories = MutableStateFlow<List<Category>>(emptyList())
        override val accounts = MutableStateFlow<List<AccountLabel>>(emptyList())
        override val aliases = MutableStateFlow<List<MerchantAlias>>(emptyList())
        override val rules = MutableStateFlow<List<CategoryRule>>(emptyList())
        val commands = mutableListOf<LedgerCommand>()
        override suspend fun execute(command: LedgerCommand): CommandResult {
            commands += command
            if (failFirst && commands.size == 1) throw java.io.IOException("Synthetic interrupted response")
            val now = Instant.now()
            val entry = when (command) {
                is LedgerCommand.Save -> LedgerEntry(command.entryId, 1, command.draft, createdAt = now, updatedAt = now, lastOperationId = command.operationId)
                is LedgerCommand.Edit -> entries.value.first { it.id == command.entryId }.copy(revision = command.expectedRevision + 1, draft = command.draft, updatedAt = now, lastOperationId = command.operationId)
                else -> error("Unsupported test command")
            }
            entries.value = entries.value.filterNot { it.id == entry.id } + entry
            return CommandResult(CommandStatus.APPLIED, entry.id, 1, entry)
        }
    }
}
