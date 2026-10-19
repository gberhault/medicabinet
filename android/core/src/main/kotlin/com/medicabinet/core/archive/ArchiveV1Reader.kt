package com.medicabinet.core.archive

import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.Location
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RemovalReason
import java.time.Instant
import java.util.UUID

/** What a reader hands back: the archive without its version. */
internal class ArchiveBody {
    var exportedAt: Instant = Instant.EPOCH
    var app = ArchiveApp(name = "", platform = "", version = "", build = "")
    val locations = ArrayList<Location>()
    val boxes = ArrayList<ArchivedBox>()
}

/** Reads the members of one `formatVersion` after `archive` and `formatVersion`, into the current model. */
internal interface ArchiveVersionReader {
    fun read(reader: JsonReader, limits: ArchiveLimits): ArchiveBody
}

/**
 * The `formatVersion` 1 reader (design §2). Never edit once shipped: a change is a new version with its own
 * reader, and migrates into the current model there (F9).
 */
internal object ArchiveV1Reader : ArchiveVersionReader {
    override fun read(reader: JsonReader, limits: ArchiveLimits): ArchiveBody {
        val fields = listOf(
            ObjectField<ArchiveBody>("exportedAt") { body, json, field -> body.exportedAt = json.readDate(field) },
            ObjectField("app") { body, json, field ->
                val draft = AppDraft()
                json.readObject(field.text, draft, appFields)
                body.app = ArchiveApp(draft.name, draft.platform, draft.version, draft.build)
            },
            ObjectField("locations") { body, json, field ->
                val cursor = ListCursor(json, field.text, limits.maxLocations)
                var index = cursor.next(json)
                while (index != null) {
                    body.locations.add(readLocation(json, "locations[$index]"))
                    index = cursor.next(json)
                }
            },
            ObjectField("boxes") { body, json, field ->
                val cursor = ListCursor(json, field.text, limits.maxBoxes)
                var index = cursor.next(json)
                while (index != null) {
                    body.boxes.add(readBox(json, "boxes[$index]"))
                    index = cursor.next(json)
                }
            }
        )
        val body = ArchiveBody()
        reader.readObject("", body, fields, opened = true)
        return body
    }

    // MARK: Objects

    private class AppDraft {
        var name = ""
        var platform = ""
        var version = ""
        var build = ""
    }

    private val appFields = listOf(
        ObjectField<AppDraft>("name") { draft, json, field -> draft.name = json.readString(field) },
        ObjectField("platform") { draft, json, field -> draft.platform = json.readString(field) },
        ObjectField("version") { draft, json, field -> draft.version = json.readString(field) },
        ObjectField("build") { draft, json, field -> draft.build = json.readString(field) }
    )

    private class LocationDraft {
        var id: UUID = UUID(0, 0)
        var name = ""
        var isDefault = false
        var sortOrder = 0
        var createdAt: Instant = Instant.EPOCH
        var updatedAt: Instant = Instant.EPOCH
    }

    private val locationFields = listOf(
        ObjectField<LocationDraft>("id") { draft, json, field -> draft.id = json.readUuid(field) },
        ObjectField("name") { draft, json, field -> draft.name = json.readString(field) },
        ObjectField("isDefault") { draft, json, field -> draft.isDefault = json.readBoolean(field) },
        ObjectField("sortOrder") { draft, json, field -> draft.sortOrder = json.readInt32(field) },
        ObjectField("createdAt") { draft, json, field -> draft.createdAt = json.readDate(field) },
        ObjectField("updatedAt") { draft, json, field -> draft.updatedAt = json.readDate(field) }
    )

    private fun readLocation(reader: JsonReader, path: String): Location {
        val draft = LocationDraft()
        reader.readObject(path, draft, locationFields)
        return Location(draft.id, draft.name, draft.isDefault, draft.sortOrder, draft.createdAt, draft.updatedAt)
    }

    private class BoxDraft {
        var id: UUID = UUID(0, 0)
        var gtin: String? = null
        var productName = ""
        var category = ""
        var categoryOverridden = false
        var locationId: UUID = UUID(0, 0)
        var expiry: ExpiryDate? = null
        var lot: String? = null
        var serial: String? = null
        var isOpened = false
        var removal: Box.Removal? = null
        var createdAt: Instant = Instant.EPOCH
        var updatedAt: Instant = Instant.EPOCH
        var deletedAt: Instant? = null
    }

    private val boxFields = listOf(
        ObjectField<BoxDraft>("id") { draft, json, field -> draft.id = json.readUuid(field) },
        ObjectField("gtin") { draft, json, field -> draft.gtin = json.readOptionalString(field) },
        ObjectField("productName") { draft, json, field -> draft.productName = json.readString(field) },
        ObjectField("category") { draft, json, field -> draft.category = json.readString(field) },
        ObjectField("categoryOverridden") { draft, json, field -> draft.categoryOverridden = json.readBoolean(field) },
        ObjectField("locationId") { draft, json, field -> draft.locationId = json.readUuid(field) },
        ObjectField("expiry") { draft, json, field -> draft.expiry = readExpiry(json, field) },
        ObjectField("lot") { draft, json, field -> draft.lot = json.readOptionalString(field) },
        ObjectField("serial") { draft, json, field -> draft.serial = json.readOptionalString(field) },
        ObjectField("isOpened") { draft, json, field -> draft.isOpened = json.readBoolean(field) },
        ObjectField("removal") { draft, json, field -> draft.removal = readRemoval(json, field) },
        ObjectField("createdAt") { draft, json, field -> draft.createdAt = json.readDate(field) },
        ObjectField("updatedAt") { draft, json, field -> draft.updatedAt = json.readDate(field) },
        ObjectField("deletedAt") { draft, json, field -> draft.deletedAt = json.readOptionalDate(field) }
    )

    private fun readBox(reader: JsonReader, path: String): ArchivedBox {
        val draft = BoxDraft()
        reader.readObject(path, draft, boxFields)
        val box = Box(
            id = draft.id,
            product = ProductRef(draft.gtin, draft.productName),
            category = CategoryID(draft.category),
            categoryOverridden = draft.categoryOverridden,
            locationId = draft.locationId,
            expiry = draft.expiry,
            lot = draft.lot,
            serial = draft.serial,
            isOpened = draft.isOpened,
            createdAt = draft.createdAt,
            updatedAt = draft.updatedAt,
            removal = draft.removal
        )
        return ArchivedBox(box, draft.deletedAt)
    }

    // MARK: Expiry and removal

    private class ExpiryDraft {
        var year = 0L
        var month = 0L
        var day: Long? = null
    }

    private val expiryFields = listOf(
        ObjectField<ExpiryDraft>("year") { draft, json, field -> draft.year = json.readInteger(field) },
        ObjectField("month") { draft, json, field -> draft.month = json.readInteger(field) },
        ObjectField("day") { draft, json, field ->
            draft.day = if (json.readNullIfPresent()) null else json.readInteger(field)
        }
    )

    /**
     * `null` is an unknown expiry. A date `ExpiryDate` rejects makes the file invalid: it is never clamped,
     * corrected or turned into "unknown" (V3).
     */
    private fun readExpiry(reader: JsonReader, field: FieldPath): ExpiryDate? {
        if (reader.readNullIfPresent()) return null
        val draft = ExpiryDraft()
        reader.readObject(field.text, draft, expiryFields)
        val year = draft.year.clampedToInt()
        val month = draft.month.clampedToInt()
        val day = draft.day?.clampedToInt()
        if (ExpiryDate.validate(year, month, day) != null) {
            throw ArchiveException(ArchiveErrorKind.INVALID_VALUE, field.text)
        }
        return ExpiryDate.of(year, month, day)
    }

    private class RemovalDraft {
        var reason = RemovalReason.USED_UP
        var date: Instant = Instant.EPOCH
    }

    private val removalFields = listOf(
        ObjectField<RemovalDraft>("reason") { draft, json, field ->
            val name = json.readString(field)
            val reason = RemovalReason.entries.firstOrNull { it.rawValue == name }
            draft.reason = reason ?: throw ArchiveException(ArchiveErrorKind.INVALID_VALUE, field.text)
        },
        ObjectField("at") { draft, json, field -> draft.date = json.readDate(field) }
    )

    private fun readRemoval(reader: JsonReader, field: FieldPath): Box.Removal? {
        if (reader.readNullIfPresent()) return null
        val draft = RemovalDraft()
        reader.readObject(field.text, draft, removalFields)
        return Box.Removal(draft.reason, draft.date)
    }
}

/**
 * A date part narrowed to Int. A value outside Int's range clamps to its limit, which no date accepts either,
 * so it is refused exactly as on the platform where the value fits.
 */
private fun Long.clampedToInt(): Int = coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
