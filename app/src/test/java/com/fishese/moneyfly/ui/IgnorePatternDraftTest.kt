package com.fishese.moneyfly.ui

import com.fishese.moneyfly.capture.NotificationPayload
import com.fishese.moneyfly.recognition.MessageField
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class IgnorePatternDraftTest {
    private fun payload(title: String, source: String = "synthetic.app") = NotificationPayload(source, "synthetic", "synthetic", Instant.EPOCH, Instant.EPOCH, title = title)

    @Test fun onlyKnownSystemStatusFormatsAreGeneralizedAutomatically() {
        assertEquals("Charging*", suggestedIgnorePattern(payload("Charging 75%", "android"), MessageField.TITLE))
        assertEquals("Charging 75%", suggestedIgnorePattern(payload("Charging 75%", "synthetic.bank"), MessageField.TITLE))
        assertEquals("Synthetic purchase 12.34", suggestedIgnorePattern(payload("Synthetic purchase 12.34"), MessageField.TITLE))
    }

    @Test fun literalWildcardAndOversizeMessagesAreNotTurnedIntoBroaderRules() {
        assertEquals("", suggestedIgnorePattern(payload("SYNTHETIC*SHOP"), MessageField.TITLE))
        assertEquals("", suggestedIgnorePattern(payload("X".repeat(257)), MessageField.TITLE))
        assertEquals("", suggestedIgnorePattern(null, MessageField.TITLE))
    }
}
