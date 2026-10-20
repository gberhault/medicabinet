package com.medicabinet.core.named

import com.medicabinet.core.CategoryID
import com.medicabinet.core.NamedProduct
import com.medicabinet.core.cabinet.TextNormalizer
import com.medicabinet.core.reference.Taxonomy
import com.medicabinet.core.scan.ScanIntake

/** Why a named-product edit was rejected. [kind] is the platform-neutral name shared with iOS. */
enum class NamedProductEditError(val kind: String) {
    /** The name is empty once trimmed. */
    EMPTY_NAME("emptyName"),

    /** The name is longer than [ScanIntake.MAX_NAME_LENGTH] code points. */
    NAME_TOO_LONG("nameTooLong"),

    /** The category is not one the user may pick. */
    CATEGORY_NOT_SELECTABLE("categoryNotSelectable")
}

class NamedProductEditException(val error: NamedProductEditError) : IllegalArgumentException(error.kind)

/** What an edit changed, and only that: `null` = left as it was. */
data class NamedProductChanges(val name: String?, val category: Category?) {
    data class Category(val id: CategoryID, val overridden: Boolean)

    val isEmpty: Boolean get() = name == null && category == null
}

/**
 * What the *Edit product* form changes: the name and the category, nothing else. The same shape as `BoxEdit`; the
 * name rule is the box rule (1…200 code points once trimmed).
 */
data class NamedProductEdit(
    val product: NamedProduct,
    val name: String,
    val category: CategoryID,
    val categoryOverridden: Boolean
) {
    /** The user picks a category from the taxonomy. One the user may not pick is refused. */
    fun chooseCategory(category: CategoryID): NamedProductEdit {
        if (category !in Taxonomy.userSelectable) {
            throw NamedProductEditException(NamedProductEditError.CATEGORY_NOT_SELECTABLE)
        }
        return copy(category = category, categoryOverridden = true)
    }

    /** **Automatic category**: back to `uncategorized`, flag off (no reference category exists for a named code). */
    fun resetCategory(): NamedProductEdit = copy(category = CategoryID.UNCATEGORIZED, categoryOverridden = false)

    /**
     * The changes to write: the trimmed name when it differs from the product's, the category and flag when either
     * differs.
     *
     * @throws NamedProductEditException when the name is empty or too long.
     */
    fun validated(): NamedProductChanges {
        val trimmed = TextNormalizer.trimmed(name)
        if (trimmed.isEmpty()) throw NamedProductEditException(NamedProductEditError.EMPTY_NAME)
        if (trimmed.codePointCount(0, trimmed.length) > ScanIntake.MAX_NAME_LENGTH) {
            throw NamedProductEditException(NamedProductEditError.NAME_TOO_LONG)
        }
        val categoryChanged = category != product.category || categoryOverridden != product.categoryOverridden
        return NamedProductChanges(
            name = trimmed.takeIf { it != product.name },
            category = NamedProductChanges.Category(category, categoryOverridden).takeIf { categoryChanged }
        )
    }

    companion object {
        /** The form's starting point: the product as it is. */
        fun from(product: NamedProduct): NamedProductEdit =
            NamedProductEdit(product, product.name, product.category, product.categoryOverridden)
    }
}
