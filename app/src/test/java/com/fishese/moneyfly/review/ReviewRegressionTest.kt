package com.fishese.moneyfly.review

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fishese.moneyfly.capture.*
import com.fishese.moneyfly.ledger.*
import com.fishese.moneyfly.security.AppLockManager
import com.fishese.moneyfly.security.BackupCrypto
import com.fishese.moneyfly.storage.*
import com.fishese.moneyfly.ui.MoneyFlyApp
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.fishese.moneyfly.fx.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class ReviewRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lockPreservesUnsavedEntryDraft() {
        val unlocked = mutableStateOf(true)
        val store = Store()
        compose.setContent { val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder(); if (unlocked.value) holder.SaveableStateProvider("app") { MoneyFlyApp(store) } else Text("Synthetic locked screen") }
        compose.onNodeWithTag("add_entry").performClick()
        compose.onNodeWithTag("entry_amount").performTextInput("12.34")
        compose.runOnIdle { unlocked.value = false }
        compose.onNodeWithTag("entry_amount").assertDoesNotExist()
        compose.runOnIdle { unlocked.value = true }
        compose.onNodeWithTag("entry_amount").assertTextContains("12.34")
        assertTrue(store.commands.isEmpty())
    }

    @Test fun passwordSetupRemainsLockedAfterBackgrounding() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("app_lock_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val manager = AppLockManager(context)
            val creating = async(Dispatchers.Default) { manager.create("synthetic review password".toCharArray()) }
            assertFalse(manager.state.value.configured)
            manager.lock()
            creating.await()
            assertTrue(manager.state.value.configured)
            assertTrue(manager.state.value.locked)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun staleAuthenticationCannotUnlockAfterBackgrounding() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("app_lock_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val manager = AppLockManager(context)
            manager.create("synthetic review password".toCharArray())
            manager.lock()
            manager.onForeground()
            val attempt = manager.authGeneration()
            manager.lock()
            manager.onForeground()
            manager.unlock(attempt)
            assertTrue(manager.state.value.locked)
            manager.unlock(manager.authGeneration())
            assertFalse(manager.state.value.locked)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun backupOmitsExpiredNotificationText() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        var now = Instant.parse("2026-01-01T00:00:00Z")
        try {
            val store = RoomLedgerStore(db, ReceiptDigester { it.reversed() }, { now })
            store.execute(LedgerCommand.SetCaptureConsent(true, 0))
            store.execute(LedgerCommand.CaptureNotification(NotificationPayload("review.synthetic", "synthetic", "key", now, now,
                title = "EXPIRED_SYNTHETIC_SECRET")))
            now = now.plusSeconds(8 * 86400L)
            assertTrue(store.inbox.first().isEmpty())
            val password = "synthetic review password".toCharArray()
            val backup = store.createEncryptedBackup(password)
            val decoded = BackupCrypto.decrypt(backup, password).toString(Charsets.UTF_8)
            assertFalse(decoded.contains("EXPIRED_SYNTHETIC_SECRET"))
        } finally { db.close() }
    }

    @Test fun ignoredNotificationsDoNotAccumulateOperationRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val now = Instant.parse("2026-01-01T00:00:00Z")
        try {
            val store = RoomLedgerStore(db, ReceiptDigester { it.reversed() }, { now })
            store.execute(LedgerCommand.SetCaptureConsent(true, 0))
            store.execute(LedgerCommand.SetIgnoredSource("review.ignored", true, 0))
            repeat(100) { index -> store.execute(LedgerCommand.CaptureNotification(NotificationPayload("review.ignored", "synthetic", "key-$index", now, now))) }
            assertTrue(store.inbox.first().isEmpty())
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM operation_results").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0))
            }
        } finally { db.close() }
    }

    @Test fun completedRefreshDoesNotOverwriteDisconnect() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = CaptureStore(entered, release)
        store.captureSettings.value = CaptureSettings(true)
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = AndroidCaptureController(context, store, store, controllerScope) { true }
        val listener = object : NotificationListenerService() {
            override fun getActiveNotifications(): Array<StatusBarNotification> = emptyArray()
        }
        try {
            controller.connected(listener)
            withTimeout(5000) { entered.await() }
            controller.disconnected(listener)
            assertFalse(controller.connection.value.connected)
            release.complete(Unit)
            withTimeout(5000) { while (controller.connection.value.refreshing) delay(10) }
            assertFalse(controller.connection.value.connected)
        } finally { controllerScope.cancel() }
    }

    @Test fun slowExchangeRateRequestDoesNotBlockDisplaySettings() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("reporting-currency-and-rates", Context.MODE_PRIVATE).edit().clear().commit()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = FxRateClient { source, target, dates ->
            entered.complete(Unit); release.await()
            dates.map { FxQuote(it, source, target, "0.05", it, 0) }
        }
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val repo = FxRepository(context, client) { now.toEpochMilli() }
        val entry = LedgerEntry("synthetic", 1, EntryDraft("100", "JPY", occurredAt = now), createdAt = now, updatedAt = now, lastOperationId = "synthetic")
        val refreshing = async(Dispatchers.Default) { repo.refresh(listOf(entry)) }
        entered.await()
        val changing = async(Dispatchers.Default) { repo.setSeparate(true) }
        withTimeout(3000) { changing.await() }
        assertTrue(repo.state.value.separate)
        release.complete(Unit)
        refreshing.await(); changing.await()
        assertTrue(repo.state.value.separate)
    }

    private class CaptureStore(val entered: CompletableDeferred<Unit>, val release: CompletableDeferred<Unit>) : LedgerStore, InboxStore {
        override val entries = MutableStateFlow(emptyList<LedgerEntry>())
        override val categories = MutableStateFlow(emptyList<Category>())
        override val accounts = MutableStateFlow(emptyList<AccountLabel>())
        override val aliases = MutableStateFlow(emptyList<MerchantAlias>())
        override val rules = MutableStateFlow(emptyList<CategoryRule>())
        override val inbox = MutableStateFlow(emptyList<InboxItem>())
        override val sources = MutableStateFlow(emptyList<SourcePreference>())
        override val captureSettings = MutableStateFlow(CaptureSettings(false))
        override suspend fun execute(command: LedgerCommand): CommandResult {
            if (command is LedgerCommand.PruneInbox) { entered.complete(Unit); release.await() }
            return CommandResult(CommandStatus.APPLIED)
        }
    }

    private class Store : LedgerStore {
        override val entries = MutableStateFlow(emptyList<LedgerEntry>())
        override val categories = MutableStateFlow(emptyList<Category>())
        override val accounts = MutableStateFlow(emptyList<AccountLabel>())
        override val aliases = MutableStateFlow(emptyList<MerchantAlias>())
        override val rules = MutableStateFlow(emptyList<CategoryRule>())
        val commands = mutableListOf<LedgerCommand>()
        override suspend fun execute(command: LedgerCommand): CommandResult { commands += command; return CommandResult(CommandStatus.INVALID) }
    }
}
