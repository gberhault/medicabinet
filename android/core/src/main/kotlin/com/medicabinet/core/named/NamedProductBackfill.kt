package com.medicabinet.core.named

import com.medicabinet.core.Box
import com.medicabinet.core.BoxLookups
import com.medicabinet.core.CategoryID
import com.medicabinet.core.Clock
import com.medicabinet.core.NamedProduct
import com.medicabinet.core.NamedProductRepository
import com.medicabinet.core.reference.ReferenceDataSource
import com.medicabinet.core.reference.ReferenceStatus
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * The one-time fill of the named products after the upgrade to schema v4: the cabinet already holds boxes whose names
 * the household typed for codes the reference does not know. Pure; pinned by `fixtures/named-product-backfill.json`.
 */
object NamedProductBackfill {
    /**
     * [latest]: the newest non-deleted box of every GTIN (`BoxLookups.latestBoxPerGTIN`). [isKnown]: the reference
     * knows the code. A box without a GTIN, or with a known one, gives nothing. The name is the box's; the category and
     * its flag are the box's when its flag is on, else `uncategorized` with the flag off. Ordered by GTIN.
     */
    fun products(latest: List<Box>, isKnown: (String) -> Boolean, ids: () -> UUID, now: Instant): List<NamedProduct> =
        latest.mapNotNull { box -> box.product.gtin?.takeUnless(isKnown)?.let { gtin -> gtin to box } }
            .sortedWith { left, right -> left.first.compareTo(right.first) }
            .map { (gtin, box) ->
                NamedProduct(
                    id = ids(),
                    gtin = gtin,
                    name = box.product.name,
                    category = if (box.categoryOverridden) box.category else CategoryID.UNCATEGORIZED,
                    categoryOverridden = box.categoryOverridden,
                    createdAt = now
                )
            }
}

/**
 * Runs the fill once, in the background after launch (and after an import of an older archive). Ports only, so both
 * platforms share its tests. Nothing is written unless the whole fill can be computed: the next launch retries.
 */
class NamedProductBackfillRunner(
    private val named: NamedProductRepository,
    private val boxes: BoxLookups,
    private val reference: ReferenceDataSource,
    private val clock: Clock,
    private val ids: () -> UUID = UUID::randomUUID
) {
    enum class Outcome {
        /** The marker was not set: nothing to do, no lookup made. */
        NOT_PENDING,

        /** The reference cannot say which codes it knows: wait for a launch where it can. */
        REFERENCE_UNAVAILABLE,

        /** A lookup failed: nothing was written. */
        LOOKUP_FAILED,

        /** The products were inserted and the marker cleared. */
        DONE
    }

    suspend fun run(): Outcome = when {
        !named.backfillPending() -> Outcome.NOT_PENDING
        reference.status() !is ReferenceStatus.Available -> Outcome.REFERENCE_UNAVAILABLE
        else -> fill()
    }

    private suspend fun fill(): Outcome {
        val latest = boxes.latestBoxPerGTIN()
        val known = knownCodes(latest) ?: return Outcome.LOOKUP_FAILED
        named.completeBackfill(NamedProductBackfill.products(latest, known::contains, ids, clock.now()))
        return Outcome.DONE
    }

    /** The codes of [latest] the reference knows, or `null` when any lookup failed. */
    private suspend fun knownCodes(latest: List<Box>): Set<String>? {
        val known = mutableSetOf<String>()
        for (gtin in latest.mapNotNull { it.product.gtin }) {
            try {
                if (reference.product(gtin) != null) known += gtin
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception) {
                return null
            }
        }
        return known
    }
}
