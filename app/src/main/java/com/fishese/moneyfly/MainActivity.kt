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

class MainActivity : ComponentActivity() {
    private var readyStore by mutableStateOf<com.fishese.moneyfly.storage.RoomLedgerStore?>(null)
    private var startupFailed by mutableStateOf(false)
    val backupMessage = MutableStateFlow<Int?>(null)
    private var pendingBackup: ByteArray? = null
    private var pendingRestorePassword: CharArray? = null
    private val saveBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingBackup.also { pendingBackup = null }
        if (uri == null || bytes == null) { bytes?.fill(0); return@registerForActivityResult }
        lifecycleScope.launch(Dispatchers.IO) {
            backupMessage.value = try {
                val output = contentResolver.openOutputStream(uri) ?: error("Unable to open destination")
                output.use { it.write(bytes); it.flush() }
                R.string.backup_saved
            } catch (_: Exception) { R.string.backup_failed } finally { bytes.fill(0) }
        }
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val password = pendingRestorePassword.also { pendingRestorePassword = null }
        if (uri == null || password == null) { password?.fill('\u0000'); return@registerForActivityResult }
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
        pendingBackup?.fill(0)
        pendingBackup = bytes
        backupMessage.value = null
        saveBackup.launch("MoneyFly-backup.mfly")
    }

    fun chooseBackupToRestore(password: CharArray) {
        pendingRestorePassword?.fill('\u0000')
        pendingRestorePassword = password
        backupMessage.value = null
        openBackup.launch(arrayOf("application/octet-stream", "*/*"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        InboxRetentionWorker.schedule(this)
        com.fishese.moneyfly.fx.FxRateWorker.schedule(this)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val store = (application as MoneyFlyApplication).ledgerStore
                if (seedExpenseCategories(this@MainActivity, store)) seedTransportRules(this@MainActivity, store)
                kotlinx.coroutines.withContext(Dispatchers.Main) { readyStore = store }
            } catch (_: Exception) { kotlinx.coroutines.withContext(Dispatchers.Main) { startupFailed = true } }
        }
        setContent {
            val app = application as MoneyFlyApplication
            val lock by app.appLock.state.collectAsStateWithLifecycle()
            androidx.compose.runtime.SideEffect {
                if (lock.configured) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            if (lock.locked) MaterialTheme { AppLockScreen(this, app.appLock, lock.biometric) }
            else if (readyStore != null) MoneyFlyApp(readyStore!!, app.captureController, app.fxRepository)
            else MaterialTheme { Text(stringResource(if (startupFailed) R.string.security_storage_open_failed else R.string.security_storage_loading)) }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) {
            try { (application as MoneyFlyApplication).captureController.onForeground() } catch (_: Exception) { }
        }
    }

    override fun onPause() {
        if (!isChangingConfigurations) (application as MoneyFlyApplication).appLock.lock()
        super.onPause()
    }

    override fun onDestroy() {
        pendingRestorePassword?.fill('\u0000')
        pendingRestorePassword = null
        pendingBackup?.fill(0)
        pendingBackup = null
        super.onDestroy()
    }
}
