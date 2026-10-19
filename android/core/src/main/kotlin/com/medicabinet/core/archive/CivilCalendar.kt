@file:Suppress("MagicNumber") // The constants are those of Howard Hinnant's published civil-calendar algorithms.

package com.medicabinet.core.archive

/** A proleptic Gregorian date. */
internal class CivilDate(val year: Long, val month: Int, val day: Int)

/**
 * The calendar maths behind archive dates, done by hand so both platforms write and accept exactly
 * the same text (no time zone, locale or library behaviour involved).
 */
internal object CivilCalendar {
    fun daysInMonth(year: Long, month: Long): Int = when {
        month == 2L -> if (isLeap(year)) 29 else 28
        month == 4L || month == 6L || month == 9L || month == 11L -> 30
        else -> 31
    }

    fun floorDiv(dividend: Long, divisor: Long): Long = Math.floorDiv(dividend, divisor)

    /** Days since 1970-01-01 (`days_from_civil`). */
    fun daysFromCivil(year: Long, month: Long, day: Long): Long {
        val marchBasedYear = if (month <= 2) year - 1 else year
        val era = floorDiv(marchBasedYear, 400)
        val yearOfEra = marchBasedYear - era * 400
        val marchBasedMonth = if (month > 2) month - 3 else month + 9
        val dayOfYear = (153 * marchBasedMonth + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    /** The inverse (`civil_from_days`). */
    fun civilFromDays(days: Long): CivilDate {
        val shiftedDays = days + 719_468
        val era = floorDiv(shiftedDays, 146_097)
        val dayOfEra = shiftedDays - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val marchBasedMonth = (5 * dayOfYear + 2) / 153
        val day = (dayOfYear - (153 * marchBasedMonth + 2) / 5 + 1).toInt()
        val month = (if (marchBasedMonth < 10) marchBasedMonth + 3 else marchBasedMonth - 9).toInt()
        val year = yearOfEra + era * 400 + (if (month <= 2) 1 else 0)
        return CivilDate(year, month, day)
    }

    private fun isLeap(year: Long): Boolean = (year % 4 == 0L && year % 100 != 0L) || year % 400 == 0L
}
