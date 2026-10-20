package com.medicabinet.core.expiry

import com.medicabinet.core.testing.SharedFixtures
import com.medicabinet.core.testing.expiryDigest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Runs every case of `fixtures/expiry-digest.json`, which the Swift core also runs. */
class ExpiryDigestFixtureTest {
    @Test
    fun theSharedFixtureLoadsAndHasCases() {
        val fixture = SharedFixtures.expiryDigest()
        assertEquals(1, fixture.formatVersion)
        assertTrue(fixture.cases.size >= 15, "expected at least 15 cases, found ${fixture.cases.size}")
        assertTrue(fixture.scheduleCases.size >= 10, "expected at least 10 schedule cases")
    }

    @TestFactory
    fun countsMatchTheSharedExpectation(): List<DynamicTest> = SharedFixtures.expiryDigest().cases.map { case ->
        dynamicTest(case.name) {
            val day = assertNotNull(CalendarDay.parse(case.digestDay))
            val window = assertNotNull(ExpiryWindow.entries.firstOrNull { it.days == case.windowDays })
            val location = UUID.randomUUID()

            val counts = ExpiryDigest.counts(case.boxes.map { it.box(location) }, day, window)

            if (case.expect == null) {
                assertNull(counts)
            } else {
                assertEquals(DigestCounts(case.expect.expired, case.expect.expiringSoon), counts)
            }
        }
    }

    @TestFactory
    fun scheduleMatchesTheSharedExpectation(): List<DynamicTest> =
        SharedFixtures.expiryDigest().scheduleCases.map { case ->
            dynamicTest(case.name) {
                val today = assertNotNull(CalendarDay.parse(case.today))

                val upcoming = ExpiryDigest.upcoming(today, case.minutesSinceMidnight, case.count)
                val due = ExpiryDigest.due(today, case.minutesSinceMidnight)

                assertEquals(case.expect.upcoming, upcoming.map { it.toString() })
                assertEquals(case.expect.due, due.toString())
            }
        }

    @Test
    fun namedConstantsMatchTheFixtureDescription() {
        assertEquals(1, ExpiryDigest.DAY_OF_MONTH)
        assertEquals(540, ExpiryDigest.MINUTES_SINCE_MIDNIGHT)
        assertEquals(12, ExpiryDigest.SCHEDULED_AHEAD)
    }

    @Test
    fun aNegativeCountYieldsNoDigests() {
        assertTrue(ExpiryDigest.upcoming(CalendarDay.of(2027, 3, 15), 0, -3).isEmpty())
    }
}
