package com.medicabinet.app.reminders

import com.medicabinet.core.expiry.CalendarDay
import com.medicabinet.core.expiry.ExpiryDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** When the next digest alarm must go off. Pure: the clock, the zone and the stored month are arguments. */
object DigestTiming {
    private const val SECONDS_PER_MINUTE = 60

    /**
     * [now] when this month's digest was missed (the phone was off or the app was killed on the 1st) and not yet
     * delivered, which [lastDigestMonth] tells; else the next 1st at 09:00 on the wall clock of [zone], so a change of
     * time zone moves the digest to 09:00 in the new one.
     */
    fun nextTrigger(now: Instant, zone: ZoneId, lastDigestMonth: String?): Instant {
        val today = CalendarDay.from(now, zone)
        val minutes = CalendarDay.minutesSinceMidnight(now, zone)
        val due = ExpiryDigest.due(today, minutes)
        val dueIsThisMonth = due.year == today.year && due.month == today.month
        if (dueIsThisMonth && DigestMonth.key(due) != lastDigestMonth) return now
        val next = ExpiryDigest.upcoming(today, minutes, count = 1).single()
        val time = LocalTime.ofSecondOfDay((ExpiryDigest.MINUTES_SINCE_MIDNIGHT * SECONDS_PER_MINUTE).toLong())
        return LocalDate.of(next.year, next.month, next.day).atTime(time).atZone(zone).toInstant()
    }
}
