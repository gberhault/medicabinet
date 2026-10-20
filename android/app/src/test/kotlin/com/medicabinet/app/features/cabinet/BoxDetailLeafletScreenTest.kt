package com.medicabinet.app.features.cabinet

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.features.common.LocalUndoController
import com.medicabinet.app.features.common.UndoController
import com.medicabinet.app.ui.theme.MediCabinetTheme
import com.medicabinet.core.reference.ReferenceDataDate
import com.medicabinet.core.reference.ReferenceProduct
import com.medicabinet.core.reference.ReferenceStatus
import com.medicabinet.core.testing.InMemoryReferenceData
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The official-page link of box detail (#56), on the screen: label, note, size, the address it opens, failures. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w360dp-h2400dp")
class BoxDetailLeafletScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val kit = CabinetTestKit()
    private val page = "https://base-donnees-publique.medicaments.gouv.fr/medicament/60234100/extrait"
    private val reference = InMemoryReferenceData(
        mapOf("3400930000014" to ReferenceProduct("DOLIPRANE 1000 mg", cis = "60234100")),
        ReferenceStatus.Available(ReferenceDataDate(2026, 10, 1))
    )
    private val opened = mutableListOf<String>()

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    @Composable
    private fun Host(viewModel: BoxDetailViewModel, handler: UriHandler) {
        val snackbars = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val controller = remember { UndoController(snackbars, scope) }
        MediCabinetTheme {
            CompositionLocalProvider(LocalUndoController provides controller, LocalUriHandler provides handler) {
                Scaffold(snackbarHost = { SnackbarHost(snackbars) }) { padding ->
                    BoxDetailScreen(viewModel, ZoneOffset.UTC, onBack = {}, modifier = Modifier.padding(padding))
                }
            }
        }
    }

    private fun show(gtin: String?, handler: UriHandler = recording()) {
        val box = runBlocking {
            kit.open()
            kit.box("Doliprane 1000", gtin, CabinetTestKit.SOON)
        }
        val environment = AppEnvironment(kit.store, kit.store, kit.clock, reference, ZoneOffset.UTC)
        val viewModel = BoxDetailViewModel(box.id, environment, UnconfinedTestDispatcher())
        compose.setContent { Host(viewModel, handler) }
    }

    private fun linkCount() = compose.onAllNodes(hasTestTag("box.leaflet")).fetchSemanticsNodes().size

    private fun waitForLink() = compose.waitUntil { linkCount() > 0 }

    private fun recording() = object : UriHandler {
        override fun openUri(uri: String) {
            opened += uri
        }
    }

    @Test
    fun aKnownMedicineShowsTheLinkItsNoteAndOpensTheOfficialPage() {
        show(CabinetTestKit.DOLIPRANE)
        waitForLink()
        compose.onNodeWithText("Official page and leaflet").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Page of the French public medicines database, opened in the browser.")
            .assertIsDisplayed()
        val node = compose.onNodeWithTag("box.leaflet").fetchSemanticsNode()
        assertEquals(Role.Button, node.config[SemanticsProperties.Role])
        val bounds = compose.onNodeWithTag("box.leaflet").getBoundsInRoot()
        assertTrue(bounds.height >= 48.dp, "touch target is ${bounds.height}")
        compose.onNodeWithTag("box.leaflet").performClick()
        assertEquals(listOf(page), opened)
    }

    @Test
    fun theLinkIsBelowTheInformationAndAboveTheTakeOutActions() {
        show(CabinetTestKit.DOLIPRANE)
        waitForLink()
        val added = compose.onNodeWithTag("box.added").getBoundsInRoot()
        val link = compose.onNodeWithTag("box.leaflet").getBoundsInRoot()
        assertTrue(link.top >= added.bottom, "link ${link.top} must follow the information ${added.bottom}")
    }

    @Test
    fun aBoxWithoutACodeShowsNoLink() {
        show(null)
        compose.onNodeWithTag("box.name").assertIsDisplayed()
        assertEquals(0, linkCount())
    }

    @Test
    fun anUnknownCodeShowsNoLink() {
        show("03401900000003")
        compose.onNodeWithTag("box.name").assertIsDisplayed()
        assertEquals(0, linkCount())
    }

    @Test
    fun aBrowserThatCannotBeOpenedSaysSoWithoutCrashing() {
        val failing = object : UriHandler {
            override fun openUri(uri: String) = throw IllegalStateException("no browser")
        }
        show(CabinetTestKit.DOLIPRANE, failing)
        waitForLink()
        compose.onNodeWithTag("box.leaflet").performScrollTo().performClick()
        compose.onNodeWithText("The page could not be opened.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-w360dp-h2400dp", fontScale = 2f)
    fun theLinkStaysReachableAndKeepsItsTouchTargetAtDoubleFontScale() {
        show(CabinetTestKit.DOLIPRANE)
        waitForLink()
        compose.onNodeWithTag("box.leaflet").performScrollTo().assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("box.leaflet").getBoundsInRoot().height >= 48.dp)
        compose.onNodeWithTag("box.leaflet").performClick()
        assertEquals(listOf(page), opened)
    }
}
