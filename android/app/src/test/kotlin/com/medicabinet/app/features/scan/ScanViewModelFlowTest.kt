package com.medicabinet.app.features.scan

import app.cash.turbine.test
import com.medicabinet.core.Location
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.reference.ReferenceProduct
import com.medicabinet.core.scan.IntakeField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** What happens to each scan (requirements D, I, K, R and the confirmation of C6). */
class ScanViewModelFlowTest {
    private val unknownProduct = StubLookup()
    private val state get() = kit.model.state.value
    private lateinit var kit: ScanTestKit

    private fun run(lookup: StubLookup? = null, body: suspend () -> Unit) = runBlocking {
        kit = if (lookup == null) ScanTestKit().open() else ScanTestKit(lookup).open()
        body()
    }

    // adding

    @Test
    fun aCompleteCodeOfAKnownProductIsAddedAndConfirmed() = run {
        kit.model.feedback.test {
            kit.see(ScanTestKit.payload())
            assertEquals(FeedbackRequest(FeedbackEvent.ADDED, sound = false), awaitItem())
        }
        val stored = kit.storedBoxes()
        assertEquals(1, stored.size)
        val box = stored.single()
        assertEquals("DOLIPRANE", box.product.name)
        assertEquals(ScanTestKit.date(2027, 3, 31), box.expiry)
        assertEquals("LOT1", box.lot)
        assertEquals("SER1", box.serial)
        assertEquals(kit.defaultLocation.id, box.locationId)
        assertEquals(1, state.session.count)
        assertEquals(Notice.Added(box), state.notice)
    }

    @Test
    fun aBoxHeldInFrontOfTheCameraIsCountedOnce() = run {
        var seconds = 0.0
        while (seconds <= 4.0) {
            kit.see(ScanTestKit.payload(), at = seconds)
            seconds += 0.2
        }
        assertEquals(1, kit.storedBoxes().size)
    }

    @Test
    fun theSameSerialShownAgainIsRefusedWithItsLocation() = run {
        kit.see(ScanTestKit.payload(), at = 0.0)
        kit.see(ScanTestKit.payload(), at = 5.0)
        assertEquals(1, kit.storedBoxes().size)
        assertEquals(Notice.AlreadyInCabinet(kit.storedBoxes().single(), "Cabinet"), state.notice)
    }

    @Test
    fun aRemovedBoxScannedAgainCanBePutBack() = run {
        kit.see(ScanTestKit.payload(), at = 0.0)
        val box = kit.storedBoxes().single()
        kit.store.markRemoved(box.id, RemovalReason.USED_UP, ScanTestKit.START)
        val kitchen = Location(name = "Kitchen", createdAt = ScanTestKit.START)
        kit.store.insert(kitchen)
        kit.model.load()
        kit.model.selectLocation(kitchen.id)
        kit.model.dismissNotice()

        kit.see(ScanTestKit.payload(), at = 5.0)
        val found = assertIs<Notice.RemovedEarlier>(state.notice).box
        assertEquals(box.id, found.id)
        assertTrue(kit.storedBoxes().isEmpty())

        kit.model.putBack(found)
        val restored = kit.storedBoxes().single()
        assertEquals(box.id, restored.id)
        assertEquals(kitchen.id, restored.locationId)
        assertNull(restored.removal)
        assertIs<Notice.PutBack>(state.notice)
    }

    @Test
    fun anUnreadableCodeIsReportedOncePerPayload() = run {
        kit.model.feedback.test {
            kit.see("not a gs1 code", at = 0.0)
            assertEquals(Notice.Unrecognized, state.notice)
            assertEquals(FeedbackEvent.REJECTED, awaitItem().event)
            kit.model.dismissNotice()
            kit.see("not a gs1 code", at = 5.0)
            assertNull(state.notice)
            expectNoEvents()
        }
        assertTrue(kit.storedBoxes().isEmpty())
    }

    // completion

    @Test
    fun anUnknownProductAsksForANameWhileTheBoxIsInHand() = run(unknownProduct) {
        kit.see(ScanTestKit.payload())
        val completion = assertNotNull(state.completion)
        assertEquals(setOf(IntakeField.NAME), completion.missing)
        assertTrue(kit.storedBoxes().isEmpty())

        kit.model.confirmCompletion("  Mon sirop ", null)
        assertNull(state.completion)
        val box = kit.storedBoxes().single()
        assertEquals("Mon sirop", box.product.name)
        assertEquals(ScanTestKit.date(2027, 3, 31), box.expiry)
    }

    @Test
    fun aCodeWithoutExpiryAsksForTheDateAndNeverDefaultsIt() = run {
        kit.see(ScanTestKit.payload(expiry = null))
        val completion = assertNotNull(state.completion)
        assertEquals(setOf(IntakeField.EXPIRY), completion.missing)
        assertEquals(false, completion.expiryUnreadable)
        assertNull(completion.draft.expiry)

        kit.model.confirmCompletion(null, ScanTestKit.date(2028, 2, null))
        assertEquals(ScanTestKit.date(2028, 2, null), kit.storedBoxes().single().expiry)
    }

    @Test
    fun dateNotPrintedSavesAnUnknownExpiry() = run {
        kit.see(ScanTestKit.payload(expiry = null))
        kit.model.confirmCompletion(null, null)
        assertNull(kit.storedBoxes().single().expiry)
        assertEquals(1, state.session.unknownExpiryCount)
    }

    @Test
    fun anImpossibleDateInTheCodeIsFlaggedUnreadableAndNotPrefilled() = run {
        kit.see(ScanTestKit.payload(expiry = "271315"))
        val completion = assertNotNull(state.completion)
        assertTrue(completion.expiryUnreadable)
        assertNull(completion.draft.expiry)
    }

    @Test
    fun cancellingTheCompletionAddsNothing() = run {
        kit.see(ScanTestKit.payload(expiry = null))
        kit.model.cancelCompletion()
        assertNull(state.completion)
        assertTrue(kit.storedBoxes().isEmpty())
        assertEquals(0, state.session.count)
    }

    @Test
    fun aBoxStillInFrontOfTheCameraIsNotRecountedWhenThePromptCloses() = run {
        kit.see(ScanTestKit.payload(expiry = null), at = 0.0)
        assertNotNull(state.completion)
        var seconds = 0.2
        while (seconds <= 6.0) {
            kit.see(ScanTestKit.payload(expiry = null), at = seconds)
            seconds += 0.2
        }
        kit.model.confirmCompletion(null, ScanTestKit.date(2028, 1, 5))
        kit.see(ScanTestKit.payload(expiry = null), at = 6.2)
        assertEquals(1, kit.storedBoxes().size)
        assertNull(state.completion)
    }

    // another box

    @Test
    fun theSameProductWithoutASerialAsksBeforeAddingAnotherBox() = run {
        val noSerial = ScanTestKit.payload(lot = null, serial = null)
        kit.see(noSerial, at = 0.0)
        assertEquals(1, kit.storedBoxes().size)

        kit.see(noSerial, at = 5.0)
        assertNotNull(state.another)
        assertEquals(1, kit.storedBoxes().size)

        kit.model.confirmAnother()
        assertNull(state.another)
        assertEquals(2, kit.storedBoxes().size)
    }

    @Test
    fun theAnotherPromptDisappearsAtTheNextScanOrWhenDismissed() = run {
        val noSerial = ScanTestKit.payload(lot = null, serial = null)
        kit.see(noSerial, at = 0.0)
        kit.see(noSerial, at = 5.0)
        assertNotNull(state.another)
        kit.see(ScanTestKit.payload(serial = "OTHER"), at = 10.0)
        assertNull(state.another)

        kit.see(noSerial, at = 15.0)
        assertNotNull(state.another)
        kit.model.dismissAnother()
        assertNull(state.another)
        assertEquals(2, kit.storedBoxes().size)
    }

    @Test
    fun confirmingAnotherBoxStillAsksForAMissingDate() = run {
        val noExpiry = ScanTestKit.payload(expiry = null, lot = null, serial = null)
        kit.see(noExpiry, at = 0.0)
        kit.model.confirmCompletion(null, ScanTestKit.date(2028, 1, 31))
        kit.see(noExpiry, at = 5.0)
        kit.model.confirmAnother()
        assertEquals(setOf(IntakeField.EXPIRY), state.completion?.missing)
        assertEquals(1, kit.storedBoxes().size)
    }

    // session list

    @Test
    fun deletingABoxKeepsATombstoneAndCanBeUndone() = run {
        kit.see(ScanTestKit.payload())
        val box = kit.storedBoxes().single()

        kit.model.delete(box)
        assertEquals(0, state.session.count)
        assertTrue(kit.storedBoxes().isEmpty())
        assertEquals(Notice.Deleted(box), state.notice)

        kit.model.undoDelete(box)
        assertEquals(1, state.session.count)
        assertEquals(listOf(box.id), kit.storedBoxes().map { it.id })
        assertNull(state.notice)
    }

    @Test
    fun aDeletedSerialCanBeScannedAgainAsANewBox() = run {
        kit.see(ScanTestKit.payload(), at = 0.0)
        kit.model.delete(kit.storedBoxes().single())
        kit.see(ScanTestKit.payload(), at = 5.0)
        assertEquals(1, kit.storedBoxes().size)
        assertEquals(1, state.session.count)
    }

    @Test
    fun aCorrectionChangesNameExpiryAndLocationAndCanSetTheExpiryBackToUnknown() = run {
        val kitchen = Location(name = "Kitchen", createdAt = ScanTestKit.START)
        kit.store.insert(kitchen)
        kit.model.load()
        kit.see(ScanTestKit.payload())
        val box = kit.storedBoxes().single()

        assertTrue(kit.model.update(box, " Paracétamol ", ScanTestKit.date(2029, 6, 1), kitchen.id))
        var saved = kit.storedBoxes().single()
        assertEquals("Paracétamol", saved.product.name)
        assertEquals(ScanTestKit.date(2029, 6, 1), saved.expiry)
        assertEquals(kitchen.id, saved.locationId)
        assertEquals(saved, state.session.newestFirst.first())

        assertTrue(kit.model.update(saved, "Paracétamol", null, kitchen.id))
        saved = kit.storedBoxes().single()
        assertNull(saved.expiry)
    }

    @Test
    fun anInvalidNameIsRejectedWithoutChangingTheBox() = run {
        kit.see(ScanTestKit.payload())
        val box = kit.storedBoxes().single()
        for (name in listOf("", "   ", "x".repeat(201))) {
            assertEquals(false, kit.model.update(box, name, box.expiry, box.locationId))
        }
        assertEquals("DOLIPRANE", kit.storedBoxes().single().product.name)
    }

    // done and manual entry

    @Test
    fun doneSummarisesTheIntakeAndClosingStartsAFreshSession() = run {
        kit.see(ScanTestKit.payload(serial = "A"), at = 0.0)
        kit.see(ScanTestKit.payload(expiry = null, serial = "B"), at = 0.1)
        kit.model.confirmCompletion(null, null)

        kit.model.finish()
        assertEquals(Summary(added = 2, unknownExpiry = 1), state.summary)

        kit.model.closeSummary()
        assertNull(state.summary)
        assertEquals(0, state.session.count)
        assertNull(state.notice)
        assertEquals(2, kit.storedBoxes().size)
    }

    @Test
    fun codesAreIgnoredWhileTheSummaryIsOpen() = run {
        kit.model.finish()
        kit.see(ScanTestKit.payload())
        assertTrue(kit.storedBoxes().isEmpty())
    }

    @Test
    fun aBoxCanBeTypedInWithoutAnyCamera() = run {
        kit.model.onCameraAccess(CameraAccess.DENIED)
        val error = kit.model.addManually("3400930000014", "", ScanTestKit.date(2028, 5, null), kit.defaultLocation.id)
        assertNull(error)
        val box = kit.storedBoxes().single()
        assertEquals("DOLIPRANE", box.product.name)
        assertEquals(ScanTestKit.GTIN, box.product.gtin)
        assertEquals(ScanTestKit.date(2028, 5, null), box.expiry)
        assertEquals(1, state.session.count)
    }

    @Test
    fun aBoxWithoutCodeOrDateCanBeTypedIn() = run {
        assertNull(kit.model.addManually("", "Thermomètre", null, kit.defaultLocation.id))
        val box = kit.storedBoxes().single()
        assertNull(box.expiry)
        assertNull(box.product.gtin)
    }

    @Test
    fun typedEntriesAreValidated() = run {
        val location = kit.defaultLocation.id
        assertEquals(ManualError.INVALID_CODE, kit.model.addManually("12345", "X", null, location))
        assertEquals(ManualError.INVALID_NAME, kit.model.addManually("", "  ", null, location))
        assertTrue(kit.storedBoxes().isEmpty())
    }

    @Test
    fun aKnownProductNameComesFromTheReferenceLookup() = run(
        StubLookup(
            mapOf(
                ScanTestKit.GTIN to ReferenceProduct("SPASFON")
            )
        )
    ) {
        kit.see(ScanTestKit.payload())
        assertEquals("SPASFON", kit.storedBoxes().single().product.name)
    }
}
