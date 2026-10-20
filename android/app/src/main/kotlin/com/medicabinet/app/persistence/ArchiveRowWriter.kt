package com.medicabinet.app.persistence

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import com.medicabinet.core.Box
import com.medicabinet.core.Location
import com.medicabinet.core.NamedProduct
import com.medicabinet.core.archive.ArchivedBox
import com.medicabinet.core.archive.ArchivedLocation
import com.medicabinet.core.archive.ArchivedNamedProduct
import java.time.Instant

/** Writes the rows of an archive back as given: ids, fields and dates are kept, one prepared statement per kind. */
internal object ArchiveRowWriter {
    fun locations(db: SupportSQLiteDatabase, locations: List<ArchivedLocation>) {
        db.compileStatement("INSERT INTO location (${Columns.LOCATION}, deletedAt) VALUES (?, ?, ?, ?, ?, ?, ?)").use {
            for (archived in locations) it.insert(archived.location, archived.deletedAt)
        }
    }

    fun boxes(db: SupportSQLiteDatabase, boxes: List<ArchivedBox>) {
        db.compileStatement(
            "INSERT INTO box (${Columns.BOX}, deletedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ).use {
            for (archived in boxes) it.insert(archived.box, archived.deletedAt)
        }
    }

    fun namedProducts(db: SupportSQLiteDatabase, products: List<ArchivedNamedProduct>) {
        db.compileStatement(
            "INSERT INTO namedProduct (${Columns.NAMED_PRODUCT}, deletedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        ).use {
            for (archived in products) it.insert(archived.product, archived.deletedAt)
        }
    }

    private fun SupportSQLiteStatement.insert(product: NamedProduct, deletedAt: Instant?) = insertRow(
        product.id.stored(),
        product.gtin,
        product.name,
        product.category.rawValue,
        product.categoryOverridden.stored(),
        StoredDate.write(product.createdAt),
        StoredDate.write(product.updatedAt),
        deletedAt?.let(StoredDate::write)
    )

    private fun SupportSQLiteStatement.insert(location: Location, deletedAt: Instant?) = insertRow(
        location.id.stored(),
        location.name,
        location.isDefault.stored(),
        location.sortOrder.toLong(),
        StoredDate.write(location.createdAt),
        StoredDate.write(location.updatedAt),
        deletedAt?.let(StoredDate::write)
    )

    /** Ids, fields and dates as given; the expiry sort key is derived, so it is computed here (F5). */
    private fun SupportSQLiteStatement.insert(box: Box, deletedAt: Instant?) = insertRow(
        box.id.stored(),
        box.product.gtin,
        box.product.name,
        box.category.rawValue,
        box.categoryOverridden.stored(),
        box.locationId.stored(),
        box.expiry?.year?.toLong(),
        box.expiry?.month?.toLong(),
        box.expiry?.day?.toLong(),
        box.expiry?.sortKey,
        box.lot,
        box.serial,
        box.isOpened.stored(),
        box.removal?.reason?.rawValue,
        box.removal?.date?.let(StoredDate::write),
        StoredDate.write(box.createdAt),
        StoredDate.write(box.updatedAt),
        deletedAt?.let(StoredDate::write)
    )

    /** Binds [values] to the statement's placeholders, in order, and runs it. */
    private fun SupportSQLiteStatement.insertRow(vararg values: Any?) {
        clearBindings()
        values.forEachIndexed { index, value ->
            when (value) {
                null -> bindNull(index + 1)
                is Long -> bindLong(index + 1, value)
                else -> bindString(index + 1, value.toString())
            }
        }
        executeInsert()
    }
}
