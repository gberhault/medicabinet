package com.medicabinet.app.features.scan

import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.medicabinet.core.Box
import com.medicabinet.core.ProductRef
import com.medicabinet.core.reference.ReferenceProduct
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ExpiryInputTest {
    private fun date(year: Int, month: Int, day: Int?) = ScanTestKit.date(year, month, day)

    @Test
    fun nothingIsPreselected() {
        assertEquals(ExpiryInput.Outcome.Incomplete, ExpiryInput().outcome)
        assertEquals(ExpiryChoice.Resolved.Pending, ExpiryChoice().resolved)
        assertNull(ExpiryChoice().expiry)
    }

    @Test
    fun aMonthAndAYearAreEnoughAndGiveMonthPrecision() {
        assertEquals(ExpiryInput.Outcome.Valid(date(2027, 3, null)), ExpiryInput(month = 3, year = 2027).outcome)
    }

    @Test
    fun aDayMakesItDayPrecise() {
        assertEquals(ExpiryInput.Outcome.Valid(date(2027, 3, 15)), ExpiryInput(3, 2027, 15).outcome)
    }

    @Test
    fun anImpossibleDateIsInvalidNeverCorrected() {
        listOf(
            ExpiryInput(4, 2027, 31),
            ExpiryInput(2, 2027, 29),
            ExpiryInput(13, 2027),
            ExpiryInput(3, 1999),
            ExpiryInput(3, 2100)
        ).forEach { assertEquals(ExpiryInput.Outcome.Invalid, it.outcome, it.toString()) }
    }

    @Test
    fun aLeapDayIsValid() {
        assertEquals(ExpiryInput.Outcome.Valid(date(2028, 2, 29)), ExpiryInput(2, 2028, 29).outcome)
    }

    @Test
    fun halfAnInputIsIncomplete() {
        assertEquals(ExpiryInput.Outcome.Incomplete, ExpiryInput(month = 3).outcome)
        assertEquals(ExpiryInput.Outcome.Incomplete, ExpiryInput(year = 2027).outcome)
    }

    @Test
    fun dateNotPrintedResolvesToUnknownWhateverWasTyped() {
        val choice = ExpiryChoice(ExpiryInput(13, 1, 99), notPrinted = true)
        assertEquals(ExpiryChoice.Resolved.Unknown, choice.resolved)
        assertNull(choice.expiry)
    }

    @Test
    fun aValidInputResolvesToItsDate() {
        val choice = ExpiryChoice(ExpiryInput(5, 2029))
        assertEquals(ExpiryChoice.Resolved.Date(date(2029, 5, null)), choice.resolved)
        assertEquals(date(2029, 5, null), choice.expiry)
    }

    @Test
    fun editingSeedsTheChoiceFromTheBox() {
        val known = ExpiryChoice.of(date(2027, 3, 15), editing = true)
        assertEquals(ExpiryInput(3, 2027, 15), known.input)
        assertFalse(known.notPrinted)
        val unknown = ExpiryChoice.of(null, editing = true)
        assertTrue(unknown.notPrinted)
        assertEquals(ExpiryChoice.Resolved.Unknown, unknown.resolved)
        assertEquals(ExpiryChoice.Resolved.Pending, ExpiryChoice.of(null, editing = false).resolved)
    }
}

class ScanSessionTest {
    private fun box(name: String, gtin: String? = "03400930000014", expiry: com.medicabinet.core.ExpiryDate? = null) =
        Box(
            product = ProductRef(gtin, name),
            locationId = UUID.randomUUID(),
            expiry = expiry,
            createdAt = Instant.EPOCH
        )

    @Test
    fun itListsTheNewestFirstAndCountsTheBoxesWithoutADate() {
        val session = ScanSession().add(box("A", expiry = ScanTestKit.date(2027, 3, null))).add(box("B"))
        assertEquals(2, session.count)
        assertEquals(listOf("B", "A"), session.newestFirst.map { it.product.name })
        assertEquals(1, session.unknownExpiryCount)
    }

    @Test
    fun itKnowsTheProductsAddedAndIgnoresBoxesWithoutGtin() {
        val session = ScanSession().add(box("A", gtin = "03400930000014")).add(box("B", gtin = null))
        assertEquals(setOf("03400930000014"), session.gtins)
    }

    @Test
    fun removingForgetsTheBoxAndReplacingKeepsItsPlace() {
        val first = box("A")
        val second = box("B")
        var session = ScanSession().add(first).add(second)
        session = session.replace(first.copy(product = ProductRef(first.product.gtin, "A2")))
        assertEquals(listOf("B", "A2"), session.newestFirst.map { it.product.name })
        session = session.remove(second.id)
        assertEquals(1, session.count)
        assertEquals(1, session.replace(box("unknown")).count)
    }
}

@RunWith(RobolectricTestRunner::class)
class ScanPreferencesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun soundIsOffAndThereIsNoLocationByDefault() {
        val preferences = SharedScanPreferences(context)
        assertFalse(preferences.soundEnabled)
        assertNull(preferences.lastLocationId)
        assertFalse(preferences.cameraRequested)
    }

    @Test
    fun valuesSurviveANewInstance() {
        val id = UUID.randomUUID()
        SharedScanPreferences(context).apply {
            soundEnabled = true
            lastLocationId = id
            cameraRequested = true
        }
        val again = SharedScanPreferences(context)
        assertTrue(again.soundEnabled && again.cameraRequested)
        assertEquals(id, again.lastLocationId)
        again.lastLocationId = null
        assertNull(SharedScanPreferences(context).lastLocationId)
    }

    @Test
    fun aCorruptStoredLocationIsIgnored() {
        context.getSharedPreferences(SharedScanPreferences.FILE, Context.MODE_PRIVATE).edit()
            .putString(SharedScanPreferences.LOCATION_KEY, "not a uuid").commit()
        assertNull(SharedScanPreferences(context).lastLocationId)
    }

    @Test
    fun theLiveServicesKnowWhetherTheDeviceHasACameraAndUseTheGivenLookup() = runBlocking {
        val lookup = StubLookup(mapOf("03400930000014" to ReferenceProduct("DOLIPRANE")))
        val services = ScanServices.live(context, lookup)
        assertFalse(services.hasCamera, "Robolectric devices declare no camera")
        assertEquals("DOLIPRANE", services.lookup.product("03400930000014")?.name)
        assertEquals(services.preferences::class, SharedScanPreferences::class)
    }

    @Test
    fun inMemoryPreferencesStartEmpty() {
        val preferences = InMemoryScanPreferences()
        assertFalse(preferences.soundEnabled)
        assertNull(preferences.lastLocationId)
    }
}

@RunWith(RobolectricTestRunner::class)
class ScanFeedbackPlayerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Config(sdk = [Build.VERSION_CODES.R])
    @Test
    fun modernAndroidHasConfirmAndRejectHaptics() {
        assertEquals(HapticFeedbackConstants.CONFIRM, ScanFeedbackPlayer.constant(FeedbackEvent.ADDED))
        assertEquals(HapticFeedbackConstants.REJECT, ScanFeedbackPlayer.constant(FeedbackEvent.REJECTED))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, ScanFeedbackPlayer.constant(FeedbackEvent.ATTENTION))
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, ScanFeedbackPlayer.constant(FeedbackEvent.REMOVED))
    }

    @Config(sdk = [Build.VERSION_CODES.O])
    @Test
    fun olderAndroidFallsBackToTheStandardHaptics() {
        listOf(FeedbackEvent.ADDED, FeedbackEvent.REJECTED).forEach {
            assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, ScanFeedbackPlayer.constant(it))
        }
        assertNotEquals(HapticFeedbackConstants.KEYBOARD_TAP, ScanFeedbackPlayer.constant(FeedbackEvent.ATTENTION))
        // Removing a box feels different from adding one, on every version.
        assertNotEquals(
            ScanFeedbackPlayer.constant(FeedbackEvent.ADDED),
            ScanFeedbackPlayer.constant(FeedbackEvent.REMOVED)
        )
    }

    @Test
    fun theHapticIsPerformedOnTheViewAndNoPermissionIsNeeded() {
        val player = ScanFeedbackPlayer(context)
        val view = View(context)
        player.play(view, FeedbackRequest(FeedbackEvent.ADDED, sound = true))
        player.play(view, FeedbackRequest(FeedbackEvent.REJECTED, sound = false))
        player.play(view, FeedbackRequest(FeedbackEvent.REMOVED, sound = true))
        player.close()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class ScanTextEnglishTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun aDayPreciseDateIsFormattedInTheUsersLanguage() {
        assertEquals(
            "Expires March 15, 2027",
            ScanText.expiry(context, ScanTestKit.date(2027, 3, 15), java.util.Locale.US)
        )
    }

    @Test
    fun aMonthPreciseDateSaysEndOfMonth() {
        assertEquals(
            "Expires end of March 2027",
            ScanText.expiry(context, ScanTestKit.date(2027, 3, null), java.util.Locale.US)
        )
    }

    @Test
    fun anUnknownExpiryIsSaidNotHidden() {
        assertEquals("Expiry unknown", ScanText.expiry(context, null))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr")
class ScanTextFrenchTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun datesAreFormattedInFrench() {
        assertEquals(
            "Expire le 15 mars 2027",
            ScanText.expiry(context, ScanTestKit.date(2027, 3, 15), java.util.Locale.FRANCE)
        )
        assertEquals(
            "Expire fin mars 2027",
            ScanText.expiry(context, ScanTestKit.date(2027, 3, null), java.util.Locale.FRANCE)
        )
        assertEquals("Péremption inconnue", ScanText.expiry(context, null))
    }
}
