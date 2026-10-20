package com.medicabinet.app.features

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.LocalFlorist
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import com.medicabinet.core.CategoryID
import com.medicabinet.core.reference.Taxonomy

/**
 * The icon of a category card. Decorative: the label carries the meaning, so screens give it no content
 * description. The same concepts as the iOS symbols (`CategoryIcon`).
 */
object CategoryIcons {
    private val icons: Map<CategoryID, ImageVector> = mapOf(
        CategoryID.PAIN_FEVER to Icons.Outlined.Thermostat,
        CategoryID.COLD_ENT to Icons.Outlined.Air,
        CategoryID.DIGESTION to Icons.Outlined.Restaurant,
        CategoryID.ALLERGY to Icons.Outlined.LocalFlorist,
        CategoryID.SKIN_WOUND to Icons.Outlined.Healing,
        CategoryID.EYES to Icons.Outlined.Visibility,
        CategoryID.MUSCLES_JOINTS to Icons.AutoMirrored.Outlined.DirectionsWalk,
        CategoryID.MOUTH_TEETH to Icons.Outlined.SentimentSatisfied,
        CategoryID.VITAMINS_MINERALS to Icons.Outlined.Medication,
        CategoryID.FIRST_AID to Icons.Outlined.MedicalServices,
        CategoryID.OTHER to Icons.Outlined.Category,
        CategoryID.UNCATEGORIZED to Icons.Outlined.Inbox
    )

    /** An identifier this app version does not know gets the `other` icon, like its label. */
    fun icon(id: CategoryID): ImageVector = icons.getValue(Taxonomy.displayed(id))
}
