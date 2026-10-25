package com.fishese.moneyfly.capture

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.fishese.moneyfly.ledger.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidCaptureControllerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun consentGatesAdaptationAndPausingDiscardsQueuedEvents() = runTest {
        val store = CaptureStore()
        val controller = AndroidCaptureController(context, store, store, backgroundScope) { true }
        runCurrent()
        // Invalid extras would fail adaptation if consent were bypassed.
        val malformed = syntheticNotification(notification = Notification().apply { extras = null })
        controller.posted(malformed)
        controller.removed(syntheticNotification())
        runCurrent()
        assertTrue(store.commands.isEmpty())
        assertNull(controller.connection.value.problem)
        store.captureSettings.value = CaptureSettings(consent = true, revision = 1)
        runCurrent()
        controller.posted(syntheticNotification())
        store.captureSettings.value = CaptureSettings(consent = false, revision = 2)
        runCurrent()
        controller.posted(malformed)
        controller.removed(syntheticNotification())
        runCurrent()
        assertTrue(store.commands.isEmpty())
        assertNull(controller.connection.value.problem)
    }

    @Test fun callbacksExcludeOwnPackageAndRemovalNeverSavesFinancialEntries() = runTest {
        val store = CaptureStore(consent = true)
        val controller = AndroidCaptureController(context, store, store, backgroundScope) { true }
        runCurrent()
        val otherApp = syntheticNotification()
        controller.posted(syntheticNotification(sourcePackage = context.packageName))
        controller.removed(syntheticNotification(sourcePackage = context.packageName))
        controller.posted(otherApp)
        controller.removed(otherApp)
        runCurrent()
        assertEquals(2, store.commands.size)
        assertEquals("Synthetic title", store.commands.filterIsInstance<LedgerCommand.CaptureNotification>().single().payload.title)
        val removal = store.commands.filterIsInstance<LedgerCommand.NotificationRemoved>().single()
        assertEquals(otherApp.packageName, removal.sourcePackage)
        assertEquals(otherApp.user.toString(), removal.userScope)
        assertEquals(otherApp.key, removal.notificationKey)
        assertTrue(store.commands.all { it is LedgerCommand.CaptureNotification || it is LedgerCommand.NotificationRemoved })
        assertTrue(store.entries.value.isEmpty())
    }

    @Test fun refreshRequiresConsentPermissionAndConnectedListener() = runTest {
        val store = CaptureStore()
        var access = true
        val controller = AndroidCaptureController(context, store, store, backgroundScope) { access }
        val listener = SyntheticListener()
        controller.connected(listener)
        runCurrent()
        assertFalse(controller.refresh()) // Awaits the initial connected refresh, too.
        assertEquals(0, listener.snapshotQueries)
        assertNull(controller.connection.value.lastRefresh)
        store.captureSettings.value = CaptureSettings(consent = true)
        runCurrent()
        access = false
        assertFalse(controller.refresh())
        assertEquals(0, listener.snapshotQueries)
        assertEquals(CaptureProblem.ACCESS_DENIED, controller.connection.value.problem)
        assertFalse(controller.connection.value.accessGranted)
        assertFalse(controller.connection.value.connected)
        access = true
        controller.disconnected(listener)
        assertFalse(controller.refresh())
        assertEquals(0, listener.snapshotQueries)
        assertEquals(CaptureProblem.DISCONNECTED, controller.connection.value.problem)
        assertTrue(controller.connection.value.accessGranted)
        assertFalse(controller.connection.value.connected)
        assertNull(controller.connection.value.lastRefresh)
    }

    @Test fun successfulRefreshCapturesActiveSnapshotAndRecordsCompletion() = runTest {
        val store = CaptureStore()
        val controller = AndroidCaptureController(context, store, store, backgroundScope) { true }
        val listener = SyntheticListener().apply {
            snapshot = arrayOf(syntheticNotification(), syntheticNotification(sourcePackage = context.packageName))
        }
        controller.connected(listener)
        runCurrent()
        assertFalse(controller.refresh())
        store.captureSettings.value = CaptureSettings(consent = true)
        runCurrent()
        assertTrue(controller.refresh())
        assertEquals(1, listener.snapshotQueries)
        val payload = store.commands.filterIsInstance<LedgerCommand.CaptureNotification>().single().payload
        assertEquals("synthetic.bank", payload.sourcePackage)
        assertEquals("Synthetic body", payload.body)
        assertEquals(java.time.Instant.ofEpochMilli(SYNTHETIC_POST_TIME), payload.postedAt)
        assertEquals(1, store.commands.filterIsInstance<LedgerCommand.PruneInbox>().size)
        assertNotNull(controller.connection.value.lastRefresh)
        assertTrue(controller.connection.value.connected)
        assertFalse(controller.connection.value.refreshing)
        assertNull(controller.connection.value.problem)
    }

    @Test fun securityFailureClearsAccessAndDoesNotRecordSuccessfulRefresh() = runTest {
        val store = CaptureStore()
        val controller = AndroidCaptureController(context, store, store, backgroundScope) { true }
        val listener = SyntheticListener().apply { failure = SecurityException("Synthetic permission revocation") }
        controller.connected(listener)
        runCurrent()
        assertFalse(controller.refresh())
        store.captureSettings.value = CaptureSettings(consent = true)
        runCurrent()
        assertFalse(controller.refresh())
        assertEquals(1, listener.snapshotQueries)
        assertEquals(CaptureProblem.ACCESS_DENIED, controller.connection.value.problem)
        assertFalse(controller.connection.value.accessGranted)
        assertFalse(controller.connection.value.connected)
        assertFalse(controller.connection.value.refreshing)
        assertNull(controller.connection.value.lastRefresh)
        assertTrue(store.commands.isEmpty())
    }

    private class SyntheticListener : NotificationListenerService() {
        var snapshot = emptyArray<StatusBarNotification>()
        var failure: RuntimeException? = null
        var snapshotQueries = 0
        override fun getActiveNotifications(): Array<StatusBarNotification> {
            snapshotQueries++
            failure?.let { throw it }
            return snapshot
        }
    }

    private class CaptureStore(consent: Boolean = false) : LedgerStore, InboxStore {
        override val entries = MutableStateFlow<List<LedgerEntry>>(emptyList())
        override val categories = MutableStateFlow<List<Category>>(emptyList())
        override val accounts = MutableStateFlow<List<AccountLabel>>(emptyList())
        override val aliases = MutableStateFlow<List<MerchantAlias>>(emptyList())
        override val rules = MutableStateFlow<List<CategoryRule>>(emptyList())
        override val inbox = MutableStateFlow<List<InboxItem>>(emptyList())
        override val sources = MutableStateFlow<List<SourcePreference>>(emptyList())
        override val captureSettings = MutableStateFlow(CaptureSettings(consent))
        val commands = CopyOnWriteArrayList<LedgerCommand>()
        override suspend fun execute(command: LedgerCommand): CommandResult {
            commands += command
            return CommandResult(CommandStatus.APPLIED)
        }
    }
}
