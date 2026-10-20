package com.medicabinet.core.cabinet

import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ExpiryStatus
import java.util.UUID

/** What the Cabinet list is narrowed to. All parts combine. */
data class CabinetFilter(
    /** Free text; matched against the row name ([TextNormalizer.matches]). */
    val query: String = "",
    /** `null` = all locations. */
    val locationId: UUID? = null,
    /** `null` = all statuses. */
    val status: ExpiryStatus? = null
) {
    val isActive: Boolean get() = TextNormalizer.trimmed(query).isNotEmpty() || locationId != null || status != null
}

/** What makes two boxes "the same product" in the list. */
sealed interface ProductKey {
    data class Gtin(val value: String) : ProductKey

    /** Normalized name, for boxes without a code. */
    data class Name(val normalized: String) : ProductKey
}

/**
 * How urgent an expiry status is: expired first, then expiring soon, then unknown, then ok — an unknown
 * expiry is never ranked as safe.
 */
object ExpiryUrgency {
    private val ORDER = listOf(ExpiryStatus.EXPIRED, ExpiryStatus.EXPIRING_SOON, ExpiryStatus.UNKNOWN, ExpiryStatus.OK)

    fun rank(status: ExpiryStatus): Int = ORDER.indexOf(status)
}

data class ProductRowId(val category: CategoryID, val key: ProductKey)

/** One row of the Cabinet list: the boxes in the cabinet that share a product key and a category. */
data class ProductRow(
    val id: ProductRowId,
    /** Name of the most recently created box of the row. */
    val name: String,
    /**
     * What the list shows: the short name, or the full [name] when another row of the same category has the same
     * short name (compared as normalized text).
     */
    val displayName: String,
    /** Only the boxes that passed the filters, earliest expiry first (unknown last). */
    val boxes: List<Box>,
    /** The most urgent status among [boxes]. */
    val status: ExpiryStatus,
    /** `null` when no box of the row has a known expiry. */
    val earliestExpiry: ExpiryDate?
)

data class CategorySection(val category: CategoryID, val rows: List<ProductRow>)

data class CabinetSnapshot(
    val sections: List<CategorySection>,
    /** Boxes shown (the sum over all rows). */
    val boxCount: Int
) {
    companion object {
        val EMPTY = CabinetSnapshot(emptyList(), 0)
    }
}

/**
 * Builds the Cabinet list from the boxes in the cabinet — the Kotlin twin of the Swift `CabinetRows`. Pure and
 * synchronous; callers run it off the main thread. The ordering rules are pinned by `fixtures/cabinet-rows.json`.
 */
object CabinetRows {
    private const val LISTED = 0
    private const val UNLISTED = 1
    private const val UNCATEGORIZED = 2

    /**
     * @param boxes boxes in the cabinet (not removed, not deleted).
     * @param categoryOrder the taxonomy's order; categories not listed follow, by key; `uncategorized` is last.
     * @param status the expiry status of a box (the expiry-status rule with today's date).
     */
    fun build(
        boxes: List<Box>,
        filter: CabinetFilter,
        categoryOrder: List<CategoryID>,
        status: (Box) -> ExpiryStatus
    ): CabinetSnapshot {
        val groups = boxes.asSequence()
            .filter { filter.locationId == null || it.locationId == filter.locationId }
            .map { it to status(it) }
            .filter { filter.status == null || it.second == filter.status }
            .groupBy { ProductRowId(it.first.category, productKey(it.first)) }

        val entries = groups.mapNotNull { (id, group) -> entry(id, group, filter.query) }
        val rank = categoryRank(categoryOrder)
        val sections = entries.groupBy { it.row.id.category }
            .toSortedMap(compareBy<CategoryID> { rank(it) })
            .map { (category, rows) -> CategorySection(category, orderedRows(rows)) }
        return CabinetSnapshot(sections, entries.sumOf { it.row.boxes.size })
    }

    private class Entry(val row: ProductRow, val firstBoxId: String)

    private data class CategoryRank(val bucket: Int, val index: Int, val key: String) : Comparable<CategoryRank> {
        override fun compareTo(other: CategoryRank): Int = when {
            bucket != other.bucket -> bucket.compareTo(other.bucket)
            index != other.index -> index.compareTo(other.index)
            else -> TextNormalizer.compare(key, other.key)
        }
    }

    private fun productKey(box: Box): ProductKey =
        box.product.gtin?.let { ProductKey.Gtin(it) } ?: ProductKey.Name(TextNormalizer.normalize(box.product.name))

    private fun entry(id: ProductRowId, group: List<Pair<Box, ExpiryStatus>>, query: String): Entry? {
        val newest = group.map { it.first }.maxWithOrNull(BoxOrdering.CREATION)
        val normalizedName = newest?.let { TextNormalizer.normalize(it.product.name) }
        if (newest == null || normalizedName == null || !TextNormalizer.matchesNormalized(normalizedName, query)) {
            return null
        }
        val ordered = group.sortedWith { left, right ->
            BoxOrdering.EXPIRY_THEN_CREATION.compare(left.first, right.first)
        }
        val mostUrgent = ordered.map { it.second }.minByOrNull { ExpiryUrgency.rank(it) } ?: ExpiryStatus.UNKNOWN
        val earliest = ordered.mapNotNull { it.first.expiry }.minOrNull()
        val row = ProductRow(
            id, newest.product.name, newest.product.name, ordered.map { it.first }, mostUrgent, earliest
        )
        return Entry(row, BoxOrdering.idKey(ordered.first().first))
    }

    /**
     * The rows of one category with their display names, A–Z by displayed name (normalized), then first box id. A
     * row shows its short name unless another row of the category has the same one: then both show their full
     * name, so two forms of a product stay distinguishable. Status and expiry never move a row.
     */
    private fun orderedRows(entries: List<Entry>): List<ProductRow> {
        val shortNames = entries.map { ShortName.of(it.row.name) }
        val sharing = shortNames.groupingBy { TextNormalizer.normalize(it) }.eachCount()
        return entries.zip(shortNames)
            .map { (entry, short) ->
                val shared = (sharing[TextNormalizer.normalize(short)] ?: 0) > 1
                val displayName = if (shared) entry.row.name else short
                Triple(entry.row.copy(displayName = displayName), TextNormalizer.normalize(displayName), entry.firstBoxId)
            }
            .sortedWith { left, right ->
                val byName = TextNormalizer.compare(left.second, right.second)
                if (byName != 0) byName else TextNormalizer.compare(left.third, right.third)
            }
            .map { it.first }
    }

    private fun categoryRank(order: List<CategoryID>): (CategoryID) -> CategoryRank {
        val positions = HashMap<CategoryID, Int>()
        order.filter { it != CategoryID.UNCATEGORIZED }
            .forEachIndexed { index, category -> positions.putIfAbsent(category, index) }
        return { category ->
            val position = positions[category]
            when {
                category == CategoryID.UNCATEGORIZED -> CategoryRank(UNCATEGORIZED, 0, category.rawValue)
                position != null -> CategoryRank(LISTED, position, category.rawValue)
                else -> CategoryRank(UNLISTED, 0, category.rawValue)
            }
        }
    }
}
