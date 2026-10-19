package com.medicabinet.core.archive

import com.medicabinet.core.Box
import com.medicabinet.core.Location
import java.time.Instant

private const val DEFAULT_MAX_FILE_BYTES = 32 * 1024 * 1024
private const val DEFAULT_MAX_BOXES = 20_000
private const val DEFAULT_MAX_LOCATIONS = 200
private const val DEFAULT_MAX_STRING_BYTES = 4_096

object ArchiveFormat {
    const val MAGIC = "medicabinet"
    const val FILE_EXTENSION = "medicabinet"
    const val MIME_TYPE = "application/vnd.medicabinet+json"

    /** The newest `formatVersion` this app reads and writes: one reader per version (see [ArchiveDecoder]). */
    val currentVersion: Int get() = ArchiveDecoder.readers.size
}

/**
 * Named defaults, well above any real cabinet (a box is about 650 bytes in the file; a household adds a few
 * hundred boxes a year). The encoder obeys them too, so the app never writes a file it would refuse.
 */
data class ArchiveLimits(
    val maxFileBytes: Int = DEFAULT_MAX_FILE_BYTES,
    val maxBoxes: Int = DEFAULT_MAX_BOXES,
    val maxLocations: Int = DEFAULT_MAX_LOCATIONS,
    /** A decoded string: longer than any field allows (200 code points of 4 bytes). */
    val maxStringBytes: Int = DEFAULT_MAX_STRING_BYTES
) {
    companion object {
        val DEFAULT = ArchiveLimits()
    }
}

/**
 * A box as the archive carries it: removed boxes keep their `removal`, deleted ones (added by mistake,
 * tombstones for a future sync) also have a [deletedAt].
 */
data class ArchivedBox(val box: Box, val deletedAt: Instant? = null)

/** What a cabinet holds, in numbers (the import preview). */
data class CabinetSummary(
    val locations: Int,
    /** Boxes still in the cabinet (not removed, not deleted). */
    val inCabinet: Int,
    /** Used up or thrown away. */
    val removed: Int,
    /** Added by mistake (tombstones). */
    val deleted: Int,
    /** Boxes in the cabinet with no expiry date. */
    val unknownExpiry: Int
)

/** The whole cabinet data: every location and every box, removed and deleted ones included. */
data class CabinetSnapshot(val locations: List<Location>, val boxes: List<ArchivedBox>) {
    val summary: CabinetSummary
        get() {
            val live = boxes.filter { it.deletedAt == null }
            val inCabinet = live.filter { it.box.removal == null }
            return CabinetSummary(
                locations = locations.size,
                inCabinet = inCabinet.size,
                removed = live.size - inCabinet.size,
                deleted = boxes.size - live.size,
                unknownExpiry = inCabinet.count { it.box.expiry == null }
            )
        }

    /** False only when there is nothing to lose: no box of any kind and a single (default) location. */
    val needsBackup: Boolean get() = !(boxes.isEmpty() && locations.size == 1)
}

/** Who wrote the archive. */
data class ArchiveApp(val name: String, val platform: String, val version: String, val build: String) {
    companion object {
        val PLATFORMS = listOf("ios", "android")
    }
}

data class Archive(
    val exportedAt: Instant,
    val app: ArchiveApp,
    val cabinet: CabinetSnapshot,
    /** The `formatVersion` of the file this was read from (the import preview shows it); ignored when encoding. */
    val sourceFormatVersion: Int = ArchiveFormat.currentVersion
)
