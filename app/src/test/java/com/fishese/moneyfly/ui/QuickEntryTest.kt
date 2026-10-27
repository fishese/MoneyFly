package com.fishese.moneyfly.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import com.fishese.moneyfly.ledger.*
import java.time.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class QuickEntryTest {
    @get:Rule val compose = createComposeRule()
    private val categories = listOf(Category("food", "Food", 1), Category("work", "Work travel", 1))
    private val accounts = listOf(AccountLabel("card", "Daily card", AccountKind.CARD, 1), AccountLabel("cash", "Cash", AccountKind.ACCOUNT, 1))
    private fun history() = LedgerEntry("cafe", 1, EntryDraft(amount = "17.00", currency = "USD", merchant = "CAFE-01", canonicalMerchant = "Cafe", categoryId = "food", accountId = "card"), createdAt = Instant.now(), updatedAt = Instant.now(), lastOperationId = "past")

    /** Nested horizontal chip rows need their vertical form scrolled before the row itself. */
    private fun scrollAndClick(tag: String) {
        val form = compose.onNodeWithTag("entry_form")
        val target = compose.onNodeWithTag(tag, useUnmergedTree = true)
        val targetNode = target.fetchSemanticsNode()
        val formNode = form.fetchSemanticsNode()
        val deltaY = targetNode.positionInRoot.y + targetNode.size.height / 2f - formNode.positionInRoot.y - formNode.size.height / 2f
        form.performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, deltaY) }
        target.performScrollTo().assertIsDisplayed().performClick()
    }

    private fun editor(store: FakeStore, recent: List<LedgerEntry> = emptyList(), rules: List<CategoryRule> = emptyList()) {
        val open = mutableStateOf(true)
        store.categories.value = categories
        store.accounts.value = accounts
        compose.setContent {
            MaterialTheme {
                val currentCategories by store.categories.collectAsState()
                val currentAccounts by store.accounts.collectAsState()
                if (open.value) EntryEditor("new", 0, null, currentCategories, currentAccounts, listOf(MerchantAlias("alias", "CAFE-01", "Cafe", 1)), rules, store,
                    close = { open.value = false }, saved = { _, _ -> open.value = false }, delete = {}, recentEntries = recent)
            }
        }
    }

    @Test fun selectingMerchantProposesLabelsButDoesNotImportCurrencyOrHiddenChoices() {
        val store = FakeStore()
        editor(store, listOf(history()))
        compose.onNodeWithTag("entry_amount").performTextInput("9.50")
        scrollAndClick("recent_merchant_cafe")
        compose.onNodeWithTag("apply_recent_category", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("save_entry", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.isNotEmpty() }
        compose.runOnIdle {
            val draft = (store.commands.single() as LedgerCommand.Save).draft
            assertEquals("CAFE-01", draft.merchant)
            assertEquals("Cafe", draft.canonicalMerchant)
            assertEquals("HKD", draft.currency)
            assertEquals("9.50", draft.amount)
            assertNull(draft.categoryId)
            assertNull(draft.accountId)
        }
    }

    @Test fun blankNewManualEntryFocusesAmountForDecimalKeyboardEntry() {
        editor(FakeStore())
        compose.onNodeWithTag("entry_amount").assertIsFocused()
    }

    @Test fun automaticCategoryIsVisibleAndCanBeOverriddenForOneEntry() {
        val store = FakeStore()
        editor(store, listOf(history()), listOf(CategoryRule("rule", RuleField.MERCHANT, LiteralMatch.EQUALS, "Cafe", "food")))
        compose.onNodeWithTag("entry_amount").performTextInput("12")
        scrollAndClick("recent_merchant_cafe")
        compose.onNodeWithTag("quick_category_food", useUnmergedTree = true).assertIsSelected()
        compose.onNodeWithTag("category_none", useUnmergedTree = true).assertIsNotSelected()
        scrollAndClick("category_none")
        compose.onNodeWithTag("category_none", useUnmergedTree = true).assertIsSelected()
        compose.onNodeWithTag("save_entry", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.Save } }
        val draft = (store.commands.last() as LedgerCommand.Save).draft
        assertNull(draft.categoryId)
        assertEquals(CategoryOrigin.EXPLICIT, draft.categoryOrigin)
        assertTrue(store.commands.none { it is LedgerCommand.PutRule })
    }

    @Test fun categorySelectionDoesNotCreateRuleUntilExplicitlyRequested() {
        val store = FakeStore()
        editor(store, listOf(history()))
        compose.onNodeWithTag("entry_amount").performTextInput("12")
        scrollAndClick("recent_merchant_cafe")
        scrollAndClick("apply_recent_category")
        assertTrue(store.commands.none { it is LedgerCommand.PutRule })
        scrollAndClick("create_merchant_rule")
        compose.onNodeWithTag("confirm_merchant_rule", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.PutRule } }
        val rule = store.commands.filterIsInstance<LedgerCommand.PutRule>().single().rule
        assertEquals("Cafe", rule.literal)
        assertEquals("food", rule.categoryId)
        assertEquals(LiteralMatch.EQUALS, rule.match)
    }

    @Test fun searchedExplicitCategoryAndAccountWinOverHistoryAndAliasRule() {
        val store = FakeStore()
        editor(store, listOf(history()), listOf(CategoryRule("rule", RuleField.MERCHANT, LiteralMatch.EQUALS, "Cafe", "food", priority = 99)))
        compose.onNodeWithTag("entry_amount").performTextInput("12")
        scrollAndClick("choose_category")
        compose.onNodeWithTag("editor_label_search").performTextInput("travel")
        compose.onNodeWithTag("editor_label_work", useUnmergedTree = true).performClick()
        scrollAndClick("choose_account")
        compose.onNodeWithTag("editor_label_cash", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("entry_merchant").performScrollTo()
        scrollAndClick("recent_merchant_cafe")
        compose.onNodeWithTag("apply_recent_category", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("apply_recent_account", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("save_entry", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.isNotEmpty() }
        compose.runOnIdle {
            val draft = (store.commands.single() as LedgerCommand.Save).draft
            assertEquals("work", draft.categoryId)
            assertEquals("cash", draft.accountId)
            assertEquals(CategoryOrigin.EXPLICIT, draft.categoryOrigin)
            assertEquals("HKD", draft.currency)
        }
    }

    @Test fun yesterdayShortcutStoresLocalDateWithMinuteUserEvidence() {
        val store = FakeStore()
        editor(store)
        compose.onNodeWithTag("entry_amount").performTextInput("23.50")
        scrollAndClick("date_yesterday")
        compose.onNodeWithTag("date_yesterday", useUnmergedTree = true).assertIsSelected()
        compose.onNodeWithTag("save_entry", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.isNotEmpty() }
        compose.runOnIdle {
            val draft = (store.commands.single() as LedgerCommand.Save).draft
            assertEquals(LocalDate.now().minusDays(1), draft.occurredAt.atZone(ZoneId.systemDefault()).toLocalDate())
            assertEquals(TimeOrigin.USER, draft.timeOrigin)
            assertEquals(TimePrecision.MINUTE, draft.timePrecision)
        }
    }

    @Test fun cancelNeverCommitsEvenWithAValidAmount() {
        val store = FakeStore()
        editor(store)
        compose.onNodeWithTag("entry_amount").performTextInput("20")
        compose.onNodeWithTag("cancel_entry", useUnmergedTree = true).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertTrue(store.commands.isEmpty()) }
    }

    @Suppress("DEPRECATION")
    @Test fun platformDialogBackClosesWithoutSaving() {
        val store = FakeStore()
        editor(store)
        compose.onNodeWithTag("entry_amount").performTextInput("20")
        compose.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertTrue(store.commands.isEmpty()) }
    }

    @Test fun inlineCategoryCreationRetriesOneCategoryBeforeFinancialSave() {
        val store = FakeStore(failCategoryResponseOnce = true)
        editor(store)
        compose.onNodeWithTag("entry_amount").performTextInput("12.50")
        scrollAndClick("choose_category")
        compose.onNodeWithTag("editor_label_search").performTextInput("Concerts")
        compose.onNodeWithTag("editor_create_category", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("editor_category_error", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("editor_label_search").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, store.categories.value.count { it.name == "Concerts" })
            assertFalse(store.commands.any { it is LedgerCommand.Save })
        }
        compose.onNodeWithTag("editor_create_category", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("editor_label_search").assertDoesNotExist()
        compose.runOnIdle {
            val attempts = store.commands.filterIsInstance<LedgerCommand.PutCategory>()
            assertEquals(2, attempts.size)
            assertEquals(attempts[0], attempts[1])
            assertEquals(1, store.categories.value.count { it.name == "Concerts" })
            assertFalse(store.commands.any { it is LedgerCommand.Save })
        }
        compose.onNodeWithTag("save_entry", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.Save } }
        compose.runOnIdle {
            val draft = store.commands.filterIsInstance<LedgerCommand.Save>().single().draft
            assertEquals(store.categories.value.single { it.name == "Concerts" }.id, draft.categoryId)
            assertEquals("12.50", draft.amount)
        }
    }

    @Test fun explicitlyCreatedCategorySurvivesCancellingTheUnbookedEntry() {
        val store = FakeStore()
        editor(store)
        scrollAndClick("choose_category")
        compose.onNodeWithTag("editor_label_search").performTextInput("Gifts")
        compose.onNodeWithTag("editor_create_category", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("editor_label_search").assertDoesNotExist()
        compose.onNodeWithTag("cancel_entry", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals(1, store.categories.value.count { it.name == "Gifts" })
            assertTrue(store.commands.single() is LedgerCommand.PutCategory)
        }
    }

    @Test fun daylightSavingGapIsRejectedInsteadOfShiftingTheClock() {
        val initial = EntryDraft(occurredAt = Instant.parse("2026-03-07T17:00:00Z"))
        val result = resolveEditorTime("2026-03-08", "02:30", ZoneId.of("America/New_York"), initial, preserveOriginal = false)
        assertEquals(EditorTimeIssue.GAP, result.issue)
        assertNull(result.instant)
    }

    @Test fun overlapNeedsResolutionButUnchangedOriginalKeepsOffsetAndPrecision() {
        val zone = ZoneId.of("America/New_York")
        val edited = resolveEditorTime("2026-11-01", "01:30", zone, EntryDraft(occurredAt = Instant.parse("2026-10-31T17:00:00Z")), preserveOriginal = false)
        assertEquals(EditorTimeIssue.OVERLAP, edited.issue)
        assertNull(edited.instant)
        val original = EntryDraft(occurredAt = Instant.parse("2026-11-01T06:30:47.123456789Z"), timeOrigin = TimeOrigin.NOTIFICATION_POSTED, timePrecision = TimePrecision.UNKNOWN)
        val unchanged = resolveEditorTime("2026-11-01", "01:30", zone, original, preserveOriginal = true)
        assertEquals(original.occurredAt, unchanged.instant)
        assertEquals(original.timeOrigin, unchanged.origin)
        assertEquals(original.timePrecision, unchanged.precision)
        assertTrue(unchanged.preservesOriginal)
        val newManualDefault = resolveEditorTime("2026-11-01", "01:30", zone, original, preserveOriginal = false)
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"), newManualDefault.instant)
        assertEquals(TimeOrigin.USER, newManualDefault.origin)
        assertEquals(TimePrecision.MINUTE, newManualDefault.precision)
    }

    @Test fun removedLabelsBlockSaveUntilExplicitlyClearedOrReselectedWithoutLosingDraft() {
        val store = FakeStore()
        editor(store)
        compose.onNodeWithTag("entry_amount").performTextInput("45.60")
        compose.onNodeWithTag("entry_merchant").performScrollTo().performTextInput("Keep this merchant")
        scrollAndClick("choose_category")
        compose.onNodeWithTag("editor_label_work", useUnmergedTree = true).performClick()
        scrollAndClick("choose_account")
        compose.onNodeWithTag("editor_label_cash", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            store.categories.value = store.categories.value.filterNot { it.id == "work" }
            store.accounts.value = store.accounts.value.filterNot { it.id == "cash" }
        }
        compose.onNodeWithTag("save_entry", useUnmergedTree = true).assertIsNotEnabled()
        compose.onNodeWithTag("entry_label_error", useUnmergedTree = true).assertExists()
        scrollAndClick("clear_missing_category")
        compose.onNodeWithTag("save_entry", useUnmergedTree = true).assertIsNotEnabled()
        scrollAndClick("choose_account")
        compose.onNodeWithTag("editor_label_card", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("save_entry", useUnmergedTree = true).assertIsEnabled().performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.Save } }
        compose.runOnIdle {
            val draft = store.commands.filterIsInstance<LedgerCommand.Save>().single().draft
            assertEquals("45.60", draft.amount)
            assertEquals("Keep this merchant", draft.merchant)
            assertNull(draft.categoryId)
            assertEquals("card", draft.accountId)
        }
    }

    private class FakeStore(val failCategoryResponseOnce: Boolean = false) : LedgerStore {
        override val entries = MutableStateFlow<List<LedgerEntry>>(emptyList())
        override val categories = MutableStateFlow<List<Category>>(emptyList())
        override val accounts = MutableStateFlow<List<AccountLabel>>(emptyList())
        override val aliases = MutableStateFlow<List<MerchantAlias>>(emptyList())
        override val rules = MutableStateFlow<List<CategoryRule>>(emptyList())
        val commands = mutableListOf<LedgerCommand>()
        private var categoryResponseFailed = false
        private val categoryOperations = mutableMapOf<String, LedgerCommand.PutCategory>()
        override suspend fun execute(command: LedgerCommand): CommandResult {
            commands += command
            if (command is LedgerCommand.PutCategory) {
                val existing = categoryOperations[command.operationId]
                if (existing == null) {
                    categoryOperations[command.operationId] = command
                    categories.value = categories.value + Category(command.id, command.name, 1)
                } else assertEquals(existing, command)
                if (failCategoryResponseOnce && !categoryResponseFailed) {
                    categoryResponseFailed = true
                    throw java.io.IOException("Synthetic response interruption after commit")
                }
            }
            return CommandResult(CommandStatus.APPLIED, revision = 1)
        }
    }
}
