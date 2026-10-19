package com.medicabinet.app.features

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.medicabinet.app.features.settings.AboutViewModel
import com.medicabinet.app.features.settings.AppInfo
import com.medicabinet.app.features.settings.PackageAppInfo
import com.medicabinet.app.ui.theme.MediCabinetTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val fakeInfo = object : AppInfo {
    override val version = "1.2.3"
    override val build = "45"
}

class AboutViewModelTest {
    @Test
    fun exposesTheVersionAndBuildItWasGiven() {
        val viewModel = AboutViewModel(fakeInfo)
        assertEquals("1.2.3", viewModel.version)
        assertEquals("45", viewModel.build)
    }
}

@RunWith(RobolectricTestRunner::class)
class PackageAppInfoTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun readsTheVersionAndBuildOfTheInstalledPackage() {
        val info = PackageAppInfo(context)
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(info.version), "version was ${info.version}")
        assertTrue(info.build.toLongOrNull() != null, "build was ${info.build}")
    }

    @Test
    fun anUnknownPackageFallsBackToAVisibleMarkerInsteadOfCrashing() {
        val info = PackageAppInfo(context, packageName = "not.installed.anywhere")
        assertEquals("?", info.version)
        assertEquals("?", info.build)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en")
class AboutScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsLeadsToAboutWithVersionBuildAndTheNoMedicalAdviceNotice() {
        compose.setContent { MediCabinetTheme { MainScaffold(appInfo = fakeInfo) } }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("About").performClick()

        compose.onNodeWithText("MediCabinet").assertIsDisplayed()
        compose.onNodeWithText("Version 1.2.3 (45)").assertIsDisplayed()
        compose.onNodeWithText("no medical advice", substring = true).assertIsDisplayed()
    }

    @Test
    fun theBackButtonReturnsToSettings() {
        compose.setContent { MediCabinetTheme { MainScaffold(appInfo = fakeInfo) } }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("About").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("About").assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "fr")
class AboutScreenFrenchTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aboutIsShownInFrench() {
        compose.setContent { MediCabinetTheme { MainScaffold(appInfo = fakeInfo) } }
        compose.onNodeWithText("Réglages").performClick()
        compose.onNodeWithText("À propos").performClick()
        compose.onNodeWithText("Version 1.2.3 (45)").assertIsDisplayed()
        compose.onNodeWithText("aucun conseil médical", substring = true).assertIsDisplayed()
    }
}
