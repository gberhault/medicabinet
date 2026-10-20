package com.medicabinet.core.reference

import com.medicabinet.core.CategoryID

/**
 * A product known to the reference data (BDPM), by GTIN. [cis] is BDPM's 8-digit identifier of the medicine (`null`
 * when the reference does not hold it); [isWithdrawn] says the presentation left the BDPM, so it has no official page.
 */
data class ReferenceProduct(
    val name: String,
    val category: CategoryID? = null,
    val cis: String? = null,
    val isWithdrawn: Boolean = false
)

interface ProductLookup {
    /** `null` = unknown product. Never a partial or guessed match. */
    suspend fun product(gtin: String): ReferenceProduct?
}
