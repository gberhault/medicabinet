package com.medicabinet.app.persistence

import com.medicabinet.app.persistence.Box as BoxRow
import com.medicabinet.app.persistence.Location as LocationRow
import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.InvalidExpiryDateException
import com.medicabinet.core.Location
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RemovalReason
import java.util.UUID

/** Ids are stored as upper-case text UUIDs, as on iOS. */
internal fun UUID.stored(): String = toString().uppercase()

private fun uuid(text: String, table: String): UUID = try {
    UUID.fromString(text)
} catch (@Suppress("SwallowedException") error: IllegalArgumentException) {
    throw PersistenceException.CorruptRow(table)
}

internal fun LocationRow.toModel(): Location = Location(
    id = uuid(id, "location"),
    name = name,
    isDefault = isDefault == 1L,
    sortOrder = sortOrder.toInt(),
    createdAt = StoredDate.read(createdAt, "location"),
    updatedAt = StoredDate.read(updatedAt, "location")
)

internal fun BoxRow.toModel(): Box = Box(
    id = uuid(id, "box"),
    product = ProductRef(gtin = gtin, name = productName),
    category = CategoryID(category),
    categoryOverridden = categoryOverridden == 1L,
    locationId = uuid(locationId, "box"),
    expiry = expiry(),
    lot = lot,
    serial = serial,
    isOpened = isOpened == 1L,
    createdAt = StoredDate.read(createdAt, "box"),
    updatedAt = StoredDate.read(updatedAt, "box"),
    removal = removal()
)

private fun BoxRow.expiry(): ExpiryDate? {
    if (expiryYear == null || expiryMonth == null) return null
    return try {
        ExpiryDate.of(expiryYear.toInt(), expiryMonth.toInt(), expiryDay?.toInt())
    } catch (@Suppress("SwallowedException") error: InvalidExpiryDateException) {
        throw PersistenceException.CorruptRow("box")
    }
}

private fun BoxRow.removal(): Box.Removal? {
    if (removalReason == null || removedAt == null) return null
    val reason = RemovalReason.entries.firstOrNull { it.rawValue == removalReason }
        ?: throw PersistenceException.CorruptRow("box")
    return Box.Removal(reason, StoredDate.read(removedAt, "box"))
}

internal fun Boolean.stored(): Long = if (this) 1L else 0L
