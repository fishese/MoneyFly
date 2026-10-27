package com.fishese.moneyfly.ledger

import java.util.Locale

data class CategorySuggestion(val categoryId: String?, val conflictingCategoryIds: Set<String> = emptySet()) {
    val needsChoice: Boolean get() = conflictingCategoryIds.size > 1
}

object Categorization {
    fun normalize(text: String): String = text.replace(Regex("[\\p{Z}\\s]+"), " ").trim().lowercase(Locale.ROOT)

    fun canonicalMerchant(observed: String, aliases: List<MerchantAlias>): String? =
        aliases.firstOrNull { normalize(it.spelling) == normalize(observed) }?.canonicalName

    /** Prefer exact merchant + app, then exact merchant, then app. Conflicts remain unassigned. */
    fun suggestAccount(draft: EntryDraft, aliases: List<MerchantAlias>, rules: List<AccountRule>): String? {
        val merchant = canonicalMerchant(draft.merchant, aliases) ?: draft.canonicalMerchant ?: draft.merchant
        val matches = rules.filter { (it.sourcePackage == null || it.sourcePackage == draft.sourcePackage) &&
            (it.merchant == null || matches(merchant, it.merchant, LiteralMatch.EQUALS)) }
        val rank: (AccountRule) -> Int = { (if (it.merchant != null) 2 else 0) + (if (it.sourcePackage != null) 1 else 0) }
        val best = matches.maxOfOrNull(rank) ?: return null
        return matches.filter { rank(it) == best }.map { it.accountId }.distinct().singleOrNull()
    }

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
