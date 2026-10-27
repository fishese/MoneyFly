package com.fishese.moneyfly.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fishese.moneyfly.MainActivity
import com.fishese.moneyfly.MoneyFlyApplication
import com.fishese.moneyfly.R
import com.fishese.moneyfly.security.AppLockManager
import kotlinx.coroutines.launch

@Composable
internal fun BackupSettings(manager: AppLockManager, activity: MainActivity) {
    val state by manager.state.collectAsStateWithLifecycle()
    val message by activity.backupMessage.collectAsStateWithLifecycle()
    var action by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.backup_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = { action = "save" }, enabled = state.configured, modifier = Modifier.testTag("backup_export")) { Text(stringResource(R.string.backup_create)) }
    if (!state.configured) Text(stringResource(R.string.backup_requires_password), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { action = "restore" }, modifier = Modifier.testTag("backup_import")) { Text(stringResource(R.string.backup_restore)) }
    message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
    action?.let { mode ->
        var password by remember(mode) { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<Int?>(null) }
        val saving = mode == "save"
        AlertDialog(onDismissRequest = { if (!busy) action = null }, title = { Text(stringResource(if (saving) R.string.backup_create else R.string.backup_restore)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (saving) R.string.backup_enter_current_password else R.string.backup_replace_warning))
                OutlinedTextField(password, { password = it; error = null }, label = { Text(stringResource(R.string.security_password)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.testTag("backup_password"))
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !busy && password.isNotEmpty(), onClick = {
                val candidate = password.toCharArray(); password = ""; busy = true
                scope.launch {
                    var handedOff = false
                    try {
                        if (saving) {
                            if (!manager.verify(candidate)) { error = R.string.security_wrong_password; return@launch }
                            val bytes = (activity.application as MoneyFlyApplication).ledgerStore.createEncryptedBackup(candidate)
                            activity.chooseBackupDestination(bytes)
                        } else { activity.chooseBackupToRestore(candidate); handedOff = true }
                        action = null
                    } catch (_: Exception) { error = R.string.backup_failed }
                    finally { if (!handedOff) candidate.fill('\u0000'); busy = false }
                }
            }, modifier = Modifier.testTag("backup_confirm")) { Text(stringResource(if (saving) R.string.backup_choose_destination else R.string.backup_choose_file)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { action = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
