package com.medicabinet.core.reference

import com.medicabinet.core.CategoryID

/** A product known to the reference data (BDPM), by GTIN. */
data class ReferenceProduct(val name: String, val category: CategoryID? = null)

interface ProductLookup {
    /** `null` = unknown product. Never a partial or guessed match. */
    suspend fun product(gtin: String): ReferenceProduct?
}
