package com.medicabinet.core

/** Why an expiry date was rejected. [kind] is the platform-neutral name shared with iOS and the fixtures. */
sealed interface ExpiryDateError {
    val kind: String

    data class InvalidYear(val year: Int) : ExpiryDateError {
        override val kind: String get() = "invalidYear"
    }

    data class InvalidMonth(val month: Int) : ExpiryDateError {
        override val kind: String get() = "invalidMonth"
    }

    data class InvalidDay(val day: Int, val month: Int, val year: Int) : ExpiryDateError {
        override val kind: String get() = "invalidDay"
    }
}

class InvalidExpiryDateException(val error: ExpiryDateError) : IllegalArgumentException(error.toString())

/**
 * The expiry printed on a box: day-precise, or month-precise when the day is unknown (GS1 `DD=00`
 * means "end of that month"). Built only through [of], so an invalid date cannot exist — invalid
 * input is rejected, never clamped or guessed.
 */
class ExpiryDate private constructor(
    val year: Int,
    val month: Int,
    /** `null` means month precision. */
    val day: Int?
) : Comparable<ExpiryDate> {
    val isMonthPrecision: Boolean get() = day == null

    /** The last day the box may still be used: its printed day, or the last day of the month. */
    val lastValidDay: Int get() = day ?: daysInMonth(year, month)

    /** `YYYY-MM-DD` of [lastValidDay]; orders dates correctly as text (the DB sort key). */
    val sortKey: String get() = "%04d-%02d-%02d".format(year, month, lastValidDay)

    override fun compareTo(other: ExpiryDate): Int = sortKey.compareTo(other.sortKey)

    override fun equals(other: Any?): Boolean =
        other is ExpiryDate && year == other.year && month == other.month && day == other.day

    override fun hashCode(): Int = (year * 31 + month) * 31 + (day ?: 0)

    override fun toString(): String =
        if (day == null) "%04d-%02d".format(year, month) else "%04d-%02d-%02d".format(year, month, day)

    companion object {
        val SUPPORTED_YEARS = 2000..2099

        /** Validation order: year, then month, then day. */
        fun validate(year: Int, month: Int, day: Int?): ExpiryDateError? = when {
            year !in SUPPORTED_YEARS -> ExpiryDateError.InvalidYear(year)
            month !in 1..MONTHS_IN_YEAR -> ExpiryDateError.InvalidMonth(month)
            day != null && day !in 1..daysInMonth(year, month) -> ExpiryDateError.InvalidDay(day, month, year)
            else -> null
        }

        fun of(year: Int, month: Int, day: Int?): ExpiryDate {
            validate(year, month, day)?.let { throw InvalidExpiryDateException(it) }
            return ExpiryDate(year, month, day)
        }

        private const val MONTHS_IN_YEAR = 12
        private const val FEBRUARY = 2
        private const val LEAP_FEBRUARY = 29
        private const val COMMON_FEBRUARY = 28
        private const val LONG_MONTH = 31
        private const val SHORT_MONTH = 30
        private const val LEAP_CYCLE = 4
        private const val CENTURY = 100
        private const val LEAP_CENTURY_CYCLE = 400
        private val SHORT_MONTHS = setOf(4, 6, 9, 11)

        internal fun daysInMonth(year: Int, month: Int): Int = when {
            month == FEBRUARY -> if (isLeap(year)) LEAP_FEBRUARY else COMMON_FEBRUARY
            month in SHORT_MONTHS -> SHORT_MONTH
            else -> LONG_MONTH
        }

        private fun isLeap(year: Int): Boolean =
            (year % LEAP_CYCLE == 0 && year % CENTURY != 0) || year % LEAP_CENTURY_CYCLE == 0
    }
}
