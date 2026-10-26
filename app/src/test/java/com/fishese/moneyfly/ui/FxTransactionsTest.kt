package com.fishese.moneyfly.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.fishese.moneyfly.fx.*
import com.fishese.moneyfly.ledger.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class FxTransactionsTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.now()
    private val date = now.atZone(ZoneId.systemDefault()).toLocalDate()
    private fun row(id: String, currency: String, amount: String) = LedgerEntry(id, 1,
        EntryDraft(amount, currency, occurredAt = now, merchant = "Synthetic $id", categoryId = "food"), createdAt = now, updatedAt = now, lastOperationId = id)
    private fun snapshot(): FxSnapshot {
        val rate = FxQuote(date, "JPY", "HKD", "0.05", date, 1)
        return FxSnapshot(quotes = mapOf(rate.key to rate))
    }

    @Test fun combinedChartDrilldownKeepsForeignEntriesAndEditsOriginalMoney() {
        val rows = listOf(row("native", "HKD", "12.00"), row("foreign", "JPY", "1000"))
        var edited: LedgerEntry? = null
        compose.setContent { MaterialTheme { Transactions(rows, listOf(Category("food", "Synthetic food", 1)), emptyList(), Modifier, fx = snapshot()) { edited = it } } }
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("currency_total_HKD"))
        compose.onNodeWithTag("currency_total_JPY").assertDoesNotExist()
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("category_spending_overview"))
        compose.onNodeWithTag("category_spending_overview").performClick()
        compose.onNodeWithTag("category_spending_detail").performScrollToNode(hasTestTag("chart_category_food"))
        compose.onNodeWithTag("chart_category_food").performClick()
        compose.onNodeWithTag("category_spending_detail").performScrollToNode(hasTestTag("chart_show_entries"))
        compose.onNodeWithTag("chart_show_entries").performClick()
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("transaction_foreign"))
        compose.onNodeWithTag("transaction_foreign").performClick()
        compose.runOnIdle { assertEquals("JPY", edited!!.draft.currency); assertEquals("1000", edited!!.draft.amount) }
    }

    @Test fun separateCardsFitSideBySideAndOriginalCurrencyFilterStillWorks() {
        val rows = listOf(row("native", "HKD", "12.00"), row("foreign", "JPY", "1000"))
        val state = mutableStateOf(snapshot())
        compose.setContent { MaterialTheme { Transactions(rows, emptyList(), emptyList(), Modifier, fx = state.value,
            changeDisplay = { state.value = state.value.copy(separate = it) }) {} } }
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("separate_currencies"))
        compose.onNodeWithTag("separate_currencies").performClick()
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("currency_total_HKD"))
        val left = compose.onNodeWithTag("currency_total_HKD").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag("currency_total_JPY").fetchSemanticsNode().boundsInRoot
        assertTrue(left.right <= right.left); assertEquals(left.top, right.top, 1f)
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasText("Filters"))
        compose.onNodeWithText("Filters").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Original currency: All").performScrollTo().performClick()
        compose.onNodeWithText("JPY", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Show 1 entry").performScrollTo().performClick()
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("transaction_foreign"))
        compose.onNodeWithTag("transaction_foreign").assertExists()
        compose.onNodeWithTag("transaction_native").assertDoesNotExist()
    }

    @Test fun editorSavesCardRateWithExplicitTargetAndRejectsZero() {
        val store = Store()
        val original = row("foreign", "JPY", "1000").copy(draft = row("foreign", "JPY", "1000").draft.copy(categoryId = null))
        compose.setContent { MaterialTheme { EntryEditor("foreign", 1, original, emptyList(), emptyList(), emptyList(), emptyList(), store, {}, { _, _ -> }, {}, reportingCurrency = "HKD", fx = snapshot()) } }
        compose.onNodeWithTag("custom_exchange_rate").performScrollTo().performClick()
        compose.onNodeWithTag("entry_exchange_rate").performScrollTo().performTextInput("0")
        compose.onNodeWithTag("save_entry").assertIsNotEnabled()
        compose.onNodeWithTag("entry_exchange_rate").performTextReplacement("0.055")
        compose.onNodeWithTag("save_entry").performClick()
        compose.waitUntil { store.command != null }
        compose.runOnIdle {
            val draft = (store.command as LedgerCommand.Edit).draft
            assertEquals("JPY", draft.currency); assertEquals("1000", draft.amount)
            assertEquals("0.055", draft.exchangeRate); assertEquals("HKD", draft.exchangeRateCurrency)
        }
    }

    private class Store : LedgerStore {
        override val entries = MutableStateFlow(emptyList<LedgerEntry>())
        override val categories = MutableStateFlow(emptyList<Category>())
        override val accounts = MutableStateFlow(emptyList<AccountLabel>())
        override val aliases = MutableStateFlow(emptyList<MerchantAlias>())
        override val rules = MutableStateFlow(emptyList<CategoryRule>())
        var command: LedgerCommand? = null
        override suspend fun execute(command: LedgerCommand): CommandResult { this.command = command; return CommandResult(CommandStatus.APPLIED) }
    }
}
