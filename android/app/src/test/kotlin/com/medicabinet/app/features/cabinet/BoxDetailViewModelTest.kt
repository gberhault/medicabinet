package com.medicabinet.app.features.cabinet

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ExpiryStatus
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.cabinet.BoxEdit
import com.medicabinet.core.cabinet.BoxEditError
import com.medicabinet.core.expiry.ExpiryWindow
import com.medicabinet.core.reference.ReferenceProduct
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BoxDetailViewModelTest {
    private val kit = CabinetTestKit(
        mapOf("3400930000014" to ReferenceProduct("DOLIPRANE 1000 mg", CategoryID.PAIN_FEVER))
    )

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun model(box: Box, environment: AppEnvironment = kit.environment) =
        BoxDetailViewModel(box.id, environment, UnconfinedTestDispatcher())

    private suspend fun ReceiveTurbine<BoxDetailState>.loaded(): BoxDetailState.Loaded {
        var item = awaitItem()
        while (item !is BoxDetailState.Loaded) item = awaitItem()
        return item
    }

    private suspend fun stored(box: Box): Box? = kit.store.box(box.id)

    @Test
    fun showsTheBoxItsLocationAndItsStatus() = runTest {
        kit.open()
        val box = kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.SOON, lot = "L1", serial = "S1")
        model(box).state.test {
            val state = loaded()
            assertEquals("Home", state.locationName)
            assertEquals(ExpiryStatus.EXPIRING_SOON, state.status)
            assertEquals("L1", state.box.lot)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun anUnknownExpiryIsUnknownNeverOk() = runTest {
        kit.open()
        val box = kit.box("Pansements")
        model(box).state.test {
            assertEquals(ExpiryStatus.UNKNOWN, loaded().status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aBoxThatIsNotInTheCabinetIsGone() = runTest {
        kit.open()
        val box = kit.box("Doliprane", expiry = CabinetTestKit.FINE)
        kit.store.markRemoved(box.id, RemovalReason.USED_UP, kit.clock.now())
        model(box).state.test {
            assertEquals(BoxDetailState.Gone, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theOpenedSwitchSavesAtOnceAndTogglesBack() = runTest {
        kit.open()
        val box = kit.box("Doliprane", expiry = CabinetTestKit.FINE)
        val viewModel = model(box)
        viewModel.state.test {
            loaded()
            viewModel.setOpened(true)
            assertTrue(loaded().box.isOpened)
            assertEquals(true, stored(box)?.isOpened)
            viewModel.setOpened(false)
            assertFalse(loaded().box.isOpened)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun savingTrimsTheNameAndKeepsAnUnknownExpiryUnknown() = runTest {
        kit.open()
        val kitchen = kit.store.create("Kitchen")
        val box = kit.box("Doliprane", CabinetTestKit.DOLIPRANE, expiry = null)
        val viewModel = model(box)
        viewModel.state.test {
            loaded()
            val edit = BoxEdit.from(box).copy(name = "  Doliprane 500  ", locationId = kitchen.id)
            assertEquals(SaveResult.Saved, viewModel.save(edit))
            val saved = assertNotNull(stored(box))
            assertEquals("Doliprane 500", saved.product.name)
            assertEquals(kitchen.id, saved.locationId)
            assertNull(saved.expiry)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun savingAnEditedExpiryStoresIt() = runTest {
        kit.open()
        val box = kit.box("Doliprane", expiry = null)
        val viewModel = model(box)
        viewModel.state.test {
            loaded()
            val date = ExpiryDate.of(2029, 4, null)
            assertEquals(SaveResult.Saved, viewModel.save(BoxEdit.from(box).copy(expiry = date)))
            assertEquals(date, stored(box)?.expiry)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun anEmptyOrTooLongNameIsRefusedAndNothingIsWritten() = runTest {
        kit.open()
        val box = kit.box("Doliprane", expiry = CabinetTestKit.FINE)
        val viewModel = model(box)
        viewModel.state.test {
            loaded()
            assertEquals(
                SaveResult.Invalid(BoxEditError.EMPTY_NAME),
                viewModel.save(BoxEdit.from(box).copy(name = "   "))
            )
            assertEquals(
                SaveResult.Invalid(BoxEditError.NAME_TOO_LONG),
                viewModel.save(BoxEdit.from(box).copy(name = "x".repeat(201)))
            )
            assertEquals("Doliprane", stored(box)?.product?.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aFailedSaveLeavesTheBoxAsItWas() = runTest {
        kit.open()
        val box = kit.box("Doliprane", expiry = CabinetTestKit.FINE)
        val viewModel = model(box, kit.readOnlyEnvironment())
        viewModel.state.test {
            loaded()
            assertEquals(SaveResult.Failed, viewModel.save(BoxEdit.from(box).copy(name = "Other")))
            assertEquals("Doliprane", stored(box)?.product?.name)
            assertTrue(viewModel.writeFailed.value)
            viewModel.acknowledgeWriteFailure()
            assertFalse(viewModel.writeFailed.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun usedUpRemovesTheBoxAtTheCurrentDateAndUndoPutsItBackAsItWas() = runTest {
        kit.open()
        val box = kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.SOON, opened = true, serial = "S1")
        val viewModel = model(box)
        viewModel.state.test {
            loaded()
            val offer = assertNotNull(viewModel.takeOut(TakeOut.USED_UP))
            assertEquals("Doliprane", offer.productName)
            assertEquals(TakeOut.USED_UP, offer.action)
            val removal = assertNotNull(stored(box)?.removal)
            assertEquals(RemovalReason.USED_UP, removal.reason)
            assertEquals(kit.clock.now(), removal.date)
            assertEquals(BoxDetailState.Gone, awaitItem())

            assertEquals(UndoOutcome.RESTORED, offer.undo())
            val restored = assertNotNull(stored(box))
            assertNull(restored.removal)
            assertEquals(box.locationId, restored.locationId)
            assertEquals(box.expiry, restored.expiry)
            assertTrue(restored.isOpened)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun thrownAwayRecordsThatReason() = runTest {
        kit.open()
        val box = kit.box("Doliprane", expiry = CabinetTestKit.EXPIRED)
        val viewModel = model(box)
        viewModel.state.test {
            loaded()
            assertNotNull(viewModel.takeOut(TakeOut.THROWN_AWAY))
            assertEquals(RemovalReason.THROWN_AWAY, stored(box)?.removal?.reason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun addedByMistakeDeletesTheBoxAndUndoRestoresIt() = runTest {
        kit.open()
        val box = kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE, serial = "S1")
        val viewModel = model(box)
        viewModel.state.test {
            loaded()
            val offer = assertNotNull(viewModel.takeOut(TakeOut.MISTAKE))
            assertNull(stored(box))
            assertEquals(BoxDetailState.Gone, awaitItem())
            assertEquals(UndoOutcome.RESTORED, offer.undo())
            assertNotNull(stored(box))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun undoingADeletionWhoseSerialWasTakenSaysSo() = runTest {
        kit.open()
        val box = kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE, serial = "S1")
        val viewModel = model(box)
        viewModel.state.test {
            loaded()
            val offer = assertNotNull(viewModel.takeOut(TakeOut.MISTAKE))
            kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE, serial = "S1")
            assertEquals(UndoOutcome.SERIAL_TAKEN, offer.undo())
            assertNull(stored(box))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun undoingAnUnknownBoxFailsWithoutCrashing() = runTest {
        kit.open()
        val box = kit.box("Doliprane", expiry = CabinetTestKit.FINE)
        val viewModel = model(box)
        viewModel.state.test {
            loaded()
            val offer = assertNotNull(viewModel.takeOut(TakeOut.MISTAKE))
            kit.store.undoDelete(box.id)
            // Already restored: a second undo has nothing to restore.
            assertEquals(UndoOutcome.FAILED, offer.undo())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aFailedTakeOutKeepsTheBoxAndOffersNoUndo() = runTest {
        kit.open()
        val box = kit.box("Doliprane", expiry = CabinetTestKit.FINE)
        val viewModel = model(box, kit.readOnlyEnvironment())
        viewModel.state.test {
            loaded()
            assertNull(viewModel.takeOut(TakeOut.USED_UP))
            assertNull(viewModel.takeOut(TakeOut.MISTAKE))
            assertNull(stored(box)?.removal)
            assertTrue(viewModel.writeFailed.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theAutomaticCategoryIsTheReferencesWhenTheProductIsKnown() = runTest {
        kit.open()
        val box = kit.box(
            "Whatever",
            CabinetTestKit.DOLIPRANE,
            CabinetTestKit.FINE,
            category = CategoryID.EYES,
            overridden = true
        )
        val viewModel = model(box)
        viewModel.state.test {
            loaded()
            assertEquals(CategoryID.PAIN_FEVER, viewModel.automaticCategory())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theAutomaticCategoryIsUncategorizedForAnUnknownProductOrNoCode() = runTest {
        kit.open()
        val unknown = kit.box("Mystery", "03400999999999", CabinetTestKit.FINE)
        val noCode = kit.box("Pansements", expiry = CabinetTestKit.FINE)
        val unknownModel = model(unknown)
        unknownModel.state.test {
            loaded()
            assertEquals(CategoryID.UNCATEGORIZED, unknownModel.automaticCategory())
            cancelAndIgnoreRemainingEvents()
        }
        val noCodeModel = model(noCode)
        noCodeModel.state.test {
            loaded()
            assertEquals(CategoryID.UNCATEGORIZED, noCodeModel.automaticCategory())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun anUnreadableCabinetFailsAndRetryTriesAgain() = runTest {
        kit.open()
        val box = kit.box("Doliprane", expiry = CabinetTestKit.FINE)
        val viewModel = model(box, kit.failingEnvironment())
        viewModel.state.test {
            assertEquals(BoxDetailState.Failed, awaitItem())
            viewModel.retry()
            assertEquals(BoxDetailState.Loading, awaitItem())
            assertIs<BoxDetailState.Failed>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun actionsBeforeTheBoxIsLoadedDoNothing() = runTest {
        kit.open()
        val box = kit.box("Doliprane", expiry = CabinetTestKit.FINE)
        val viewModel = BoxDetailViewModel(box.id, kit.environment, UnconfinedTestDispatcher())
        // Nobody collects the state yet, so it is still Loading.
        viewModel.setOpened(true)
        assertNull(viewModel.takeOut(TakeOut.USED_UP))
        assertEquals(SaveResult.Failed, viewModel.save(BoxEdit.from(box)))
        assertEquals(CategoryID.UNCATEGORIZED, viewModel.automaticCategory())
        assertEquals(false, stored(box)?.isOpened)
    }

    @Test
    fun aNewExpiryWindowEvaluatesTheStatusAgain() = runTest {
        kit.open()
        val box = kit.box("Doliprane", CabinetTestKit.DOLIPRANE, ExpiryDate.of(2027, 3, 1))
        model(box).state.test {
            assertEquals(ExpiryStatus.EXPIRING_SOON, loaded().status)
            kit.environment.expiryPreferences.window = ExpiryWindow.DAYS_30
            assertEquals(ExpiryStatus.OK, loaded().status)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
