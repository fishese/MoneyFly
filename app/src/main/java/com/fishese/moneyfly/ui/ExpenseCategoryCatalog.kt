package com.fishese.moneyfly.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fishese.moneyfly.R
import com.fishese.moneyfly.ledger.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ExpenseCategoryOption(val key: String, val label: Int, val english: String, val traditionalChinese: String, val starter: Boolean = false) {
    fun matches(name: String): Boolean = Categorization.normalize(name) in setOf(Categorization.normalize(english), Categorization.normalize(traditionalChinese))
}

internal val expenseCategoryCatalog = listOf(
    ExpenseCategoryOption("meals", R.string.category_meals, "Meals", "餐飲", true),
    ExpenseCategoryOption("entertainment", R.string.category_entertainment, "Entertainment", "娛樂", true),
    ExpenseCategoryOption("hobby", R.string.category_hobby, "Hobby", "興趣", true),
    ExpenseCategoryOption("transportation", R.string.category_transportation, "Transportation", "交通", true),
    ExpenseCategoryOption("shopping", R.string.category_shopping, "Shopping", "購物", true),
    ExpenseCategoryOption("groceries", R.string.category_groceries, "Groceries", "日常雜貨", true),
    ExpenseCategoryOption("bills", R.string.category_bills, "Bills", "帳單", true),
    ExpenseCategoryOption("health", R.string.category_health, "Health", "健康", true),
    ExpenseCategoryOption("travel", R.string.category_travel, "Travel", "旅遊"),
    ExpenseCategoryOption("education", R.string.category_education, "Education", "教育"),
    ExpenseCategoryOption("gifts", R.string.category_gifts, "Gifts", "禮物"),
    ExpenseCategoryOption("subscriptions", R.string.category_subscriptions, "Subscriptions", "訂閱"),
    ExpenseCategoryOption("personal-care", R.string.category_personal_care, "Personal care", "個人護理"),
    ExpenseCategoryOption("home", R.string.category_home, "Home", "家居"),
    ExpenseCategoryOption("pets", R.string.category_pets, "Pets", "寵物"),
    ExpenseCategoryOption("sports", R.string.category_sports, "Sports", "運動"),
    ExpenseCategoryOption("family", R.string.category_family, "Family", "家庭"),
    ExpenseCategoryOption("charity", R.string.category_charity, "Charity", "捐款"),
    ExpenseCategoryOption("fees", R.string.category_fees, "Fees", "手續費"),
)

private val categoryCatalogMutex = Mutex()
internal const val CATEGORY_STARTER_PREFERENCES = "expense_category_starters_v1"

/** Labels only. Completed/per-key markers are app preferences, never part of ledger export. */
internal suspend fun seedExpenseCategories(context: Context, store: LedgerStore): Boolean = categoryCatalogMutex.withLock {
    val preferences = context.getSharedPreferences(CATEGORY_STARTER_PREFERENCES, Context.MODE_PRIVATE)
    if (preferences.getBoolean("completed", false)) return@withLock true
    for (option in expenseCategoryCatalog.filter { it.starter }) {
        if (preferences.getBoolean("${option.key}.done", false)) continue
        if (store.categories.first().any { option.matches(it.name) }) {
            if (!preferences.edit().putBoolean("${option.key}.done", true).commit()) return@withLock false
            continue
        }
        // Freeze the localized payload before the durable writer sees it. A locale change cannot change a retry.
        val name = preferences.getString("${option.key}.name", null) ?: context.getString(option.label).also {
            if (!preferences.edit().putString("${option.key}.name", it).commit()) return@withLock false
        }
        val result = try {
            store.execute(LedgerCommand.PutCategory("starter-v1-${option.key}", name, operationId = "starter-v1-create-${option.key}"))
        } catch (_: Exception) { return@withLock false }
        if (!result.applied) return@withLock false
        if (!preferences.edit().putBoolean("${option.key}.done", true).commit()) return@withLock false
    }
    preferences.edit().putBoolean("completed", true).commit()
}

/** Serializes catalog adds with startup seeding and checks actual writer state before creating another name. */
private suspend fun addCatalogCategory(store: LedgerStore, option: ExpenseCategoryOption, command: LedgerCommand.PutCategory): CommandResult = categoryCatalogMutex.withLock {
    val existing = store.categories.first().firstOrNull { option.matches(it.name) }
    if (existing != null) CommandResult(CommandStatus.APPLIED, entityId = existing.id, revision = existing.revision)
    else store.execute(command)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExpenseCategorySuggestions(categories: List<Category>, store: LedgerStore, customCategory: () -> Unit) {
    var more by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.category_common), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.category_catalog_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        expenseCategoryCatalog.filter { it.starter }.forEach { option ->
            CatalogCategoryAction(option, categories, store, chip = true)
        }
    }
    OutlinedButton(onClick = { more = true }, modifier = Modifier.testTag("more_expense_categories")) { Text(stringResource(R.string.category_more)) }
    if (more) ExpenseCategoryCatalogDialog(store, dismiss = { more = false }, customCategory = { more = false; customCategory() })
}

@Composable
internal fun ExpenseCategoryCatalogDialog(store: LedgerStore, dismiss: () -> Unit, customCategory: () -> Unit, selectCategory: ((String) -> Unit)? = null) {
    val categories by store.categories.collectAsStateWithLifecycle(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.category_more)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.category_catalog_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.category_catalog_search)) }, modifier = Modifier.fillMaxWidth().testTag("category_catalog_search"))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                val rows = expenseCategoryCatalog.filter { option ->
                    query.isBlank() || option.english.contains(query.trim(), true) || option.traditionalChinese.contains(query.trim(), true)
                }
                items(rows, key = { it.key }) { option -> CatalogCategoryAction(option, categories, store, chip = false, selectCategory = selectCategory) }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.category_done)) } }, dismissButton = {
        TextButton(onClick = customCategory, modifier = Modifier.testTag("catalog_custom_category")) { Text(stringResource(R.string.category_custom)) }
    })
}

@Composable
private fun CatalogCategoryAction(option: ExpenseCategoryOption, categories: List<Category>, store: LedgerStore, chip: Boolean, selectCategory: ((String) -> Unit)? = null) {
    val label = stringResource(option.label)
    val existing = categories.firstOrNull { option.matches(it.name) }
    var id by rememberSaveable(option.key) { mutableStateOf(UUID.randomUUID().toString()) }
    var operation by rememberSaveable(option.key) { mutableStateOf(UUID.randomUUID().toString()) }
    var frozenName by rememberSaveable(option.key) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var retry by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.category_catalog_failed)
    val scope = rememberCoroutineScope()
    fun add() {
        if (existing != null) { selectCategory?.invoke(existing.id); return }
        if (busy) return
        val command = LedgerCommand.PutCategory(id, frozenName ?: label.also { frozenName = it }, operationId = operation)
        busy = true
        scope.launch {
            try {
                val result = addCatalogCategory(store, option, command)
                if (result.applied) {
                    val chosenId = result.entityId ?: command.id
                    retry = false; error = null; frozenName = null
                    // This intent is settled. A later explicit re-add after deletion is a NEW creation.
                    id = UUID.randomUUID().toString(); operation = UUID.randomUUID().toString()
                    selectCategory?.invoke(chosenId)
                }
                else {
                    error = failed; retry = false; frozenName = null
                    id = UUID.randomUUID().toString(); operation = UUID.randomUUID().toString()
                }
            } catch (_: Exception) { error = failed; retry = true }
            finally { busy = false }
        }
    }
    Column {
        val enabled = !busy && (existing == null || selectCategory != null)
        if (chip) SuggestionChip(onClick = ::add, enabled = enabled, label = { Text(if (retry) stringResource(R.string.category_retry, frozenName ?: label) else label) }, modifier = Modifier.testTag("catalog_add_${option.key}"))
        else TextButton(onClick = ::add, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("catalog_add_${option.key}")) {
            Text(if (retry) stringResource(R.string.category_retry, frozenName ?: label) else existing?.name ?: label, Modifier.weight(1f))
            Text(stringResource(if (existing != null) R.string.category_added else R.string.category_add), style = MaterialTheme.typography.labelMedium)
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
