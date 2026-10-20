package com.medicabinet.core.archive

import com.medicabinet.core.Box
import com.medicabinet.core.Location
import com.medicabinet.core.NamedProduct
import java.time.Instant

private const val DEFAULT_MAX_FILE_BYTES = 32 * 1024 * 1024
private const val DEFAULT_MAX_BOXES = 20_000
private const val DEFAULT_MAX_LOCATIONS = 200
private const val DEFAULT_MAX_NAMED_PRODUCTS = 20_000
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
    val maxNamedProducts: Int = DEFAULT_MAX_NAMED_PRODUCTS,
    /** A decoded string: longer than any field allows (200 code points of 4 bytes). */
    val maxStringBytes: Int = DEFAULT_MAX_STRING_BYTES
) {
    companion object {
        val DEFAULT = ArchiveLimits()
    }
}

/**
 * A location as the archive carries it: one the user deleted keeps its row, with [deletedAt], because removed and
 * deleted boxes still point at it (schema v3).
 */
data class ArchivedLocation(val location: Location, val deletedAt: Instant? = null)

/**
 * A box as the archive carries it: removed boxes keep their `removal`, deleted ones (added by mistake,
 * tombstones for a future sync) also have a [deletedAt].
 */
data class ArchivedBox(val box: Box, val deletedAt: Instant? = null)

/**
 * A named product as the archive carries it (`formatVersion` 2): one the user forgot keeps its row, with
 * [deletedAt], so a later sync can tell "forgotten" from "never existed".
 */
data class ArchivedNamedProduct(val product: NamedProduct, val deletedAt: Instant? = null)

/** What a cabinet holds, in numbers (the import preview). */
data class CabinetSummary(
    /** Locations that are not deleted. */
    val locations: Int,
    /** Boxes still in the cabinet (not removed, not deleted). */
    val inCabinet: Int,
    /** Used up or thrown away. */
    val removed: Int,
    /** Added by mistake (tombstones). */
    val deleted: Int,
    /** Boxes in the cabinet with no expiry date. */
    val unknownExpiry: Int,
    /** Products the household named and has not forgotten. */
    val namedProducts: Int = 0
) {
    /**
     * False when there is nothing to lose: no box of any kind, no named product and no location besides (at most)
     * the default one. Forgotten named products are not in a summary: [CabinetContents.needsBackup] also counts them.
     */
    val needsBackup: Boolean get() = locations > 1 || inCabinet + removed + deleted + namedProducts > 0
}

/**
 * The whole cabinet data: every location, every box and every named product, removed, deleted and forgotten ones
 * included.
 */
data class CabinetContents(
    val locations: List<ArchivedLocation>,
    val boxes: List<ArchivedBox>,
    val namedProducts: List<ArchivedNamedProduct> = emptyList(),
    /**
     * True when the named products still have to be filled from the boxes: the archive was a version 1 file, which
     * has none (`NamedProductBackfillRunner` does it after the import). Never part of a file: the decoder sets it, a
     * store snapshot is always false, and `replace` turns it into the backfill marker.
     */
    val namedProductsNeedFill: Boolean = false
) {
    val summary: CabinetSummary
        get() {
            val live = boxes.filter { it.deletedAt == null }
            val inCabinet = live.filter { it.box.removal == null }
            return CabinetSummary(
                locations = locations.count { it.deletedAt == null },
                inCabinet = inCabinet.size,
                removed = live.size - inCabinet.size,
                deleted = boxes.size - live.size,
                unknownExpiry = inCabinet.count { it.box.expiry == null },
                namedProducts = namedProducts.count { it.deletedAt == null }
            )
        }

    /** A forgotten named product still counts: it is the household's data too. */
    val needsBackup: Boolean get() = summary.needsBackup || namedProducts.isNotEmpty()
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
    val cabinet: CabinetContents,
    /** The `formatVersion` of the file this was read from (the import preview shows it); ignored when encoding. */
    val sourceFormatVersion: Int = ArchiveFormat.currentVersion
)
