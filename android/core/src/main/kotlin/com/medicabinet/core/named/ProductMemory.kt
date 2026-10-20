package com.medicabinet.core.named

import com.medicabinet.core.Box
import com.medicabinet.core.NamedProductRepository
import com.medicabinet.core.reference.ProductLookup
import com.medicabinet.core.reference.ReferenceProduct
import com.medicabinet.core.scan.ProductResolver
import com.medicabinet.core.scan.ResolvedProduct
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the app knows about a code from the household: the names it gave to codes the reference data does not know.
 * [resolve] answers a scan (reference, then named product, else ask); [record] remembers a saved box (see
 * [MemoryPolicy]).
 */
class ProductMemory(private val named: NamedProductRepository, private val lookup: ProductLookup) {
    /** Reference name, else the named product's, else `null` (ask). [previousBox] only carries a user category. */
    suspend fun resolve(gtin: String, previousBox: Box?): ResolvedProduct {
        val reference = referenceProduct(gtin)
        // The resolver ignores the named product of a code the reference knows: skip the query.
        val namedProduct = if (reference == null) named.namedProduct(gtin) else null
        return ProductResolver.resolve(reference, namedProduct, previousBox)
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
