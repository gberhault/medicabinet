package com.medicabinet.core.cabinet

import com.medicabinet.core.CategoryID

/**
 * The order of the category cards in the Cabinet: A–Z by the label the app shows, then *Other*, then
 * *Uncategorized*. Labels are compared as [TextNormalizer] text by code point — not with `Collator`, which can
 * differ between platforms and OS versions. Pinned by `fixtures/category-order.json`; the Swift twin is
 * `CategoryOrder`.
 */
object CategoryOrder {
    /**
     * @param labels the localized label of each category to order.
     * @return the ids of [labels], A–Z by normalized label (ties by raw id), `other` then `uncategorized` last.
     */
    fun alphabetical(labels: Map<CategoryID, String>): List<CategoryID> {
        val regular = labels.entries
            .filter { it.key != CategoryID.OTHER && it.key != CategoryID.UNCATEGORIZED }
            .map { it.key to TextNormalizer.normalize(it.value) }
            .sortedWith { lhs, rhs ->
                val byLabel = TextNormalizer.compare(lhs.second, rhs.second)
                if (byLabel != 0) byLabel else TextNormalizer.compare(lhs.first.rawValue, rhs.first.rawValue)
            }
            .map { it.first }
        return regular +
            listOfNotNull(
                CategoryID.OTHER.takeIf { it in labels },
                CategoryID.UNCATEGORIZED.takeIf { it in labels }
            )
    }
}
