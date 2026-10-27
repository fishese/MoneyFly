package com.fishese.moneyfly.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.fishese.moneyfly.R
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
class SettingsOrganizeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun suggestedCategoryCanBeRecreatedAfterDeletionWithoutReplayingItsOldCreation() {
        val store = LabelStore()
        compose.setContent { MoneyFlyApp(store) }
        compose.onNodeWithTag("destination_2", useUnmergedTree = true).performClick()
        val starter = "catalog_add_meals"
        compose.onNodeWithTag(starter, useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil { store.categories.value.size == 1 }
        val originalId = store.categories.value.single().id
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_label_$originalId"))
        compose.onNodeWithTag("settings_label_$originalId").performClick()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        compose.waitUntil { store.categories.value.isEmpty() }
        compose.onNodeWithTag(starter, useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil { store.categories.value.size == 1 }
        compose.runOnIdle {
            assertNotEquals(originalId, store.categories.value.single().id)
            assertEquals("Meals", store.categories.value.single().name)
            assertTrue(store.commands.none { it is LedgerCommand.Save || it is LedgerCommand.SaveCaptured })
        }
    }

    @Test fun applyRulesToExistingOnlyChangesUncategorizedExpenses() {
        val store = LabelStore()
        val now = Instant.parse("2026-01-01T12:00:00Z")
        fun entry(id: String, draft: EntryDraft) = LedgerEntry(id, 1, draft, createdAt = now, updatedAt = now, lastOperationId = "synthetic")
        val draft = EntryDraft("10.00", merchant = "Synthetic bus", occurredAt = now)
        store.categories.value = listOf(Category("transport", "Transport", 1), Category("other", "Other", 1))
        store.rules.value = listOf(CategoryRule("bus", RuleField.MERCHANT, LiteralMatch.EQUALS, "Synthetic bus", "transport"))
        store.entries.value = listOf(entry("uncategorized", draft), entry("already-chosen", draft.copy(categoryId = "other", categoryOrigin = CategoryOrigin.EXPLICIT)), entry("income", draft.copy(kind = EntryKind.INCOME)))
        compose.setContent { MoneyFlyApp(store) }
        compose.onNodeWithTag("destination_2", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Category rules", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("apply_category_rules").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_apply_category_rules").performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.Edit } }
        compose.runOnIdle {
            val edit = store.commands.filterIsInstance<LedgerCommand.Edit>().single()
            assertEquals("uncategorized", edit.entryId)
            assertEquals("transport", edit.draft.categoryId)
            assertEquals(CategoryOrigin.RULE, edit.draft.categoryOrigin)
            assertEquals("other", store.entries.value.single { it.id == "already-chosen" }.draft.categoryId)
            assertNull(store.entries.value.single { it.id == "income" }.draft.categoryId)
        }
    }

    private class LabelStore : LedgerStore {
        override val entries = MutableStateFlow<List<LedgerEntry>>(emptyList())
        override val categories = MutableStateFlow<List<Category>>(emptyList())
        override val accounts = MutableStateFlow<List<AccountLabel>>(emptyList())
        override val aliases = MutableStateFlow<List<MerchantAlias>>(emptyList())
        override val rules = MutableStateFlow<List<CategoryRule>>(emptyList())
        val commands = mutableListOf<LedgerCommand>()
        private val results = mutableMapOf<String, CommandResult>()
        override suspend fun execute(command: LedgerCommand): CommandResult {
            commands += command
            results[command.operationId]?.let { return it.copy(replayed = true) }
            val result = when (command) {
                is LedgerCommand.PutCategory -> {
                    categories.value = categories.value + Category(command.id, command.name, 1)
                    CommandResult(CommandStatus.APPLIED, command.id, 1)
                }
                is LedgerCommand.DeleteCategory -> {
                    categories.value = categories.value.filterNot { it.id == command.id }
                    CommandResult(CommandStatus.APPLIED, command.id, 2)
                }
                is LedgerCommand.Edit -> {
                    val current = entries.value.first { it.id == command.entryId }
                    if (current.revision != command.expectedRevision) CommandResult(CommandStatus.CONFLICT)
                    else {
                        val updated = current.copy(draft = command.draft, revision = current.revision + 1)
                        entries.value = entries.value.map { if (it.id == updated.id) updated else it }
                        CommandResult(CommandStatus.APPLIED, updated.id, updated.revision, updated)
                    }
                }
                else -> error("Unexpected financial command in label organization")
            }
            results[command.operationId] = result
            return result
        }
    }
}
