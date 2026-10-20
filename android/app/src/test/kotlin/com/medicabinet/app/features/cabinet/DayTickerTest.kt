package com.medicabinet.app.features.cabinet

import app.cash.turbine.test
import com.medicabinet.core.testing.MutableClock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
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
}
