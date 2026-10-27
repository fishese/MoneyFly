package com.fishese.moneyfly.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.fishese.moneyfly.ledger.*
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class ExpenseCategoryCatalogTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun clearSeedMarker() { context.getSharedPreferences(CATEGORY_STARTER_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun seedsStarterLabelsAndDoesNotRestoreDeletedOrRenameEditedLabels() = runBlocking {
        val store = CatalogTestStore()
        assertTrue(seedExpenseCategories(context, store))
        assertEquals(setOf("Meals", "Snacks", "Entertainment", "Hobby", "Transportation", "Shopping", "Groceries", "Bills", "Health"), store.categories.value.map { it.name }.toSet())
        assertEquals(9, store.commands.size)
        val originalCommands = store.commands.toList()
        store.categories.value = store.categories.value.filterNot { it.name == "Meals" }.map { if (it.name == "Health") it.copy(name = "My wellbeing", revision = 2) else it }
        assertTrue(seedExpenseCategories(context, store))
        assertEquals(originalCommands, store.commands)
        assertFalse(store.categories.value.any { it.name == "Meals" })
        assertTrue(store.categories.value.any { it.name == "My wellbeing" })
        assertTrue(store.entries.value.isEmpty())
    }

    @Test fun recognizesExistingTranslatedNamesWithoutRenamingLegacyUserLabels() = runBlocking {
        val store = CatalogTestStore()
        store.categories.value = listOf(Category("user-meals", "餐飲", 5), Category("old-food", "Food", 1), Category("old-transport", "Transport", 1))
        assertTrue(seedExpenseCategories(context, store))
        val meals = expenseCategoryCatalog.first { it.key == "meals" }
        assertEquals(1, store.categories.value.count { meals.matches(it.name) })
        assertEquals(Category("user-meals", "餐飲", 5), store.categories.value.first { it.id == "user-meals" })
        assertEquals("Food", store.categories.value.first { it.id == "old-food" }.name)
        assertEquals("Transport", store.categories.value.first { it.id == "old-transport" }.name)
        assertTrue(store.categories.value.any { it.name == "Transportation" })
        assertEquals(8, store.commands.size)
    }

    @Test fun interruptedSeedRetriesItsFrozenNameAndCommandAcrossLocaleChange() = runBlocking {
        val store = CatalogTestStore(failBeforeFirstCommit = true)
        assertFalse(seedExpenseCategories(context, store))
        val chinese = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.TRADITIONAL_CHINESE) })
        assertTrue(seedExpenseCategories(chinese, store))
        assertEquals(store.commands[0], store.commands[1])
        assertEquals("Meals", store.categories.value.first { it.id == "starter-v1-meals" }.name)
        assertEquals(9, store.categories.value.size)
    }

    @Test fun deletedCategoryAfterUncertainSeedCommitCannotBeResurrectedByRetry() = runBlocking {
        val store = CatalogTestStore(failAfterFirstCommit = true)
        assertFalse(seedExpenseCategories(context, store))
        assertEquals(1, store.categories.value.size)
        store.categories.value = emptyList() // An explicit user deletion; writer replay evidence remains.
        assertTrue(seedExpenseCategories(context, store))
        assertEquals(store.commands[0], store.commands[1])
        assertFalse(store.categories.value.any { it.id == "starter-v1-meals" })
        assertEquals(8, store.categories.value.size)
    }

    @Test fun upgradeAddsSnacksWithoutRestoringPreviouslyDeletedCategories() = runBlocking {
        val prefs = context.getSharedPreferences(CATEGORY_STARTER_PREFERENCES, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("completed", true).commit()
        val store = CatalogTestStore()
        assertTrue(seedExpenseCategories(context, store))
        assertEquals(listOf("Snacks"), store.categories.value.map { it.name })
        store.categories.value = emptyList()
        assertTrue(seedExpenseCategories(context, store))
        assertTrue(store.categories.value.isEmpty())
    }
}

internal class CatalogTestStore(val failBeforeFirstCommit: Boolean = false, val failAfterFirstCommit: Boolean = false) : LedgerStore {
    override val entries = MutableStateFlow<List<LedgerEntry>>(emptyList())
    override val categories = MutableStateFlow<List<Category>>(emptyList())
    override val accounts = MutableStateFlow<List<AccountLabel>>(emptyList())
    override val aliases = MutableStateFlow<List<MerchantAlias>>(emptyList())
    override val rules = MutableStateFlow<List<CategoryRule>>(emptyList())
    val commands = mutableListOf<LedgerCommand.PutCategory>()
    private val committed = mutableMapOf<String, Pair<LedgerCommand.PutCategory, CommandResult>>()
    private var failed = false
    override suspend fun execute(command: LedgerCommand): CommandResult {
        require(command is LedgerCommand.PutCategory) { "Catalog must only create labels." }
        commands += command
        committed[command.operationId]?.let { (previous, result) -> assertEquals(previous, command); return result.copy(replayed = true) }
        if (failBeforeFirstCommit && !failed) { failed = true; throw java.io.IOException("Synthetic interrupted response") }
        categories.value = categories.value + Category(command.id, command.name, 1)
        val result = CommandResult(CommandStatus.APPLIED, command.id, 1)
        committed[command.operationId] = command to result
        if (failAfterFirstCommit && !failed) { failed = true; throw java.io.IOException("Synthetic interrupted response") }
        return result
    }
}
