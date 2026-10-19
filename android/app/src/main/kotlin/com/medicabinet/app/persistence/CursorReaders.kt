package com.medicabinet.app.persistence

import android.database.Cursor
import java.util.UUID

/** Ids are stored as upper-case text UUIDs, as on iOS. */
internal fun UUID.stored(): String = toString().uppercase()

internal fun Boolean.stored(): Long = if (this) 1L else 0L

internal fun Cursor.str(column: String): String = getString(getColumnIndexOrThrow(column))

internal fun Cursor.strOrNull(column: String): String? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

internal fun Cursor.intOrNull(column: String): Int? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getInt(it) }

internal fun Cursor.flag(column: String): Boolean = getLong(getColumnIndexOrThrow(column)) == 1L

internal fun uuid(text: String, table: String): UUID = try {
    UUID.fromString(text)
} catch (@Suppress("SwallowedException") error: IllegalArgumentException) {
    throw PersistenceException.CorruptRow(table)
}
