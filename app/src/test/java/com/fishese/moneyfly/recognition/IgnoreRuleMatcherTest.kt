package com.fishese.moneyfly.recognition

import com.fishese.moneyfly.capture.NotificationPayload
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class IgnoreRuleMatcherTest {
    private val now = Instant.parse("2026-10-25T01:00:00Z")
    private fun payload(source: String = "synthetic.app", title: String = "Charging battery is full") =
        NotificationPayload(source, "synthetic", "key", now, now, title = title, body = "Synthetic body")

    @Test fun anchoredWildcardMatchesOnlyItsAppAndField() {
        val rule = IgnoreRule("rule", "Synthetic charging status", "synthetic.app", MessageField.TITLE, "Charging * full*")
        assertTrue(IgnoreRuleMatcher.matches(payload(), rule))
        assertFalse(IgnoreRuleMatcher.matches(payload(title = "Before Charging battery is full"), rule))
        assertFalse(IgnoreRuleMatcher.matches(payload(source = "other.app"), rule))
        assertTrue(IgnoreRuleMatcher.matches(payload(source = "other.app"), rule.copy(sameAppOnly = false)))
        assertFalse(IgnoreRuleMatcher.matches(payload(), rule.copy(field = MessageField.BODY)))
    }

    @Test fun matchingCanBeCaseSensitiveAndDoesNotInterpretRegex() {
        val insensitive = IgnoreRule("rule", "Synthetic", "synthetic.app", MessageField.TITLE, "charging * FULL*")
        assertTrue(IgnoreRuleMatcher.matches(payload(), insensitive))
        assertFalse(IgnoreRuleMatcher.matches(payload(), insensitive.copy(ignoreCase = false)))
        assertTrue(IgnoreRuleMatcher.wildcard("Price .+ literal", "Price .+ literal", false))
        assertFalse(IgnoreRuleMatcher.wildcard("Price anything literal", "Price .+ literal", false))
        assertTrue(IgnoreRuleMatcher.wildcard("aba", "*a", false))
    }

    @Test fun rejectsUnboundedAndOversizedRules() {
        val base = IgnoreRule("rule", "Synthetic", "synthetic.app", MessageField.TITLE, "*")
        assertTrue(IgnoreRuleMatcher.validate(base).isNotEmpty())
        assertTrue(IgnoreRuleMatcher.validate(base.copy(pattern = "**")).isNotEmpty())
        assertTrue(IgnoreRuleMatcher.validate(base.copy(pattern = "x".repeat(4097))).isNotEmpty())
        assertTrue(IgnoreRuleMatcher.validate(base.copy(pattern = "*status*")).isEmpty())
    }

    @Test fun builtInStatusRulesAreNarrowAndNeverTargetFinancialSources() {
        val android = DefaultStatusRules.forSource("com.android.systemui").single()
        assertEquals(MessageField.TITLE, android.field)
        assertTrue(IgnoreRuleMatcher.matches(payload(source = "com.android.systemui"), android))
        assertTrue(DefaultStatusRules.forSource("android").isNotEmpty())
        assertEquals(2, DefaultStatusRules.forSource("android").size)
        assertTrue(IgnoreRuleMatcher.matches(payload(source = "android", title = "Zone Launcher synthetic status"), DefaultStatusRules.forSource("android").single { it.name.startsWith("Zone") }))
        assertTrue(DefaultStatusRules.forSource("synthetic.bank").isEmpty())
    }
}
