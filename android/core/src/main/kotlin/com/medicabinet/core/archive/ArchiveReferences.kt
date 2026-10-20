package com.medicabinet.core.archive

import com.medicabinet.core.Box
import java.util.UUID

/**
 * The checks that relate rows to each other: unique ids, exactly one default location, boxes in known locations,
 * and the database's unique (GTIN, serial). Paths name the later offender; they never carry a value.
 */
internal object ArchiveReferences {
    fun validate(cabinet: CabinetContents) {
        requireUnique(cabinet.locations.map { it.id }, "locations")
        requireUnique(cabinet.boxes.map { it.box.id }, "boxes")
        if (cabinet.locations.count { it.isDefault } != 1) {
            throw ArchiveException(ArchiveErrorKind.DEFAULT_LOCATION, "locations")
        }
        val known = cabinet.locations.map { it.id }.toSet()
        val unknown = cabinet.boxes.indexOfFirst { it.box.locationId !in known }
        if (unknown >= 0) throw ArchiveException(ArchiveErrorKind.UNKNOWN_LOCATION, "boxes[$unknown].locationId")
        requireUniqueSerials(cabinet.boxes)
    }

    private fun requireUnique(ids: List<UUID>, list: String) {
        val seen = HashSet<UUID>()
        ids.forEachIndexed { index, id ->
            if (!seen.add(id)) throw ArchiveException(ArchiveErrorKind.DUPLICATE_ID, "$list[$index].id")
        }
    }

    /**
     * Among boxes that are not deleted, a (GTIN, serial) pair appears once, like the database's unique index.
     * A box without a GTIN is never compared (SQL treats NULLs as distinct).
     */
    private fun requireUniqueSerials(boxes: List<ArchivedBox>) {
        val seen = HashSet<Pair<String, String>>()
        boxes.forEachIndexed { index, archived ->
            val key = serialKey(archived.box)
            if (archived.deletedAt == null && key != null && !seen.add(key)) {
                throw ArchiveException(ArchiveErrorKind.DUPLICATE_SERIAL, "boxes[$index].serial")
            }
        }
    }

    private fun serialKey(box: Box): Pair<String, String>? {
        val gtin = box.product.gtin
        val serial = box.serial
        return if (gtin != null && serial != null) gtin to serial else null
    }
}
