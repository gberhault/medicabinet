package com.medicabinet.core.reference

import com.medicabinet.core.CategoryID

/**
 * The household categories, in display order. The identifiers are shared with the iOS app and the reference-data build
 * (`fixtures/categories.json`); the labels are localized by the apps.
 */
object Taxonomy {
    val ordered: List<CategoryID> = listOf(
        CategoryID.PAIN_FEVER,
        CategoryID.COLD_ENT,
        CategoryID.DIGESTION,
        CategoryID.ALLERGY,
        CategoryID.SKIN_WOUND,
        CategoryID.EYES,
        CategoryID.MUSCLES_JOINTS,
        CategoryID.MOUTH_TEETH,
        CategoryID.VITAMINS_MINERALS,
        CategoryID.FIRST_AID,
        CategoryID.OTHER,
        CategoryID.UNCATEGORIZED
    )

    /** What a user may pick: everything but `uncategorized`, which means "nobody chose". */
    val userSelectable: List<CategoryID> = ordered.filter { it != CategoryID.UNCATEGORIZED }

    /**
     * The category to show for a stored value. An identifier this app version does not know (for instance from a newer
     * app's export) is shown as `other`; the stored value itself is never rewritten.
     */
    fun displayed(id: CategoryID): CategoryID = if (id in ordered) id else CategoryID.OTHER
}
