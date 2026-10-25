package com.fishese.moneyfly.ledger

import java.util.Locale

data class CategorySuggestion(val categoryId: String?, val conflictingCategoryIds: Set<String> = emptySet()) {
    val needsChoice: Boolean get() = conflictingCategoryIds.size > 1
}

object Categorization {
    fun normalize(text: String): String = text.replace(Regex("[\\p{Z}\\s]+"), " ").trim().lowercase(Locale.ROOT)

    fun canonicalMerchant(observed: String, aliases: List<MerchantAlias>): String? =
        aliases.firstOrNull { normalize(it.spelling) == normalize(observed) }?.canonicalName

    fun suggest(draft: EntryDraft, aliases: List<MerchantAlias>, rules: List<CategoryRule>): CategorySuggestion {
        if (draft.categoryOrigin == CategoryOrigin.EXPLICIT && draft.categoryId != null) return CategorySuggestion(draft.categoryId)
        val merchant = canonicalMerchant(draft.merchant, aliases) ?: draft.canonicalMerchant ?: draft.merchant
        val matching = rules.filter { rule ->
            (rule.sourcePackage == null || rule.sourcePackage == draft.sourcePackage) &&
                (rule.accountId == null || rule.accountId == draft.accountId) &&
                matches(if (rule.field == RuleField.MERCHANT) merchant else draft.notes, rule.literal, rule.match)
        }
        val priority = matching.maxOfOrNull { it.priority } ?: return CategorySuggestion(null)
        val categories = matching.filter { it.priority == priority }.map { it.categoryId }.toSet()
        return if (categories.size == 1) CategorySuggestion(categories.single()) else CategorySuggestion(null, categories)
    }

    fun matches(text: String, literal: String, match: LiteralMatch): Boolean {
        val haystack = normalize(text)
        val needle = normalize(literal)
        if (needle.isBlank()) return false
        return when (match) {
            LiteralMatch.EQUALS -> haystack == needle
            LiteralMatch.CONTAINS -> haystack.contains(needle)
            LiteralMatch.WHOLE_TOKEN -> {
                var start = haystack.indexOf(needle)
                var found = false
                while (start >= 0 && !found) {
                    val end = start + needle.length
                    val leftBoundary = start == 0 || !isToken(haystack.codePointBefore(start))
                    val rightBoundary = end == haystack.length || !isToken(haystack.codePointAt(end))
                    found = leftBoundary && rightBoundary
                    start = if (found) -1 else haystack.indexOf(needle, start + 1)
                }
                found
            }
        }
    }
    private fun isToken(codePoint: Int) = Character.isLetterOrDigit(codePoint) || codePoint == '_'.code
}
