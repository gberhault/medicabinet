package com.medicabinet.core

import com.medicabinet.core.testing.SharedFixtures
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Runs every case of the repository-wide `fixtures/expiry-dates.json`, which the Swift core also
 * runs. A case added there runs here with no code change.
 */
class ExpiryDateFixtureTest {
    @Test
    fun theSharedFixtureLoadsAndHasCases() {
        val fixture = SharedFixtures.expiryDates()
        assertEquals(1, fixture.formatVersion)
        assertTrue(fixture.cases.size >= 30, "expected at least 30 cases, found ${fixture.cases.size}")
    }

    @TestFactory
    fun caseMatchesTheSharedExpectation(): List<DynamicTest> = SharedFixtures.expiryDates().cases.map { case ->
        dynamicTest(case.name) {
            val expect = case.expect
            val error = ExpiryDate.validate(case.year, case.month, case.day)
            if (expect.error != null) {
                assertEquals(expect.error, error?.kind, "expected error ${expect.error}")
            } else {
                assertNull(error, "unexpected error $error")
                val date = ExpiryDate.of(case.year, case.month, case.day)
                assertEquals(expect.lastValidDay, date.lastValidDay)
                assertEquals(expect.sortKey, date.sortKey)
                assertEquals(expect.monthPrecision, date.isMonthPrecision)
            }
        }
    }

    @Test
    fun errorKindsUseThePlatformNeutralNames() {
        assertEquals("invalidYear", ExpiryDateError.InvalidYear(1).kind)
        assertEquals("invalidMonth", ExpiryDateError.InvalidMonth(1).kind)
        assertEquals("invalidDay", ExpiryDateError.InvalidDay(1, month = 1, year = 2027).kind)
    }
}
