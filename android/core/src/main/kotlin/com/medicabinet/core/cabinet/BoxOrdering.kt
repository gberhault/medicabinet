package com.medicabinet.core.cabinet

import com.medicabinet.core.Box

/** The box orderings shared by the Cabinet list and removal by scan; identical on iOS (`BoxOrdering`). */
internal object BoxOrdering {
    /** Lower-case canonical string: ordered the same way on both platforms (never `UUID.compareTo`). */
    fun idKey(box: Box): String = box.id.toString().lowercase()

    /** Oldest first: creation time, then id. */
    val CREATION = Comparator<Box> { left, right ->
        val byTime = left.createdAt.compareTo(right.createdAt)
        if (byTime != 0) byTime else TextNormalizer.compare(idKey(left), idKey(right))
    }

    /** Earliest expiry first (month precision = last day of the month), unknown last, then [CREATION]. */
    val EXPIRY_THEN_CREATION = Comparator<Box> { left, right ->
        val leftKey = left.expiry?.sortKey
        val rightKey = right.expiry?.sortKey
        when {
            leftKey != null && rightKey != null && leftKey != rightKey -> leftKey.compareTo(rightKey)
            leftKey == null && rightKey != null -> 1
            leftKey != null && rightKey == null -> -1
            else -> CREATION.compare(left, right)
        }
    }
}
