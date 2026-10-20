package com.medicabinet.core.archive

import com.medicabinet.core.Box
import java.util.UUID

/**
 * The checks that relate rows to each other: unique ids, exactly one default location, boxes in known locations,
 * and the database's unique (GTIN, serial). Paths name the later offender; they never carry a value.
 */
internal object ArchiveReferences {
    fun validate(cabinet: CabinetContents) {
        requireUnique(cabinet.locations.map { it.location.id }, "locations")
        requireUnique(cabinet.boxes.map { it.box.id }, "boxes")
        val defaults = cabinet.locations.filter { it.location.isDefault }
        if (defaults.size != 1 || defaults.first().deletedAt != null) {
            throw ArchiveException(ArchiveErrorKind.DEFAULT_LOCATION, "locations")
        }
        val known = cabinet.locations.map { it.location.id }.toSet()
        val live = cabinet.locations.filter { it.deletedAt == null }.map { it.location.id }.toSet()
        val unknown = cabinet.boxes.indexOfFirst { !isPlaced(it, known, live) }
        if (unknown >= 0) throw ArchiveException(ArchiveErrorKind.UNKNOWN_LOCATION, "boxes[$unknown].locationId")
        requireUniqueSerials(cabinet.boxes)
    }

    /**
     * A box sits in a location of the file; one still in the cabinet sits in a location that is not deleted (the app
     * moves a location's boxes away before deleting it). Removed and deleted boxes keep pointing at a deleted one.
     */
    private fun isPlaced(archived: ArchivedBox, known: Set<UUID>, live: Set<UUID>): Boolean {
        val id = archived.box.locationId
        val isHistory = archived.box.removal != null || archived.deletedAt != null
        return id in known && (id in live || isHistory)
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
