package com.medicabinet.app.app

import app.cash.turbine.test
import com.medicabinet.core.testing.MutableClock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DayTickerTest {
    private val clock = MutableClock(Instant.parse("2027-01-15T23:30:00Z"))

    @Test
    fun todayIsTheClocksDateInTheZone() {
        assertEquals("2027-01-15", DayTicker(clock, ZoneOffset.UTC).today().toString())
        assertEquals("2027-01-16", DayTicker(clock, ZoneOffset.ofHours(2)).today().toString())
    }

    @Test
    fun emitsTodayThenTheNextDayAfterMidnight() = runTest {
        val ticker = DayTicker(clock, ZoneOffset.UTC)
        ticker.days().test {
            assertEquals("2027-01-15", awaitItem().toString())
            clock.advance(Duration.ofMinutes(31))
            testScheduler.advanceTimeBy(Duration.ofMinutes(31).toMillis())
            assertEquals("2027-01-16", awaitItem().toString())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aTimeZoneChangeShowsTheNewDayAtOnce() = runTest {
        var zone: ZoneId = ZoneOffset.UTC
        val changes = MutableSharedFlow<Unit>()
        DayTicker(clock, { zone }, changes).days().test {
            assertEquals("2027-01-15", awaitItem().toString())
            zone = ZoneOffset.ofHours(2)
            changes.emit(Unit)
            assertEquals("2027-01-16", awaitItem().toString())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aManualClockChangeShowsTheNewDayAtOnce() = runTest {
        val changes = MutableSharedFlow<Unit>()
        DayTicker(clock, { ZoneOffset.UTC }, changes).days().test {
            assertEquals("2027-01-15", awaitItem().toString())
            clock.advance(Duration.ofDays(3))
            changes.emit(Unit)
            assertEquals("2027-01-18", awaitItem().toString())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aChangeThatKeepsTheSameDayEmitsNothing() = runTest {
        val changes = MutableSharedFlow<Unit>()
        DayTicker(clock, { ZoneOffset.UTC }, changes).days().test {
            assertEquals("2027-01-15", awaitItem().toString())
            changes.emit(Unit)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
