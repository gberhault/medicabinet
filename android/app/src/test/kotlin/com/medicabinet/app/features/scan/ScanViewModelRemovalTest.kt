package com.medicabinet.app.features.scan

import app.cash.turbine.test
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.features.scan.camera.FakeCodeScanner
import com.medicabinet.core.Box
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.reference.ReferenceStatus
import com.medicabinet.core.reference.ReferenceUnavailableReason
import com.medicabinet.core.testing.InMemoryReferenceData
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Remove mode of the scanner (requirements R1-R4, R6-R9 of the cabinet-inventory spec; issue #21). */
class ScanViewModelRemovalTest {
    private val state get() = kit.model.state.value
    private lateinit var kit: ScanTestKit

    private fun run(body: suspend () -> Unit) = runBlocking {
        kit = ScanTestKit().open()
        body()
    }

    private suspend fun stock(
        serial: String?,
        expiry: ExpiryDate? = ScanTestKit.date(2027, 3, 31),
        opened: Boolean = false,
        removed: RemovalReason? = null
    ): Box {
        val box = Box(
            product = ProductRef(ScanTestKit.GTIN, "DOLIPRANE"),
            locationId = kit.defaultLocation.id,
            expiry = expiry,
            serial = serial,
            isOpened = opened,
            createdAt = ScanTestKit.START,
            removal = removed?.let { Box.Removal(it, ScanTestKit.START) }
        )
        kit.store.insert(box)
        return box
    }

    private suspend fun stored(id: UUID): Box = assertNotNull(kit.store.box(id))

    // mode and reason (R1, R3)

    @Test
    fun theScannerOpensInAddModeAndRemoveIsNeverRemembered() = run {
        assertEquals(ScanMode.ADD, state.mode)
        kit.model.setMode(ScanMode.REMOVE)
        assertEquals(ScanMode.REMOVE, state.mode)
        kit.model.resetMode()
        assertEquals(ScanMode.ADD, state.mode)
    }

    @Test
    fun theReasonStartsAsUsedUpAndTheLastChoiceIsRemembered() = run {
        assertEquals(RemovalReason.USED_UP, state.removalReason)
        kit.model.setRemovalReason(RemovalReason.THROWN_AWAY)
        assertEquals(RemovalReason.THROWN_AWAY, kit.preferences.removalReason)
        assertEquals(RemovalReason.THROWN_AWAY, state.removalReason)
    }

    @Test
    fun aNewScreenStartsWithTheRememberedReason() = run {
        kit.preferences.removalReason = RemovalReason.THROWN_AWAY
        val environment = AppEnvironment(
            kit.store,
            kit.store,
            kit.store.named,
            kit.clock,
            InMemoryReferenceData(status = ReferenceStatus.Unavailable(ReferenceUnavailableReason.MISSING))
        )
        val services = ScanServices({ FakeCodeScanner() }, kit.preferences, StubLookup())
        assertEquals(RemovalReason.THROWN_AWAY, ScanViewModel(environment, services).state.value.removalReason)
    }

    @Test
    fun theReasonIsStoredUnderTheDocumentedKey() {
        assertEquals("scan.removalReason", SharedScanPreferences.REMOVAL_REASON_KEY)
    }

    // outcomes (R5, R6)

    @Test
    fun aSerialMatchIsRemovedWithTheSessionReasonAndConfirmed() = run {
        val box = stock("SER1")
        kit.model.setMode(ScanMode.REMOVE)
        kit.model.setRemovalReason(RemovalReason.THROWN_AWAY)
        kit.model.feedback.test {
            kit.see(ScanTestKit.payload())
            assertEquals(FeedbackRequest(FeedbackEvent.REMOVED, sound = false), awaitItem())
        }
        val removed = stored(box.id)
        assertEquals(Box.Removal(RemovalReason.THROWN_AWAY, ScanTestKit.START), removed.removal)
        assertEquals(Notice.Removed(removed, RemovalReason.THROWN_AWAY, "Cabinet"), state.notice)
        assertEquals(1, state.removalSession.count)
        assertEquals(0, state.session.count)
    }

    @Test
    fun aSingleCandidateWithoutSerialIsRemoved() = run {
        val box = stock(null)
        kit.model.setMode(ScanMode.REMOVE)
        kit.see(ScanTestKit.payload(serial = null))
        assertEquals(RemovalReason.USED_UP, stored(box.id).removal?.reason)
    }

    @Test
    fun aBoxThatIsNotInTheCabinetIsSaidSoAndNeverAdded() = run {
        kit.model.setMode(ScanMode.REMOVE)
        kit.model.feedback.test {
            kit.see(ScanTestKit.payload())
            assertEquals(FeedbackEvent.REJECTED, awaitItem().event)
        }
        assertEquals(Notice.NotInCabinet, state.notice)
        assertTrue(kit.storedBoxes().isEmpty())
        assertNull(state.completion)
    }

    @Test
    fun aBoxAlreadyRemovedChangesNothing() = run {
        val box = stock("SER1", removed = RemovalReason.USED_UP)
        kit.model.setMode(ScanMode.REMOVE)
        kit.model.setRemovalReason(RemovalReason.THROWN_AWAY)
        kit.see(ScanTestKit.payload())
        assertEquals(Notice.AlreadyRemoved(stored(box.id)), state.notice)
        assertEquals(RemovalReason.USED_UP, stored(box.id).removal?.reason)
        assertEquals(0, state.removalSession.count)
    }

    @Test
    fun anUnreadableCodeBehavesAsInAddMode() = run {
        kit.model.setMode(ScanMode.REMOVE)
        kit.see("not a gs1 code", at = 0.0)
        assertEquals(Notice.Unrecognized, state.notice)
    }

    // several candidates (R7)

    @Test
    fun severalCandidatesAskWhichOneAndIgnoreRecognitionsWhileTheSheetIsOpen() = run {
        val later = stock(null, ScanTestKit.date(2028, 1, 31))
        val sooner = stock(null, ScanTestKit.date(2026, 12, 31))
        kit.model.setMode(ScanMode.REMOVE)
        kit.see(ScanTestKit.payload(serial = null), at = 0.0)

        val candidates = assertNotNull(state.candidates)
        assertEquals(listOf(sooner.id, later.id), candidates.boxes.map { it.id })
        assertEquals(2, kit.storedBoxes().size)

        kit.see(ScanTestKit.payload(gtin = "03400930000021", serial = "OTHER"), at = 10.0)
        assertEquals(candidates, state.candidates)

        kit.model.removeCandidate(later.id)
        assertNull(state.candidates)
        assertNotNull(stored(later.id).removal)
        assertNull(stored(sooner.id).removal)
        assertEquals(later.id, assertIs<Notice.Removed>(state.notice).box.id)
    }

    @Test
    fun cancellingTheSheetRemovesNothing() = run {
        stock(null, ScanTestKit.date(2028, 1, 31))
        stock(null, ScanTestKit.date(2026, 12, 31))
        kit.model.setMode(ScanMode.REMOVE)
        kit.see(ScanTestKit.payload(serial = null))
        kit.model.cancelCandidates()
        assertNull(state.candidates)
        assertEquals(2, kit.storedBoxes().size)
        assertEquals(0, state.removalSession.count)
    }

    @Test
    fun aCandidateThatVanishedMeanwhileShowsAnError() = run {
        kit.model.removeCandidate(UUID.randomUUID())
        assertEquals(Notice.Failed, state.notice)
    }

    // session, Undo, summary (R8)

    @Test
    fun undoPutsTheBoxBackExactlyAsItWasAndLeavesTheSession() = run {
        val box = stock("SER1", opened = true)
        kit.model.setMode(ScanMode.REMOVE)
        kit.see(ScanTestKit.payload())
        kit.model.undoRemoval(stored(box.id))
        val restored = stored(box.id)
        assertNull(restored.removal)
        assertTrue(restored.isOpened)
        assertEquals(box.expiry, restored.expiry)
        assertEquals(0, state.removalSession.count)
        assertNull(state.notice)
    }

    @Test
    fun theSessionListsRemovalsNewestFirstAndTheSummaryCountsPerReason() = run {
        val first = stock("A")
        val second = stock("B")
        val third = stock("C")
        kit.model.setMode(ScanMode.REMOVE)
        kit.see(ScanTestKit.payload(serial = "A"), at = 0.0)
        kit.model.setRemovalReason(RemovalReason.THROWN_AWAY)
        kit.see(ScanTestKit.payload(serial = "B"), at = 5.0)
        kit.see(ScanTestKit.payload(serial = "C"), at = 10.0)
        assertEquals(listOf(third.id, second.id, first.id), state.removalSession.newestFirst.map { it.id })

        kit.model.finish()
        val summary = assertNotNull(state.summary)
        assertEquals(Triple(ScanMode.REMOVE, 1, 2), Triple(summary.mode, summary.usedUp, summary.thrownAway))
        kit.model.closeSummary()
        assertEquals(0, state.removalSession.count)
        assertNull(state.summary)
        assertTrue(kit.storedBoxes().isEmpty(), "removals were saved at once")
    }

    @Test
    fun switchingBackToAddEndsTheRemoveSessionButKeepsTheRemovals() = run {
        val box = stock("SER1")
        kit.model.setMode(ScanMode.REMOVE)
        kit.see(ScanTestKit.payload())
        kit.model.setMode(ScanMode.ADD)
        assertEquals(0, state.removalSession.count)
        assertNull(state.notice)
        assertNotNull(stored(box.id).removal)
    }

    @Test
    fun aRemovedBoxScannedInAddModeCanStillBePutBack() = run {
        val box = stock("SER1")
        kit.model.setMode(ScanMode.REMOVE)
        kit.see(ScanTestKit.payload(), at = 0.0)
        kit.model.setMode(ScanMode.ADD)
        kit.see(ScanTestKit.payload(), at = 10.0)
        assertEquals(box.id, assertIs<Notice.RemovedEarlier>(state.notice).box.id)
    }

    @Test
    fun switchingModeClosesAPendingPrompt() = run {
        stock(null, ScanTestKit.date(2028, 1, 31))
        stock(null, ScanTestKit.date(2026, 12, 31))
        kit.model.setMode(ScanMode.REMOVE)
        kit.see(ScanTestKit.payload(serial = null))
        assertNotNull(state.candidates)
        kit.model.setMode(ScanMode.ADD)
        assertNull(state.candidates)
    }
}
