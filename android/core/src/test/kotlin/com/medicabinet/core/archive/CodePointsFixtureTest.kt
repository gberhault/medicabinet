package com.medicabinet.core.archive

import com.medicabinet.core.testing.SharedFixtures
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Runs every case of `fixtures/archive-code-points.json`, which the Swift core also runs. */
class CodePointsFixtureTest {
    @Test
    fun theSharedFixtureLoadsAndHasCases() {
        val fixture = SharedFixtures.archiveCodePoints()
        assertEquals(1, fixture.formatVersion)
        assertTrue(fixture.cases.size >= 10, "expected at least 10 cases, found ${fixture.cases.size}")
    }

    @TestFactory
    fun lengthIsCountedInCodePoints(): List<DynamicTest> = SharedFixtures.archiveCodePoints().cases.map { case ->
        dynamicTest(case.name) { assertEquals(case.codePoints, codePointCount(case.text)) }
    }
}
