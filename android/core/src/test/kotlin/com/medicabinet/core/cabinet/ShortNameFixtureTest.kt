package com.medicabinet.core.cabinet

import com.medicabinet.core.testing.SharedFixtures
import com.medicabinet.core.testing.shortNames
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Runs every case of `fixtures/short-names.json`, which the Swift core also runs. */
class ShortNameFixtureTest {
    @Test
    fun theSharedFixtureLoadsAndHasCases() {
        val fixture = SharedFixtures.shortNames()
        assertEquals(1, fixture.formatVersion)
        assertTrue(fixture.cases.size >= 15, "expected at least 15 cases, found ${fixture.cases.size}")
    }

    @TestFactory
    fun caseMatchesTheSharedExpectation(): List<DynamicTest> = SharedFixtures.shortNames().cases.map { case ->
        dynamicTest(case.name) { assertEquals(case.expect, ShortName.of(case.input)) }
    }
}

class ShortNameTest {
    @Test
    fun shorteningTwiceChangesNothing() {
        for (name in listOf("DOLIPRANE 1000 mg, comprimé", "A,B", ", x", "  ")) {
            val once = ShortName.of(name)
            assertEquals(once, ShortName.of(once))
        }
    }
}
