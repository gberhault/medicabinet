package com.medicabinet.core.expiry

import com.medicabinet.core.Box
import com.medicabinet.core.ExpiryStatus

data class DigestCounts(val expired: Int, val expiringSoon: Int)

/**
 * The monthly reminder: what it says and when it is due. A digest day is the 1st of a month at 09:00
 * local time. Counts are always computed as of the digest day, never of the delivery time, so a late
 * delivery says the same as an on-time one.
 */
object ExpiryDigest {
    const val DAY_OF_MONTH = 1

    /** 09:00 local time. */
    const val MINUTES_SINCE_MIDNIGHT = 9 * 60

    /** How many digests the iOS app keeps scheduled — far below the system's pending-notification cap. */
    const val SCHEDULED_AHEAD = 12

    private const val DECEMBER = 12

    /**
     * `null` when both counts are zero, so no notification is sent that month. Boxes of unknown expiry and
     * boxes no longer in the cabinet are not counted.
     */
    fun counts(boxes: List<Box>, on: CalendarDay, window: ExpiryWindow): DigestCounts? {
        var expired = 0
        var expiringSoon = 0
        for (box in boxes.filter { it.isInCabinet }) {
            when (ExpiryRule.assess(box.expiry, on, window).status) {
                ExpiryStatus.EXPIRED -> expired++
                ExpiryStatus.EXPIRING_SOON -> expiringSoon++
                ExpiryStatus.OK, ExpiryStatus.UNKNOWN -> Unit
            }
        }
        return if (expired == 0 && expiringSoon == 0) null else DigestCounts(expired, expiringSoon)
    }

    /** The next [count] digest days strictly after now ([today] at [minutes] since local midnight). */
    fun upcoming(today: CalendarDay, minutes: Int, count: Int): List<CalendarDay> {
        var next = if (isThisMonthsDigestStillToCome(today, minutes)) {
            CalendarDay(today.year, today.month, DAY_OF_MONTH)
        } else {
            today.firstOfNextMonth()
        }
        val days = mutableListOf<CalendarDay>()
        while (days.size < count) {
            days += next
            next = next.firstOfNextMonth()
        }
        return days
    }

    /** The most recent digest day at or before now: the one a late delivery still owes. */
    fun due(today: CalendarDay, minutes: Int): CalendarDay = when {
        !isThisMonthsDigestStillToCome(today, minutes) -> CalendarDay(today.year, today.month, DAY_OF_MONTH)
        today.month == 1 -> CalendarDay(today.year - 1, DECEMBER, DAY_OF_MONTH)
        else -> CalendarDay(today.year, today.month - 1, DAY_OF_MONTH)
    }

    private fun isThisMonthsDigestStillToCome(today: CalendarDay, minutes: Int): Boolean =
        today.day == DAY_OF_MONTH && minutes < MINUTES_SINCE_MIDNIGHT
}
