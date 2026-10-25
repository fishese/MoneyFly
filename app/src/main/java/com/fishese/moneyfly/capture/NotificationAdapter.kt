package com.fishese.moneyfly.capture

import android.app.Notification
import android.service.notification.StatusBarNotification
import java.time.Instant

/** Never reads intents, remote views, images, messages from mail APIs, or hidden platform data. */
object NotificationAdapter {
    private const val MAX_FIELD_CHARS = 16_384
    private const val MAX_LINES = 32
    private const val MAX_TOTAL_CHARS = 131_072

    fun payload(sbn: StatusBarNotification, observedAt: Instant = Instant.now()): NotificationPayload {
        val notification = sbn.notification
        val extras = notification.extras
        var truncated = false
        var remainingChars = MAX_TOTAL_CHARS
        fun bounded(text: CharSequence?): String {
            if (text == null) return ""
            val limit = minOf(MAX_FIELD_CHARS, remainingChars)
            if (text.length > limit) truncated = true
            val result = text.take(limit).toString()
            remainingChars -= result.length
            return result
        }
        val title = bounded(extras.getCharSequence(Notification.EXTRA_TITLE))
        val body = bounded(extras.getCharSequence(Notification.EXTRA_TEXT))
        val bigText = bounded(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        val subText = bounded(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        val availableLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty()
        if (availableLines.size > MAX_LINES) truncated = true
        val lines = availableLines.take(MAX_LINES).map(::bounded)
        return NotificationPayload(
            sourcePackage = sbn.packageName,
            userScope = sbn.user.toString(),
            notificationKey = sbn.key,
            postedAt = Instant.ofEpochMilli(sbn.postTime),
            observedAt = observedAt,
            title = title, body = body, bigText = bigText, textLines = lines, subText = subText,
            groupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            // Android does not supply a universal redaction flag. Empty visible content
            // is marked unavailable; nonempty text is never asserted to be complete.
            redacted = title.isBlank() && body.isBlank() && bigText.isBlank() && lines.all(String::isBlank),
            truncated = truncated,
        )
    }
}
