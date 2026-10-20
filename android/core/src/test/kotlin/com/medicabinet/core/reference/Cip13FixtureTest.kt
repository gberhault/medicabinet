package com.medicabinet.core.reference

import com.medicabinet.core.testing.SharedFixtures
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** [Cip13.fromGtin] against `fixtures/reference-lookup.json`, which the Swift core runs too. */
class Cip13FixtureTest {
    @Test
    fun theSharedFixtureLoadsAndHasCases() {
        assertTrue(SharedFixtures.referenceLookup().size >= MIN_CASES)
    }

    @TestFactory
    fun conversionMatchesTheSharedExpectation(): List<DynamicTest> = SharedFixtures.referenceLookup().map { case ->
        dynamicTest(case.name) { assertEquals(case.expect.cip13, Cip13.fromGtin(case.gtin)) }
    }

    @Test
    fun aDataMatrixGtinDropsItsLeadingZero() {
        assertEquals("3400935955838", Cip13.fromGtin("03400935955838"))
    }

    @Test
    fun aWrongCheckDigitIsRefused() {
        assertNull(Cip13.fromGtin("03400935955839"))
    }

    @Test
    fun onlyThe3400PrefixIsAMedicine() {
        assertNull(Cip13.fromGtin("03401935955831"))
        assertNull(Cip13.fromGtin("13400935955835"))
    }

    @Test
    fun nothingIsTrimmedOrCorrected() {
        assertNull(Cip13.fromGtin(" 03400935955838"))
        assertNull(Cip13.fromGtin("03400935955838\n"))
        assertNull(Cip13.fromGtin("3400935955838"))
    }

    private companion object {
        const val MIN_CASES = 16
    }
}
