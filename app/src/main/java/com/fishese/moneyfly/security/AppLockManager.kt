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

    suspend fun verify(password: CharArray): Boolean = withContext(Dispatchers.Default) {
        try {
            val salt = Base64.decode(prefs.getString("salt", ""), Base64.NO_WRAP)
            val hash = Base64.decode(prefs.getString("hash", ""), Base64.NO_WRAP)
            PasswordVerifier.verify(password, PasswordVerifier.Record(salt, hash))
        } catch (_: Exception) { false }
    }

    suspend fun create(password: CharArray) = withContext(Dispatchers.Default) {
        check(!mutable.value.configured)
        val record = PasswordVerifier.create(password)
        check(prefs.edit().putString("salt", Base64.encodeToString(record.salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(record.hash, Base64.NO_WRAP)).putBoolean("biometric", false).commit())
        mutable.value = AppLockState(configured = true, biometric = false, locked = false)
    }

    fun unlock() { if (mutable.value.configured) mutable.value = mutable.value.copy(locked = false) }
    fun lock() { if (mutable.value.configured) mutable.value = mutable.value.copy(locked = true) }
    fun setBiometric(enabled: Boolean) {
        check(mutable.value.configured && !mutable.value.locked)
        check(prefs.edit().putBoolean("biometric", enabled).commit())
        mutable.value = mutable.value.copy(biometric = enabled)
    }

    suspend fun disable(password: CharArray): Boolean {
        if (!verify(password)) return false
        check(prefs.edit().clear().commit())
        mutable.value = AppLockState(configured = false, biometric = false, locked = false)
        return true
    }
}
