package com.fishese.moneyfly.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.fishese.moneyfly.R
import com.fishese.moneyfly.fx.*
import com.fishese.moneyfly.ledger.LedgerEntry
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency

@Composable
internal fun CurrencyPicker(currency: String, label: String, enabled: Boolean = true, tag: String = "reporting_currency", changed: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    val choices = remember { Currency.getAvailableCurrencies().filter { it.defaultFractionDigits >= 0 }.sortedBy { it.currencyCode } }
    OutlinedButton(onClick = { search = ""; open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag)) { Text("$label: $currency") }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = {
        Column {
            OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.fx_search_currency)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("currency_search"))
            LazyColumn(Modifier.heightIn(max = 330.dp)) {
                items(choices.filter { it.currencyCode.contains(search, true) || it.displayName.contains(search, true) }, key = { it.currencyCode }) { choice ->
                    TextButton(onClick = { changed(choice.currencyCode); open = false }, modifier = Modifier.fillMaxWidth().testTag("currency_${choice.currencyCode}")) { Text("${choice.currencyCode} · ${choice.displayName}") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun FxSettings(repository: FxRepository, state: FxSnapshot, entries: List<LedgerEntry>) {
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.fx_reporting_currency), style = MaterialTheme.typography.titleLarge)
            CurrencyPicker(state.currency, stringResource(R.string.fx_reporting_currency), !state.refreshing) { code -> scope.launch {
                try { repository.setCurrency(code); failed = false } catch (_: Exception) { failed = true }
            } }
            Text(stringResource(R.string.fx_default_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.fx_provider_hint), style = MaterialTheme.typography.bodySmall)
            state.lastChecked?.let { Text(stringResource(R.string.fx_last_checked, Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))), style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(enabled = !state.refreshing, onClick = { scope.launch { repository.refresh(entries, force = true) } }, modifier = Modifier.fillMaxWidth().testTag("refresh_exchange_rates")) {
                Text(stringResource(if (state.refreshing) R.string.fx_refreshing else R.string.fx_refresh))
            }
            if (state.failed || failed) Text(stringResource(R.string.fx_refresh_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.fx_privacy), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun EntryRateFields(currency: String, target: String, rate: String, custom: Boolean, enabled: Boolean,
    automatic: FxQuote?, changeCustom: (Boolean) -> Unit, changeRate: (String) -> Unit, changeTarget: (String) -> Unit) {
    HorizontalDivider()
    Text(stringResource(R.string.fx_entry_rate), style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(stringResource(R.string.fx_custom_rate), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(custom, changeCustom, enabled = enabled, modifier = Modifier.testTag("custom_exchange_rate"))
    }
    if (custom) {
        CurrencyPicker(target, stringResource(R.string.fx_rate_target), enabled, "entry_rate_currency", changeTarget)
        OutlinedTextField(rate, changeRate, enabled = enabled, label = { Text(stringResource(R.string.fx_rate_direction, currency, target)) },
            supportingText = { Text(stringResource(R.string.fx_rate_help)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("entry_exchange_rate"))
        if (runCatching { validatedEntryRate(rate, target) }.isFailure) Text(stringResource(R.string.fx_invalid_rate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    } else if (automatic != null) Text(stringResource(R.string.fx_auto_rate, currency, automatic.rate, target, automatic.rateDate.toString()), style = MaterialTheme.typography.bodySmall)
    else Text(stringResource(R.string.fx_rate_pending), style = MaterialTheme.typography.bodySmall)
}
