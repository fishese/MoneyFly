package com.fishese.moneyfly.recognition

import com.fishese.moneyfly.capture.NotificationPayload
import java.util.Locale

/** A scoped text pattern or Android group-summary rule. `*` is the only text metacharacter. */
data class IgnoreRule(
    val id: String,
    val name: String,
    val sourcePackage: String,
    val field: MessageField,
    val pattern: String,
    val ignoreCase: Boolean = true,
    val enabled: Boolean = true,
    val revision: Long = 0,
    val sameAppOnly: Boolean = true,
    val summaryOnly: Boolean = false,
)

/** Conservative built-ins for non-financial system status sources only. */
object DefaultStatusRules {
    private val chargingSources = setOf("android", "com.android.systemui")

    fun forSource(sourcePackage: String): List<IgnoreRule> = buildList {
        if (sourcePackage in chargingSources) add(IgnoreRule(
            id = "default-status:$sourcePackage:charging-full",
            name = "Charging status",
            sourcePackage = sourcePackage,
            field = MessageField.TITLE,
            pattern = "Charging*",
            ignoreCase = true,
            enabled = true,
        ))
        if (sourcePackage in chargingSources) {
            val statusPatterns = listOf(
                "battery-full" to "Battery full*",
                "battery-charged" to "Battery fully charged*",
                "plugged-in" to "Device plugged in*",
                "unplugged" to "Device unplugged*",
                "power-connected" to "Power connected*",
                "power-disconnected" to "Power disconnected*",
                "usb-charging" to "Charging this device via USB*",
            )
            for ((id, pattern) in statusPatterns) add(IgnoreRule(
                id = "default-status:$sourcePackage:$id", name = "Power status",
                sourcePackage = sourcePackage, field = MessageField.TITLE, pattern = pattern,
            ))
        }
        if (sourcePackage == "android") add(IgnoreRule(
            id = "default-status:android:zone-launcher",
            name = "Zone Launcher status",
            sourcePackage = sourcePackage,
            field = MessageField.TITLE,
            pattern = "Zone Launcher *",
            ignoreCase = true,
            enabled = true,
        ))
    }
}

object IgnoreRuleMatcher {
    private const val MAX_PATTERN = 4096
    private const val MAX_INPUT = 16384

    class Compiled internal constructor(
        val rule: IgnoreRule,
        internal val pieces: List<String>,
        internal val leadingWildcard: Boolean,
        internal val trailingWildcard: Boolean,
    )

    fun validate(rule: IgnoreRule): List<String> = buildList {
        if (rule.id.isBlank() || rule.id.length > 200) add("A valid ignore rule ID is required.")
        if (rule.name.isBlank() || rule.name.length > 120) add("Enter an ignore rule name of 1–120 characters.")
        if (rule.sourcePackage.isBlank() || rule.sourcePackage.length > 300) add("Choose a source app.")
        if (rule.pattern.length > MAX_PATTERN) add("The pattern cannot exceed 4096 characters.")
        if (!rule.summaryOnly) {
            if (rule.pattern.isEmpty()) add("Enter a pattern of 1–4096 characters.")
            if (rule.pattern.all { it == '*' }) add("The pattern must contain some literal text.")
        }
    }.distinct()

    fun matches(payload: NotificationPayload, rule: IgnoreRule): Boolean {
        val compiled = compile(rule) ?: return false
        return matchingRule(payload, listOf(compiled)) != null
    }

    /** Compile once when a persisted-rule flow changes, rather than for every retained message. */
    fun compile(rule: IgnoreRule): Compiled? {
        if (!rule.enabled || validate(rule).isNotEmpty()) return null
        val pattern = normalize(rule.pattern, rule.ignoreCase)
        return Compiled(rule, pattern.split('*').filter { it.isNotEmpty() }, pattern.startsWith('*'), pattern.endsWith('*'))
    }

    /** Normalizes each requested notification field at most once for a set of compiled rules. */
    fun matchingRule(payload: NotificationPayload, rules: List<Compiled>): IgnoreRule? {
        val fields = mutableMapOf<Pair<MessageField, Boolean>, String?>()
        return rules.firstOrNull { compiled ->
            if (compiled.rule.sameAppOnly && payload.sourcePackage != compiled.rule.sourcePackage) false
            else if (compiled.rule.summaryOnly) payload.groupSummary else {
                val key = compiled.rule.field to compiled.rule.ignoreCase
                if (key !in fields) fields[key] = boundedField(payload, compiled.rule.field)?.let { normalize(it, compiled.rule.ignoreCase) }
                val value = fields[key]
                value != null && wildcard(value, compiled)
            }
        }?.rule
    }

    /** Anchored glob match with bounded literal searching; user input is never compiled as regex. */
    internal fun wildcard(input: String, pattern: String, ignoreCase: Boolean): Boolean {
        if (input.length > MAX_INPUT || pattern.length > MAX_PATTERN) return false
        val text = normalize(input, ignoreCase)
        val glob = normalize(pattern, ignoreCase)
        val pieces = glob.split('*').filter { it.isNotEmpty() }
        if (pieces.isEmpty()) return false
        return wildcard(text, Compiled(IgnoreRule("internal", "internal", "internal", MessageField.TITLE, pattern, ignoreCase), pieces, glob.startsWith('*'), glob.endsWith('*')))
    }

    private fun wildcard(text: String, compiled: Compiled): Boolean {
        val pieces = compiled.pieces
        val leading = compiled.leadingWildcard
        val trailing = compiled.trailingWildcard
        if (!leading && !trailing && pieces.size == 1) return text == pieces.single()
        var cursor = 0
        pieces.forEachIndexed { index, piece ->
            val found = when {
                index == 0 && !leading -> if (text.startsWith(piece)) 0 else -1
                index == pieces.lastIndex && !trailing -> (text.length - piece.length).takeIf {
                    it >= cursor && text.startsWith(piece, it)
                } ?: -1
                else -> text.indexOf(piece, cursor)
            }
            if (found < 0) return false
            cursor = found + piece.length
        }
        return true
    }

    private fun boundedField(payload: NotificationPayload, field: MessageField): String? = when (field) {
        MessageField.TITLE -> payload.title.takeIf { it.length <= MAX_INPUT }
        MessageField.BODY -> payload.body.takeIf { it.length <= MAX_INPUT }
        MessageField.BIG_TEXT -> payload.bigText.takeIf { it.length <= MAX_INPUT }
        MessageField.SUB_TEXT -> payload.subText.takeIf { it.length <= MAX_INPUT }
        MessageField.LINES -> {
            val size = payload.textLines.sumOf { it.length } + (payload.textLines.size - 1).coerceAtLeast(0)
            if (size <= MAX_INPUT) payload.textLines.joinToString("\n") else null
        }
    }

    private fun normalize(text: String, ignoreCase: Boolean) = if (ignoreCase) text.lowercase(Locale.ROOT) else text
}
