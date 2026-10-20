package com.medicabinet.core.scan

import com.medicabinet.core.Box
import com.medicabinet.core.BoxRepository
import com.medicabinet.core.CategoryID
import com.medicabinet.core.Clock
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import com.medicabinet.core.named.MemoryEvent
import com.medicabinet.core.named.ProductMemory
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** What will become a box. The completion step, the session list and manual entry edit it before [ScanIntake.add]. */
data class BoxDraft(
    val gtin: String? = null,
    /** `null` = unknown, the app asks for it. */
    val name: String? = null,
    val category: CategoryID = CategoryID.UNCATEGORIZED,
    val categoryOverridden: Boolean = false,
    /** `null` = unknown. Only ever set from the code or typed by the user — never defaulted. */
    val expiry: ExpiryDate? = null,
    val lot: String? = null,
    val serial: String? = null
)

sealed interface IntakeOutcome {
    /** Add it now. */
    data class Ready(val draft: BoxDraft) : IntakeOutcome

    /** Ask for what is missing, then add. */
    data class NeedsCompletion(val draft: BoxDraft, val missing: Set<IntakeField>, val expiryUnreadable: Boolean) :
        IntakeOutcome

    /** Ask "another box?", then (when [thenMissing] is not empty) ask for what is missing, then add. */
    data class ConfirmAnother(val draft: BoxDraft, val thenMissing: Set<IntakeField>, val expiryUnreadable: Boolean) :
        IntakeOutcome

    data class AlreadyInCabinet(val box: Box) : IntakeOutcome

    data class RemovedEarlier(val box: Box) : IntakeOutcome
}

/** [kind] is the platform-neutral name shared with iOS. */
enum class IntakeError(val kind: String) {
    /** The name is empty after trimming, or longer than [ScanIntake.MAX_NAME_LENGTH]. */
    INVALID_NAME("invalidName"),

    /** A typed code is not a valid EAN-13 / GTIN-14. */
    INVALID_CODE("invalidCode")
}

class IntakeException(val error: IntakeError) : Exception(error.kind)

/**
 * Turns scans and typed entries into boxes. Stateless: the scan session (GTINs added so far, the review list)
 * lives in the view model.
 */
class ScanIntake(
    private val boxes: BoxRepository,
    private val memory: ProductMemory,
    private val clock: Clock,
    /** Told when remembering the name failed. The box is already saved: the failure never reaches the caller. */
    private val onMemoryFailure: (Exception) -> Unit = {}
) {
    suspend fun evaluate(code: ScannedCode, sessionGtins: Set<String>): IntakeOutcome {
        val match = code.serial?.let { boxes.box(code.gtin, it) }
        val resolved = resolve(code.gtin)
        val draft = BoxDraft(
            gtin = code.gtin,
            name = resolved.name,
            category = resolved.category,
            categoryOverridden = resolved.categoryOverridden,
            expiry = (code.expiry as? ExpiryReading.Date)?.value,
            lot = code.lot,
            serial = code.serial
        )
        val decision = IntakePolicy.decide(code, resolved.name != null, match, code.gtin in sessionGtins)
        return when (decision) {
            IntakeDecision.Add -> IntakeOutcome.Ready(draft)
            is IntakeDecision.Complete ->
                IntakeOutcome.NeedsCompletion(draft, decision.missing, decision.expiryUnreadable)
            is IntakeDecision.ConfirmAnother ->
                IntakeOutcome.ConfirmAnother(draft, decision.thenMissing, decision.expiryUnreadable)
            IntakeDecision.AlreadyInCabinet -> IntakeOutcome.AlreadyInCabinet(matched(match))
            IntakeDecision.RemovedEarlier -> IntakeOutcome.RemovedEarlier(matched(match))
        }
    }

    /**
     * A draft for a typed entry. [code] may be empty or `null` (no code); a non-empty code must be a valid
     * EAN-13 / GTIN-14. A typed [name] wins over a resolved one.
     */
    suspend fun manualDraft(code: String?, name: String?): BoxDraft {
        val typedName = name?.trim()?.takeIf { it.isNotEmpty() }
        val typedCode = code?.trim().orEmpty()
        if (typedCode.isEmpty()) return BoxDraft(name = typedName)
        val gtin = GTIN.normalized(typedCode) ?: throw IntakeException(IntakeError.INVALID_CODE)
        val resolved = resolve(gtin)
        return BoxDraft(
            gtin = gtin,
            name = typedName ?: resolved.name,
            category = resolved.category,
            categoryOverridden = resolved.categoryOverridden
        )
    }

    /**
     * Validates the name (1…200 characters once trimmed), stamps the identity and the creation date and inserts.
     * Throws [RepositoryError.DUPLICATE_SERIAL] when the same box is already in the cabinet.
     */
    suspend fun add(draft: BoxDraft, location: UUID): Box {
        val name = draft.name?.trim()
        if (name == null || name.codePointCount(0, name.length) !in 1..MAX_NAME_LENGTH) {
            throw IntakeException(IntakeError.INVALID_NAME)
        }
        val box = Box(
            product = ProductRef(draft.gtin, name),
            category = draft.category,
            categoryOverridden = draft.categoryOverridden,
            locationId = location,
            expiry = draft.expiry,
            lot = draft.lot,
            serial = draft.serial,
            createdAt = clock.now()
        )
        boxes.insert(box)
        remember(MemoryEvent.Added(box))
        return box
    }

    /** Undoes the removal of a box scanned again, and moves it to [location]. */
    suspend fun putBack(box: Box, location: UUID): Box {
        boxes.undoRemoval(box.id)
        val restored = boxes.box(box.id) ?: throw RepositoryException(RepositoryError.NOT_FOUND)
        if (restored.locationId == location) return restored
        boxes.update(restored.copy(locationId = location))
        return boxes.box(box.id) ?: restored
    }

    suspend fun delete(id: UUID) = boxes.delete(id)

    suspend fun undoDelete(id: UUID) = boxes.undoDelete(id)

    /** The box is the household's data, the memory a convenience: a failing write is reported, not rethrown. */
    private suspend fun remember(event: MemoryEvent) {
        try {
            memory.record(event)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            onMemoryFailure(failure)
        }
    }

    /** Reference data, else the product the household named, else unknown. A previous box only carries a category. */
    private suspend fun resolve(gtin: String): ResolvedProduct = memory.resolve(gtin, boxes.latestBox(gtin))

    private fun matched(match: Box?): Box = match ?: throw RepositoryException(RepositoryError.NOT_FOUND)

    companion object {
        const val MAX_NAME_LENGTH = 200
    }
}
