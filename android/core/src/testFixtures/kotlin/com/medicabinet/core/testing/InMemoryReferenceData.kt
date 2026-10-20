package com.medicabinet.core.testing

import com.medicabinet.core.reference.Cip13
import com.medicabinet.core.reference.ReferenceDataSource
import com.medicabinet.core.reference.ReferenceProduct
import com.medicabinet.core.reference.ReferenceStatus

/**
 * A reference data source held in memory. Products are keyed by CIP13; lookups go through [Cip13.fromGtin], like the
 * real implementations.
 */
class InMemoryReferenceData(
    private val products: Map<String, ReferenceProduct> = emptyMap(),
    private val status: ReferenceStatus
) : ReferenceDataSource {
    override suspend fun product(gtin: String): ReferenceProduct? {
        if (status !is ReferenceStatus.Available) return null
        return Cip13.fromGtin(gtin)?.let { products[it] }
    }

    override suspend fun status(): ReferenceStatus = status
}
