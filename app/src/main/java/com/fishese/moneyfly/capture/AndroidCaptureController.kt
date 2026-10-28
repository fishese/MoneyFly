package com.fishese.moneyfly.capture

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.fishese.moneyfly.ledger.LedgerCommand
import com.fishese.moneyfly.ledger.LedgerStore
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Process-local listener connection; consent/settlements are durable and owned by the writer. */
class AndroidCaptureController(
    context: Context,
    private val store: LedgerStore,
    private val inbox: InboxStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val accessOverride: (() -> Boolean)? = null,
) : CaptureController {
    private val context = context.applicationContext
    private val component = ComponentName(this.context, MoneyFlyNotificationListener::class.java)
    private val refreshPreferences = this.context.getSharedPreferences("capture_connection", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(CaptureConnection(lastRefresh =
        refreshPreferences.getLong("last_successful_refresh", 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)))
    override val connection = state.asStateFlow()
    private val sourceNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Suppress("DEPRECATION")
    override fun sourceName(packageName: String): String {
        sourceNames[packageName]?.let { return it }
        val label = try {
            // Look up only a source already present in the Inbox, never enumerate apps.
            val info = this.context.packageManager.getApplicationInfo(packageName, 0)
            this.context.packageManager.getApplicationLabel(info).toString().ifBlank { packageName }
        } catch (_: Exception) { packageName }
        // An unavailable app may become visible or be installed later; don't cache failures.
        if (label != packageName) sourceNames[packageName] = label
        return label
    }
    private val refreshMutex = Mutex()
    @Volatile private var listener: NotificationListenerService? = null
    private val listenerLock = Any()
    @Volatile private var captureAllowed = false
    // Bound incoming work, instead of retaining unbounded raw notification objects.
    private val pending = Channel<LedgerCommand>(64)
    private val initialSettings = CompletableDeferred<Unit>()

    init {
        scope.launch {
            inbox.captureSettings.collect { settings ->
                captureAllowed = settings.consent
                initialSettings.complete(Unit)
                if (!settings.consent) while (pending.tryReceive().isSuccess) { /* discard queued raw payloads */ }
            }
        }
        scope.launch {
            for (command in pending) {
                try {
                    if (inbox.captureSettings.first().consent) store.execute(command)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { state.update { it.copy(problem = CaptureProblem.CAPTURE_FAILED) } }
            }
        }
    }

    internal suspend fun awaitInitialSettings() = initialSettings.await()

    private fun hasAccess(): Boolean = try {
        accessOverride?.invoke() ?: context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
    } catch (_: Exception) { false }

    override fun onForeground() {
        sourceNames.clear()
        val granted = hasAccess()
        state.update { it.copy(accessGranted = granted, connected = granted && listener != null,
            problem = if (!granted) CaptureProblem.ACCESS_DENIED else if (listener == null) CaptureProblem.DISCONNECTED else it.problem?.takeUnless { p -> p == CaptureProblem.ACCESS_DENIED || p == CaptureProblem.DISCONNECTED }) }
        if (granted && listener == null) requestReconnect()
        scope.launch {
            try { store.execute(LedgerCommand.PruneInbox()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.update { it.copy(problem = CaptureProblem.CAPTURE_FAILED) } }
        }
    }

    override fun openAccessSettings() {
        try {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { state.update { it.copy(problem = CaptureProblem.SETTINGS_UNAVAILABLE) } }
    }

    override fun requestReconnect() {
        if (!hasAccess()) {
            state.update { it.copy(accessGranted = false, connected = false, problem = CaptureProblem.ACCESS_DENIED) }
            return
        }
        try { NotificationListenerService.requestRebind(component) }
        catch (_: Exception) { state.update { it.copy(problem = CaptureProblem.DISCONNECTED) } }
    }

    internal fun connected(service: NotificationListenerService) {
        synchronized(listenerLock) {
            listener = service
            state.update { it.copy(accessGranted = hasAccess(), connected = true, problem = null) }
        }
        scope.launch { refresh() }
    }

    internal fun disconnected(service: NotificationListenerService) {
        val granted = synchronized(listenerLock) {
            if (listener !== service) return
            listener = null
            val access = hasAccess()
            state.update { it.copy(accessGranted = access, connected = false,
                problem = if (access) CaptureProblem.DISCONNECTED else CaptureProblem.ACCESS_DENIED) }
            access
        }
        if (granted) requestReconnect()
    }

    internal fun posted(sbn: StatusBarNotification) {
        if (!captureAllowed || sbn.packageName == context.packageName) return
        try {
            enqueue(LedgerCommand.CaptureNotification(NotificationAdapter.payload(sbn)))
        } catch (_: Exception) { state.update { it.copy(problem = CaptureProblem.CAPTURE_FAILED) } }
    }

    internal fun removed(sbn: StatusBarNotification) {
        if (!captureAllowed || sbn.packageName == context.packageName) return
        enqueue(LedgerCommand.NotificationRemoved(sbn.packageName, sbn.user.toString(), sbn.key))
    }

    private fun enqueue(command: LedgerCommand) {
        if (pending.trySend(command).isFailure) state.update { it.copy(problem = CaptureProblem.INTAKE_BUSY) }
    }

    internal fun reportIntakeBusy() { state.update { it.copy(problem = CaptureProblem.INTAKE_BUSY) } }

    override suspend fun refresh(): Boolean = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!inbox.captureSettings.first().consent) return@withContext false
            val activeListener = listener
            if (!hasAccess() || activeListener == null) {
                onForeground()
                return@withContext false
            }
            state.update { it.copy(refreshing = true, problem = null) }
            try {
                // Query only after onListenerConnected. This is an active snapshot,
                // never a historical notification archive or mailbox read.
                val snapshot = activeListener.activeNotifications.orEmpty()
                for (sbn in snapshot) {
                    if (sbn.packageName != context.packageName) {
                        store.execute(LedgerCommand.CaptureNotification(NotificationAdapter.payload(sbn)))
                    }
                }
                store.execute(LedgerCommand.PruneInbox())
                val completedAt = Instant.now()
                synchronized(listenerLock) {
                    if (listener !== activeListener || !hasAccess()) return@withContext false
                    refreshPreferences.edit().putLong("last_successful_refresh", completedAt.toEpochMilli()).apply()
                    state.update { it.copy(lastRefresh = completedAt, connected = true) }
                }
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) {
                state.update { it.copy(accessGranted = false, connected = false, problem = CaptureProblem.ACCESS_DENIED) }
                false
            } catch (_: Exception) {
                state.update { it.copy(problem = CaptureProblem.CAPTURE_FAILED) }
                false
            } finally { state.update { it.copy(refreshing = false) } }
        }
    }
}
