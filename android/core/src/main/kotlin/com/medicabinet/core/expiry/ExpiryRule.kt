package com.medicabinet.core.expiry

import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ExpiryStatus

private const val SHORT_WINDOW_DAYS = 30
private const val MEDIUM_WINDOW_DAYS = 60
private const val LONG_WINDOW_DAYS = 90

/**
 * How early a box counts as "expiring soon". A closed set of choices keeps the setting simple and the
 * notification text predictable.
 */
enum class ExpiryWindow(val days: Int) {
    DAYS_30(SHORT_WINDOW_DAYS),
    DAYS_60(MEDIUM_WINDOW_DAYS),
    DAYS_90(LONG_WINDOW_DAYS);

    companion object {
        /** Named default of the "expiring soon" setting. */
        val DEFAULT = DAYS_60

        /** A missing or unrecognised stored value falls back to the default, never to a crash. */
        fun fromStoredDays(days: Int?): ExpiryWindow = entries.firstOrNull { it.days == days } ?: DEFAULT
    }
}

data class ExpiryAssessment(
    val status: ExpiryStatus,
    /** Last valid day minus today: 0 on the expiry day, negative once expired. `null` only for UNKNOWN. */
    val daysLeft: Int?
) {
    /** The label "expires today" (still usable, counted as expiring soon). */
    val expiresToday: Boolean get() = daysLeft == 0
}

object ExpiryRule {
    /**
     * The one status rule. A box with no date is UNKNOWN, never OK; the expiry day itself is still
     * usable; month precision runs to the last day of the month.
     */
    fun assess(expiry: ExpiryDate?, today: CalendarDay, window: ExpiryWindow): ExpiryAssessment {
        if (expiry == null) return ExpiryAssessment(ExpiryStatus.UNKNOWN, null)
        val daysLeft = expiry.lastValidCalendarDay.epochDay - today.epochDay
        val status = when {
            daysLeft < 0 -> ExpiryStatus.EXPIRED
            daysLeft <= window.days -> ExpiryStatus.EXPIRING_SOON
            else -> ExpiryStatus.OK
        }
        return ExpiryAssessment(status, daysLeft)
    }
}
