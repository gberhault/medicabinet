package com.medicabinet.core.scan

import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.testing.SharedFixtures
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Runs every case of the repository-wide `fixtures/gs1-datamatrix.json`, which the Swift core also runs.
 * A case added there runs here with no code change.
 */
class GS1ParserFixtureTest {
    @Test
    fun theSharedFixtureLoadsAndHasCases() {
        val fixture = SharedFixtures.gs1Datamatrix()
        assertEquals(1, fixture.formatVersion)
        assertTrue(fixture.cases.size >= 80, "expected at least 80 cases, found ${fixture.cases.size}")
    }

    @TestFactory
    fun caseMatchesTheSharedExpectation(): List<DynamicTest> = SharedFixtures.gs1Datamatrix().cases.map { case ->
        dynamicTest(case.name) {
            val expect = case.expect
            assertEquals(case.shape, GS1Parser.shape(case.payload))
            if (expect.error != null) {
                val failure = assertFailsWith<ScanParseException> { GS1Parser.parse(case.payload) }
                assertEquals(expect.error, failure.error.kind)
            } else {
                val code = GS1Parser.parse(case.payload)
                assertEquals(expect.gtin, code.gtin)
                assertEquals(expect.symbology, code.symbology.rawValue)
                assertEquals(expect.lot, code.lot)
                assertEquals(expect.serial, code.serial)
                assertExpiry(requireNotNull(expect.expiry), code.expiry)
            }
        }
    }

    private fun assertExpiry(expected: JsonElement, actual: ExpiryReading) {
        if (expected is JsonPrimitive) {
            assertEquals("missing", expected.content)
            assertEquals(ExpiryReading.Missing, actual)
            return
        }
        val fields = expected as JsonObject
        val invalid = fields["invalid"]?.jsonPrimitive?.content
        if (invalid != null) {
            assertEquals(ExpiryReading.Invalid(invalid), actual)
            return
        }
        val day = fields.getValue("day").takeUnless { it is JsonNull }?.jsonPrimitive?.int
        val year = fields.getValue("year").jsonPrimitive.int
        val month = fields.getValue("month").jsonPrimitive.int
        val date = ExpiryDate.of(year, month, day)
        assertEquals(ExpiryReading.Date(date), actual)
    }

    @Test
    fun aShapeNeverContainsALotOrSerialValue() {
        for (case in SharedFixtures.gs1Datamatrix().cases) {
            val code = runCatching { GS1Parser.parse(case.payload) }.getOrNull() ?: continue
            for (value in listOfNotNull(code.lot, code.serial).filter { it.length > 3 }) {
                assertFalse(value in case.shape, case.name)
            }
        }
    }
}
