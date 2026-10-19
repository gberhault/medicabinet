package com.medicabinet.core.expiry

import com.medicabinet.core.testing.SharedFixtures
import com.medicabinet.core.testing.expiryStatus
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Runs every case of `fixtures/expiry-status.json`, which the Swift core also runs. */
class ExpiryRuleFixtureTest {
    @Test
    fun theSharedFixtureLoadsAndHasCases() {
        val fixture = SharedFixtures.expiryStatus()
        assertEquals(1, fixture.formatVersion)
        assertTrue(fixture.cases.size >= 30, "expected at least 30 cases, found ${fixture.cases.size}")
        assertTrue(fixture.todayCases.size >= 10, "expected at least 10 today cases, found ${fixture.todayCases.size}")
    }

    @TestFactory
    fun statusMatchesTheSharedExpectation(): List<DynamicTest> = SharedFixtures.expiryStatus().cases.map { case ->
        dynamicTest(case.name) {
            val today = assertNotNull(CalendarDay.parse(case.today))
            val window = assertNotNull(ExpiryWindow.entries.firstOrNull { it.days == case.windowDays })

            val assessment = ExpiryRule.assess(case.expiry?.expiryDate(), today, window)

            assertEquals(case.expect.status, assessment.status.rawValue)
            assertEquals(case.expect.daysLeft, assessment.daysLeft)
        }
    }

    @TestFactory
    fun todayMatchesTheSharedExpectation(): List<DynamicTest> = SharedFixtures.expiryStatus().todayCases.map { case ->
        dynamicTest(case.name) {
            val instant = case.instantValue()
            val zone = ZoneId.of(case.timeZone)

            assertEquals(case.expect, CalendarDay.from(instant, zone).toString())
            assertEquals(case.minutesSinceMidnight, CalendarDay.minutesSinceMidnight(instant, zone))
        }
    }
}
