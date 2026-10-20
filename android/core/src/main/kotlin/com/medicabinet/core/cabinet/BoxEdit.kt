package com.medicabinet.core.cabinet

import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.reference.Taxonomy
import com.medicabinet.core.scan.ScanIntake
import java.util.UUID

/** Why a box edit was rejected. [kind] is the platform-neutral name shared with iOS. */
enum class BoxEditError(val kind: String) {
    /** The name is empty once trimmed. */
    EMPTY_NAME("emptyName"),

    /** The name is longer than [ScanIntake.MAX_NAME_LENGTH] code points. */
    NAME_TOO_LONG("nameTooLong"),

    /** The category is not one the user may pick (`uncategorized`, or an identifier this version does not know). */
    CATEGORY_NOT_SELECTABLE("categoryNotSelectable")
}

/** A change of the box's category (B4). Absent = the category stays as it is. */
sealed interface CategoryChange {
    /** The user picked it: the box is marked `categoryOverridden`, so the classifier never moves it again. */
    data class Chosen(val category: CategoryID) : CategoryChange

    /**
     * **Automatic category**: the classifier's category (looked up by the caller) and the box is no longer
     * `categoryOverridden`.
     */
    data class Automatic(val category: CategoryID) : CategoryChange
}

class BoxEditException(val error: BoxEditError) : IllegalArgumentException(error.kind)

/**
 * What the box edit form can change: the name, the expiry, the location and the category. The code (GTIN), lot and
 * serial are what the box says and stay as scanned.
 */
data class BoxEdit(
    val name: String,
    /** `null` = unknown. Set only by an explicit choice (**Date not printed**), never filled in or guessed. */
    val expiry: ExpiryDate?,
    val locationId: UUID,
    val categoryChange: CategoryChange? = null
) {
    /** The user picks a category from the taxonomy. One the user may not pick is refused. */
    fun chooseCategory(category: CategoryID): BoxEdit {
        if (category !in Taxonomy.userSelectable) throw BoxEditException(BoxEditError.CATEGORY_NOT_SELECTABLE)
        return copy(categoryChange = CategoryChange.Chosen(category))
    }

    /**
     * **Automatic category**: gives the box back to the classifier, whose answer is [automatic] (the reference
     * category of its GTIN, else `uncategorized`).
     */
    fun resetCategory(automatic: CategoryID): BoxEdit = copy(categoryChange = CategoryChange.Automatic(automatic))

    /**
     * The box with the edit applied. `updatedAt` is left to the repository, which stamps it on `update`.
     *
     * @throws BoxEditException when the name is empty or too long.
     */
    fun applyTo(box: Box): Box {
        val trimmed = TextNormalizer.trimmed(name)
        if (trimmed.isEmpty()) throw BoxEditException(BoxEditError.EMPTY_NAME)
        if (trimmed.codePointCount(0, trimmed.length) > ScanIntake.MAX_NAME_LENGTH) {
            throw BoxEditException(BoxEditError.NAME_TOO_LONG)
        }
        val edited = box.copy(product = box.product.copy(name = trimmed), expiry = expiry, locationId = locationId)
        return when (val change = categoryChange) {
            is CategoryChange.Chosen -> edited.copy(category = change.category, categoryOverridden = true)
            is CategoryChange.Automatic -> edited.copy(category = change.category, categoryOverridden = false)
            null -> edited
        }
    }

    companion object {
        /** The form's starting point: the box as it is, an unknown expiry staying unknown. */
        fun from(box: Box): BoxEdit = BoxEdit(box.product.name, box.expiry, box.locationId)
    }
}
