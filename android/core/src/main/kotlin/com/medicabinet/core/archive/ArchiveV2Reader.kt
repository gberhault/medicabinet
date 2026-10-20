package com.medicabinet.core.archive

import com.medicabinet.core.CategoryID
import com.medicabinet.core.NamedProduct
import java.time.Instant
import java.util.UUID

/**
 * The `formatVersion` 2 reader: version 1 plus the root member `namedProducts` (design named-products §7). The
 * members of version 1 are read by the same code, because they did not change. Never edit once shipped: a change is
 * a new version with its own reader (F9).
 */
internal object ArchiveV2Reader : ArchiveVersionReader {
    override fun read(reader: JsonReader, limits: ArchiveLimits): ArchiveBody {
        val namedProducts = ObjectField<ArchiveBody>("namedProducts") { body, json, field ->
            val cursor = ListCursor(json, field.text, limits.maxNamedProducts)
            var index = cursor.next(json)
            while (index != null) {
                body.namedProducts.add(readNamedProduct(json, "namedProducts[$index]"))
                index = cursor.next(json)
            }
        }
        val body = ArchiveBody()
        reader.readObject("", body, ArchiveV1Reader.fields(limits) + namedProducts, opened = true)
        return body
    }

    private class NamedProductDraft {
        var id: UUID = UUID(0, 0)
        var gtin = ""
        var name = ""
        var category = ""
        var categoryOverridden = false
        var createdAt: Instant = Instant.EPOCH
        var updatedAt: Instant = Instant.EPOCH
        var deletedAt: Instant? = null
    }

    private val namedProductFields = listOf(
        ObjectField<NamedProductDraft>("id") { draft, json, field -> draft.id = json.readUuid(field) },
        ObjectField("gtin") { draft, json, field -> draft.gtin = json.readString(field) },
        ObjectField("name") { draft, json, field -> draft.name = json.readString(field) },
        ObjectField("category") { draft, json, field -> draft.category = json.readString(field) },
        ObjectField("categoryOverridden") { draft, json, field -> draft.categoryOverridden = json.readBoolean(field) },
        ObjectField("createdAt") { draft, json, field -> draft.createdAt = json.readDate(field) },
        ObjectField("updatedAt") { draft, json, field -> draft.updatedAt = json.readDate(field) },
        ObjectField("deletedAt") { draft, json, field -> draft.deletedAt = json.readOptionalDate(field) }
    )

    private fun readNamedProduct(reader: JsonReader, path: String): ArchivedNamedProduct {
        val draft = NamedProductDraft()
        reader.readObject(path, draft, namedProductFields)
        val product = NamedProduct(
            id = draft.id,
            gtin = draft.gtin,
            name = draft.name,
            category = CategoryID(draft.category),
            categoryOverridden = draft.categoryOverridden,
            createdAt = draft.createdAt,
            updatedAt = draft.updatedAt
        )
        return ArchivedNamedProduct(product, draft.deletedAt)
    }
}
