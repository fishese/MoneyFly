package com.fishese.moneyfly.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
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
class TransactionBulkActionsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun selectAllAssignCategoryThenConfirmRemoveOne() {
        val store = Store()
        compose.setContent { MoneyFlyApp(store) }
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("start_selection"))
        compose.onNodeWithTag("start_selection").performClick()
        compose.onNodeWithTag("add_entry").assertDoesNotExist()
        compose.onNodeWithTag("select_visible").performClick()
        compose.onNodeWithTag("bulk_category").performClick()
        compose.onNodeWithTag("editor_label_travel", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.filterIsInstance<LedgerCommand.Edit>().size == 2 }
        compose.runOnIdle { assertTrue(store.entries.value.all { it.draft.categoryId == "travel" && it.draft.categoryOrigin == CategoryOrigin.EXPLICIT }) }
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("start_selection"))
        compose.onNodeWithTag("start_selection").performClick()
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("select_entry_one"))
        compose.onNodeWithTag("select_entry_one").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("1 selected", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("bulk_remove").performClick()
        compose.runOnIdle { assertTrue(store.commands.none { it is LedgerCommand.Delete }) }
        compose.onNodeWithTag("confirm_bulk_remove").performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.Delete } }
        compose.runOnIdle { assertEquals("one", store.commands.filterIsInstance<LedgerCommand.Delete>().single().entryId) }
    }

    @Test fun swipeOpensChoicesBeforeUpdatingTheEntry() {
        val store = Store()
        compose.setContent { MoneyFlyApp(store) }
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("transaction_swipe_one"))
        compose.onNodeWithTag("transaction_swipe_one", useUnmergedTree = true).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(store.commands.isEmpty()) }
        compose.onNodeWithTag("swipe_assign_category", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("editor_label_travel", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.Edit } }
        compose.runOnIdle { assertEquals("travel", store.commands.filterIsInstance<LedgerCommand.Edit>().single().draft.categoryId) }
    }

    @Test fun swipeRightCanCreateAccountRuleWithoutChangingTheEntry() {
        val store = Store()
        compose.setContent { MoneyFlyApp(store) }
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("transaction_swipe_one"))
        compose.onNodeWithTag("transaction_swipe_one", useUnmergedTree = true).performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag("swipe_rule_account", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("editor_label_card", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("confirm_account_rule", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.PutAccountRule } }
        compose.runOnIdle {
            val rule = store.commands.filterIsInstance<LedgerCommand.PutAccountRule>().single().rule
            assertEquals("Synthetic one", rule.merchant)
            assertEquals("card", rule.accountId)
            assertTrue(store.commands.none { it is LedgerCommand.Edit })
        }
    }

    private class Store : LedgerStore {
        private val now = Instant.now()
        private fun entry(id: String) = LedgerEntry(id, 1, EntryDraft("12.00", merchant = "Synthetic $id", occurredAt = now), createdAt = now, updatedAt = now, lastOperationId = "initial")
        override val entries = MutableStateFlow(listOf(entry("one"), entry("two")))
        override val categories = MutableStateFlow(listOf(Category("travel", "Travel", 1)))
        override val accounts = MutableStateFlow(listOf(AccountLabel("card", "Card", AccountKind.CARD, 1)))
        override val aliases = MutableStateFlow(emptyList<MerchantAlias>())
        override val rules = MutableStateFlow(emptyList<CategoryRule>())
        val commands = mutableListOf<LedgerCommand>()
        override suspend fun execute(command: LedgerCommand): CommandResult {
            commands += command
            return when (command) {
                is LedgerCommand.Edit -> {
                    val old = entries.value.first { it.id == command.entryId }
                    if (old.revision != command.expectedRevision) CommandResult(CommandStatus.CONFLICT)
                    else {
                        val changed = old.copy(draft = command.draft, revision = old.revision + 1, lastOperationId = command.operationId)
                        entries.value = entries.value.map { if (it.id == old.id) changed else it }
                        CommandResult(CommandStatus.APPLIED, changed.id, changed.revision, changed)
                    }
                }
                is LedgerCommand.Delete -> {
                    val old = entries.value.first { it.id == command.entryId }
                    entries.value = entries.value.filterNot { it.id == old.id }
                    CommandResult(CommandStatus.APPLIED, old.id, old.revision + 1)
                }
                is LedgerCommand.PutAccountRule -> CommandResult(CommandStatus.APPLIED, command.rule.id, 1)
                else -> CommandResult(CommandStatus.INVALID)
            }
        }
    }
}
