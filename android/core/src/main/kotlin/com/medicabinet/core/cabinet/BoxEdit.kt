package com.medicabinet.core.cabinet

import com.medicabinet.core.Box
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.scan.ScanIntake
import java.util.UUID

/** Why a box edit was rejected. [kind] is the platform-neutral name shared with iOS. */
enum class BoxEditError(val kind: String) {
    /** The name is empty once trimmed. */
    EMPTY_NAME("emptyName"),

    /** The name is longer than [ScanIntake.MAX_NAME_LENGTH] code points. */
    NAME_TOO_LONG("nameTooLong")
}

class BoxEditException(val error: BoxEditError) : IllegalArgumentException(error.kind)

/**
 * What the box edit form can change: the name, the expiry and the location. The code (GTIN), lot and serial are
 * what the box says and stay as scanned; the category has its own control (taxonomy, #10).
 */
data class BoxEdit(
    val name: String,
    /** `null` = unknown. Set only by an explicit choice (**Date not printed**), never filled in or guessed. */
    val expiry: ExpiryDate?,
    val locationId: UUID
) {
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
        return box.copy(product = box.product.copy(name = trimmed), expiry = expiry, locationId = locationId)
    }

    companion object {
        /** The form's starting point: the box as it is, an unknown expiry staying unknown. */
        fun from(box: Box): BoxEdit = BoxEdit(box.product.name, box.expiry, box.locationId)
    }
}
