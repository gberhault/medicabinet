package com.medicabinet.core.scan

import com.medicabinet.core.CategoryID
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.Location
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import com.medicabinet.core.reference.ProductLookup
import com.medicabinet.core.reference.ReferenceDataDate
import com.medicabinet.core.reference.ReferenceProduct
import com.medicabinet.core.reference.ReferenceStatus
import com.medicabinet.core.reference.ReferenceUnavailableReason
import com.medicabinet.core.testing.InMemoryCabinetStore
import com.medicabinet.core.testing.InMemoryReferenceData
import com.medicabinet.core.testing.MutableClock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ScanIntakeTest {
    private class StubLookup(
        private val products: Map<String, ReferenceProduct> = emptyMap(),
        private val fails: Boolean = false
    ) : ProductLookup {
        override suspend fun product(gtin: String): ReferenceProduct? {
            if (fails) throw RepositoryException(RepositoryError.NOT_FOUND)
            return products[gtin]
        }
    }

    private val gtin = "03400930000014"
    private val march = ExpiryDate.of(2027, 3, null)
    private val clock = MutableClock(Instant.ofEpochSecond(1_800_000_000))
    private val store = InMemoryCabinetStore(clock)
    private val doliprane = StubLookup(mapOf(gtin to ReferenceProduct("DOLIPRANE")))

    private fun intake(lookup: ProductLookup = StubLookup()) = ScanIntake(store, lookup, clock)

    private fun code(expiry: ExpiryReading = ExpiryReading.Date(march), serial: String? = "SER1") =
        ScannedCode(gtin, expiry, lot = "LOT1", serial = serial, symbology = Symbology.DATA_MATRIX)

    private fun test(body: suspend (Location) -> Unit) = runBlocking { body(store.ensureDefault("Cabinet")) }

    // evaluate

    @Test
    fun aKnownProductWithADateIsReady() = test {
        val outcome = intake(doliprane).evaluate(code(), emptySet())
        val draft = assertIs<IntakeOutcome.Ready>(outcome).draft
        assertEquals("DOLIPRANE", draft.name)
        assertEquals(gtin, draft.gtin)
        assertEquals(march, draft.expiry)
        assertEquals("LOT1", draft.lot)
        assertEquals("SER1", draft.serial)
        assertEquals(CategoryID.UNCATEGORIZED, draft.category)
    }

    @Test
    fun anUnknownProductNeedsAName() = test {
        val outcome = intake().evaluate(code(), emptySet())
        assertEquals(
            IntakeOutcome.NeedsCompletion(
                BoxDraft(gtin, name = null, expiry = march, lot = "LOT1", serial = "SER1"),
                setOf(IntakeField.NAME),
                expiryUnreadable = false
            ),
            outcome
        )
    }

    @Test
    fun aMissingExpiryIsAskedNeverDefaulted() = test {
        val outcome = assertIs<IntakeOutcome.NeedsCompletion>(
            intake(doliprane).evaluate(code(expiry = ExpiryReading.Missing), emptySet())
        )
        assertNull(outcome.draft.expiry)
        assertEquals(setOf(IntakeField.EXPIRY), outcome.missing)
        assertEquals(false, outcome.expiryUnreadable)
    }

    @Test
    fun anInvalidExpiryIsFlaggedUnreadableAndNotPrefilled() = test {
        val outcome = assertIs<IntakeOutcome.NeedsCompletion>(
            intake(doliprane).evaluate(code(expiry = ExpiryReading.Invalid("invalidDay")), emptySet())
        )
        assertNull(outcome.draft.expiry)
        assertEquals(setOf(IntakeField.EXPIRY), outcome.missing)
        assertTrue(outcome.expiryUnreadable)
    }

    @Test
    fun aSerialAlreadyInTheCabinetIsRefused() = test { location ->
        val service = intake(doliprane)
        val box = service.add(BoxDraft(gtin, "DOLIPRANE", serial = "SER1"), location.id)
        assertEquals(IntakeOutcome.AlreadyInCabinet(box), service.evaluate(code(), emptySet()))
    }

    @Test
    fun aRemovedBoxScannedAgainOffersToPutItBack() = test { location ->
        val service = intake(doliprane)
        val box = service.add(BoxDraft(gtin, "DOLIPRANE", serial = "SER1"), location.id)
        store.markRemoved(box.id, RemovalReason.USED_UP, clock.now())
        val found = assertIs<IntakeOutcome.RemovedEarlier>(service.evaluate(code(), emptySet())).box
        assertEquals(box.id, found.id)
    }

    @Test
    fun aDeletedBoxIsForgottenSoTheScanIsNew() = test { location ->
        val service = intake(doliprane)
        val box = service.add(BoxDraft(gtin, "DOLIPRANE", serial = "SER1"), location.id)
        service.delete(box.id)
        assertIs<IntakeOutcome.Ready>(service.evaluate(code(), emptySet()))
    }

    @Test
    fun sameProductWithoutSerialInTheSessionAsksForConfirmation() = test {
        val outcome = intake(doliprane).evaluate(code(serial = null), setOf(gtin))
        assertTrue(assertIs<IntakeOutcome.ConfirmAnother>(outcome).thenMissing.isEmpty())
    }

    @Test
    fun theNameOfAnEarlierBoxIsReusedWhenTheReferenceDoesNotKnowTheProduct() = test { location ->
        val service = intake()
        service.add(
            BoxDraft(gtin, "Mon paracétamol", CategoryID("pain-fever"), categoryOverridden = true, serial = "OLD"),
            location.id
        )
        val draft = assertIs<IntakeOutcome.Ready>(service.evaluate(code(), emptySet())).draft
        assertEquals("Mon paracétamol", draft.name)
        assertEquals(CategoryID("pain-fever"), draft.category)
        assertTrue(draft.categoryOverridden)
    }

    @Test
    fun aFailingLookupLeavesTheProductUnknownInsteadOfStoppingIntake() = test {
        val outcome = intake(StubLookup(fails = true)).evaluate(code(), emptySet())
        assertEquals(setOf(IntakeField.NAME), assertIs<IntakeOutcome.NeedsCompletion>(outcome).missing)
    }

    // the bundled reference data (CIP13 behind the GTIN)

    private fun reference(status: ReferenceStatus = ReferenceStatus.Available(ReferenceDataDate(2026, 10, 18))) =
        InMemoryReferenceData(
            mapOf("3400935955838" to ReferenceProduct("PARACETAMOL EXEMPLE 500 mg", CategoryID("pain-fever"))),
            status
        )

    @Test
    fun aGtinKnownToTheReferenceDataIsNamedAndCategorisedWithoutAsking() = test {
        val outcome = ScanIntake(store, reference(), clock).evaluate(code().copy(gtin = "03400935955838"), emptySet())
        val draft = assertIs<IntakeOutcome.Ready>(outcome).draft
        assertEquals("PARACETAMOL EXEMPLE 500 mg", draft.name)
        assertEquals(CategoryID("pain-fever"), draft.category)
        assertEquals(false, draft.categoryOverridden)
    }

    @Test
    fun aGtinMissingFromTheReferenceDataStillAsksForAName() = test {
        val outcome = ScanIntake(store, reference(), clock).evaluate(code(), emptySet())
        assertEquals(setOf(IntakeField.NAME), assertIs<IntakeOutcome.NeedsCompletion>(outcome).missing)
    }

    @Test
    fun unavailableReferenceDataLeavesEveryProductUnknownInsteadOfStoppingIntake() = test {
        val unavailable = reference(ReferenceStatus.Unavailable(ReferenceUnavailableReason.UNREADABLE))
        val outcome = ScanIntake(store, unavailable, clock).evaluate(code().copy(gtin = "03400935955838"), emptySet())
        assertEquals(setOf(IntakeField.NAME), assertIs<IntakeOutcome.NeedsCompletion>(outcome).missing)
    }

    // add

    @Test
    fun addStoresTheBoxWithTheClockAndTheLocation() = test { location ->
        val draft = BoxDraft(gtin, "  DOLIPRANE  ", CategoryID("pain-fever"), expiry = march, lot = "L", serial = "S")
        val box = intake().add(draft, location.id)
        assertEquals("DOLIPRANE", box.product.name)
        assertEquals(gtin, box.product.gtin)
        assertEquals(location.id, box.locationId)
        assertEquals(clock.now(), box.createdAt)
        assertEquals(clock.now(), box.updatedAt)
        assertEquals(box, store.box(box.id))
    }

    @Test
    fun anUnknownExpiryStaysUnknown() = test { location ->
        val box = intake().add(BoxDraft(name = "Thermomètre"), location.id)
        assertNull(box.expiry)
        assertNull(box.product.gtin)
    }

    @Test
    fun aMissingOrOverlongNameIsRejected() = test { location ->
        for (name in listOf(null, "", "   ", "x".repeat(201))) {
            val failure = assertFailsWith<IntakeException> { intake().add(BoxDraft(name = name), location.id) }
            assertEquals("invalidName", failure.error.kind)
        }
        assertTrue(store.activeBoxes().isEmpty())
    }

    @Test
    fun aNameOfTwoHundredCharactersIsAccepted() = test { location ->
        intake().add(BoxDraft(name = "x".repeat(200)), location.id)
    }

    @Test
    fun addingTheSameSerialTwiceIsRefusedByTheRepository() = test { location ->
        val draft = BoxDraft(gtin, "DOLIPRANE", serial = "SER1")
        intake().add(draft, location.id)
        val failure = assertFailsWith<RepositoryException> { intake().add(draft, location.id) }
        assertEquals(RepositoryError.DUPLICATE_SERIAL, failure.error)
    }

    // put back, delete

    @Test
    fun putBackUndoesTheRemovalAndMovesTheBox() = test { location ->
        val service = intake()
        val other = Location(name = "Kitchen", createdAt = clock.now())
        store.insert(other)
        val box = service.add(BoxDraft(name = "X"), location.id)
        store.markRemoved(box.id, RemovalReason.THROWN_AWAY, clock.now())
        val restored = service.putBack(box, other.id)
        assertNull(restored.removal)
        assertEquals(other.id, restored.locationId)
        assertEquals(listOf(box.id), store.activeBoxes().map { it.id })
    }

    @Test
    fun putBackInTheSameLocationOnlyUndoesTheRemoval() = test { location ->
        val service = intake()
        val box = service.add(BoxDraft(name = "X"), location.id)
        store.markRemoved(box.id, RemovalReason.USED_UP, clock.now())
        val restored = service.putBack(box, location.id)
        assertNull(restored.removal)
        assertEquals(location.id, restored.locationId)
    }

    @Test
    fun deleteAndUndoDeleteRoundTrip() = test { location ->
        val service = intake()
        val box = service.add(BoxDraft(name = "X"), location.id)
        service.delete(box.id)
        assertTrue(store.activeBoxes().isEmpty())
        service.undoDelete(box.id)
        assertEquals(listOf(box.id), store.activeBoxes().map { it.id })
    }

    // manual entry

    @Test
    fun aTypedEanBecomesAGtinAndTakesTheReferenceName() = test {
        val draft = intake(doliprane).manualDraft("3400930000014", null)
        assertEquals(gtin, draft.gtin)
        assertEquals("DOLIPRANE", draft.name)
        assertNull(draft.expiry)
        assertNull(draft.serial)
    }

    @Test
    fun aTypedNameWinsOverTheReferenceName() = test {
        assertEquals("Mon sirop", intake(doliprane).manualDraft("3400930000014", "  Mon sirop ").name)
    }

    @Test
    fun noCodeGivesADraftWithoutGtin() = test {
        for (code in listOf(null, "", "  ")) {
            val draft = intake().manualDraft(code, "Pansements")
            assertNull(draft.gtin)
            assertEquals("Pansements", draft.name)
            assertNull(intake().manualDraft(code, "  ").name)
        }
    }

    @Test
    fun aWrongCodeIsRejected() = test {
        for (code in listOf("3400930000015", "12345", "abc", "034009300000145")) {
            val failure = assertFailsWith<IntakeException> { intake().manualDraft(code, "X") }
            assertEquals("invalidCode", failure.error.kind)
        }
    }

    @Test
    fun intakeErrorsUseThePlatformNeutralNames() {
        assertEquals(listOf("invalidName", "invalidCode"), IntakeError.entries.map { it.kind })
    }
}
