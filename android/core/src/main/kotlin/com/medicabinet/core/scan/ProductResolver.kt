package com.medicabinet.core.scan

import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID

/** A product known to the reference data (BDPM), by GTIN. */
data class ReferenceProduct(val name: String, val category: CategoryID? = null)

interface ProductLookup {
    /** `null` = unknown product. */
    suspend fun product(gtin: String): ReferenceProduct?
}

/** What a scan knows about the product. `name == null` means unknown: the app asks for a name. */
data class ResolvedProduct(val name: String?, val category: CategoryID, val categoryOverridden: Boolean)

object ProductResolver {
    /**
     * The name is the reference name, else the previous box's. The category is the previous box's when the
     * user set it, else the reference category, else `uncategorized`.
     */
    fun resolve(reference: ReferenceProduct?, previousBox: Box?): ResolvedProduct {
        val name = reference?.name ?: previousBox?.product?.name
        if (previousBox != null && previousBox.categoryOverridden) {
            return ResolvedProduct(name, previousBox.category, categoryOverridden = true)
        }
        return ResolvedProduct(name, reference?.category ?: CategoryID.UNCATEGORIZED, categoryOverridden = false)
    }
}
