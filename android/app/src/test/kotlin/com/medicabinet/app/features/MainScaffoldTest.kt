package com.medicabinet.app.features

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.medicabinet.app.features.scan.testEnvironment
import com.medicabinet.app.features.scan.testScanServices
import com.medicabinet.app.ui.theme.MediCabinetTheme
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
class MainScaffoldTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(dark: Boolean = false) = compose.setContent {
        MediCabinetTheme(darkTheme = dark) { MainScaffold(testEnvironment(), scanServices = testScanServices()) }
    }

    @Test
    fun fourDestinationsAreShown() {
        show()
        listOf("Scan", "Cabinet", "Expiring", "Settings").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun scanIsTheStartDestinationAndEachUnbuiltFeatureExplainsItself() {
        show()
        compose.onNodeWithText("Allow the camera").assertIsDisplayed()
        compose.onNodeWithText("Cabinet").performClick()
        compose.onNodeWithText("Your cabinet is coming soon").assertIsDisplayed()
        compose.onNodeWithText("Expiring").performClick()
        compose.onNodeWithText("Expiry tracking is coming soon").assertIsDisplayed()
    }

    @Test
    fun destinationsStayUsableAtTwiceTheFontSizeInTheDarkTheme() {
        RuntimeEnvironment.setFontScale(2f)
        show(dark = true)
        listOf("Scan", "Cabinet", "Expiring", "Settings").forEach {
            compose.onNodeWithText(it).assertIsDisplayed().assertHasClickAction()
        }
        compose.onNodeWithText("Cabinet").performClick()
        compose.onNodeWithText("Your cabinet is coming soon").assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "fr")
class MainScaffoldFrenchTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theSameDestinationsAreShownInFrench() {
        compose.setContent { MediCabinetTheme { MainScaffold(testEnvironment(), scanServices = testScanServices()) } }
        listOf("Scanner", "Armoire", "Péremptions", "Réglages").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithTag("scan.permission.allow").assertIsDisplayed()
    }
}
