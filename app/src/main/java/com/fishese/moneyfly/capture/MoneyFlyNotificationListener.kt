package com.fishese.moneyfly.capture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.fishese.moneyfly.MoneyFlyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Captures evidence only. Never dismisses notifications or saves financial entries. */
class MoneyFlyNotificationListener : NotificationListenerService() {
    private val controller get() = (application as MoneyFlyApplication).captureController
    private val events = Channel<(AndroidCaptureController) -> Unit>(64)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val overflowed = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun enqueue(event: (AndroidCaptureController) -> Unit) {
        if (events.trySend(event).isFailure) overflowed.set(true)
    }
    init {
        scope.launch {
            var connectedController: AndroidCaptureController? = null
            try {
                for (event in events) try {
                    val current = controller
                    connectedController = current
                    current.awaitInitialSettings()
                    if (overflowed.getAndSet(false)) current.reportIntakeBusy()
                    event(current)
                } catch (_: Exception) { /* startup errors must not block the system listener */ }
            } finally { connectedController?.disconnected(this@MoneyFlyNotificationListener) }
        }
    }
    override fun onListenerConnected() { InboxRetentionWorker.schedule(this); enqueue { it.connected(this) } }
    override fun onListenerDisconnected() { enqueue { it.disconnected(this) } }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { sbn?.let { notification -> enqueue { it.posted(notification) } } }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { sbn?.let { notification -> enqueue { it.removed(notification) } } }
    override fun onDestroy() { events.close(); super.onDestroy() }
}
