package com.medicabinet.core.archive

import java.time.Instant

private const val TEXT_LENGTH = 24
private const val MILLIS_PER_SECOND = 1000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_HOUR = 3_600_000L
private const val MILLIS_PER_DAY = 86_400_000L
private const val NANOS_PER_MILLI = 1_000_000L
private const val HALF_MILLI_IN_NANOS = 500_000L
private const val HOURS_PER_DAY = 24
private const val MINUTES_PER_HOUR = 60
private const val SECONDS_PER_MINUTE = 60
private const val MONTHS_PER_YEAR = 12
private const val YEAR_DIGITS = 4
private const val MILLISECOND_DIGITS = 3

/** The digit groups of yyyy-MM-ddTHH:mm:ss.SSSZ and where they sit in the text. */
@Suppress("MagicNumber") // Character positions in the fixed 24-character shape.
private enum class DateField(val range: IntRange) {
    YEAR(0..3),
    MONTH(5..6),
    DAY(8..9),
    HOUR(11..12),
    MINUTE(14..15),
    SECOND(17..18),
    MILLISECOND(20..22)
}

@Suppress("MagicNumber") // Character positions in the fixed 24-character shape.
private val separators = listOf(4 to '-', 7 to '-', 10 to 'T', 13 to ':', 16 to ':', 19 to '.', 23 to 'Z')

/**
 * Archive timestamps: `yyyy-MM-ddTHH:mm:ss.SSSZ`, UTC, exactly 24 characters, millisecond precision
 * (the database's precision, so a round trip is exact).
 */
internal object ArchiveDates {
    /** Milliseconds since the epoch for 0000-01-01T00:00:00.000Z and 9999-12-31T23:59:59.999Z. */
    const val MIN_MILLISECONDS = -62_167_219_200_000L
    const val MAX_MILLISECONDS = 253_402_300_799_999L

    fun instant(milliseconds: Long): Instant = Instant.ofEpochMilli(milliseconds)

    /** Rounded to the nearest millisecond. Values far outside the representable range saturate just beyond it. */
    fun milliseconds(instant: Instant): Long {
        val seconds = instant.epochSecond
        val limit = MAX_MILLISECONDS / MILLIS_PER_SECOND + 1
        return when {
            seconds > limit -> MAX_MILLISECONDS + 1
            seconds < -limit -> MIN_MILLISECONDS - 1
            else -> seconds * MILLIS_PER_SECOND + (instant.nano + HALF_MILLI_IN_NANOS) / NANOS_PER_MILLI
        }
    }

    fun isRepresentable(instant: Instant): Boolean = milliseconds(instant) in MIN_MILLISECONDS..MAX_MILLISECONDS

    /** The text for a representable instant (callers check [isRepresentable] first). */
    fun format(instant: Instant): String {
        val total = milliseconds(instant)
        val days = CivilCalendar.floorDiv(total, MILLIS_PER_DAY)
        val millisOfDay = total - days * MILLIS_PER_DAY
        val date = CivilCalendar.civilFromDays(days)
        val hour = millisOfDay / MILLIS_PER_HOUR
        val minute = millisOfDay / MILLIS_PER_MINUTE % MINUTES_PER_HOUR
        val second = millisOfDay / MILLIS_PER_SECOND % SECONDS_PER_MINUTE
        val milli = millisOfDay % MILLIS_PER_SECOND
        return "${pad(date.year, YEAR_DIGITS)}-${pad(date.month.toLong(), 2)}-${pad(date.day.toLong(), 2)}T" +
            "${pad(hour, 2)}:${pad(minute, 2)}:${pad(second, 2)}.${pad(milli, MILLISECOND_DIGITS)}Z"
    }

    /** `null` unless [text] has exactly the shape above with every field in range. */
    fun parse(text: String): Instant? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val fields = if (bytes.size == TEXT_LENGTH && hasSeparators(bytes)) readFields(bytes) else null
        return fields?.takeIf { isInRange(it) }?.let { instant(toMilliseconds(it)) }
    }

    private fun isInRange(fields: Map<DateField, Long>): Boolean {
        val year = fields.getValue(DateField.YEAR)
        val month = fields.getValue(DateField.MONTH)
        val timeInRange = fields.getValue(DateField.HOUR) < HOURS_PER_DAY &&
            fields.getValue(DateField.MINUTE) < MINUTES_PER_HOUR &&
            fields.getValue(DateField.SECOND) < SECONDS_PER_MINUTE
        val dayInRange = fields.getValue(DateField.DAY) in 1..CivilCalendar.daysInMonth(year, month)
        return month in 1..MONTHS_PER_YEAR && timeInRange && dayInRange
    }

    private fun toMilliseconds(fields: Map<DateField, Long>): Long {
        val days = CivilCalendar.daysFromCivil(
            fields.getValue(DateField.YEAR),
            fields.getValue(DateField.MONTH),
            fields.getValue(DateField.DAY)
        )
        return days * MILLIS_PER_DAY + fields.getValue(DateField.HOUR) * MILLIS_PER_HOUR +
            fields.getValue(DateField.MINUTE) * MILLIS_PER_MINUTE +
            fields.getValue(DateField.SECOND) * MILLIS_PER_SECOND + fields.getValue(DateField.MILLISECOND)
    }
}

private fun hasSeparators(bytes: ByteArray): Boolean =
    separators.all { (position, expected) -> bytes[position].toInt() == expected.code }

/** The seven numbers of the text, or `null` when any of them holds a byte that is not an ASCII digit. */
private fun readFields(bytes: ByteArray): Map<DateField, Long>? = buildMap {
    for (field in DateField.entries) put(field, digits(bytes, field.range) ?: return null)
}

/** The number written in [range], or `null` unless every byte there is an ASCII digit. */
private fun digits(bytes: ByteArray, range: IntRange): Long? {
    val text = String(bytes, range.first, range.last - range.first + 1, Charsets.US_ASCII)
    return if (text.all { it in '0'..'9' }) text.toLong() else null
}

private fun pad(value: Long, width: Int): String = value.toString().padStart(width, '0')
