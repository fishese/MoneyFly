package com.fishese.moneyfly.recognition

import com.fishese.moneyfly.capture.NotificationPayload
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class GenericEmailStarterTest {
    private fun sample(currency: String, amount: String = "1,200.00", source: String = "com.google.android.gm") = NotificationPayload(source, "synthetic", "test", Instant.EPOCH, Instant.EPOCH,
        bigText = "Synthetic header\nHSBC Credit Card Transaction Notification\nCredit card number\nEnding with TEST-0000\nMerchant\nSYNTHETIC STORE\nDate\n01 Jan\nAmount\n$currency$amount\nSynthetic footer")

    @Test fun sanitizedDefaultRecognizesGenericExamplesAndCommonCurrencies() {
        val template = StarterTemplates.definitions.single { it.id == "starter-email-card-alert-v1" }
        assertNull(template.fixedMerchant)
        assertNull(template.fixedCurrency)
        assertTrue(TemplateEngine.preview(template, sample("HKD")).canEnable)
        for ((token, expected) in listOf("HKD" to "HKD", "HK$" to "HKD", "£" to "GBP", "€" to "EUR")) {
            val result = TemplateEngine.recognize(sample(token), listOf(template))
            assertEquals(token, RecognitionStatus.READY, result.status)
            assertEquals(expected, result.draft!!.currency)
            assertEquals("1200.00", result.draft!!.amount)
        }
        assertNotEquals(RecognitionStatus.READY, TemplateEngine.recognize(sample("$"), listOf(template)).status)
        assertEquals(RecognitionStatus.NO_MATCH, TemplateEngine.recognize(sample("HKD", source = "other.synthetic"), listOf(template)).status)
    }
}
