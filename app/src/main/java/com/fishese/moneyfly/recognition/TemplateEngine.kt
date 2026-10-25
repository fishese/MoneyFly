package com.fishese.moneyfly.recognition

import com.fishese.moneyfly.capture.NotificationPayload
import com.fishese.moneyfly.ledger.*
import java.util.Currency

/** Anchored field-local literal matching. User text is never evaluated as regex or code. */
object TemplateEngine {
    private const val MAX_INPUT = 16384
    private const val WORK_LIMIT = 50000

    fun parsePattern(field: MessageField, expression: String): FieldPattern {
        require(expression.length <= MAX_INPUT && expression.toByteArray(Charsets.UTF_8).size <= MAX_INPUT) { "The pattern exceeds the 16 KiB limit." }
        val segments = mutableListOf<PatternSegment>()
        var cursor = 0
        while (cursor < expression.length) {
            val open = expression.indexOf('{', cursor)
            if (open < 0) { segments += PatternSegment.Literal(expression.substring(cursor)); break }
            if (open > cursor) segments += PatternSegment.Literal(expression.substring(cursor, open))
            val close = expression.indexOf('}', open + 1)
            require(close >= 0) { "Close the field placeholder." }
            val name = when (expression.substring(open + 1, close)) {
                "amount" -> SlotName.AMOUNT; "currency" -> SlotName.CURRENCY; "merchant" -> SlotName.MERCHANT
                "reference" -> SlotName.REFERENCE; "notes" -> SlotName.NOTES; "accountHint" -> SlotName.ACCOUNT_HINT
                "variable" -> SlotName.VARIABLE
                else -> throw IllegalArgumentException("Use a supported field placeholder.")
            }
            segments += PatternSegment.Slot(name, if (name == SlotName.AMOUNT) 64 else 256)
            cursor = close + 1
        }
        return FieldPattern(field, segments)
    }

    fun validate(definition: TemplateDefinition): List<String> = buildList {
        val d = definition
        if (d.id.isBlank() || d.id.length > 128 || d.name.isBlank() || d.name.length > 120 || d.sourcePackage.isBlank() || d.sourcePackage.length > 256) add("Enter a template name and source app within the supported limits.")
        if (d.patterns.size !in 1..4 || d.patterns.map { it.field }.distinct().size != d.patterns.size) add("Use one to four different notification fields.")
        val slots = d.patterns.flatMap { it.segments }.filterIsInstance<PatternSegment.Slot>()
        if (slots.map { it.name }.distinct().size != slots.size) add("Each extracted field can appear only once.")
        if (slots.none { it.name == SlotName.AMOUNT }) add("Select an amount field.")
        for (p in d.patterns) {
            val wholeFieldMerchant = p.segments == listOf(PatternSegment.Slot(SlotName.MERCHANT)) &&
                d.patterns.size > 1 && d.patterns.any { other -> other.field != p.field && other.segments.any { it is PatternSegment.Literal } }
            if (p.segments.size !in 2..32 && !wholeFieldMerchant) add("A pattern needs 2–32 literal or field segments.")
            if (p.segments.any { it is PatternSegment.Literal && it.text.isEmpty() }) add("Literal segments cannot be empty.")
            if (p.segments.zipWithNext().any { it.first is PatternSegment.Slot && it.second is PatternSegment.Slot }) add("Separate extracted fields with literal text.")
            if (p.segments.filterIsInstance<PatternSegment.Slot>().any { it.maxLength !in 1..4096 }) add("Extracted field lengths must be 1–4096.")
            if (p.segments.filterIsInstance<PatternSegment.Slot>().any { it.numericOnly && it.name != SlotName.AMOUNT }) add("Only amount fields can use numeric boundaries.")
        }
        val literalLength = d.patterns.flatMap { it.segments }.filterIsInstance<PatternSegment.Literal>().sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() } + d.predicates.sumOf { it.literal.toByteArray(Charsets.UTF_8).size.toLong() }
        if (literalLength > 4096) add("Combined literal definitions exceed 4 KiB.")
        if (d.predicates.size > 16 || d.predicates.any { it.literal.isEmpty() || it.literal.length > 4096 }) add("Use at most sixteen nonempty source restrictions.")
        val currencySlot = slots.any { it.name == SlotName.CURRENCY }
        if (currencySlot == (d.fixedCurrency != null)) add("Choose either a currency field or a fixed currency.")
        if ((d.fixedCurrency == null) != (d.fixedCurrencyEvidence == null)) add("Explain the fixed currency evidence.")
        if (d.fixedCurrency != null && !validCurrency(d.fixedCurrency)) add("Choose a supported ISO currency.")
        if (d.fixedCurrencyEvidence == FixedCurrencyEvidence.EXPLICIT_MATCHED_LITERAL && d.fixedCurrency != null) {
            val literals = d.patterns.flatMap { it.segments }.filterIsInstance<PatternSegment.Literal>().map { it.text } + d.predicates.map { it.literal }
            if (literals.none { literal -> explicitCurrencyTokens(d.fixedCurrency).any { token -> containsCurrencyToken(literal, token) } }) add("The matched literal must explicitly identify the fixed currency, or confirm an assumption.")
        }
        if (d.currencyTokens.size > 20 || d.currencyTokens.any { it.token.isEmpty() || it.token.length > 16 || !validCurrency(it.currency) } || d.currencyTokens.map { it.token }.distinct().size != d.currencyTokens.size) add("Use unique currency tokens with supported currencies.")
        if (d.currencyTokens.any { it.token in setOf("$", "¥", "￥") && !it.userConfirmedAmbiguous }) add("Confirm the currency assumption for an ambiguous symbol.")
        if (d.currencyTokens.any { token -> currencyAlias(token.token)?.let { it != token.currency } == true }) add("A known currency token cannot be mapped to a different currency.")
        if (d.fixedMerchant != null) {
            if (d.fixedMerchant.isBlank() || d.fixedMerchant.length > 256 || slots.any { it.name == SlotName.MERCHANT }) add("A fixed merchant cannot replace an extracted merchant.")
            if (d.predicates.none { it.literal == d.fixedMerchant && it.operation in setOf(PredicateOperation.EQUALS, PredicateOperation.CONTAINS) }) add("A fixed merchant needs an explicit merchant-specific restriction.")
        }
    }.distinct()

    fun recognize(payload: NotificationPayload, definitions: List<TemplateDefinition>): RecognitionResult {
        val year = try { payload.postedAt.atZone(java.time.ZoneOffset.UTC).year } catch (_: java.time.DateTimeException) { 0 }
        if (year !in 1..9999) return review("Notification posting time is outside the supported date range.")
        if (payload.groupSummary || payload.redacted || payload.truncated) return review("Grouped, redacted or truncated content needs manual review.")
        if (inputLength(payload) > MAX_INPUT) return review("Notification text exceeds the 16 KiB recognition limit.")
        val enabled = definitions.filter { it.enabled }
        if (enabled.size > 100) return review("More than 100 templates are enabled.")
        val budget = WorkBudget()
        val results = enabled.filter { it.sourcePackage == payload.sourcePackage }.sortedBy { it.id }.map { match(payload, it, budget) }
        val reviews = results.filter { it.status == RecognitionStatus.REVIEW_NEEDED }
        val ready = results.filter { it.status == RecognitionStatus.READY }
        if (reviews.isNotEmpty()) return review(reviews.flatMap { it.issues }.distinct().joinToString(" "))
        if (ready.map { it.draft }.distinct().size > 1) return review("Matching templates disagree. Choose the entry fields manually.")
        return ready.firstOrNull() ?: RecognitionResult(RecognitionStatus.NO_MATCH)
    }

    fun preview(definition: TemplateDefinition, payload: NotificationPayload): TemplatePreview {
        val errors = validate(definition)
        if (errors.isNotEmpty()) return TemplatePreview(review(errors.joinToString(" ")), emptyList(), errors)
        val current = recognize(payload, listOf(definition.copy(enabled = true)))
        val examples = generatedExamples(definition)
        val issues = buildList {
            if (examples.size != 3 || examples.any { (it.result.status == RecognitionStatus.READY) != it.shouldMatch }) add("The generated format variations did not pass. Adjust field boundaries or restrictions.")
            val positives = examples.filter { it.shouldMatch }.mapNotNull { it.result.draft }
            if (positives.size == 2 && positives[0].amount == positives[1].amount) add("The format must recognize changing amounts.")
            if (definition.patterns.any { p -> p.segments.any { it is PatternSegment.Slot && it.name == SlotName.MERCHANT } } && positives.size == 2 && positives[0].merchant == positives[1].merchant) add("The format must recognize changing merchants.")
        }
        return TemplatePreview(current, examples, issues)
    }

    /** Temporary synthetic examples. Callers must not persist these notification-shaped values. */
    fun generatedExamples(d: TemplateDefinition): List<SyntheticPreview> {
        if (validate(d).isNotEmpty()) return emptyList()
        val currency = d.fixedCurrency ?: d.currencyTokens.firstOrNull()?.currency ?: "HKD"
        val scale = Currency.getInstance(currency).defaultFractionDigits
        fun synthetic(index: Int): NotificationPayload {
            val fields = mutableMapOf<MessageField, String>()
            for ((field, predicates) in d.predicates.groupBy { it.field }) if (d.patterns.none { it.field == field }) {
                val equality = predicates.firstOrNull { it.operation == PredicateOperation.EQUALS }
                fields[field] = equality?.literal ?: buildList {
                    predicates.firstOrNull { it.operation == PredicateOperation.STARTS_WITH }?.let { add(it.literal) }
                    add("Synthetic")
                    predicates.filter { it.operation == PredicateOperation.CONTAINS }.forEach { add(it.literal) }
                    predicates.firstOrNull { it.operation == PredicateOperation.ENDS_WITH }?.let { add(it.literal) }
                }.joinToString(" ")
            }
            for (pattern in d.patterns) fields[pattern.field] = pattern.segments.joinToString("") { segment ->
                when (segment) {
                    is PatternSegment.Literal -> segment.text
                    is PatternSegment.Slot -> when (segment.name) {
                        SlotName.AMOUNT -> {
                            val amount = if (scale == 0 || segment.maxLength < 5 || d.numberConvention == NumberConvention.INTEGER_NO_GROUPING) if (index == 0) "2" else "7" else if (index == 0) "12.34" else "56.78"
                            if (d.numberConvention == NumberConvention.DECIMAL_COMMA_GROUP_DOT) amount.replace('.', ',') else amount
                        }
                        SlotName.CURRENCY -> d.currencyTokens.firstOrNull()?.token ?: currency
                        SlotName.MERCHANT -> (if (index == 0) "A SYNTHETIC SHOP" else "B INVENTED MARKET").take(segment.maxLength)
                        SlotName.REFERENCE -> (if (index == 0) "A-SYNTHETIC" else "B-SYNTHETIC").take(segment.maxLength)
                        SlotName.ACCOUNT_HINT -> (if (index == 0) "A LABEL" else "B LABEL").take(segment.maxLength)
                        SlotName.NOTES -> (if (index == 0) "A SYNTHETIC NOTE" else "B SYNTHETIC NOTE").take(segment.maxLength)
                        SlotName.VARIABLE -> (if (index == 0) "A VARYING TEXT" else "B DIFFERENT TEXT").take(segment.maxLength)
                    }
                }
            }
            return NotificationPayload(d.sourcePackage, "synthetic", "synthetic-$index", java.time.Instant.EPOCH, java.time.Instant.EPOCH,
                fields[MessageField.TITLE].orEmpty(), fields[MessageField.BODY].orEmpty(), fields[MessageField.BIG_TEXT].orEmpty(),
                fields[MessageField.LINES]?.split('\n') ?: emptyList(), fields[MessageField.SUB_TEXT].orEmpty())
        }
        val positives = listOf(synthetic(0), synthetic(1))
        val negative = replaceField(positives[0], d.patterns.first().field, "")
        return (positives + negative).mapIndexed { index, p -> SyntheticPreview(if (index < 2) "Synthetic variation ${index + 1}" else "Synthetic non-match", p, index < 2, recognize(p, listOf(d.copy(enabled = true)))) }
    }

    private class WorkBudget { var remaining = WORK_LIMIT; fun use(cost: Int = 1): Boolean { remaining -= cost; return remaining >= 0 } }
    private fun match(p: NotificationPayload, d: TemplateDefinition, budget: WorkBudget): RecognitionResult {
        if (!budget.use()) return review("The patterns exceeded the shared matching work limit.")
        val errors = validate(d)
        if (errors.isNotEmpty()) return review(errors.joinToString(" "))
        for (predicate in d.predicates) {
            val value = field(p, predicate.field)
            val literal = FinancialIdentity.canonicalText(predicate.literal)
            if (!budget.use(1 + value.length + literal.length)) return review("The patterns exceeded the shared matching work limit.")
            val matches = when (predicate.operation) {
                PredicateOperation.EQUALS -> value.equals(literal, predicate.ignoreCase)
                PredicateOperation.CONTAINS -> value.contains(literal, predicate.ignoreCase)
                PredicateOperation.STARTS_WITH -> value.startsWith(literal, predicate.ignoreCase)
                PredicateOperation.ENDS_WITH -> value.endsWith(literal, predicate.ignoreCase)
            }
            if (!matches || (d.fixedMerchant != null && predicate.literal == d.fixedMerchant && !Categorization.matches(value, d.fixedMerchant, LiteralMatch.WHOLE_TOKEN))) return RecognitionResult(RecognitionStatus.NO_MATCH)
        }
        val values = mutableMapOf<SlotName, String>()
        for (pattern in d.patterns) {
            val matched = assignments(field(p, pattern.field), pattern.segments, budget)
            if (matched.exhausted) return review("The pattern exceeded the bounded matching work limit.")
            if (matched.values.size > 1) return review("Repeated delimiters allow multiple field assignments. Adjust the boundaries.")
            if (matched.values.isEmpty()) return RecognitionResult(RecognitionStatus.NO_MATCH)
            values.putAll(matched.values.single())
        }
        return try {
            val currency = d.fixedCurrency ?: values[SlotName.CURRENCY]?.let { token -> d.currencyTokens.firstOrNull { it.token == token }?.currency ?: currencyAlias(token) }
                ?: throw IllegalArgumentException("The matched currency is unknown or ambiguous.")
            val amount = Money.normalize(parseNumber(values.getValue(SlotName.AMOUNT), d.numberConvention), currency)
            val merchant = values[SlotName.MERCHANT] ?: d.fixedMerchant.orEmpty()
            require(merchant.length <= 2000 && (SlotName.MERCHANT !in values || merchant.isNotBlank())) { "The merchant field is empty or too long." }
            val issues = buildList {
                add("Date uses notification posting time; original transaction time is unknown.")
                if (merchant.isBlank()) add("Merchant was not provided; add it while reviewing this entry.")
                if (d.fixedCurrencyEvidence == FixedCurrencyEvidence.USER_CONFIRMED_ASSUMPTION || d.currencyTokens.any { it.token == values[SlotName.CURRENCY] && it.userConfirmedAmbiguous }) add("Currency uses a user-confirmed $currency assumption.")
                if (SlotName.ACCOUNT_HINT in values) add("An account hint was found; choose an account label manually.")
            }
            RecognitionResult(RecognitionStatus.READY, EntryDraft(amount, currency, d.kind, p.postedAt, merchant, values[SlotName.NOTES].orEmpty(), sourcePackage = p.sourcePackage,
                timeOrigin = TimeOrigin.NOTIFICATION_POSTED, timePrecision = TimePrecision.UNKNOWN), issues, d.id, d.revision)
        } catch (e: IllegalArgumentException) { review(e.message ?: "Matched fields need review.") }
    }

    private data class Assignments(val values: List<Map<SlotName, String>>, val exhausted: Boolean)
    private fun assignments(text: String, segments: List<PatternSegment>, budget: WorkBudget): Assignments {
        val results = mutableListOf<Map<SlotName, String>>()
        var exhausted = false
        fun visit(index: Int, position: Int, values: Map<SlotName, String>) {
            if (!budget.use()) { exhausted = true; return }
            if (results.size >= 2 || exhausted) return
            if (index == segments.size) { if (position == text.length) results += values; return }
            when (val segment = segments[index]) {
                is PatternSegment.Literal -> {
                    val literal = FinancialIdentity.canonicalText(segment.text)
                    if (!budget.use(literal.length)) { exhausted = true; return }
                    if (text.startsWith(literal, position)) visit(index + 1, position + literal.length, values)
                }
                is PatternSegment.Slot -> {
                    val max = minOf(text.length, position + segment.maxLength)
                    val nextLiteral = (segments.getOrNull(index + 1) as? PatternSegment.Literal)?.let { FinancialIdentity.canonicalText(it.text) }
                    if (index == segments.lastIndex) {
                        if (text.length in (position + 1)..max) visit(index + 1, text.length, values + (segment.name to text.substring(position)))
                    } else for (end in (position + 1)..max) {
                        if (!budget.use(1 + (nextLiteral?.length ?: 0))) { exhausted = true; break }
                        if (segment.numericOnly && text.substring(position, end).any { !it.isDigit() && it != '.' && it != ',' }) continue
                        if (nextLiteral != null && !text.startsWith(nextLiteral, end)) continue
                        visit(index + 1, end, values + (segment.name to text.substring(position, end)))
                        if (results.size >= 2 || exhausted) break
                    }
                }
            }
        }
        visit(0, 0, emptyMap())
        return Assignments(results, exhausted)
    }

    private fun parseNumber(text: String, convention: NumberConvention): String {
        require(text.length <= 64) { "The amount is too long." }
        val pattern = when (convention) {
            NumberConvention.DECIMAL_DOT_GROUP_COMMA -> "(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?"
            NumberConvention.DECIMAL_COMMA_GROUP_DOT -> "(?:[0-9]+|[0-9]{1,3}(?:\\.[0-9]{3})+)(?:,[0-9]+)?"
            NumberConvention.DECIMAL_DOT_NO_GROUPING -> "[0-9]+(?:\\.[0-9]+)?"
            NumberConvention.INTEGER_NO_GROUPING -> "[0-9]+"
        }
        require(Regex(pattern).matches(text)) { "The amount does not follow the chosen number convention." }
        return when (convention) {
            NumberConvention.DECIMAL_DOT_GROUP_COMMA -> text.replace(",", "")
            NumberConvention.DECIMAL_COMMA_GROUP_DOT -> text.replace(".", "").replace(',', '.')
            else -> text
        }
    }
    private fun validCurrency(code: String) = try { Money.normalize("1", code); true } catch (_: IllegalArgumentException) { false }
    private fun explicitCurrencyTokens(code: String): List<String> = listOf(code) + when (code) { "HKD" -> listOf("HK$", "港幣", "港 幣"); "USD" -> listOf("US$"); "SGD" -> listOf("S$"); "TWD" -> listOf("NT$"); "CNY" -> listOf("RMB", "CN¥"); "JPY" -> listOf("JP¥"); "EUR" -> listOf("€"); "GBP" -> listOf("£"); else -> emptyList() }
    private fun currencyAlias(token: String): String? = when (token) { "HK$" -> "HKD"; "US$" -> "USD"; "S$" -> "SGD"; "NT$" -> "TWD"; "RMB", "CN¥" -> "CNY"; "JP¥" -> "JPY"; "€" -> "EUR"; "£" -> "GBP"; else -> token.takeIf { validCurrency(it) } }
    private fun review(message: String) = RecognitionResult(RecognitionStatus.REVIEW_NEEDED, issues = listOf(message))
    private fun inputLength(p: NotificationPayload): Long = listOf(p.title, p.body, p.bigText, p.subText, p.textLines.joinToString("\n")).sumOf { FinancialIdentity.canonicalText(it).toByteArray(Charsets.UTF_8).size.toLong() }
    private fun containsCurrencyToken(text: String, token: String): Boolean {
        var index = text.indexOf(token)
        while (index >= 0) {
            val end = index + token.length
            val left = !Character.isLetterOrDigit(token.codePointAt(0)) || index == 0 || !Character.isLetterOrDigit(text.codePointBefore(index))
            val right = !Character.isLetterOrDigit(token.codePointBefore(token.length)) || end == text.length || !Character.isLetterOrDigit(text.codePointAt(end))
            if (left && right) return true
            index = text.indexOf(token, index + 1)
        }
        return false
    }
    private fun field(p: NotificationPayload, field: MessageField): String = FinancialIdentity.canonicalText(when (field) { MessageField.TITLE -> p.title; MessageField.BODY -> p.body; MessageField.BIG_TEXT -> p.bigText; MessageField.SUB_TEXT -> p.subText; MessageField.LINES -> p.textLines.joinToString("\n") })
    private fun replaceField(p: NotificationPayload, field: MessageField, value: String) = when (field) { MessageField.TITLE -> p.copy(title = value); MessageField.BODY -> p.copy(body = value); MessageField.BIG_TEXT -> p.copy(bigText = value); MessageField.SUB_TEXT -> p.copy(subText = value); MessageField.LINES -> p.copy(textLines = value.split('\n')) }
}
