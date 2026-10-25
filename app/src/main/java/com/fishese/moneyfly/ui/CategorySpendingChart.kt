package com.fishese.moneyfly.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fishese.moneyfly.R
import com.fishese.moneyfly.ledger.Category
import com.fishese.moneyfly.ledger.LedgerEntry
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** A compact entry point; detailed charts and every category live in a scrollable sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategorySpendingOverview(
    entries: List<LedgerEntry>, categories: List<Category>, modifier: Modifier = Modifier,
    onCategorySelected: (String?, String) -> Unit,
) {
    val currencies = remember(entries) { categorySpending(entries) }
    var open by rememberSaveable { mutableStateOf(false) }
    var currency by rememberSaveable { mutableStateOf("") }
    var style by rememberSaveable { mutableStateOf("pie") }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val spending = currencies.firstOrNull { it.currency == currency } ?: currencies.firstOrNull()
    val selectedCategory = spending?.categories?.firstOrNull { it.categoryId.orEmpty() == selectedKey }
    val detailState = rememberLazyListState()
    LaunchedEffect(open, selectedKey, currency) {
        if (open && selectedCategory != null) detailState.animateScrollToItem(2)
    }
    val openLabel = stringResource(R.string.chart_open)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().testTag("category_spending_overview").semantics { contentDescription = openLabel }.clickable { open = true },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (spending != null) SpendingPie(spending, Modifier.size(44.dp), selectedKey = null) { selectedKey = it.categoryId.orEmpty(); open = true }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.chart_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                if (spending == null) Text(stringResource(R.string.chart_empty), style = MaterialTheme.typography.labelSmall)
                else Text(stringResource(R.string.chart_summary, spending.currency, spending.total.toPlainString(), pluralStringResource(R.plurals.chart_category_count, spending.categories.size, spending.categories.size)), style = MaterialTheme.typography.labelSmall)
                if (currencies.size > 1) Text(stringResource(R.string.chart_currency_count, currencies.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().testTag("category_spending_detail"), state = detailState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.chart_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.chart_gross_expenses), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (currencies.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    currencies.forEach { value -> FilterChip(selected = value.currency == spending?.currency, onClick = { currency = value.currency; selectedKey = null }, label = { Text(value.currency) }, modifier = Modifier.testTag("chart_currency_${value.currency}")) }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("pie" to R.string.chart_pie, "bar" to R.string.chart_bar, "list" to R.string.chart_list).forEach { (value, label) ->
                        FilterChip(selected = style == value, onClick = { style = value }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("chart_style_$value"))
                    }
                }
            }
            if (spending == null) item { Text(stringResource(R.string.chart_empty)) }
            else {
                item {
                    Text("${spending.currency} ${spending.total.toPlainString()}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    if (style == "pie") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            SpendingPie(spending, Modifier.size(220.dp).testTag("category_spending_pie"), selectedKey) { selectedKey = it.categoryId.orEmpty() }
                        }
                        Text(stringResource(R.string.chart_tap), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (selectedCategory != null) item(key = "selected") {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag("chart_selected_category")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(categoryName(selectedCategory, categories), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${spending.currency} ${selectedCategory.amount.toPlainString()} · ${spending.percentage(selectedCategory)}%", style = MaterialTheme.typography.bodyMedium)
                            selectedCategory.entries.take(3).forEach { entry ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(entry.draft.canonicalMerchant?.takeIf { it.isNotBlank() } ?: entry.draft.merchant.ifBlank { stringResource(R.string.no_merchant) }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    Text(entry.draft.amount, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Button(onClick = { open = false; onCategorySelected(selectedCategory.categoryId, spending.currency) }, modifier = Modifier.testTag("chart_show_entries")) { Text(stringResource(R.string.chart_show_entries)) }
                        }
                    }
                }
                items(spending.categories, key = { it.categoryId.orEmpty() }) { category ->
                    val name = categoryName(category, categories)
                    val description = stringResource(R.string.chart_accessible_category, name, spending.currency, category.amount.toPlainString(), spending.percentage(category), pluralStringResource(R.plurals.journal_entry_count, category.entries.size, category.entries.size))
                    val isSelected = selectedKey == category.categoryId.orEmpty()
                    Column(Modifier.fillMaxWidth().testTag("chart_category_${category.categoryId.orEmpty()}").semantics(mergeDescendants = true) { contentDescription = description; selected = isSelected }.clickable { selectedKey = category.categoryId.orEmpty() }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(color = categoryColor(category.categoryId), shape = MaterialTheme.shapes.small, modifier = Modifier.size(12.dp)) {}
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                            Text("${spending.currency} ${category.amount.toPlainString()}", Modifier.widthIn(max = 180.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Text(stringResource(R.string.chart_share, spending.percentage(category), pluralStringResource(R.plurals.journal_entry_count, category.entries.size, category.entries.size)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (style == "bar") LinearProgressIndicator(progress = { spending.fraction(category).toFloat() }, modifier = Modifier.fillMaxWidth().height(10.dp), color = categoryColor(category.categoryId), trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, drawStopIndicator = {})
                        else HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun categoryName(category: CategoryExpense, categories: List<Category>): String = categories.firstOrNull { it.id == category.categoryId }?.name ?: stringResource(R.string.uncategorized)

@Composable
private fun categoryColor(id: String?): Color {
    val hue = ((id ?: "uncategorized").hashCode().toLong().and(0xffffffffL) * 137.508 % 360.0).toFloat()
    return Color.hsl(hue, 0.58f, if (MaterialTheme.colorScheme.surface.luminance() < 0.4f) 0.67f else 0.40f)
}

@Composable
private fun SpendingPie(spending: CurrencyCategorySpending, modifier: Modifier, selectedKey: String?, onSelect: (CategoryExpense) -> Unit) {
    val colors = spending.categories.map { categoryColor(it.categoryId) }
    val outline = MaterialTheme.colorScheme.surface
    val label = stringResource(R.string.chart_tap)
    Canvas(modifier.semantics { contentDescription = label }.pointerInput(spending) {
        detectTapGestures { point ->
            val dx = point.x - size.width / 2f
            val dy = point.y - size.height / 2f
            val radius = min(size.width, size.height) / 2f
            val distance = hypot(dx, dy)
            if (distance <= radius && distance >= radius * 0.48f) {
                val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90.0
                categoryAtAngle(spending, angle)?.let(onSelect)
            }
        }
    }) {
        var angle = -90f
        spending.categories.forEachIndexed { index, category ->
            val sweep = if (index == spending.categories.lastIndex) 270f - angle else (spending.fraction(category) * 360.0).toFloat()
            drawArc(colors[index], angle, sweep.coerceAtLeast(0f), useCenter = true)
            if (selectedKey == category.categoryId.orEmpty()) {
                drawArc(outline, angle, sweep.coerceAtLeast(0f), useCenter = true, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
            }
            angle += sweep
        }
        drawCircle(outline, radius = size.minDimension * 0.24f, center = Offset(size.width / 2, size.height / 2))
    }
}
