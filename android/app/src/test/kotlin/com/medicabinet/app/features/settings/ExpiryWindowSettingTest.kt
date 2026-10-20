package com.medicabinet.app.features.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.app.InMemoryExpiryPreferences
import com.medicabinet.app.ui.theme.MediCabinetTheme
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ExpiryStatus
import com.medicabinet.core.expiry.CalendarDay
import com.medicabinet.core.expiry.ExpiryWindow
import com.medicabinet.core.reference.ReferenceStatus
import com.medicabinet.core.reference.ReferenceUnavailableReason
import com.medicabinet.core.testing.InMemoryCabinetStore
import com.medicabinet.core.testing.InMemoryReferenceData
import com.medicabinet.core.testing.MutableClock
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en")
class ExpiryWindowSettingTest {
    @get:Rule
    val compose = createComposeRule()

    private val preferences = InMemoryExpiryPreferences()

    private fun show() = compose.setContent { MediCabinetTheme { ExpiryWindowSetting(preferences) } }

    @Test
    fun offersThirtySixtyAndNinetyDaysWithTheDefaultSelectedAndAnExplanation() {
        show()
        compose.onNodeWithText("Expiring soon").assertIsDisplayed()
        compose.onNodeWithText("30 days").assertIsDisplayed()
        compose.onNodeWithText("60 days").assertIsDisplayed()
        compose.onNodeWithText("90 days").assertIsDisplayed()
        compose.onNodeWithTag("settings.expiryWindow.60").assertIsSelected()
        compose.onNodeWithTag("settings.expiryWindow.30").assertIsNotSelected()
        compose.onNodeWithText("Boxes expiring within this many days are shown as expiring soon.").assertIsDisplayed()
    }

    @Test
    fun choosingAWindowSavesItAtOnce() {
        show()
        compose.onNodeWithTag("settings.expiryWindow.90").performClick()
        assertEquals(ExpiryWindow.DAYS_90, preferences.window)
        compose.onNodeWithTag("settings.expiryWindow.90").assertIsSelected()
        compose.onNodeWithTag("settings.expiryWindow.60").assertIsNotSelected()
    }

    @Test
    fun aChangeMadeElsewhereIsShown() {
        show()
        preferences.window = ExpiryWindow.DAYS_30
        compose.waitForIdle()
        compose.onNodeWithTag("settings.expiryWindow.30").assertIsSelected()
    }

    @Test
    fun staysReadableAtTwiceTheFontSize() {
        RuntimeEnvironment.setFontScale(2f)
        show()
        compose.onNodeWithText("90 days").assertIsDisplayed()
        compose.onNodeWithText("Boxes expiring within this many days are shown as expiring soon.").assertIsDisplayed()
    }

    @Test
    fun theEnvironmentStatusFollowsTheSetting() {
        val clock = MutableClock(Instant.ofEpochSecond(1_800_000_000))
        val store = InMemoryCabinetStore(clock)
        val environment = AppEnvironment(
            store,
            store,
            clock,
            InMemoryReferenceData(status = ReferenceStatus.Unavailable(ReferenceUnavailableReason.MISSING)),
            expiryPreferences = preferences
        )
        val today = CalendarDay.of(2027, 1, 15)
        val inFortyFiveDays = ExpiryDate.of(2027, 3, 1)
        assertEquals(ExpiryStatus.EXPIRING_SOON, environment.expiryStatus(inFortyFiveDays, today))
        preferences.window = ExpiryWindow.DAYS_30
        assertEquals(ExpiryWindow.DAYS_30, environment.expiryWindow)
        assertEquals(ExpiryStatus.OK, environment.expiryStatus(inFortyFiveDays, today))
        assertEquals(ExpiryStatus.UNKNOWN, environment.expiryStatus(null, today))
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "fr")
class ExpiryWindowSettingFrenchTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun saysTheSameInFrench() {
        compose.setContent { MediCabinetTheme { ExpiryWindowSetting(InMemoryExpiryPreferences()) } }
        compose.onNodeWithText("Bientôt périmé").assertIsDisplayed()
        compose.onNodeWithText("30 jours").assertIsDisplayed()
        compose.onNodeWithText(
            "Les boîtes qui périment dans ce nombre de jours sont affichées comme bientôt périmées."
        ).assertIsDisplayed()
    }
}
