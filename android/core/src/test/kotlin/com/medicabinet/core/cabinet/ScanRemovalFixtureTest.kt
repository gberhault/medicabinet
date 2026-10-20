package com.medicabinet.core.cabinet

import com.medicabinet.core.Box
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.FixedClock
import com.medicabinet.core.Location
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import com.medicabinet.core.scan.ExpiryReading
import com.medicabinet.core.scan.ScannedCode
import com.medicabinet.core.scan.Symbology
import com.medicabinet.core.testing.InMemoryCabinetStore
import com.medicabinet.core.testing.ScanRemovalCase
import com.medicabinet.core.testing.SharedFixtures
import com.medicabinet.core.testing.scanRemoval
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Runs every case of `fixtures/scan-removal.json`, which the Swift core also runs: first the pure policy, then the
 * service against the in-memory store (so the queries and the stock movement are covered too).
 */
class ScanRemovalFixtureTest {
    @Test
    fun theSharedFixtureLoadsAndHasCases() {
        val fixture = SharedFixtures.scanRemoval()
        assertEquals(1, fixture.formatVersion)
        assertTrue(fixture.cases.size >= 15, "expected at least 15 cases, found ${fixture.cases.size}")
    }

    @TestFactory
    fun thePolicyMatchesTheSharedExpectation(): List<DynamicTest> = SharedFixtures.scanRemoval().cases.map { case ->
        dynamicTest("policy: ${case.name}") {
            val boxes = case.cabinet.map { makeBox(it, UUID.randomUUID()) }
            val live = case.cabinet.zip(boxes).filter { it.first.state != "deleted" }.map { it.second }
            val code = scannedCode(case.scan)
            val serialMatch = live.firstOrNull {
                code.serial != null && it.product.gtin == code.gtin && it.serial == code.serial
            }
            val cabinet = live.filter { it.isInCabinet && it.product.gtin == code.gtin }

            val decision = RemovalPolicy.decide(code, serialMatch, cabinet)

            assertEquals(case.expect.decision, decision.kind)
            when (decision) {
                is RemovalDecision.Remove -> assertEquals(case.expect.box, label(decision.id))
                is RemovalDecision.AlreadyRemoved -> assertEquals(case.expect.box, label(decision.id))
                is RemovalDecision.Choose -> assertEquals(case.expect.candidates, decision.ids.map(::label))
                RemovalDecision.NotInCabinet -> Unit
            }
        }
    }

    @TestFactory
    fun theServiceMatchesTheSharedExpectationAndWritesOnlyWhatItShould(): List<DynamicTest> =
        SharedFixtures.scanRemoval().cases.map { case ->
            dynamicTest("service: ${case.name}") { runBlocking { serve(case) } }
        }

    private suspend fun serve(case: ScanRemovalCase) {
        val clock = FixedClock(Instant.ofEpochSecond(START))
        val store = InMemoryCabinetStore(clock)
        val location = Location(name = "Bathroom", isDefault = true, createdAt = clock.now())
        store.insert(location)
        for (fixtureBox in case.cabinet) {
            val box = makeBox(fixtureBox, location.id)
            store.insert(box)
            when (fixtureBox.state) {
                "removed" -> store.markRemoved(box.id, RemovalReason.THROWN_AWAY, Instant.ofEpochSecond(REMOVED_AT))
                "deleted" -> store.delete(box.id)
            }
        }
        val before = store.activeBoxes().map { it.id }.toSet()

        val outcome = ScanRemoval(store, clock).handle(scannedCode(case.scan), RemovalReason.USED_UP)
        val after = store.activeBoxes().map { it.id }.toSet()

        when (outcome) {
            is RemovalOutcome.Removed -> {
                assertEquals("remove", case.expect.decision)
                assertEquals(case.expect.box, label(outcome.box.id))
                assertEquals(Box.Removal(RemovalReason.USED_UP, clock.now()), outcome.box.removal)
                assertEquals(before - outcome.box.id, after)
            }
            is RemovalOutcome.Choose -> {
                assertEquals("choose", case.expect.decision)
                assertEquals(case.expect.candidates, outcome.candidates.map { label(it.id) })
                assertEquals(before, after)
            }
            is RemovalOutcome.AlreadyRemoved -> {
                assertEquals("alreadyRemoved", case.expect.decision)
                assertEquals(case.expect.box, label(outcome.box.id))
                assertEquals(before, after)
            }
            RemovalOutcome.NotInCabinet -> {
                assertEquals("notInCabinet", case.expect.decision)
                assertEquals(before, after)
            }
        }
    }

    private companion object {
        const val START = 1_800_000_000L
        const val REMOVED_AT = 5L
        const val ID_DIGITS = 12

        /** `bN` → UUID ending in N, so id order is numeric (the fixture's tie-break rule). */
        fun boxId(label: String): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(label.drop(1).toInt()))

        fun label(id: UUID): String = "b" + id.toString().takeLast(ID_DIGITS).toInt()

        fun makeBox(fixtureBox: ScanRemovalCase.FixtureBox, location: UUID) = Box(
            id = boxId(fixtureBox.id),
            product = ProductRef(fixtureBox.gtin, "Doliprane"),
            locationId = location,
            expiry = fixtureBox.expiry?.let { ExpiryDate.of(it.year, it.month, it.day) },
            serial = fixtureBox.serial,
            createdAt = Instant.ofEpochSecond(fixtureBox.createdOrder.toLong()),
            removal = if (fixtureBox.state == "removed") {
                Box.Removal(RemovalReason.THROWN_AWAY, Instant.ofEpochSecond(REMOVED_AT))
            } else {
                null
            }
        )

        fun scannedCode(scan: ScanRemovalCase.Scan) = ScannedCode(
            gtin = scan.gtin,
            expiry = scan.expiry?.let { ExpiryReading.Date(ExpiryDate.of(it.year, it.month, it.day)) }
                ?: ExpiryReading.Missing,
            lot = null,
            serial = scan.serial,
            symbology = Symbology.DATA_MATRIX
        )
    }
}

class ScanRemovalTest {
    private val clock = FixedClock(Instant.ofEpochSecond(1_800_000_000))
    private val store = InMemoryCabinetStore(clock)
    private val removal = ScanRemoval(store, clock)
    private val location = Location(name = "Bathroom", isDefault = true, createdAt = clock.now())
    private val code = ScannedCode("03400930000014", ExpiryReading.Missing, null, null, Symbology.EAN_13)

    private fun box(serial: String? = null) = Box(
        product = ProductRef("03400930000014", "Doliprane"),
        locationId = location.id,
        serial = serial,
        createdAt = Instant.ofEpochSecond(10)
    )

    @Test
    fun removingThenUndoingPutsTheBoxBackAsItWas() = runBlocking {
        store.insert(location)
        val original = box()
        store.insert(original)

        val removed = (removal.handle(code, RemovalReason.THROWN_AWAY) as RemovalOutcome.Removed).box
        assertEquals(RemovalReason.THROWN_AWAY, removed.removal?.reason)
        assertTrue(store.activeBoxes().isEmpty())

        removal.undo(removed.id)
        val restored = assertNullable(store.box(original.id))
        assertNull(restored.removal)
        assertEquals(original.product, restored.product)
        assertEquals(original.locationId, restored.locationId)
    }

    @Test
    fun pickingAmongCandidatesRemovesOnlyThePickedBox() = runBlocking {
        store.insert(location)
        val first = box("S1")
        val second = box("S2")
        store.insert(first)
        store.insert(second)

        val outcome = removal.handle(code, RemovalReason.USED_UP) as RemovalOutcome.Choose
        assertEquals(2, outcome.candidates.size)
        assertEquals(second.id, removal.remove(second.id, RemovalReason.USED_UP).id)
        assertEquals(listOf(first.id), store.activeBoxes().map { it.id })
    }

    @Test
    fun removingAnUnknownBoxThrowsNotFound() = runBlocking {
        val failure = assertFailsWith<RepositoryException> { removal.remove(UUID.randomUUID(), RemovalReason.USED_UP) }
        assertEquals(RepositoryError.NOT_FOUND, failure.error)
    }

    @Test
    fun decisionKindsUseThePlatformNeutralNames() {
        assertEquals("remove", RemovalDecision.Remove(UUID.randomUUID()).kind)
        assertEquals("choose", RemovalDecision.Choose(emptyList()).kind)
        assertEquals("alreadyRemoved", RemovalDecision.AlreadyRemoved(UUID.randomUUID()).kind)
        assertEquals("notInCabinet", RemovalDecision.NotInCabinet.kind)
    }

    private fun assertNullable(box: Box?): Box = box ?: error("box expected")
}
