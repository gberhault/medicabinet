package com.medicabinet.core.archive

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class ArchiveDatesTest {
    @TestFactory
    fun knownInstantsParseAndFormatExactly(): List<DynamicTest> = listOf(
        "1970-01-01T00:00:00.000Z" to 0L,
        "1970-01-01T00:00:00.001Z" to 1L,
        "1969-12-31T23:59:59.999Z" to -1L,
        "2026-10-19T08:15:30.123Z" to 1_792_397_730_123L,
        "2000-02-29T12:00:00.000Z" to 951_825_600_000L,
        "0000-01-01T00:00:00.000Z" to -62_167_219_200_000L,
        "9999-12-31T23:59:59.999Z" to 253_402_300_799_999L,
        "1900-03-01T00:00:00.000Z" to -2_203_891_200_000L
    ).map { (text, milliseconds) ->
        dynamicTest(text) {
            val instant = assertNotNull(ArchiveDates.parse(text))
            assertEquals(milliseconds, ArchiveDates.milliseconds(instant))
            assertEquals(text, ArchiveDates.format(instant))
        }
    }

    @TestFactory
    fun textOutsideTheExactShapeIsRefused(): List<DynamicTest> = listOf(
        "", "2026-10-19", "2026-10-19T08:15:30Z", "2026-10-19T08:15:30.123", "2026-10-19T08:15:30.123+02:00",
        "2026-10-19t08:15:30.123Z", "2026-10-19T08:15:30.123z", "2026-10-19 08:15:30.123Z",
        "2026/10/19T08:15:30.123Z", "2026-10-19T08-15-30.123Z", "2026-10-19T08:15:30,123Z",
        "2026-13-19T08:15:30.123Z", "2026-00-19T08:15:30.123Z", "2026-10-32T08:15:30.123Z",
        "2026-10-00T08:15:30.123Z", "2026-02-30T08:15:30.123Z", "2027-02-29T08:15:30.123Z",
        "1900-02-29T08:15:30.123Z", "2026-04-31T08:15:30.123Z",
        "2026-10-19T24:00:00.000Z", "2026-10-19T08:60:00.000Z", "2026-10-19T08:15:60.000Z",
        "2026-1a-19T08:15:30.123Z", "2026-10-19T08:15:30.12٣Z", "+026-10-19T08:15:30.123Z",
        " 2026-10-19T08:15:30.123Z", "2026-10-19T08:15:30.123Z ",
        // a non-digit in every numeric field
        "x026-10-19T08:15:30.123Z", "2026-x0-19T08:15:30.123Z", "2026-10-x9T08:15:30.123Z",
        "2026-10-19Tx8:15:30.123Z", "2026-10-19T08:x5:30.123Z", "2026-10-19T08:15:x0.123Z",
        "2026-10-19T08:15:30.12xZ", "2026-1/-19T08:15:30.123Z", "2026-1:-19T08:15:30.123Z"
    ).map { text -> dynamicTest("'$text'") { assertNull(ArchiveDates.parse(text)) } }

    @Test
    fun leapDaysFollowTheGregorianRule() {
        assertNotNull(ArchiveDates.parse("2000-02-29T00:00:00.000Z"))
        assertNotNull(ArchiveDates.parse("2024-02-29T00:00:00.000Z"))
        assertNull(ArchiveDates.parse("1900-02-29T00:00:00.000Z"))
        assertNull(ArchiveDates.parse("2100-02-29T00:00:00.000Z"))
        assertNotNull(ArchiveDates.parse("2026-02-28T00:00:00.000Z"))
        assertNotNull(ArchiveDates.parse("2026-04-30T00:00:00.000Z"))
        assertNotNull(ArchiveDates.parse("2026-12-31T00:00:00.000Z"))
    }

    @Test
    fun everyDayOfARangeOfYearsRoundTrips() {
        var milliseconds = ArchiveDates.MIN_MILLISECONDS
        var last = ""
        while (milliseconds < 4_102_444_800_000L) { // up to 2100-01-01, in steps of 37 days 11 minutes
            val text = ArchiveDates.format(ArchiveDates.instant(milliseconds))
            assertTrue(last.isEmpty() || text > last)
            last = text
            assertEquals(milliseconds, ArchiveDates.milliseconds(assertNotNull(ArchiveDates.parse(text))))
            milliseconds += 37 * 86_400_000L + 11 * 60_000L + 7
        }
    }

    @Test
    fun subMillisecondNoiseRoundsToTheNearestMillisecond() {
        assertEquals(1000, ArchiveDates.milliseconds(Instant.ofEpochSecond(1, 499_900)))
        assertEquals("1970-01-01T00:00:01.000Z", ArchiveDates.format(Instant.ofEpochSecond(1, 499_900)))
        assertEquals(1001, ArchiveDates.milliseconds(Instant.ofEpochSecond(1, 500_100)))
    }

    @Test
    fun datesOutsideTheFourDigitYearRangeAreNotRepresentable() {
        assertTrue(ArchiveDates.isRepresentable(ArchiveDates.instant(ArchiveDates.MIN_MILLISECONDS)))
        assertTrue(ArchiveDates.isRepresentable(ArchiveDates.instant(ArchiveDates.MAX_MILLISECONDS)))
        assertFalse(ArchiveDates.isRepresentable(ArchiveDates.instant(ArchiveDates.MIN_MILLISECONDS - 1)))
        assertFalse(ArchiveDates.isRepresentable(ArchiveDates.instant(ArchiveDates.MAX_MILLISECONDS + 1)))
        assertFalse(ArchiveDates.isRepresentable(Instant.MIN))
        assertFalse(ArchiveDates.isRepresentable(Instant.MAX))
        assertFalse(ArchiveDates.isRepresentable(Instant.ofEpochSecond(-1_000_000_000_000_000L)))
        assertFalse(ArchiveDates.isRepresentable(Instant.ofEpochSecond(1_000_000_000_000_000L)))
    }
}
