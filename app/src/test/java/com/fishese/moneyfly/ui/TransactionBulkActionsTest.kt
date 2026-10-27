package com.fishese.moneyfly.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Offset
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
    private fun centerRow(tag: String) {
        val list = compose.onNodeWithTag("transactions_list")
        list.performScrollToNode(hasTestTag(tag))
        val listNode = list.fetchSemanticsNode()
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val delta = node.positionInRoot.y + node.size.height / 2f - listNode.positionInRoot.y - listNode.size.height / 2f
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
    }
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
        centerRow("transaction_swipe_one")
        compose.onNode(hasText("Account") and hasAnyAncestor(hasTestTag("transaction_swipe_one")), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("transaction_swipe_one", useUnmergedTree = true).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(store.commands.isEmpty()) }
        compose.onNodeWithTag("editor_label_travel", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertTrue(store.commands.isEmpty()) }
        compose.onNodeWithTag("category_save_entry", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.Edit } }
        compose.runOnIdle { assertEquals("travel", store.commands.filterIsInstance<LedgerCommand.Edit>().single().draft.categoryId) }
    }

    @Test fun categorySwipeCanSaveEntryAndCreateVendorRule() {
        val store = Store()
        store.entries.value = store.entries.value.map { if (it.id == "one") it.copy(draft = it.draft.copy(categoryId = "old", categoryOrigin = CategoryOrigin.EXPLICIT)) else it }
        compose.setContent { MoneyFlyApp(store) }
        centerRow("transaction_swipe_one")
        compose.onNodeWithTag("transaction_swipe_one", useUnmergedTree = true).performTouchInput { swipeLeft() }
        compose.onNodeWithTag("editor_label_travel", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("category_save_rule", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertTrue(store.commands.isEmpty()) }
        compose.onNodeWithTag("confirm_merchant_rule", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.PutRule } }
        compose.runOnIdle {
            val edit = store.commands.filterIsInstance<LedgerCommand.Edit>().single()
            val rule = store.commands.filterIsInstance<LedgerCommand.PutRule>().single().rule
            assertEquals("one", edit.entryId); assertEquals("travel", edit.draft.categoryId)
            assertEquals(CategoryOrigin.EXPLICIT, edit.draft.categoryOrigin)
            assertEquals("Synthetic one", rule.literal); assertEquals("travel", rule.categoryId)
        }
    }

    @Test fun categorySwipeDoesNotOfferRuleWithoutMerchantOrCategory() {
        val store = Store()
        store.entries.value = store.entries.value.map { if (it.id == "one") it.copy(draft = it.draft.copy(merchant = "")) else it }
        compose.setContent { MoneyFlyApp(store) }
        centerRow("transaction_swipe_one")
        compose.onNodeWithTag("transaction_swipe_one", useUnmergedTree = true).performTouchInput { swipeLeft() }
        compose.onNodeWithTag("category_save_rule", useUnmergedTree = true).assertIsNotEnabled()
        compose.onNodeWithTag("editor_label_travel", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("category_save_rule", useUnmergedTree = true).assertIsNotEnabled()
        compose.onNodeWithTag("category_save_entry", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.Edit } }
        compose.runOnIdle { assertTrue(store.commands.none { it is LedgerCommand.PutRule }) }
    }

    @Test fun ruleFailureExplainsEntryWasSavedAndRetryDoesNotEditTwice() {
        val store = Store().apply { failRule = true }
        compose.setContent { MoneyFlyApp(store) }
        centerRow("transaction_swipe_one")
        compose.onNodeWithTag("transaction_swipe_one", useUnmergedTree = true).performTouchInput { swipeLeft() }
        compose.onNodeWithTag("editor_label_travel", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("category_save_rule", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("confirm_merchant_rule", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.PutRule } }
        compose.onNodeWithText("The entry was updated, but the rule was not saved. Try again or check the rules in Settings.", useUnmergedTree = true).assertExists()
        compose.runOnIdle { store.failRule = false }
        compose.onNodeWithTag("confirm_merchant_rule", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.filterIsInstance<LedgerCommand.PutRule>().size == 2 }
        compose.runOnIdle { assertEquals(1, store.commands.filterIsInstance<LedgerCommand.Edit>().size) }
    }

    @Test fun dividerStaysStillDuringPartialSwipe() {
        val store = Store()
        compose.setContent { MoneyFlyApp(store) }
        centerRow("transaction_swipe_one")
        val divider = compose.onNodeWithTag("transaction_divider_one", useUnmergedTree = true)
        val initial = divider.fetchSemanticsNode().positionInRoot
        val row = compose.onNodeWithTag("transaction_one", useUnmergedTree = true)
        val initialRowX = row.fetchSemanticsNode().positionInRoot.x
        compose.onNodeWithTag("transaction_swipe_one", useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.2f, 0f))
        }
        val during = divider.fetchSemanticsNode().positionInRoot
        assertEquals(initial.x, during.x, 0.5f)
        assertEquals(initial.y, during.y, 0.5f)
        compose.onNodeWithTag("transaction_swipe_one", useUnmergedTree = true).performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(initialRowX, row.fetchSemanticsNode().positionInRoot.x, 1f)
        compose.runOnIdle { assertTrue(store.commands.isEmpty()) }
    }

    @Test fun transactionRowReturnsToRestAfterEitherSwipeAndSheetDismissal() {
        val store = Store()
        compose.setContent { MoneyFlyApp(store) }
        centerRow("transaction_swipe_one")
        val row = compose.onNodeWithTag("transaction_one", useUnmergedTree = true)
        val initialX = row.fetchSemanticsNode().positionInRoot.x
        for (left in listOf(true, false)) {
            compose.onNodeWithTag("transaction_swipe_one", useUnmergedTree = true).performTouchInput {
                if (left) swipeLeft() else swipeRight()
            }
            compose.waitForIdle()
            compose.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
            compose.waitForIdle()
            assertEquals(initialX, row.fetchSemanticsNode().positionInRoot.x, 1f)
        }
        compose.runOnIdle { assertTrue(store.commands.isEmpty()) }
    }

    @Test fun categoryPickerShowsMultiplePaddedChoicesPerRow() {
        val store = Store()
        var chosen = ""
        compose.setContent {
            EditorLabelPicker("Category", listOf("meals" to "Meals", "snacks" to "Snacks", "travel" to "Transportation"),
                "Uncategorized", "", dismiss = {}, categoryStore = store) { chosen = it }
        }
        val first = compose.onNodeWithTag("editor_label_none", useUnmergedTree = true).fetchSemanticsNode()
        val second = compose.onNodeWithTag("editor_label_meals", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(first.positionInRoot.y, second.positionInRoot.y, 1f)
        assertTrue(second.positionInRoot.x > first.positionInRoot.x + first.size.width)
        compose.onNodeWithTag("editor_label_meals", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals("meals", chosen) }
    }

    @Test fun transactionRowShowsAccountIconButNotAccountName() {
        val store = Store()
        store.accounts.value = listOf(AccountLabel("card", "Synthetic Card", AccountKind.CARD, 1, "匯"))
        store.entries.value = store.entries.value.map { if (it.id == "one") it.copy(draft = it.draft.copy(accountId = "card")) else it }
        compose.setContent { MoneyFlyApp(store) }
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("transaction_one"))
        compose.onNode(hasText("匯") and hasAnyAncestor(hasTestTag("transaction_one")), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Synthetic Card", substring = true) and hasAnyAncestor(hasTestTag("transaction_one")), useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasText("Unassigned", substring = true) and hasAnyAncestor(hasTestTag("transaction_one")), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun swipeRightCanCreateAccountRuleWithoutChangingTheEntry() {
        val store = Store()
        compose.setContent { MoneyFlyApp(store) }
        centerRow("transaction_swipe_one")
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
        var failRule = false
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
                is LedgerCommand.PutRule -> CommandResult(if (failRule) CommandStatus.INVALID else CommandStatus.APPLIED, command.rule.id, 1)
                else -> CommandResult(CommandStatus.INVALID)
            }
        }
    }
}
