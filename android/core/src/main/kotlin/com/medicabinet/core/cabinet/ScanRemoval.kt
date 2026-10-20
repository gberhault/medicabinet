package com.medicabinet.core.cabinet

import com.medicabinet.core.Box
import com.medicabinet.core.BoxRepository
import com.medicabinet.core.Clock
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import com.medicabinet.core.scan.ExpiryReading
import com.medicabinet.core.scan.ScannedCode
import java.util.UUID

sealed interface RemovalDecision {
    /** Platform-neutral name, shared with iOS and `fixtures/scan-removal.json`. */
    val kind: String

    data class Remove(val id: UUID) : RemovalDecision {
        override val kind: String get() = "remove"
    }

    /** Several candidates, ordered: the first is the one the picker pre-selects. */
    data class Choose(val ids: List<UUID>) : RemovalDecision {
        override val kind: String get() = "choose"
    }

    data class AlreadyRemoved(val id: UUID) : RemovalDecision {
        override val kind: String get() = "alreadyRemoved"
    }

    data object NotInCabinet : RemovalDecision {
        override val kind: String get() = "notInCabinet"
    }
}

/** What a scan in Remove mode does. Pure and fixture-driven (`fixtures/scan-removal.json`). */
object RemovalPolicy {
    /**
     * @param serialMatch the non-deleted box (in the cabinet or removed) with the scan's GTIN and serial; `null` when
     *   the scan has no serial or no box matches.
     * @param cabinet the boxes in the cabinet with the scan's GTIN.
     */
    fun decide(code: ScannedCode, serialMatch: Box?, cabinet: List<Box>): RemovalDecision {
        if (code.serial != null && serialMatch != null) {
            return if (serialMatch.isInCabinet) {
                RemovalDecision.Remove(serialMatch.id)
            } else {
                RemovalDecision.AlreadyRemoved(serialMatch.id)
            }
        }
        val candidates = cabinet
            .filter { it.isInCabinet && it.product.gtin == code.gtin && (code.serial == null || it.serial == null) }
            .sortedWith(candidateOrder((code.expiry as? ExpiryReading.Date)?.value))
        return when (candidates.size) {
            0 -> RemovalDecision.NotInCabinet
            1 -> RemovalDecision.Remove(candidates.first().id)
            else -> RemovalDecision.Choose(candidates.map { it.id })
        }
    }

    /** Same expiry as the scan first, then earliest expiry (unknown last), then creation order, then id. */
    private fun candidateOrder(scanExpiry: ExpiryDate?) = Comparator<Box> { left, right ->
        val leftSame = scanExpiry != null && left.expiry == scanExpiry
        val rightSame = scanExpiry != null && right.expiry == scanExpiry
        when {
            leftSame && !rightSame -> -1
            !leftSame && rightSame -> 1
            else -> BoxOrdering.EXPIRY_THEN_CREATION.compare(left, right)
        }
    }
}

sealed interface RemovalOutcome {
    /** Already removed (stock movement written); the box as stored. */
    data class Removed(val box: Box) : RemovalOutcome

    /** Nothing written yet: the user picks one, then [ScanRemoval.remove]. */
    data class Choose(val candidates: List<Box>) : RemovalOutcome

    data class AlreadyRemoved(val box: Box) : RemovalOutcome

    data object NotInCabinet : RemovalOutcome
}

/** Removal by scanning: the same stock movement as the box detail's **Used up** / **Thrown away**, undoable. */
class ScanRemoval(private val boxes: BoxRepository, private val clock: Clock) {
    /** Decides, and when the decision is [RemovalDecision.Remove], removes with [reason] at the clock's date. */
    suspend fun handle(code: ScannedCode, reason: RemovalReason): RemovalOutcome {
        val serialMatch = code.serial?.let { boxes.box(code.gtin, it) }
        // A serial match decides on its own: skip the cabinet query.
        val cabinet = if (serialMatch == null) boxes.cabinetBoxes(code.gtin) else emptyList()
        return when (val decision = RemovalPolicy.decide(code, serialMatch, cabinet)) {
            is RemovalDecision.Remove -> RemovalOutcome.Removed(remove(decision.id, reason))
            is RemovalDecision.Choose ->
                RemovalOutcome.Choose(decision.ids.mapNotNull { id -> cabinet.find { it.id == id } })
            is RemovalDecision.AlreadyRemoved ->
                serialMatch?.let { RemovalOutcome.AlreadyRemoved(it) } ?: RemovalOutcome.NotInCabinet
            RemovalDecision.NotInCabinet -> RemovalOutcome.NotInCabinet
        }
    }

    /** Removes the box the user picked (or the one [handle] found). Throws `notFound` when it is gone. */
    suspend fun remove(id: UUID, reason: RemovalReason): Box {
        boxes.markRemoved(id, reason, clock.now())
        return boxes.box(id) ?: throw RepositoryException(RepositoryError.NOT_FOUND)
    }

    /** Puts the box back in the cabinet exactly as it was. */
    suspend fun undo(id: UUID) {
        boxes.undoRemoval(id)
    }
}
