package com.medicabinet.core.scan

import com.medicabinet.core.Box
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.testing.IntakeCase
import com.medicabinet.core.testing.SharedFixtures
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

/** Runs every case of the repository-wide `fixtures/scan-intake.json`, which the Swift core also runs. */
class IntakePolicyFixtureTest {
    private fun code(scan: IntakeCase.Scan): ScannedCode {
        val expiry = when (scan.expiry) {
            "known" -> ExpiryReading.Date(ExpiryDate.of(2027, 3, null))
            "missing" -> ExpiryReading.Missing
            else -> ExpiryReading.Invalid("invalidMonth")
        }
        return ScannedCode(
            gtin = "03400930000014",
            expiry = expiry,
            lot = null,
            serial = if (scan.hasSerial) "SER1" else null,
            symbology = Symbology.DATA_MATRIX
        )
    }

    private fun box(match: String?): Box? = match?.let {
        Box(
            product = ProductRef("03400930000014", "X"),
            locationId = UUID.randomUUID(),
            serial = "SER1",
            createdAt = Instant.EPOCH,
            removal = if (it == "removed") Box.Removal(RemovalReason.USED_UP, Instant.ofEpochSecond(10)) else null
        )
    }

    @Test
    fun theSharedFixtureLoadsAndHasCases() {
        assertTrue(SharedFixtures.intakeCases().size >= 20)
    }

    @TestFactory
    fun caseMatchesTheSharedExpectation(): List<DynamicTest> = SharedFixtures.intakeCases().map { case ->
        dynamicTest(case.name) {
            val decision = IntakePolicy.decide(
                code(case.scan),
                case.productKnown,
                box(case.cabinetMatch),
                case.sessionHasGTIN
            )
            val expect = case.expect
            assertEquals(expect.decision, decision.kind)
            when (decision) {
                is IntakeDecision.Complete -> {
                    assertEquals(expect.missing?.toSet(), decision.missing.map { it.rawValue }.toSet())
                    assertEquals(expect.expiryUnreadable, decision.expiryUnreadable)
                }
                is IntakeDecision.ConfirmAnother -> {
                    assertEquals(expect.thenMissing?.toSet(), decision.thenMissing.map { it.rawValue }.toSet())
                    assertEquals(expect.expiryUnreadable, decision.expiryUnreadable)
                }
                else -> {
                    assertNull(expect.missing)
                    assertNull(expect.thenMissing)
                }
            }
        }
    }

    @Test
    fun fieldNamesUseThePlatformNeutralNames() {
        assertEquals(listOf("name", "expiry"), IntakeField.entries.map { it.rawValue })
    }
}
