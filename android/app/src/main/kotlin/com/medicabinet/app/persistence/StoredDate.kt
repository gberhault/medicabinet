package com.medicabinet.app.persistence

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Dates are stored as UTC text `yyyy-MM-dd HH:mm:ss.SSS` — the same representation as the iOS database. */
internal object StoredDate {
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

    fun write(instant: Instant): String = format.format(instant)

    fun read(text: String, table: String): Instant = try {
        LocalDateTime.parse(text, format).toInstant(ZoneOffset.UTC)
    } catch (@Suppress("SwallowedException") error: DateTimeParseException) {
        throw PersistenceException.CorruptRow(table)
    }
}
