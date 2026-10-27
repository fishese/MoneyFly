package com.fishese.moneyfly.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fishese.moneyfly.R
import com.fishese.moneyfly.ledger.*
import java.util.UUID
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorLabelPicker(title: String, options: List<Pair<String, String>>, emptyLabel: String, selected: String, dismiss: () -> Unit, categoryStore: LedgerStore? = null, choose: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var pendingName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingOperation by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var retryLocked by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCatalog by rememberSaveable { mutableStateOf(false) }
    var customFocusRequests by remember { mutableIntStateOf(0) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(customFocusRequests) {
        if (customFocusRequests > 0) {
            withFrameNanos { }
            searchFocus.requestFocus()
            keyboard?.show()
        }
    }
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.editor_category_create_failed)
    val interrupted = stringResource(R.string.editor_category_create_retry)
    val editable = !busy && !retryLocked
    val canCreate = categoryStore != null && query.isNotBlank() && (options.map { it.second } + emptyLabel).none { Categorization.normalize(it) == Categorization.normalize(query) }
    val sheetState = rememberModalBottomSheetState(confirmValueChange = { !busy })
    ModalBottomSheet(onDismissRequest = { if (!busy) dismiss() }, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(query, { query = it; pendingName = null; pendingId = null; pendingOperation = null; error = null }, enabled = editable, singleLine = true, label = { Text(stringResource(R.string.editor_search_labels)) }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).focusRequester(searchFocus).testTag("editor_label_search"))
            if (categoryStore != null) TextButton(onClick = { showCatalog = true }, enabled = editable, modifier = Modifier.testTag("editor_more_categories")) { Text(stringResource(R.string.category_more)) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp).testTag("editor_category_error")) }
            if (canCreate || retryLocked) {
                Button(enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("editor_create_category"), onClick = {
                    val store = categoryStore ?: return@Button
                    val name = pendingName ?: query.trim().also { pendingName = it }
                    val id = pendingId ?: UUID.randomUUID().toString().also { pendingId = it }
                    val operation = pendingOperation ?: UUID.randomUUID().toString().also { pendingOperation = it }
                    val command = LedgerCommand.PutCategory(id, name, operationId = operation)
                    busy = true
                    scope.launch {
                        try {
                            val result = store.execute(command)
                            if (result.applied) choose(id) else {
                                error = failed; retryLocked = false
                                pendingName = null; pendingId = null; pendingOperation = null
                            }
                        } catch (_: Exception) { error = interrupted; retryLocked = true }
                        finally { busy = false }
                    }
                }) { Text(stringResource(if (busy) R.string.saving else R.string.editor_create_category, pendingName ?: query.trim())) }
                Text(stringResource(R.string.editor_category_creation_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            }
            val rows = (listOf("" to emptyLabel) + options).filter { query.isBlank() || it.second.contains(query.trim(), ignoreCase = true) }
            if (categoryStore != null && rows.isNotEmpty()) {
                LazyVerticalGrid(columns = GridCells.Adaptive(120.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(rows, key = { it.first }) { (id, name) ->
                        FilterChip(selected = id == selected, onClick = { choose(id) }, enabled = editable,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("editor_label_${id.ifEmpty { "none" }}"),
                            label = { Text(name) })
                    }
                }
            } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (rows.isEmpty()) item { Text(stringResource(R.string.editor_no_label_matches), Modifier.padding(vertical = 16.dp)) }
                items(rows, key = { it.first }) { (id, name) ->
                    TextButton(onClick = { choose(id) }, enabled = editable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("editor_label_${id.ifEmpty { "none" }}")) {
                        Text(name, Modifier.weight(1f), style = if (id == selected) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
                        if (id == selected) Text(stringResource(R.string.editor_selected), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    if (showCatalog && categoryStore != null) ExpenseCategoryCatalogDialog(categoryStore, dismiss = { showCatalog = false }, customCategory = { showCatalog = false; customFocusRequests++ }, selectCategory = choose)
}
