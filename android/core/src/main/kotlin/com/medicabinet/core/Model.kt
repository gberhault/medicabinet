package com.medicabinet.core

import java.time.Instant
import java.util.UUID

/** Stable key of a category (e.g. `pain-fever`); the twelve categories are listed by `reference.Taxonomy`. */
@JvmInline
value class CategoryID(val rawValue: String) {
    companion object {
        val PAIN_FEVER = CategoryID("pain-fever")
        val COLD_ENT = CategoryID("cold-ent")
        val DIGESTION = CategoryID("digestion")
        val ALLERGY = CategoryID("allergy")
        val SKIN_WOUND = CategoryID("skin-wound")
        val EYES = CategoryID("eyes")
        val MUSCLES_JOINTS = CategoryID("muscles-joints")
        val MOUTH_TEETH = CategoryID("mouth-teeth")
        val VITAMINS_MINERALS = CategoryID("vitamins-minerals")

        /** Never assigned automatically (BDPM holds no dressings or devices): the user picks it. */
        val FIRST_AID = CategoryID("first-aid")
        val OTHER = CategoryID("other")
        val UNCATEGORIZED = CategoryID("uncategorized")
    }
}

/** What a box is. The name is a snapshot, so a box stays readable if reference data changes. */
data class ProductRef(
    /** 14 digits when known. */
    val gtin: String? = null,
    val name: String
)

enum class ExpiryStatus(val rawValue: String) {
    EXPIRED("expired"),
    EXPIRING_SOON("expiringSoon"),
    OK("ok"),

    /** No trustworthy expiry date is known. Never presented as OK. */
    UNKNOWN("unknown")
}

enum class RemovalReason(val rawValue: String) {
    USED_UP("usedUp"),
    THROWN_AWAY("thrownAway")
}

data class Location(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val isDefault: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt
)

/** One physical box in the cabinet. Quantities are counted in boxes only. */
data class Box(
    val id: UUID = UUID.randomUUID(),
    val product: ProductRef,
    val category: CategoryID = CategoryID.UNCATEGORIZED,
    val categoryOverridden: Boolean = false,
    val locationId: UUID,
    /** `null` = unknown. Never defaulted. */
    val expiry: ExpiryDate? = null,
    val lot: String? = null,
    val serial: String? = null,
    val isOpened: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
    /** `null` = still in the cabinet. */
    val removal: Removal? = null
) {
    data class Removal(val reason: RemovalReason, val date: Instant)

    val isInCabinet: Boolean get() = removal == null
}

/**
 * The name and category the household gave a code the reference database does not name: one live product per GTIN.
 * A box keeps its own copy of the name (a forgotten product leaves its boxes alone). `deletedAt` exists only on the
 * archive and record types, as for [Location].
 */
data class NamedProduct(
    val id: UUID = UUID.randomUUID(),
    /** 14 digits. */
    val gtin: String,
    val name: String,
    val category: CategoryID = CategoryID.UNCATEGORIZED,
    val categoryOverridden: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt
)

/** One row of the *Products you named* list: a named product and the boxes of its code still in the cabinet. */
data class NamedProductSummary(val product: NamedProduct, val boxesInCabinet: Int)
