package com.fishese.moneyfly.ui

import android.app.Activity
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
import com.fishese.moneyfly.R
import com.fishese.moneyfly.security.AppLockManager
import kotlinx.coroutines.launch

@Composable
internal fun SecuritySettings(manager: AppLockManager, activity: Activity) {
    val state by manager.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }
    var confirmEncryption by remember { mutableStateOf(false) }
    var encryptionError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Text(stringResource(R.string.security_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.security_background_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val encrypted = remember { com.fishese.moneyfly.storage.EncryptedStorage.isEnabled(activity) }
    Text(stringResource(if (encrypted) R.string.security_encryption_enabled else R.string.security_encryption_disabled), style = MaterialTheme.typography.bodySmall)
    if (!encrypted) OutlinedButton(onClick = { confirmEncryption = true }, enabled = state.configured, modifier = Modifier.testTag("security_encrypt")) { Text(stringResource(R.string.security_encrypt)) }
    if (encryptionError) Text(stringResource(R.string.operation_failed), color = MaterialTheme.colorScheme.error)
    if (!state.configured) Button(onClick = { dialog = "create" }, modifier = Modifier.testTag("security_create")) { Text(stringResource(R.string.security_set_password)) }
    else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.security_biometric_setting), Modifier.weight(1f))
            Switch(checked = state.biometric, enabled = biometricAvailable(activity), onCheckedChange = { enabled ->
                if (!enabled) manager.setBiometric(false)
                else requestBiometric(activity, { if (!manager.state.value.locked) manager.setBiometric(true) }, {})
            }, modifier = Modifier.testTag("security_biometric"))
        }
        TextButton(onClick = { dialog = "disable" }, modifier = Modifier.testTag("security_disable")) { Text(stringResource(R.string.security_disable)) }
    }
    dialog?.let { action ->
        var password by remember(action) { mutableStateOf("") }
        var confirmation by remember(action) { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf(false) }
        val creating = action == "create"
        AlertDialog(onDismissRequest = { if (!busy) dialog = null }, title = { Text(stringResource(if (creating) R.string.security_set_password else R.string.security_disable)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (creating) R.string.security_password_hint else R.string.security_disable_hint))
                OutlinedTextField(password, { password = it; error = false }, label = { Text(stringResource(R.string.security_password)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.testTag("security_password"))
                if (creating) OutlinedTextField(confirmation, { confirmation = it; error = false }, label = { Text(stringResource(R.string.security_confirm_password)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.testTag("security_confirm"))
                if (error) Text(stringResource(if (creating) R.string.operation_failed else R.string.security_wrong_password), color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(enabled = !busy && if (creating) password.length in 8..1024 && password == confirmation else password.isNotEmpty(), onClick = {
                val candidate = password.toCharArray(); password = ""; confirmation = ""; busy = true
                scope.launch {
                    try {
                        if (creating) { manager.create(candidate); dialog = null }
                        else if (manager.disable(candidate)) dialog = null else error = true
                    } catch (_: Exception) { error = true }
                    finally { candidate.fill('\u0000'); busy = false }
                }
            }, modifier = Modifier.testTag("security_confirm_action")) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (confirmEncryption) AlertDialog(onDismissRequest = { confirmEncryption = false },
        title = { Text(stringResource(R.string.security_encrypt)) }, text = { Text(stringResource(R.string.security_encrypt_confirm)) },
        confirmButton = { TextButton(onClick = {
            try {
                com.fishese.moneyfly.storage.EncryptedStorage.requestMigration(activity)
                confirmEncryption = false
                activity.finishAndRemoveTask()
                android.os.Process.killProcess(android.os.Process.myPid())
            } catch (_: Exception) { encryptionError = true; confirmEncryption = false }
        }, modifier = Modifier.testTag("security_encrypt_confirm")) { Text(stringResource(R.string.security_encrypt_restart)) } },
        dismissButton = { TextButton(onClick = { confirmEncryption = false }) { Text(stringResource(R.string.cancel)) } })
}
