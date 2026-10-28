package com.fishese.moneyfly.ui

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fishese.moneyfly.R
import com.fishese.moneyfly.security.AppLockManager
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal fun biometricAvailable(activity: Activity): Boolean = try {
    val manager = activity.getSystemService(BiometricManager::class.java)
    if (Build.VERSION.SDK_INT >= 30) manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    else @Suppress("DEPRECATION") (manager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS)
} catch (_: Exception) { false }

internal fun requestBiometric(activity: Activity, success: () -> Unit, error: () -> Unit): CancellationSignal {
    val cancel = CancellationSignal()
    val builder = BiometricPrompt.Builder(activity).setTitle(activity.getString(R.string.security_unlock_biometric))
        .setNegativeButton(activity.getString(R.string.security_use_password), activity.mainExecutor) { _, _ -> error() }
    if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    builder.build().authenticate(cancel, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = success()
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = error()
    })
    return cancel
}

@Composable
internal fun AppLockScreen(activity: Activity, manager: AppLockManager, biometric: Boolean) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(biometric, resumed) {
        val generation = manager.authGeneration()
        val signal = if (biometric && resumed && biometricAvailable(activity)) requestBiometric(activity, { manager.unlock(generation) }, {}) else null
        onDispose { signal?.cancel() }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.security_locked), style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(password, { password = it; error = false }, label = { Text(stringResource(R.string.security_password)) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, isError = error,
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("unlock_password"))
            if (error) Text(stringResource(R.string.security_wrong_password), color = MaterialTheme.colorScheme.error)
            Button(onClick = {
                val candidate = password.toCharArray(); val generation = manager.authGeneration(); password = ""; busy = true
                scope.launch {
                    try { if (manager.verify(candidate)) manager.unlock(generation) else error = true }
                    finally { candidate.fill('\u0000'); busy = false }
                }
            }, enabled = !busy && password.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("unlock_submit")) { Text(stringResource(R.string.security_unlock)) }
            if (biometric && biometricAvailable(activity)) TextButton(onClick = { manager.authGeneration().let { generation -> requestBiometric(activity, { manager.unlock(generation) }, {}) } }) { Text(stringResource(R.string.security_unlock_biometric)) }
        }
    }
}
