package com.fishese.moneyfly.capture

import android.app.Notification
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationAdapterTest {
    @Test fun visibleFieldsAndDeliveryTimeArePreservedWithoutInventingEventTime() {
        val notification = Notification().apply {
            `when` = 42L
            flags = Notification.FLAG_GROUP_SUMMARY
            extras.putCharSequence(Notification.EXTRA_TITLE, "Synthetic title")
            extras.putCharSequence(Notification.EXTRA_TEXT, "Synthetic body")
            extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "Synthetic expanded body")
            extras.putCharSequence(Notification.EXTRA_SUB_TEXT, "Synthetic account label")
            extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES, arrayOf("Line one", "Line two"))
        }
        val sbn = syntheticNotification(notification = notification)
        val observed = Instant.parse("2026-10-24T01:02:03Z")
        val payload = NotificationAdapter.payload(sbn, observed)
        assertEquals("synthetic.bank", payload.sourcePackage)
        assertEquals(sbn.user.toString(), payload.userScope)
        assertEquals(sbn.key, payload.notificationKey)
        assertEquals(Instant.ofEpochMilli(SYNTHETIC_POST_TIME), payload.postedAt)
        assertEquals(observed, payload.observedAt)
        assertEquals("Synthetic title", payload.title)
        assertEquals("Synthetic body", payload.body)
        assertEquals("Synthetic expanded body", payload.bigText)
        assertEquals("Synthetic account label", payload.subText)
        assertEquals(listOf("Line one", "Line two"), payload.textLines)
        assertTrue(payload.groupSummary)
        assertFalse(payload.redacted)
        assertFalse(payload.truncated)
    }

    @Test fun eachTextFieldAndLineCountAreBounded() {
        val largeText = "x".repeat(16_385)
        val notification = Notification().apply {
            extras.putCharSequence(Notification.EXTRA_TITLE, largeText)
            extras.putCharSequence(Notification.EXTRA_TEXT, largeText)
            extras.putCharSequence(Notification.EXTRA_BIG_TEXT, largeText)
            extras.putCharSequence(Notification.EXTRA_SUB_TEXT, largeText)
            extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES, Array(33) { largeText })
        }
        val payload = NotificationAdapter.payload(syntheticNotification(notification = notification))
        assertTrue(payload.truncated)
        assertEquals(listOf(16_384, 16_384, 16_384, 16_384),
            listOf(payload.title.length, payload.body.length, payload.bigText.length, payload.subText.length))
        assertEquals(32, payload.textLines.size)
        assertTrue(payload.textLines.all { it.length <= 16_384 })
        assertEquals(131_072, payload.title.length + payload.body.length + payload.bigText.length +
            payload.subText.length + payload.textLines.sumOf { it.length })
    }

    @Test fun unavailableVisibleContentIsRedactedButVisibleLinesAreNot() {
        val notification = Notification().apply {
            extras.putCharSequence(Notification.EXTRA_SUB_TEXT, "Synthetic metadata only")
        }
        assertTrue(NotificationAdapter.payload(syntheticNotification(notification = notification)).redacted)
        notification.extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES, arrayOf("Visible synthetic line"))
        assertFalse(NotificationAdapter.payload(syntheticNotification(notification = notification)).redacted)
    }
}

internal const val SYNTHETIC_POST_TIME = 1_790_000_123_456L

@Suppress("DEPRECATION")
internal fun syntheticNotification(
    sourcePackage: String = "synthetic.bank",
    notification: Notification = Notification().apply {
        extras.putCharSequence(Notification.EXTRA_TITLE, "Synthetic title")
        extras.putCharSequence(Notification.EXTRA_TEXT, "Synthetic body")
    },
): StatusBarNotification = StatusBarNotification(
    sourcePackage, sourcePackage, 7, "synthetic-tag", 10_001, 0, 0,
    notification, UserHandle.getUserHandleForUid(12 * 100_000 + 10_001), SYNTHETIC_POST_TIME,
)
