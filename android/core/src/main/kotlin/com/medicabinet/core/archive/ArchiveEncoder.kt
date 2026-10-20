package com.medicabinet.core.archive

import com.medicabinet.core.Box
import com.medicabinet.core.ExpiryDate
import java.time.Instant

/**
 * Writes an archive in canonical form (design §2.3). The model is validated first, so the app never produces
 * a file it would refuse to import; locations are ordered by `sortOrder` then id, boxes and named products by
 * `createdAt` then id, so one cabinet always gives the same bytes on both platforms. Always writes the current
 * version (2).
 */
object ArchiveEncoder {
    private val locationOrder = compareBy<ArchivedLocation>(
        { it.location.sortOrder },
        { it.location.id.toString().uppercase() }
    )

    private val boxOrder = compareBy<ArchivedBox>(
        { ArchiveDates.milliseconds(it.box.createdAt) },
        { it.box.id.toString().uppercase() }
    )

    private val namedProductOrder = compareBy<ArchivedNamedProduct>(
        { ArchiveDates.milliseconds(it.product.createdAt) },
        { it.product.id.toString().uppercase() }
    )

    fun encode(archive: Archive, limits: ArchiveLimits = ArchiveLimits.DEFAULT): ByteArray {
        ArchiveValidator.validate(archive, limits)
        val writer = CanonicalJsonWriter()
        writer.beginObject()
        writer.key("archive")
        writer.string(ArchiveFormat.MAGIC)
        writer.key("formatVersion")
        writer.integer(ArchiveFormat.currentVersion.toLong())
        writer.key("exportedAt")
        writer.string(ArchiveDates.format(archive.exportedAt))
        writer.writeApp(archive.app)
        writer.key("locations")
        writer.beginArray()
        archive.cabinet.locations.sortedWith(locationOrder).forEach { writer.writeLocation(it) }
        writer.endArray()
        writer.key("boxes")
        writer.beginArray()
        archive.cabinet.boxes.sortedWith(boxOrder).forEach { writer.writeBox(it) }
        writer.endArray()
        writer.key("namedProducts")
        writer.beginArray()
        archive.cabinet.namedProducts.sortedWith(namedProductOrder).forEach { writer.writeNamedProduct(it) }
        writer.endArray()
        writer.endObject()
        val bytes = writer.finish()
        if (bytes.size > limits.maxFileBytes) throw ArchiveException(ArchiveErrorKind.TOO_LARGE)
        return bytes
    }
}

private fun CanonicalJsonWriter.writeApp(app: ArchiveApp) {
    key("app")
    beginObject()
    key("name")
    string(app.name)
    key("platform")
    string(app.platform)
    key("version")
    string(app.version)
    key("build")
    string(app.build)
    endObject()
}

private fun CanonicalJsonWriter.writeLocation(archived: ArchivedLocation) {
    val location = archived.location
    beginObject()
    key("id")
    string(location.id.toString().uppercase())
    key("name")
    string(location.name)
    key("isDefault")
    bool(location.isDefault)
    key("sortOrder")
    integer(location.sortOrder.toLong())
    key("createdAt")
    string(ArchiveDates.format(location.createdAt))
    key("updatedAt")
    string(ArchiveDates.format(location.updatedAt))
    key("deletedAt")
    optionalDate(archived.deletedAt)
    endObject()
}

private fun CanonicalJsonWriter.writeBox(archived: ArchivedBox) {
    val box = archived.box
    beginObject()
    key("id")
    string(box.id.toString().uppercase())
    key("gtin")
    optionalString(box.product.gtin)
    key("productName")
    string(box.product.name)
    key("category")
    string(box.category.rawValue)
    key("categoryOverridden")
    bool(box.categoryOverridden)
    key("locationId")
    string(box.locationId.toString().uppercase())
    key("expiry")
    writeExpiry(box.expiry)
    key("lot")
    optionalString(box.lot)
    key("serial")
    optionalString(box.serial)
    key("isOpened")
    bool(box.isOpened)
    key("removal")
    writeRemoval(box.removal)
    key("createdAt")
    string(ArchiveDates.format(box.createdAt))
    key("updatedAt")
    string(ArchiveDates.format(box.updatedAt))
    key("deletedAt")
    optionalDate(archived.deletedAt)
    endObject()
}

private fun CanonicalJsonWriter.writeNamedProduct(archived: ArchivedNamedProduct) {
    val product = archived.product
    beginObject()
    key("id")
    string(product.id.toString().uppercase())
    key("gtin")
    string(product.gtin)
    key("name")
    string(product.name)
    key("category")
    string(product.category.rawValue)
    key("categoryOverridden")
    bool(product.categoryOverridden)
    key("createdAt")
    string(ArchiveDates.format(product.createdAt))
    key("updatedAt")
    string(ArchiveDates.format(product.updatedAt))
    key("deletedAt")
    optionalDate(archived.deletedAt)
    endObject()
}

private fun CanonicalJsonWriter.optionalString(text: String?) {
    if (text == null) nul() else string(text)
}

private fun CanonicalJsonWriter.optionalDate(date: Instant?) {
    if (date == null) nul() else string(ArchiveDates.format(date))
}

private fun CanonicalJsonWriter.writeExpiry(expiry: ExpiryDate?) {
    if (expiry == null) {
        nul()
        return
    }
    beginObject()
    key("year")
    integer(expiry.year.toLong())
    key("month")
    integer(expiry.month.toLong())
    key("day")
    val day = expiry.day
    if (day == null) nul() else integer(day.toLong())
    endObject()
}

private fun CanonicalJsonWriter.writeRemoval(removal: Box.Removal?) {
    if (removal == null) {
        nul()
        return
    }
    beginObject()
    key("reason")
    string(removal.reason.rawValue)
    key("at")
    string(ArchiveDates.format(removal.date))
    endObject()
}
