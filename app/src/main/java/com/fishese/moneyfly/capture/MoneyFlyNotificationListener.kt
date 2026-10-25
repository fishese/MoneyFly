package com.fishese.moneyfly.capture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.fishese.moneyfly.MoneyFlyApplication

/** Captures evidence only. Never dismisses notifications or saves financial entries. */
class MoneyFlyNotificationListener : NotificationListenerService() {
    private val controller get() = (application as MoneyFlyApplication).captureController
    override fun onListenerConnected() { InboxRetentionWorker.schedule(this); controller.connected(this) }
    override fun onListenerDisconnected() { controller.disconnected(this) }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { sbn?.let(controller::posted) }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { sbn?.let(controller::removed) }
    override fun onDestroy() { controller.disconnected(this); super.onDestroy() }
}
