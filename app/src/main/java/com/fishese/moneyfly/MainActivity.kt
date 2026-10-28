package com.fishese.moneyfly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fishese.moneyfly.ui.MoneyFlyApp
import com.fishese.moneyfly.capture.InboxRetentionWorker
import com.fishese.moneyfly.ui.seedExpenseCategories
import com.fishese.moneyfly.ui.seedTransportRules
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.MaterialTheme
import android.view.WindowManager
import com.fishese.moneyfly.ui.AppLockScreen
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class BackupOperationState : ViewModel() {
    var restorePassword: CharArray? = null
    val message = MutableStateFlow<Int?>(null)
    override fun onCleared() { restorePassword?.fill('\u0000'); restorePassword = null }
}

class MainActivity : ComponentActivity() {
    private var readyStore by mutableStateOf<com.fishese.moneyfly.storage.RoomLedgerStore?>(null)
    private var startupFailed by mutableStateOf(false)
    private val backupState: BackupOperationState by viewModels()
    val backupMessage get() = backupState.message
    private val stagedBackup get() = java.io.File(cacheDir, "pending-encrypted-backup.mfly")
    private val saveBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) { stagedBackup.delete(); return@registerForActivityResult }
        lifecycleScope.launch(Dispatchers.IO) {
            backupMessage.value = try {
                check(stagedBackup.isFile)
                val output = contentResolver.openOutputStream(uri) ?: error("Unable to open destination")
                stagedBackup.inputStream().use { input -> output.use { input.copyTo(it); it.flush() } }
                R.string.backup_saved
            } catch (_: Exception) { R.string.backup_failed } finally { stagedBackup.delete() }
        }
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val password = backupState.restorePassword.also { backupState.restorePassword = null }
        if (uri == null) { password?.fill('\u0000'); return@registerForActivityResult }
        if (password == null) { backupMessage.value = R.string.backup_interrupted; return@registerForActivityResult }
        lifecycleScope.launch(Dispatchers.IO) {
            backupMessage.value = try {
                val input = contentResolver.openInputStream(uri) ?: error("Unable to open backup")
                val data = input.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(32 * 1024)
                    val limit = com.fishese.moneyfly.security.BackupCrypto.MAX_PLAINTEXT_BYTES + 1024
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        require(output.size().toLong() + read <= limit) { "Backup is too large" }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                try { (application as MoneyFlyApplication).ledgerStore.restoreEncryptedBackup(data, password) } finally { data.fill(0) }
                R.string.backup_restored
            } catch (_: Exception) { R.string.backup_restore_failed } finally { password.fill('\u0000') }
        }
    }

    fun chooseBackupDestination(bytes: ByteArray) {
        backupMessage.value = null
        lifecycleScope.launch {
            try {
                kotlinx.coroutines.withContext(Dispatchers.IO) { stagedBackup.outputStream().use { it.write(bytes) } }
                saveBackup.launch("MoneyFly-backup.mfly")
            } catch (_: Exception) { stagedBackup.delete(); backupMessage.value = R.string.backup_failed }
            finally { bytes.fill(0) }
        }
    }

    fun chooseBackupToRestore(password: CharArray) {
        backupState.restorePassword?.fill('\u0000')
        backupState.restorePassword = password
        backupMessage.value = null
        openBackup.launch(arrayOf("application/octet-stream", "*/*"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        InboxRetentionWorker.schedule(this)
        com.fishese.moneyfly.fx.FxRateWorker.schedule(this)
        openStore()
        setContent {
            val app = application as MoneyFlyApplication
            val lock by app.appLock.state.collectAsStateWithLifecycle()
            androidx.compose.runtime.SideEffect {
                if (lock.configured) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            val holder = rememberSaveableStateHolder()
            if (lock.locked) MaterialTheme { AppLockScreen(this, app.appLock, lock.biometric) }
            else if (readyStore != null) holder.SaveableStateProvider("app") { MoneyFlyApp(readyStore!!, app.captureController, app.fxRepository) }
            else MaterialTheme {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text(stringResource(if (startupFailed) R.string.security_storage_open_failed else R.string.security_storage_loading))
                    if (startupFailed) {
                        Button(onClick = { openStore() }) { Text(stringResource(R.string.security_storage_retry)) }
                        if (com.fishese.moneyfly.storage.EncryptedStorage.canCancelPending(this@MainActivity)) {
                            Button(onClick = {
                                try { com.fishese.moneyfly.storage.EncryptedStorage.cancelPending(this@MainActivity); openStore() }
                                catch (_: Exception) { startupFailed = true }
                            }) { Text(stringResource(R.string.security_storage_cancel_encryption)) }
                        }
                    }
                }
            }
        }
    }

    private fun openStore() {
        startupFailed = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val store = (application as MoneyFlyApplication).ledgerStore
                if (seedExpenseCategories(this@MainActivity, store)) seedTransportRules(this@MainActivity, store)
                kotlinx.coroutines.withContext(Dispatchers.Main) { readyStore = store }
            } catch (_: Exception) { kotlinx.coroutines.withContext(Dispatchers.Main) { startupFailed = true } }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as MoneyFlyApplication).appLock.onForeground()
        lifecycleScope.launch(Dispatchers.IO) {
            try { (application as MoneyFlyApplication).captureController.onForeground() } catch (_: Exception) { }
        }
    }

    override fun onPause() {
        if (!isChangingConfigurations) (application as MoneyFlyApplication).appLock.lock()
        super.onPause()
    }

}
