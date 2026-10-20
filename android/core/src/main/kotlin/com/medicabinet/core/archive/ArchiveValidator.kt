package com.medicabinet.core.archive

import java.time.Instant

private val appNameLength = 1..50
private val appFieldLength = 1..20
private val locationNameLength = 1..100
private val productNameLength = 1..200
private val categoryLength = 1..100
private val traceabilityLength = 1..20
private const val GTIN_LENGTH = 14

/**
 * The rules an archive must satisfy, run on the decoded model after reading and on the model before
 * writing (so the app never produces a file it would refuse). Order and error attribution: design §2.7.
 * Paths name the field and never carry a value.
 */
internal object ArchiveValidator {
    fun validate(archive: Archive, limits: ArchiveLimits) {
        val cabinet = archive.cabinet
        requireWithin(cabinet.locations.size, limits.maxLocations, "locations")
        requireWithin(cabinet.boxes.size, limits.maxBoxes, "boxes")
        requireWithin(cabinet.namedProducts.size, limits.maxNamedProducts, "namedProducts")
        requireValid(isRepresentable(archive.exportedAt), FieldPath("exportedAt"))
        validateApp(archive.app)
        cabinet.locations.forEachIndexed { index, location -> validateLocation(location, "locations[$index]") }
        cabinet.boxes.forEachIndexed { index, archived -> validateBox(archived, "boxes[$index]") }
        cabinet.namedProducts.forEachIndexed { index, named -> validateNamedProduct(named, "namedProducts[$index]") }
        ArchiveReferences.validate(cabinet)
    }

    private fun validateApp(app: ArchiveApp) {
        requireValid(isLength(app.name, appNameLength), FieldPath("app", "name"))
        requireValid(app.platform in ArchiveApp.PLATFORMS, FieldPath("app", "platform"))
        requireValid(isLength(app.version, appFieldLength), FieldPath("app", "version"))
        requireValid(isLength(app.build, appFieldLength), FieldPath("app", "build"))
    }

    private fun validateLocation(archived: ArchivedLocation, prefix: String) {
        val location = archived.location
        requireValid(isLength(location.name, locationNameLength), FieldPath(prefix, "name"))
        requireValid(isRepresentable(location.createdAt), FieldPath(prefix, "createdAt"))
        requireValid(isRepresentable(location.updatedAt), FieldPath(prefix, "updatedAt"))
        requireValid(isRepresentable(archived.deletedAt), FieldPath(prefix, "deletedAt"))
    }

    private fun validateBox(archived: ArchivedBox, prefix: String) {
        val box = archived.box
        requireValid(isGtin(box.product.gtin), FieldPath(prefix, "gtin"))
        requireValid(isLength(box.product.name, productNameLength), FieldPath(prefix, "productName"))
        requireValid(isLength(box.category.rawValue, categoryLength), FieldPath(prefix, "category"))
        requireValid(isLength(box.lot, traceabilityLength), FieldPath(prefix, "lot"))
        requireValid(isLength(box.serial, traceabilityLength), FieldPath(prefix, "serial"))
        requireValid(isRepresentable(box.removal?.date), FieldPath("$prefix.removal", "at"))
        requireValid(isRepresentable(box.createdAt), FieldPath(prefix, "createdAt"))
        requireValid(isRepresentable(box.updatedAt), FieldPath(prefix, "updatedAt"))
        requireValid(isRepresentable(archived.deletedAt), FieldPath(prefix, "deletedAt"))
    }

    private fun validateNamedProduct(archived: ArchivedNamedProduct, prefix: String) {
        val product = archived.product
        requireValid(isGtin(product.gtin), FieldPath(prefix, "gtin"))
        requireValid(isLength(product.name, productNameLength), FieldPath(prefix, "name"))
        requireValid(isLength(product.category.rawValue, categoryLength), FieldPath(prefix, "category"))
        requireValid(isRepresentable(product.createdAt), FieldPath(prefix, "createdAt"))
        requireValid(isRepresentable(product.updatedAt), FieldPath(prefix, "updatedAt"))
        requireValid(isRepresentable(archived.deletedAt), FieldPath(prefix, "deletedAt"))
    }

    private fun requireWithin(count: Int, limit: Int, list: String) {
        if (count > limit) throw ArchiveException(ArchiveErrorKind.TOO_MANY_ITEMS, list)
    }

    private fun requireValid(condition: Boolean, field: FieldPath) {
        if (!condition) throw ArchiveException(ArchiveErrorKind.INVALID_VALUE, field.text)
    }

    private fun isRepresentable(instant: Instant?): Boolean = instant == null || ArchiveDates.isRepresentable(instant)

    /** Absent, or exactly 14 ASCII digits (the database's rule; the check digit is not verified). */
    private fun isGtin(gtin: String?): Boolean =
        gtin == null || (gtin.length == GTIN_LENGTH && gtin.all { it in '0'..'9' })
}

/** Length in code points within [range]; absent is fine; and no unpaired surrogate, which UTF-8 cannot encode. */
private fun isLength(text: String?, range: IntRange): Boolean =
    text == null || (codePointCount(text) in range && isWellFormed(text))

/** True when every surrogate in [text] is half of a pair. */
internal fun isWellFormed(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val char = text[index]
        val pairedHigh = char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()
        if (char.isSurrogate() && !pairedHigh) return false
        index += if (pairedHigh) 2 else 1
    }
    return true
}
