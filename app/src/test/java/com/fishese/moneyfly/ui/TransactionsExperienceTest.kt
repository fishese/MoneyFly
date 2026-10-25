package com.fishese.moneyfly.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.fishese.moneyfly.ledger.*
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class TransactionsExperienceTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.now()
    private val entry = LedgerEntry("coffee", 1, EntryDraft(amount = "28.50", merchant = "Corner Cafe", notes = "Lunch", categoryId = "food", occurredAt = now), createdAt = now, updatedAt = now, lastOperationId = "create")

    @Test fun amountSearchKeepsCurrentPeriodAndClearSearchKeepsFilters() {
        val old = entry.copy(id = "old", draft = entry.draft.copy(occurredAt = now.atZone(java.time.ZoneId.systemDefault()).minusMonths(2).toInstant()))
        compose.setContent { MaterialTheme { Transactions(listOf(entry, old), listOf(Category("food", "Food", 1)), emptyList(), Modifier) {} } }
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("journal_search"))
        compose.onNodeWithTag("journal_search").performTextInput("hkd 28.50 lunch")
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("transaction_coffee"))
        compose.onNodeWithTag("transaction_coffee").assertExists()
        compose.onNodeWithTag("transaction_old").assertDoesNotExist()
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("journal_clear_search"))
        compose.onNodeWithTag("journal_clear_search").performClick()
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("transaction_coffee"))
        compose.onNodeWithTag("transaction_coffee").assertExists()
        compose.onNodeWithTag("transaction_old").assertDoesNotExist()
    }

    @Test fun groupingSearchClearAndEditingCompose() {
        var edited: String? = null
        compose.setContent { MaterialTheme { Transactions(listOf(entry), listOf(Category("food", "Food", 1)), emptyList(), Modifier) { edited = it.id } } }
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasText("Group by: Date"))
        compose.onNodeWithText("Group by: Date").performClick()
        compose.onNodeWithText("Category", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("journal_group_CATEGORY:food"))
        compose.onNodeWithTag("journal_group_CATEGORY:food").performClick()
        compose.onNodeWithTag("journal_group_CATEGORY:food").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        compose.onNodeWithTag("transaction_coffee").assertDoesNotExist()
        compose.onNodeWithTag("journal_group_CATEGORY:food").performClick()
        compose.onNodeWithTag("journal_group_CATEGORY:food").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("transaction_coffee"))
        compose.onNodeWithTag("transaction_coffee").performClick()
        compose.runOnIdle { assertEquals("coffee", edited) }
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("journal_search"))
        compose.onNodeWithTag("journal_search").performTextInput("unmatched")
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("clear_filters"))
        compose.onNodeWithTag("clear_filters").performClick()
        compose.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("transaction_coffee"))
        compose.onNodeWithTag("transaction_coffee").assertExists()
    }
}
