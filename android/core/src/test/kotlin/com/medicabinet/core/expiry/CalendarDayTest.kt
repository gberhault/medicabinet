package com.medicabinet.core.expiry

import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ExpiryDateError
import com.medicabinet.core.InvalidExpiryDateException
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CalendarDayTest {
    private fun day(year: Int, month: Int, day: Int) = CalendarDay.of(year, month, day)

    @Test
    fun theEpochIsDayZero() {
        assertEquals(0, day(1970, 1, 1).epochDay)
        assertEquals(1, day(1970, 1, 2).epochDay)
        assertEquals(-1, day(1969, 12, 31).epochDay)
        assertEquals(11_017, day(2000, 3, 1).epochDay)
    }

    @Test
    fun everyDayOfTheSupportedYearsRoundTripsAndIncrementsByOne() {
        var previous = day(2000, 1, 1)
        assertEquals(previous, CalendarDay.fromEpochDay(previous.epochDay))
        while (previous.year < 2100) {
            val next = previous.plusDays(1)
            assertEquals(previous.epochDay + 1, next.epochDay)
            assertEquals(next, CalendarDay.fromEpochDay(next.epochDay))
            assertTrue(next > previous)
            previous = next
        }
        assertEquals(day(2100, 1, 1), previous)
    }

    @Test
    fun wideYearsRoundTripToo() {
        for (year in listOf(1, 1600, 1900, 1969, 2100, 2400, 3000)) {
            val value = day(year, 2, 28)
            assertEquals(value, CalendarDay.fromEpochDay(value.epochDay))
        }
    }

    @Test
    fun addingDaysCrossesMonthsYearsAndLeapDays() {
        assertEquals(day(2027, 2, 1), day(2027, 1, 31).plusDays(1))
        assertEquals(day(2028, 2, 29), day(2028, 2, 28).plusDays(1))
        assertEquals(day(2027, 3, 1), day(2027, 2, 28).plusDays(1))
        assertEquals(day(2028, 1, 1), day(2027, 12, 31).plusDays(1))
        assertEquals(day(2027, 2, 28), day(2027, 3, 1).plusDays(-1))
        assertEquals(day(2027, 3, 15), day(2027, 3, 15).plusDays(0))
    }

    @Test
    fun firstOfNextMonthRollsOverTheYear() {
        assertEquals(day(2027, 4, 1), day(2027, 3, 15).firstOfNextMonth())
        assertEquals(day(2027, 4, 1), day(2027, 3, 31).firstOfNextMonth())
        assertEquals(day(2028, 1, 1), day(2027, 12, 31).firstOfNextMonth())
    }

    @Test
    fun ofValidatesMonthAndDayButAcceptsAnyYear() {
        assertEquals(ExpiryDateError.InvalidMonth(13), failure(2027, 13, 1))
        assertEquals(ExpiryDateError.InvalidMonth(0), failure(2027, 0, 1))
        assertEquals(ExpiryDateError.InvalidDay(31, 4, 2027), failure(2027, 4, 31))
        assertEquals(ExpiryDateError.InvalidDay(29, 2, 2027), failure(2027, 2, 29))
        assertEquals(ExpiryDateError.InvalidDay(0, 2, 2027), failure(2027, 2, 0))
        assertEquals(29, day(2028, 2, 29).day)
        assertEquals(1999, day(1999, 12, 31).year)
        assertEquals(2100, day(2100, 1, 1).year)
    }

    private fun failure(year: Int, month: Int, day: Int): ExpiryDateError =
        assertFailsWith<InvalidExpiryDateException> { CalendarDay.of(year, month, day) }.error

    @Test
    fun parseReadsIsoDatesAndRejectsEverythingElse() {
        assertEquals(day(2027, 3, 15), CalendarDay.parse("2027-03-15"))
        val malformed = listOf("", "2027-3-15", "27-03-15", "2027-03-5", "2027/03/15", "2027-13-01", "2027-02-30")
        for (bad in malformed + listOf("abcd-ef-gh", "2027-03", "2027-03-15-01", "2027-+3-15", "٢٠٢٧-03-15")) {
            assertNull(CalendarDay.parse(bad), "$bad should not parse")
        }
    }

    @Test
    fun toStringIsZeroPaddedIso() {
        assertEquals("2027-03-05", day(2027, 3, 5).toString())
        assertEquals("0999-11-20", day(999, 11, 20).toString())
    }

    @Test
    fun equalityAndHashFollowTheFields() {
        assertEquals(day(2027, 3, 5), day(2027, 3, 5))
        assertEquals(day(2027, 3, 5).hashCode(), day(2027, 3, 5).hashCode())
        assertTrue(day(2027, 3, 5) != day(2027, 3, 6))
        assertTrue(!day(2027, 3, 5).equals("2027-03-05"))
    }

    @Test
    fun expiryDateExposesItsLastValidCalendarDay() {
        assertEquals(day(2027, 4, 30), ExpiryDate.of(2027, 4, null).lastValidCalendarDay)
        assertEquals(day(2028, 2, 29), ExpiryDate.of(2028, 2, null).lastValidCalendarDay)
        assertEquals(day(2027, 4, 7), ExpiryDate.of(2027, 4, 7).lastValidCalendarDay)
    }

    @Test
    fun fromInstantUsesTheZoneWallClock() {
        val instant = Instant.parse("2027-03-31T23:30:00Z")
        val paris = ZoneId.of("Europe/Paris")
        assertEquals("2027-03-31", CalendarDay.from(instant, ZoneId.of("UTC")).toString())
        assertEquals("2027-04-01", CalendarDay.from(instant, paris).toString())
        assertEquals(90, CalendarDay.minutesSinceMidnight(instant, paris))
    }

    @Test
    fun instantsBeforeTheEpochAndAbsurdClocksNeverCrash() {
        val utc = ZoneId.of("UTC")
        val before = Instant.ofEpochSecond(-1)
        assertEquals("1969-12-31", CalendarDay.from(before, utc).toString())
        assertEquals(1_439, CalendarDay.minutesSinceMidnight(before, utc))
        CalendarDay.from(Instant.MAX, utc)
        CalendarDay.from(Instant.MIN, utc)
    }
}
