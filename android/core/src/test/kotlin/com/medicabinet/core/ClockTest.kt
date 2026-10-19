package com.medicabinet.core

import com.medicabinet.core.testing.MutableClock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClockTest {
    private val start = Instant.ofEpochSecond(1_800_000_000)

    @Test
    fun fixedClockAlwaysReturnsItsInstant() {
        val clock = FixedClock(start)
        assertEquals(start, clock.now())
        assertEquals(start, clock.now())
    }

    @Test
    fun fixedClockAdvances() {
        val clock = FixedClock(Instant.ofEpochSecond(0))
        clock.advance(Duration.ofDays(1))
        assertEquals(Instant.ofEpochSecond(86_400), clock.now())
    }

    @Test
    fun mutableClockAdvancesAndIsSharedByReference() {
        val clock = MutableClock(start)
        val view: Clock = clock
        clock.advance(Duration.ofSeconds(60))
        assertEquals(start.plusSeconds(60), view.now())
    }

    @Test
    fun clocksAreUsableThroughTheInterface() {
        val clock: Clock = FixedClock(Instant.ofEpochSecond(42))
        assertEquals(42, clock.now().epochSecond)
    }

    @Test
    fun systemClockIsNonDecreasing() {
        val first = SystemClock.now()
        val second = SystemClock.now()
        assertTrue(second >= first)
    }

    @Test
    fun systemClockIsCloseToWallTime() {
        val difference = Duration.between(SystemClock.now(), Instant.now()).abs()
        assertTrue(difference < Duration.ofSeconds(5))
    }
}
