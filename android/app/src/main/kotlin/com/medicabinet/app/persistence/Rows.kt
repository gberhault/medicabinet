package com.medicabinet.app.persistence

import android.database.Cursor
import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.InvalidExpiryDateException
import com.medicabinet.core.Location
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RemovalReason

internal fun Cursor.toLocation(): Location = Location(
    id = uuid(str("id"), "location"),
    name = str("name"),
    isDefault = flag("isDefault"),
    sortOrder = getInt(getColumnIndexOrThrow("sortOrder")),
    createdAt = StoredDate.read(str("createdAt"), "location"),
    updatedAt = StoredDate.read(str("updatedAt"), "location")
)

internal fun Cursor.toBox(): Box = Box(
    id = uuid(str("id"), "box"),
    product = ProductRef(gtin = strOrNull("gtin"), name = str("productName")),
    category = CategoryID(str("category")),
    categoryOverridden = flag("categoryOverridden"),
    locationId = uuid(str("locationId"), "box"),
    expiry = expiry(),
    lot = strOrNull("lot"),
    serial = strOrNull("serial"),
    isOpened = flag("isOpened"),
    createdAt = StoredDate.read(str("createdAt"), "box"),
    updatedAt = StoredDate.read(str("updatedAt"), "box"),
    removal = removal()
)

private fun Cursor.expiry(): ExpiryDate? {
    val year = intOrNull("expiryYear")
    val month = intOrNull("expiryMonth")
    if (year == null || month == null) return null
    return try {
        ExpiryDate.of(year, month, intOrNull("expiryDay"))
    } catch (@Suppress("SwallowedException") error: InvalidExpiryDateException) {
        throw PersistenceException.CorruptRow("box")
    }
}

private fun Cursor.removal(): Box.Removal? {
    val reason = strOrNull("removalReason")
    val removedAt = strOrNull("removedAt")
    if (reason == null || removedAt == null) return null
    val known =
        RemovalReason.entries.firstOrNull { it.rawValue == reason } ?: throw PersistenceException.CorruptRow("box")
    return Box.Removal(known, StoredDate.read(removedAt, "box"))
}

internal inline fun <T> Cursor.mapRows(map: Cursor.() -> T): List<T> = use {
    val rows = mutableListOf<T>()
    while (moveToNext()) rows += map()
    rows
}
