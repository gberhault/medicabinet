package com.medicabinet.core.named

import com.medicabinet.core.Box
import com.medicabinet.core.NamedProductRepository
import com.medicabinet.core.reference.ProductLookup
import com.medicabinet.core.reference.ReferenceDataSource
import com.medicabinet.core.reference.ReferenceProduct
import com.medicabinet.core.reference.ReferenceStatus
import com.medicabinet.core.scan.ProductResolver
import com.medicabinet.core.scan.ResolvedProduct
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the app knows about a code from the household: the names it gave to codes the reference data does not know.
 * [resolve] answers a scan (reference, then named product, else ask); [record] remembers a saved box (see
 * [MemoryPolicy]).
 */
class ProductMemory(
    private val named: NamedProductRepository,
    private val lookup: ProductLookup,
    private val isAvailable: suspend () -> Boolean = { true }
) {
    /** The bundled reference data: available when its status says so. */
    constructor(named: NamedProductRepository, reference: ReferenceDataSource) :
        this(named, reference, { reference.status() is ReferenceStatus.Available })

    /** Reference name, else the named product's, else `null` (ask). [previousBox] only carries a user category. */
    suspend fun resolve(gtin: String, previousBox: Box?): ResolvedProduct {
        val reference = referenceProduct(gtin)
        // The resolver ignores the named product of a code the reference knows: skip the query.
        val namedProduct = if (reference == null) named.namedProduct(gtin) else null
        return ProductResolver.resolve(reference, namedProduct, previousBox)
    }

    /**
     * Remembers [event]'s box as the named product of its code when [MemoryPolicy] says so. The reference must be
     * available and must not know the code: when it cannot be asked, nothing is written. A failure here is the
     * caller's to report; it never undoes the box write, which is the household's data (the memory is a convenience).
     */
    suspend fun record(event: MemoryEvent) {
        val gtin = event.after.product.gtin ?: return
        val known = codeKnown(gtin)
        // Only a code the reference does not know is ever remembered: do not read the table for the others.
        val current = if (known == false) named.namedProduct(gtin) else null
        val decision = MemoryPolicy.decide(event, known, current)
        if (decision is MemoryDecision.Remember) {
            named.remember(gtin, decision.name, decision.category, decision.overridden)
        }
    }

    /** `null` = cannot tell: the reference is unavailable, or the lookup failed. */
    private suspend fun codeKnown(gtin: String): Boolean? {
        if (!isAvailable()) return null
        return try {
            lookup.product(gtin) != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception) {
            null
        }
    }

    /** A failing reference lookup leaves the product unknown (the user types the name): intake never stops on it. */
    private suspend fun referenceProduct(gtin: String): ReferenceProduct? = try {
        lookup.product(gtin)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception) {
        null
    }
}
