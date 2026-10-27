package com.fishese.moneyfly.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLockManagerTest {
    @Test fun passwordProtectsReopeningAndDisablingLock() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("app_lock_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val manager = AppLockManager(context)
        val password = "invented password".toCharArray()
        manager.create(password)
        assertFalse(manager.state.value.locked)
        manager.lock()
        assertTrue(manager.state.value.locked)
        assertFalse(manager.verify("incorrect".toCharArray()))
        assertTrue(manager.verify(password))
        manager.unlock()
        assertFalse(manager.disable("incorrect".toCharArray()))
        assertTrue(manager.disable(password))
        assertFalse(manager.state.value.configured)
    }
}
