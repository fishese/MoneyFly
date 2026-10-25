package com.fishese.moneyfly.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class ExpenseCategoryCatalogUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun searchableAdditionalCategoryCommitsBeforeSelectingWithoutFinancialEntry() {
        val store = CatalogTestStore()
        var selected: String? = null
        compose.setContent { MaterialTheme { ExpenseCategoryCatalogDialog(store, dismiss = {}, customCategory = {}, selectCategory = { selected = it }) } }
        compose.onNodeWithTag("category_catalog_search").performTextInput("travel")
        compose.onNodeWithTag("catalog_add_travel", useUnmergedTree = true).performClick()
        compose.waitUntil { selected != null }
        compose.runOnIdle {
            assertEquals("Travel", store.categories.value.single().name)
            assertEquals(selected, store.categories.value.single().id)
            assertEquals(1, store.commands.size)
            assertTrue(store.entries.value.isEmpty())
        }
    }
}
