package com.fishese.moneyfly.ui

import com.fishese.moneyfly.ledger.Categorization
import com.fishese.moneyfly.ledger.LedgerEntry

/** History supplies choices only; money and classification never change on a merchant selection. */
internal fun recentMerchantChoices(entries: List<LedgerEntry>, query: String): List<LedgerEntry> {
    val normalized = Categorization.normalize(query)
    return entries.asSequence().filter { !it.deleted && it.draft.merchant.isNotBlank() }
        .sortedByDescending { it.draft.occurredAt }
        .filter { normalized.isEmpty() || Categorization.normalize(it.draft.merchant).contains(normalized) || Categorization.normalize(it.draft.canonicalMerchant.orEmpty()).contains(normalized) }
        .distinctBy { Categorization.normalize(it.draft.canonicalMerchant ?: it.draft.merchant) }.take(6).toList()
}
