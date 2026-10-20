package com.medicabinet.app.app

import com.medicabinet.core.Clock
import com.medicabinet.core.expiry.CalendarDay
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart

private const val MINUTES_PER_DAY = 24 * 60
private const val MILLIS_PER_MINUTE = 60_000L

/**
 * "Today" for the expiry rule: the injected clock's instant on the device's calendar. [days] emits today when it is
 * collected and again each time the date changes, so statuses are re-evaluated at midnight while a screen is visible.
 *
 * [zone] is read every time, so a time-zone change is picked up; [changes] (the system's time-zone and clock-set
 * broadcasts in the app) makes the ticker look at the clock again at once instead of waiting for the old midnight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DayTicker(
    private val clock: Clock,
    private val zone: () -> ZoneId,
    private val changes: Flow<Unit> = emptyFlow()
) : TodaySource {
    constructor(clock: Clock, zone: ZoneId) : this(clock, { zone })

    fun today(): CalendarDay = CalendarDay.from(clock.now(), zone())

    override fun days(): Flow<CalendarDay> = changes.onStart { emit(Unit) }.flatMapLatest { untilMidnight() }
        .distinctUntilChanged()

    private fun untilMidnight(): Flow<CalendarDay> = flow {
        while (true) {
            val now = clock.now()
            val zone = zone()
            emit(CalendarDay.from(now, zone))
            // Minute precision: wakes at most a minute after midnight, then (if early) simply waits again.
            delay((MINUTES_PER_DAY - CalendarDay.minutesSinceMidnight(now, zone)) * MILLIS_PER_MINUTE)
        }
    }
}
