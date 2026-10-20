package com.medicabinet.app.features

import androidx.annotation.StringRes
import com.medicabinet.app.R
import com.medicabinet.core.CategoryID
import com.medicabinet.core.reference.Taxonomy

/** The localized name of a category. Labels describe a body area or a kind of product, never an indication. */
object CategoryLabels {
    private val labels: Map<CategoryID, Int> = mapOf(
        CategoryID.PAIN_FEVER to R.string.category_pain_fever,
        CategoryID.COLD_ENT to R.string.category_cold_ent,
        CategoryID.DIGESTION to R.string.category_digestion,
        CategoryID.ALLERGY to R.string.category_allergy,
        CategoryID.SKIN_WOUND to R.string.category_skin_wound,
        CategoryID.EYES to R.string.category_eyes,
        CategoryID.MUSCLES_JOINTS to R.string.category_muscles_joints,
        CategoryID.MOUTH_TEETH to R.string.category_mouth_teeth,
        CategoryID.VITAMINS_MINERALS to R.string.category_vitamins_minerals,
        CategoryID.FIRST_AID to R.string.category_first_aid,
        CategoryID.OTHER to R.string.category_other,
        CategoryID.UNCATEGORIZED to R.string.category_uncategorized
    )

    /**
     * The string resource of a category. An identifier this app version does not know is shown as `other` (the stored
     * value is left alone).
     */
    @StringRes
    fun resource(id: CategoryID): Int = labels.getValue(Taxonomy.displayed(id))
}
