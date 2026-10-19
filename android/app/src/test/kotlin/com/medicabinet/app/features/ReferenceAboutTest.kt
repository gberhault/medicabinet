package com.medicabinet.app.features

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.cash.turbine.test
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.app.LocalAppEnvironment
import com.medicabinet.app.features.settings.AboutViewModel
import com.medicabinet.app.features.settings.AppInfo
import com.medicabinet.app.features.settings.ReferenceUiState
import com.medicabinet.app.ui.theme.MediCabinetTheme
import com.medicabinet.core.SystemClock
import com.medicabinet.core.reference.ReferenceDataDate
import com.medicabinet.core.reference.ReferenceStatus
import com.medicabinet.core.reference.ReferenceUnavailableReason
import com.medicabinet.core.testing.InMemoryCabinetStore
import com.medicabinet.core.testing.InMemoryReferenceData
import java.util.Locale
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val info = object : AppInfo {
    override val version = "1.2.3"
    override val build = "45"
}

private val dataDate = ReferenceDataDate(2026, 10, 18)

/** About scrolls: bring the text into view first. */
private fun ComposeContentTestRule.assertShown(text: String, substring: Boolean = false) {
    onNodeWithText(text, substring = substring).performScrollTo().assertIsDisplayed()
}

private fun environment(status: ReferenceStatus): AppEnvironment {
    val store = InMemoryCabinetStore(SystemClock)
    return AppEnvironment(store, store, SystemClock, InMemoryReferenceData(status = status))
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReferenceAboutViewModelTest {
    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun showsTheDataDateOnceKnown() = runTest {
        val viewModel = AboutViewModel(info, InMemoryReferenceData(status = ReferenceStatus.Available(dataDate)))
        viewModel.referenceState.test { assertEquals(ReferenceUiState.Available(dataDate), awaitItem()) }
    }

    @Test
    fun showsUnavailableWhateverTheReason() = runTest {
        for (reason in ReferenceUnavailableReason.entries) {
            val viewModel = AboutViewModel(info, InMemoryReferenceData(status = ReferenceStatus.Unavailable(reason)))
            assertEquals(ReferenceUiState.Unavailable, viewModel.referenceState.value, reason.name)
        }
    }

    @Test
    fun noEnvironmentMeansUnavailable() {
        assertEquals(ReferenceUiState.Unavailable, AboutViewModel(info).referenceState.value)
    }

    @Test
    fun theDateIsFormattedInTheUsersLocale() {
        assertEquals("October 18, 2026", AboutViewModel.formatDate(dataDate, Locale.US))
        assertEquals("18 octobre 2026", AboutViewModel.formatDate(dataDate, Locale.FRANCE))
    }

    @Test
    fun aCalendarDayKeepsItsDayAtYearAndMonthBoundaries() {
        val expected = mapOf(
            ReferenceDataDate(2026, 1, 1) to "January 1, 2026",
            ReferenceDataDate(2026, 12, 31) to "December 31, 2026",
            ReferenceDataDate(2028, 2, 29) to "February 29, 2028",
            ReferenceDataDate(2026, 3, 29) to "March 29, 2026"
        )
        for ((date, text) in expected) assertEquals(text, AboutViewModel.formatDate(date, Locale.US))
    }

    @Test
    fun theSourceAddressIsTheBdpmHost() {
        assertEquals("base-donnees-publique.medicaments.gouv.fr", AboutViewModel.REFERENCE_ADDRESS)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en")
class ReferenceAboutScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun openAbout(status: ReferenceStatus?) {
        compose.setContent {
            MediCabinetTheme {
                CompositionLocalProvider(LocalAppEnvironment provides status?.let(::environment)) {
                    MainScaffold(appInfo = info)
                }
            }
        }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("About").performClick()
    }

    @Test
    fun aboutCitesTheSourceItsDateAndThatItIsNotEndorsed() {
        openAbout(ReferenceStatus.Available(dataDate))
        compose.assertShown("Reference data")
        compose.assertShown("Source: Base de Données Publique des Médicaments")
        compose.assertShown("base-donnees-publique.medicaments.gouv.fr")
        compose.assertShown("Data as of October 18, 2026")
        compose.assertShown("not affiliated with or endorsed by ANSM", substring = true)
    }

    @Test
    fun openingTheSourceAddressNeverCrashesWhenNoBrowserCanHandleIt() {
        openAbout(ReferenceStatus.Available(dataDate))
        val address = compose.onNodeWithText("base-donnees-publique.medicaments.gouv.fr").performScrollTo()
        address.performClick()
        address.assertIsDisplayed()
    }

    @Test
    fun aboutSaysSoWhenTheReferenceDataIsUnavailable() {
        openAbout(ReferenceStatus.Unavailable(ReferenceUnavailableReason.UNREADABLE))
        compose.assertShown("Reference data unavailable", substring = true)
    }

    @Test
    fun aboutWorksWithoutAnEnvironment() {
        openAbout(null)
        compose.assertShown("Reference data unavailable", substring = true)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "fr")
class ReferenceAboutScreenFrenchTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aboutIsShownInFrench() {
        compose.setContent {
            MediCabinetTheme {
                val reference = environment(ReferenceStatus.Available(dataDate))
                CompositionLocalProvider(LocalAppEnvironment provides reference) { MainScaffold(appInfo = info) }
            }
        }
        compose.onNodeWithText("Réglages").performClick()
        compose.onNodeWithText("À propos").performClick()
        compose.assertShown("Données de référence")
        compose.assertShown("Source : Base de Données Publique des Médicaments")
        compose.assertShown("Données du 18 octobre 2026")
        compose.assertShown("ni affilié ni approuvé par l'ANSM", substring = true)
    }
}
