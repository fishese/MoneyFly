package com.fishese.moneyfly.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
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
class CategorySpendingChartTest {
    @get:Rule val compose = createComposeRule()
    private fun entry(id: String, currency: String, category: String?): LedgerEntry {
        val now = Instant.now()
        return LedgerEntry(id, 1, EntryDraft(amount = "12.00", currency = currency, categoryId = category, merchant = "Synthetic cafe", occurredAt = now), createdAt = now, updatedAt = now, lastOperationId = id)
    }

    @Test fun pieSliceSelectsCategoryAndStylesAndCurrenciesRemainAvailable() {
        var selected: String? = "unchanged"
        var selectedCurrency: String? = null
        compose.setContent { MaterialTheme { CategorySpendingOverview(listOf(entry("hkd", "HKD", "food"), entry("usd", "USD", null)), listOf(Category("food", "Food", 1))) { id, currency -> selected = id; selectedCurrency = currency } } }
        compose.onNodeWithTag("category_spending_overview").performClick()
        compose.onNodeWithTag("category_spending_pie").performTouchInput { click(Offset(width * 0.8f, height * 0.5f)) }
        compose.onNodeWithTag("category_spending_detail").performScrollToNode(hasTestTag("chart_selected_category"))
        compose.onNodeWithTag("chart_selected_category").assertExists()
        compose.onNodeWithTag("category_spending_detail").performScrollToNode(hasTestTag("chart_style_bar"))
        compose.onNodeWithTag("chart_style_bar").performClick()
        compose.onNodeWithTag("category_spending_pie").assertDoesNotExist()
        compose.onNodeWithTag("chart_style_list").performClick()
        compose.onNodeWithTag("chart_currency_USD").performClick()
        compose.onNodeWithTag("category_spending_detail").performScrollToNode(hasTestTag("chart_category_"))
        compose.onNodeWithTag("chart_category_").performClick()
        compose.onNodeWithTag("category_spending_detail").performScrollToNode(hasTestTag("chart_show_entries"))
        compose.onNodeWithTag("chart_show_entries").performClick()
        compose.runOnIdle { assertEquals(null, selected); assertEquals("USD", selectedCurrency) }
    }
}
