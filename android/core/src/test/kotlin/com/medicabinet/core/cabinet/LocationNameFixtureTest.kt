package com.medicabinet.core.cabinet

import com.medicabinet.core.testing.SharedFixtures
import com.medicabinet.core.testing.locationNames
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Runs every case of `fixtures/location-names.json`, which the Swift core also runs. */
class LocationNameFixtureTest {
    @Test
    fun theSharedFixtureLoadsAndHasCases() {
        val fixture = SharedFixtures.locationNames()
        assertEquals(1, fixture.formatVersion)
        assertTrue(fixture.cases.size >= 15, "expected at least 15 cases, found ${fixture.cases.size}")
    }

    @TestFactory
    fun caseMatchesTheSharedExpectation(): List<DynamicTest> = SharedFixtures.locationNames().cases.map { case ->
        dynamicTest(case.name) {
            if (case.expect.error != null) {
                val failure = assertFailsWith<LocationNameException> {
                    LocationName.validate(case.input, case.existing, case.renaming)
                }
                assertEquals(case.expect.error, failure.error.kind)
            } else {
                assertNull(case.expect.error)
                assertEquals(case.expect.name, LocationName.validate(case.input, case.existing, case.renaming))
            }
        }
    }

    @Test
    fun errorKindsUseThePlatformNeutralNames() {
        assertEquals("empty", LocationNameError.EMPTY.kind)
        assertEquals("tooLong", LocationNameError.TOO_LONG.kind)
        assertEquals("duplicateName", LocationNameError.DUPLICATE_NAME.kind)
    }

    @Test
    fun theLimitFitsTheDatabaseCheck() {
        // v1: location.name CHECK (length(name) BETWEEN 1 AND 100), counted in code points.
        assertTrue(LocationName.MAX_LENGTH <= 100)
    }
}
