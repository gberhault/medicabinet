package com.medicabinet.core.expiry

import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ExpiryDateError
import com.medicabinet.core.InvalidExpiryDateException
import java.time.Instant
import java.time.ZoneId

/**
 * A calendar date (no time, no zone): what "today" means to every expiry rule. The core never sees a
 * time zone in its rules — the edge turns an instant and a zone into a [CalendarDay] once, then rules
 * use whole-day arithmetic, so both platforms agree to the day. The day arithmetic is the same
 * civil-calendar algorithm as the Swift twin, deliberately not `java.time.LocalDate`.
 */
class CalendarDay internal constructor(val year: Int, val month: Int, val day: Int) : Comparable<CalendarDay> {
    /** Days since 1970-01-01 (negative before). */
    val epochDay: Int
        get() {
            val shifted = if (month <= FEBRUARY) year - 1 else year
            val era = Math.floorDiv(shifted, YEARS_PER_ERA)
            val yearOfEra = shifted - era * YEARS_PER_ERA
            val monthFromMarch = if (month > FEBRUARY) month - MARCH else month + JANUARY_FEBRUARY_SHIFT
            val dayOfYear = (DAYS_PER_FIVE_MONTHS * monthFromMarch + 2) / FIVE_MONTHS + day - 1
            val dayOfEra = yearOfEra * DAYS_PER_YEAR + yearOfEra / LEAP_CYCLE - yearOfEra / CENTURY + dayOfYear
            return era * DAYS_PER_ERA + dayOfEra - EPOCH_SHIFT
        }

    fun plusDays(days: Int): CalendarDay = fromEpochDay(epochDay + days)

    fun firstOfNextMonth(): CalendarDay =
        if (month == DECEMBER) CalendarDay(year + 1, 1, 1) else CalendarDay(year, month + 1, 1)

    override fun compareTo(other: CalendarDay): Int = epochDay.compareTo(other.epochDay)

    override fun equals(other: Any?): Boolean =
        other is CalendarDay && year == other.year && month == other.month && day == other.day

    override fun hashCode(): Int = (year * HASH_PRIME + month) * HASH_PRIME + day

    /** `YYYY-MM-DD`. */
    override fun toString(): String = "%04d-%02d-%02d".format(year, month, day)

    companion object {
        /**
         * Validates month and day-in-month like [ExpiryDate], but accepts any year: a wrong phone clock
         * must never crash.
         */
        fun validate(year: Int, month: Int, day: Int): ExpiryDateError? = when {
            month !in 1..DECEMBER -> ExpiryDateError.InvalidMonth(month)
            day !in 1..ExpiryDate.daysInMonth(year, month) -> ExpiryDateError.InvalidDay(day, month, year)
            else -> null
        }

        fun of(year: Int, month: Int, day: Int): CalendarDay {
            validate(year, month, day)?.let { throw InvalidExpiryDateException(it) }
            return CalendarDay(year, month, day)
        }

        /** `"YYYY-MM-DD"` (ASCII digits, four-digit year), as in the shared fixtures; `null` when malformed. */
        fun parse(iso: String): CalendarDay? {
            val parts = iso.split("-")
            val wellFormed = parts.size == ISO_WIDTHS.size &&
                parts.zip(ISO_WIDTHS).all { (part, width) -> isDigits(part, width) }
            if (!wellFormed) return null
            val (year, month, day) = parts.map { it.toInt() }
            return if (validate(year, month, day) == null) CalendarDay(year, month, day) else null
        }

        private fun isDigits(text: String, width: Int): Boolean = text.length == width && text.all { it in '0'..'9' }

        /** The calendar date of [instant] on a wall clock set to [zone] (Gregorian). */
        fun from(instant: Instant, zone: ZoneId): CalendarDay =
            fromEpochDay(Math.floorDiv(localSeconds(instant, zone), SECONDS_PER_DAY).toInt())

        /** Minutes elapsed since local midnight, wall clock (so 0…1439, even on a DST change day). */
        fun minutesSinceMidnight(instant: Instant, zone: ZoneId): Int {
            val seconds = localSeconds(instant, zone)
            return ((seconds - Math.floorDiv(seconds, SECONDS_PER_DAY) * SECONDS_PER_DAY) / SECONDS_PER_MINUTE).toInt()
        }

        private fun localSeconds(instant: Instant, zone: ZoneId): Long {
            val clamped = instant.epochSecond.coerceIn(-MAX_SECONDS, MAX_SECONDS)
            return clamped + zone.rules.getOffset(instant).totalSeconds
        }

        fun fromEpochDay(epochDay: Int): CalendarDay {
            val shifted = epochDay + EPOCH_SHIFT
            val era = Math.floorDiv(shifted, DAYS_PER_ERA)
            val dayOfEra = shifted - era * DAYS_PER_ERA
            val leapAdjustedDays = dayOfEra - dayOfEra / DAYS_PER_FOUR_YEARS + dayOfEra / DAYS_PER_CENTURY -
                dayOfEra / ERA_LAST_DAY
            val yearOfEra = leapAdjustedDays / DAYS_PER_YEAR
            val dayOfYear = dayOfEra - (DAYS_PER_YEAR * yearOfEra + yearOfEra / LEAP_CYCLE - yearOfEra / CENTURY)
            val monthFromMarch = (FIVE_MONTHS * dayOfYear + 2) / DAYS_PER_FIVE_MONTHS
            val day = dayOfYear - (DAYS_PER_FIVE_MONTHS * monthFromMarch + 2) / FIVE_MONTHS + 1
            val month = if (monthFromMarch < JANUARY_FROM_MARCH) {
                monthFromMarch + MARCH
            } else {
                monthFromMarch - JANUARY_FEBRUARY_SHIFT
            }
            val year = yearOfEra + era * YEARS_PER_ERA + if (month <= FEBRUARY) 1 else 0
            return CalendarDay(year, month, day)
        }

        private const val FEBRUARY = 2
        private const val MARCH = 3
        private const val DECEMBER = 12
        private const val HASH_PRIME = 31
        private val ISO_WIDTHS = listOf(4, 2, 2)

        // Civil-calendar algorithm (March-based years, 400-year eras), same numbers as the Swift twin.
        private const val JANUARY_FEBRUARY_SHIFT = 9
        private const val JANUARY_FROM_MARCH = 10
        private const val YEARS_PER_ERA = 400
        private const val DAYS_PER_ERA = 146_097
        private const val ERA_LAST_DAY = 146_096
        private const val DAYS_PER_YEAR = 365
        private const val DAYS_PER_FOUR_YEARS = 1460
        private const val DAYS_PER_CENTURY = 36_524
        private const val LEAP_CYCLE = 4
        private const val CENTURY = 100
        private const val EPOCH_SHIFT = 719_468
        private const val FIVE_MONTHS = 5
        private const val DAYS_PER_FIVE_MONTHS = 153

        private const val SECONDS_PER_DAY = 86_400L
        private const val SECONDS_PER_MINUTE = 60L

        /** About the year 5138: far beyond any real clock, small enough that `Long` arithmetic never overflows. */
        private const val MAX_SECONDS = 100_000_000_000L
    }
}

/** The last day the box may still be used, as a calendar day. */
val ExpiryDate.lastValidCalendarDay: CalendarDay
    get() = CalendarDay(year, month, lastValidDay)
