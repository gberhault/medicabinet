package com.medicabinet.app.persistence

/** The column lists the repositories select and insert; cursors are read by column name, not position. */
internal object Columns {
    const val LOCATION = "id, name, isDefault, sortOrder, createdAt, updatedAt"
    const val NAMED_PRODUCT = "id, gtin, name, category, categoryOverridden, createdAt, updatedAt"
    const val BOX = "id, gtin, productName, category, categoryOverridden, locationId, expiryYear, expiryMonth, " +
        "expiryDay, expirySortKey, lot, serial, isOpened, removalReason, removedAt, createdAt, updatedAt"
}
