package com.medicabinet.app.features.cabinet

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.medicabinet.app.features.MainScaffold
import com.medicabinet.app.features.scan.testScanServices
import com.medicabinet.app.ui.theme.MediCabinetTheme
import com.medicabinet.core.CategoryID
import com.medicabinet.core.RemovalReason
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Cabinet tab reached the way a user reaches it, inside the main scaffold with its undo snackbar. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w360dp-h2400dp")
class CabinetNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private val kit = CabinetTestKit()

    private fun open() {
        compose.setContent { MediCabinetTheme { MainScaffold(kit.environment, scanServices = testScanServices()) } }
        compose.onNodeWithText("Cabinet").performClick()
    }

    private fun waitFor(tag: String, count: Int = 1) = compose.waitUntil(WAIT_MS) {
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size >= count
    }

    private fun waitForText(text: String) = compose.waitUntil(WAIT_MS) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun theEmptyCabinetButtonOpensTheScanTab() {
        runBlocking { kit.open() }
        open()
        waitFor("cabinet.empty.scan")
        compose.onNodeWithTag("cabinet.empty.scan").performClick()
        compose.onNodeWithText("Allow the camera").assertIsDisplayed()
    }

    @Test
    fun aRowWithSeveralBoxesListsThemAndATakeOutComesBackToTheListWithAnUndo() {
        runBlocking {
            kit.open()
            kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE, opened = true)
            kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.EXPIRED)
        }
        open()
        waitFor("cabinet.row")
        compose.onNodeWithTag("cabinet.row").performClick()

        waitFor("product.box", 2)
        assertEquals(2, compose.onAllNodesWithTag("product.box").fetchSemanticsNodes().size)
        compose.onNodeWithText("Opened").assertIsDisplayed()
        // Earliest expiry first: the expired box is the first row.
        compose.onAllNodesWithTag("product.box")[0].performClick()

        waitFor("box.takeOut")
        compose.onNodeWithTag("box.takeOut").performClick()
        compose.onNodeWithTag("box.takeOut.thrownAway").performClick()
        waitFor("product.box")
        assertEquals(1, compose.onAllNodesWithTag("product.box").fetchSemanticsNodes().size)
        compose.onNodeWithText("Doliprane: thrown away").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        waitFor("product.box", 2)
    }

    @Test
    fun aRowWithOneBoxGoesStraightToItsDetailAndTheLastTakeOutReturnsToTheCabinet() {
        runBlocking {
            kit.open()
            kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE)
        }
        open()
        waitFor("cabinet.row")
        compose.onNodeWithTag("cabinet.row").performClick()
        waitFor("box.name")
        compose.onNodeWithTag("box.takeOut").performClick()
        compose.onNodeWithTag("box.takeOut.usedUp").performClick()
        waitFor("cabinet.empty.scan")
        compose.onNodeWithText("Doliprane: used up").assertIsDisplayed()
    }

    @Test
    fun theLastBoxOfAProductLeavingFromItsListReturnsToTheCabinetList() {
        val first = runBlocking {
            kit.open()
            kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE)
            kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.SOON)
            kit.box("Pansements", expiry = CabinetTestKit.FINE)
        }
        open()
        waitFor("cabinet.row", 2)
        compose.onAllNodesWithTag("cabinet.row")[0].performClick()
        waitFor("product.box", 2)
        runBlocking {
            kit.store.activeBoxes().filter { it.product.name == "Doliprane" }.forEach {
                kit.store.markRemoved(it.id, RemovalReason.USED_UP, kit.clock.now())
            }
        }
        waitFor("cabinet.row")
        compose.onNodeWithText("Pansements").assertIsDisplayed()
        assertEquals(CategoryID.UNCATEGORIZED, first.category)
    }

    @Test
    fun aProductWithoutACodeWhoseNameHasSpecialCharactersStillOpens() {
        runBlocking {
            kit.open()
            kit.box("Gel à 50%/ml {x}", expiry = CabinetTestKit.FINE)
            kit.box("Gel à 50%/ml {x}", expiry = CabinetTestKit.SOON)
        }
        open()
        waitFor("cabinet.row")
        compose.onNodeWithTag("cabinet.row").performClick()
        waitFor("product.box", 2)
        compose.onNodeWithText("Gel à 50%/ml {x}").assertIsDisplayed()
    }

    @Test
    fun theLocationFilterAlsoNarrowsTheProductsBoxes() {
        runBlocking {
            kit.open()
            val kitchen = kit.store.create("Kitchen")
            kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE)
            kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.SOON, location = kitchen)
            kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.EXPIRED, location = kitchen)
        }
        open()
        waitFor("cabinet.row")
        compose.onNodeWithTag("cabinet.filter.button").performClick()
        waitForText("Kitchen")
        compose.onAllNodesWithText("Kitchen")[0].performClick()
        waitFor("cabinet.filter.chip")
        compose.onNodeWithTag("cabinet.row").performClick()
        waitFor("product.box", 2)
        assertEquals(2, compose.onAllNodesWithTag("product.box").fetchSemanticsNodes().size)
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
