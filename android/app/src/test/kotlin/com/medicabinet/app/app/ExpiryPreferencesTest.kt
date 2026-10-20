package com.medicabinet.app.app

import android.content.Context
import android.content.Intent
import app.cash.turbine.test
import com.medicabinet.core.expiry.ExpiryWindow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ExpiryPreferencesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val store get() = context.getSharedPreferences("expiry_preferences", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        store.edit().clear().commit()
    }

    @Test
    fun defaultsAreTheNamedWindowAndRemindersOff() {
        val preferences = SharedExpiryPreferences(context)
        assertEquals(ExpiryWindow.DEFAULT, preferences.window)
        assertEquals(ExpiryWindow.DAYS_60, preferences.window)
        assertFalse(preferences.reminderEnabled)
        assertFalse(preferences.reminderCardDismissed)
        assertNull(preferences.lastDigestMonth)
    }

    @Test
    fun everyValueRoundTripsThroughTheSharedKeys() {
        val preferences = SharedExpiryPreferences(context)
        preferences.window = ExpiryWindow.DAYS_90
        preferences.reminderEnabled = true
        preferences.reminderCardDismissed = true
        preferences.lastDigestMonth = "2027-04"

        val reopened = SharedExpiryPreferences(context)
        assertEquals(ExpiryWindow.DAYS_90, reopened.window)
        assertTrue(reopened.reminderEnabled)
        assertTrue(reopened.reminderCardDismissed)
        assertEquals("2027-04", reopened.lastDigestMonth)
        assertEquals(
            setOf("expiry.windowDays", "reminder.enabled", "reminder.cardDismissed", "reminder.lastDigestMonth"),
            store.all.keys
        )
        assertEquals(90, store.getInt("expiry.windowDays", 0))
    }

    @Test
    fun theFileIsNamedExpiryPreferences() {
        assertEquals("expiry_preferences", SharedExpiryPreferences.FILE)
    }

    @Test
    fun aStoredWindowThatIsNotAChoiceFallsBackToTheDefault() {
        listOf(45, 0, -30, 365).forEach { days ->
            store.edit().putInt("expiry.windowDays", days).commit()
            assertEquals(ExpiryWindow.DEFAULT, SharedExpiryPreferences(context).window, "stored $days")
        }
    }

    @Test
    fun valuesOfTheWrongTypeFallBackWithoutCrashing() {
        store.edit()
            .putString("expiry.windowDays", "30")
            .putString("reminder.enabled", "yes")
            .putBoolean("reminder.cardDismissed", true)
            .putInt("reminder.lastDigestMonth", 7)
            .commit()
        val preferences = SharedExpiryPreferences(context)
        assertEquals(ExpiryWindow.DEFAULT, preferences.window)
        assertFalse(preferences.reminderEnabled)
        assertTrue(preferences.reminderCardDismissed)
        assertNull(preferences.lastDigestMonth)
    }

    @Test
    fun theWindowFlowEmitsNowThenOnEachChangeEvenFromAnotherInstance() = runTest {
        val preferences = SharedExpiryPreferences(context)
        preferences.windowChanges().test {
            assertEquals(ExpiryWindow.DAYS_60, awaitItem())
            SharedExpiryPreferences(context).window = ExpiryWindow.DAYS_30
            assertEquals(ExpiryWindow.DAYS_30, awaitItem())
            preferences.window = ExpiryWindow.DAYS_90
            assertEquals(ExpiryWindow.DAYS_90, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theReminderFlowOnlyEmitsItsOwnChanges() = runTest {
        val preferences = SharedExpiryPreferences(context)
        preferences.reminderEnabledChanges().test {
            assertFalse(awaitItem())
            preferences.window = ExpiryWindow.DAYS_30
            preferences.reminderCardDismissed = true
            expectNoEvents()
            preferences.reminderEnabled = true
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theInMemoryTwinBehavesTheSame() = runTest {
        val preferences = InMemoryExpiryPreferences()
        assertEquals(ExpiryWindow.DEFAULT, preferences.window)
        preferences.windowChanges().test {
            assertEquals(ExpiryWindow.DAYS_60, awaitItem())
            preferences.window = ExpiryWindow.DAYS_90
            assertEquals(ExpiryWindow.DAYS_90, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        preferences.reminderEnabledChanges().test {
            assertFalse(awaitItem())
            preferences.reminderEnabled = true
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        preferences.lastDigestMonth = "2027-05"
        assertEquals("2027-05", preferences.lastDigestMonth)
        preferences.reminderCardDismissed = true
        assertTrue(preferences.reminderCardDismissed)
    }

    @Test
    fun theTimeZoneAndClockBroadcastsEmitWhileCollected() = runTest {
        systemTimeChanges(context).test {
            context.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            awaitItem()
            context.sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        // Unregistered once collection stops: a later broadcast reaches nobody.
        assertTrue(
            shadowOf(context as android.app.Application).registeredReceivers.none {
                it.intentFilter.hasAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
        )
    }
}
