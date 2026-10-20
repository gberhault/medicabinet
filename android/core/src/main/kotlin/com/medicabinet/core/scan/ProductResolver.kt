package com.medicabinet.core.scan

import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.NamedProduct
import com.medicabinet.core.reference.ReferenceProduct

/** What a scan knows about the product. `name == null` means unknown: the app asks for a name. */
data class ResolvedProduct(val name: String?, val category: CategoryID, val categoryOverridden: Boolean)

object ProductResolver {
    /**
     * A code the reference knows: the reference name; the category is the previous box's when the user set it, else
     * the reference category, else `uncategorized`; [named] is ignored.
     *
     * A code the reference does not know: the name is the named product's (`null` = ask), and the category is its
     * category when the user set it, else `uncategorized`. The previous box is ignored: a used-up box with a test
     * name must not name the next scan (#57).
     */
    fun resolve(reference: ReferenceProduct?, named: NamedProduct?, previousBox: Box?): ResolvedProduct =
        if (reference == null) fromNamed(named) else fromReference(reference, previousBox)

    private fun fromNamed(named: NamedProduct?): ResolvedProduct = if (named != null && named.categoryOverridden) {
        ResolvedProduct(named.name, named.category, categoryOverridden = true)
    } else {
        ResolvedProduct(named?.name, CategoryID.UNCATEGORIZED, categoryOverridden = false)
    }

    private fun fromReference(reference: ReferenceProduct, previousBox: Box?): ResolvedProduct =
        if (previousBox != null && previousBox.categoryOverridden) {
            ResolvedProduct(reference.name, previousBox.category, categoryOverridden = true)
        } else {
            ResolvedProduct(reference.name, reference.category ?: CategoryID.UNCATEGORIZED, categoryOverridden = false)
        }
}
