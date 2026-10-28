package com.fishese.moneyfly.security

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class AppLockState(val configured: Boolean, val biometric: Boolean, val locked: Boolean)

/** A UI lock. The database key remains available for background notification capture. */
class AppLockManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app_lock_v1", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(AppLockState(prefs.contains("hash"), prefs.getBoolean("biometric", false), prefs.contains("hash")))
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var foreground = true
    @Synchronized fun onForeground() { foreground = true }
    @Synchronized fun authGeneration(): Long = generation

    suspend fun verify(password: CharArray): Boolean = withContext(Dispatchers.Default) {
        try {
            val salt = Base64.decode(prefs.getString("salt", ""), Base64.NO_WRAP)
            val hash = Base64.decode(prefs.getString("hash", ""), Base64.NO_WRAP)
            PasswordVerifier.verify(password, PasswordVerifier.Record(salt, hash))
        } catch (_: Exception) { false }
    }

    suspend fun create(password: CharArray) {
        val started = synchronized(this) { check(!mutable.value.configured); generation }
        withContext(Dispatchers.Default) {
            val record = PasswordVerifier.create(password)
            check(prefs.edit().putString("salt", Base64.encodeToString(record.salt, Base64.NO_WRAP))
                .putString("hash", Base64.encodeToString(record.hash, Base64.NO_WRAP)).putBoolean("biometric", false).commit())
            synchronized(this@AppLockManager) {
                mutable.value = AppLockState(configured = true, biometric = false, locked = !foreground || generation != started)
            }
        }
    }

    @Synchronized fun unlock(expectedGeneration: Long = generation) {
        if (foreground && generation == expectedGeneration && mutable.value.configured) mutable.value = mutable.value.copy(locked = false)
    }
    @Synchronized fun lock() {
        generation++
        foreground = false
        if (mutable.value.configured) mutable.value = mutable.value.copy(locked = true)
    }
    @Synchronized fun setBiometric(enabled: Boolean) {
        check(mutable.value.configured && !mutable.value.locked)
        check(prefs.edit().putBoolean("biometric", enabled).commit())
        mutable.value = mutable.value.copy(biometric = enabled)
    }

    suspend fun disable(password: CharArray): Boolean {
        val started = authGeneration()
        if (!verify(password)) return false
        return synchronized(this) {
            if (!foreground || generation != started || mutable.value.locked) return@synchronized false
            check(prefs.edit().clear().commit())
            mutable.value = AppLockState(configured = false, biometric = false, locked = false)
            true
        }
    }
}
