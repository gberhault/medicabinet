package com.medicabinet.app.features.cabinet

import com.medicabinet.core.Clock
import com.medicabinet.core.expiry.CalendarDay
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

private const val MINUTES_PER_DAY = 24 * 60
private const val MILLIS_PER_MINUTE = 60_000L

/**
 * "Today" for the expiry rule: the injected clock's instant on the device's calendar. [days] emits today when it is
 * collected and again each time the date changes, so statuses are re-evaluated at midnight while a screen is visible.
 */
class DayTicker(private val clock: Clock, private val zone: ZoneId) {
    fun today(): CalendarDay = CalendarDay.from(clock.now(), zone)

    fun days(): Flow<CalendarDay> = flow {
        while (true) {
            val now = clock.now()
            emit(CalendarDay.from(now, zone))
            // Minute precision: wakes at most a minute after midnight, then (if early) simply waits again.
            delay((MINUTES_PER_DAY - CalendarDay.minutesSinceMidnight(now, zone)) * MILLIS_PER_MINUTE)
        }
    }.distinctUntilChanged()
}
