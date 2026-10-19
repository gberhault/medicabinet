package com.medicabinet.core.expiry

import com.medicabinet.core.Box
import com.medicabinet.core.ExpiryStatus
import java.text.Normalizer
import java.util.Locale

/**
 * Name order shared with the iOS app: the platform collators (ICU vs `java.text.Collator`) disagree on
 * edge cases, which would break the shared fixture, so names are compared on a folded form — lower
 * case, accents removed — code point by code point. "Éfferalgan" sorts next to "efferalgan".
 */
object NameOrder {
    private val COMBINING_MARKS = Regex("\\p{Mn}")

    fun folded(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD).replace(COMBINING_MARKS, "").lowercase(Locale.ROOT)

    /** `true` when [lhs] sorts before [rhs]: folded name first, then the lower-case id. */
    fun precedes(lhs: Box, rhs: Box): Boolean = comparator.compare(SortKey(lhs), SortKey(rhs)) < 0

    internal class SortKey(box: Box) {
        val name = folded(box.product.name)
        val id = box.id.toString().lowercase(Locale.ROOT)
    }

    /** Compares by code point, not UTF-16 unit, like the Swift scalar comparison. */
    private fun compareCodePoints(lhs: String, rhs: String): Int {
        val left = lhs.codePoints().toArray()
        val right = rhs.codePoints().toArray()
        for (index in 0 until minOf(left.size, right.size)) {
            if (left[index] != right[index]) return left[index].compareTo(right[index])
        }
        return left.size.compareTo(right.size)
    }

    internal val comparator: Comparator<SortKey> = Comparator { lhs, rhs ->
        val byName = compareCodePoints(lhs.name, rhs.name)
        if (byName != 0) byName else compareCodePoints(lhs.id, rhs.id)
    }
}

data class ExpiringEntry(val box: Box, val assessment: ExpiryAssessment)

/**
 * What the Expiring view shows: expired, expiring-soon and unknown-expiry boxes in display order. OK
 * boxes and boxes no longer in the cabinet are never listed.
 */
class ExpiringOverview(
    /** Oldest expiry first. */
    val expired: List<ExpiringEntry>,
    /** Soonest first. */
    val expiringSoon: List<ExpiringEntry>,
    /** By folded name, then id. */
    val unknown: List<ExpiringEntry>
) {
    /** The tab badge: boxes that need attention. Unknown boxes are not counted. */
    val attentionCount: Int get() = expired.size + expiringSoon.size

    /** Nothing to show in any section. */
    val isEmpty: Boolean get() = expired.isEmpty() && expiringSoon.isEmpty() && unknown.isEmpty()

    companion object {
        private class Keyed(val entry: ExpiringEntry, val key: NameOrder.SortKey)

        fun make(boxes: List<Box>, today: CalendarDay, window: ExpiryWindow): ExpiringOverview {
            val expired = mutableListOf<Keyed>()
            val soon = mutableListOf<Keyed>()
            val unknown = mutableListOf<Keyed>()
            for (box in boxes.filter { it.isInCabinet }) {
                val assessment = ExpiryRule.assess(box.expiry, today, window)
                val keyed = Keyed(ExpiringEntry(box, assessment), NameOrder.SortKey(box))
                when (assessment.status) {
                    ExpiryStatus.EXPIRED -> expired += keyed
                    ExpiryStatus.EXPIRING_SOON -> soon += keyed
                    ExpiryStatus.UNKNOWN -> unknown += keyed
                    ExpiryStatus.OK -> Unit
                }
            }
            // Equal days left means the same last valid day, so "oldest first" and "soonest first" are both
            // ascending days left.
            val byDaysThenName = compareBy<Keyed> { it.entry.assessment.daysLeft ?: 0 }
                .thenComparator { lhs, rhs -> NameOrder.comparator.compare(lhs.key, rhs.key) }
            return ExpiringOverview(
                expired = expired.sortedWith(byDaysThenName).map { it.entry },
                expiringSoon = soon.sortedWith(byDaysThenName).map { it.entry },
                unknown = unknown.sortedWith { lhs, rhs -> NameOrder.comparator.compare(lhs.key, rhs.key) }
                    .map { it.entry }
            )
        }
    }
}
