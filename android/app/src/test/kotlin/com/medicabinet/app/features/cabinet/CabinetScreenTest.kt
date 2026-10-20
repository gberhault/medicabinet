package com.medicabinet.app.features.cabinet

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.ui.theme.MediCabinetTheme
import com.medicabinet.core.CategoryID
import com.medicabinet.core.Location
import com.medicabinet.core.cabinet.ProductRow
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w360dp-h2400dp")
class CabinetScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val scheduler = TestCoroutineScheduler()
    private val kit = CabinetTestKit()
    private var opened: ProductRow? = null
    private var scanOpened = 0

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun show(environment: AppEnvironment = kit.environment, dark: Boolean = false): CabinetViewModel {
        val viewModel = CabinetViewModel(environment, UnconfinedTestDispatcher(scheduler))
        compose.setContent {
            MediCabinetTheme(darkTheme = dark) {
                CabinetScreen(viewModel, onOpenProduct = { opened = it }, onOpenScan = { scanOpened++ })
            }
        }
        return viewModel
    }

    /** Lets the 150 ms search debounce elapse, then the UI settle. */
    private fun settle() {
        scheduler.advanceTimeBy(200)
        compose.waitForIdle()
    }

    private fun seed(): Location {
        val kitchen = runBlocking {
            kit.open()
            val kitchen = kit.store.create("Kitchen")
            kit.box(
                "Doliprane 1000",
                CabinetTestKit.DOLIPRANE,
                CabinetTestKit.EXPIRED,
                category = CategoryID.PAIN_FEVER
            )
            kit.box("Doliprane 1000", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE, category = CategoryID.PAIN_FEVER)
            kit.box("EFFÉRALGAN 500", CabinetTestKit.EFFERALGAN, CabinetTestKit.SOON, kitchen, CategoryID.PAIN_FEVER)
            kit.box("Pansements", expiry = null, location = kitchen)
            kitchen
        }
        return kitchen
    }

    @Test
    fun rowsAreGroupedUnderCategoriesWithCountsAndStatusTextAndIcon() {
        seed()
        show()
        compose.onNodeWithText("Pain and fever").assertIsDisplayed()
        compose.onNodeWithText("Uncategorized").assertIsDisplayed()
        compose.onNodeWithTag("cabinet.count").assertTextEquals("4 boxes")
        compose.onNodeWithText("2 boxes").assertIsDisplayed()
        compose.onNodeWithText("Doliprane 1000").assertIsDisplayed()
        compose.onNodeWithText("Expired").assertIsDisplayed()
        compose.onNodeWithText("Expiring soon").assertIsDisplayed()
        // The unknown-expiry row says so in words, and is never shown as OK.
        compose.onNodeWithText("Expiry unknown").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTextCount("OK"))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(hasText(text)).fetchSemanticsNodes().size

    @Test
    fun tappingARowOpensIt() {
        seed()
        show()
        compose.onNodeWithText("Pansements").assertHasClickAction().performClick()
        assertEquals("Pansements", assertNotNull(opened).name)
    }

    @Test
    fun searchingNarrowsTheListAndTheHeaderCount() {
        seed()
        show()
        compose.onNodeWithTag("cabinet.search").performTextInput("efferalgan")
        settle()
        compose.onNodeWithTag("cabinet.count").assertTextEquals("1 box")
        compose.onNodeWithText("EFFÉRALGAN 500").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTextCount("Pansements"))
        compose.onNodeWithText("Filters: efferalgan").assertIsDisplayed()
    }

    @Test
    fun noResultsOffersToClearTheSearchAndTheFilters() {
        seed()
        show()
        compose.onNodeWithTag("cabinet.search").performTextInput("zzzz")
        settle()
        compose.onNodeWithText("No results").assertIsDisplayed()
        compose.onNodeWithText("No box matches your search or filters.").assertIsDisplayed()
        compose.onNodeWithTag("cabinet.noResults.clear").performClick()
        settle()
        compose.onNodeWithTag("cabinet.count").assertTextEquals("4 boxes")
        assertEquals(0, compose.onAllNodesWithTextCount("No results"))
    }

    @Test
    fun theLocationMenuFiltersAndTheActiveFilterIsShownAsTextWithAClear() {
        val kitchen = seed()
        show()
        compose.onNodeWithTag("cabinet.filter.location").performClick()
        compose.onNodeWithTag("cabinet.filter.location.${kitchen.id}").performClick()
        settle()
        compose.onNodeWithTag("cabinet.count").assertTextEquals("2 boxes")
        compose.onNodeWithText("Filters: Kitchen").assertIsDisplayed()
        compose.onNodeWithTag("cabinet.filter.clear").assertHasClickAction().performClick()
        settle()
        compose.onNodeWithTag("cabinet.count").assertTextEquals("4 boxes")
        assertEquals(0, compose.onAllNodesWithTextCount("Filters: Kitchen"))
    }

    @Test
    fun theStatusMenuKeepsRowsWithABoxInThatStatus() {
        seed()
        show()
        compose.onNodeWithTag("cabinet.filter.status").performClick()
        compose.onNodeWithTag("cabinet.filter.status.expired").performClick()
        settle()
        compose.onNodeWithTag("cabinet.count").assertTextEquals("1 box")
        compose.onNodeWithText("Filters: Expired").assertIsDisplayed()
    }

    @Test
    fun clearingTheSearchFieldWithItsButtonShowsEverythingAgain() {
        seed()
        show()
        compose.onNodeWithTag("cabinet.search").performTextInput("zzzz")
        settle()
        compose.onNodeWithText("No results").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search and filters").performClick()
        settle()
        compose.onNodeWithTag("cabinet.count").assertTextEquals("4 boxes")
    }

    @Test
    fun anEmptyCabinetExplainsHowToAddBoxesAndOpensTheScanTab() {
        runBlocking { kit.open() }
        show()
        compose.onNodeWithText("The cabinet is empty").assertIsDisplayed()
        compose.onNodeWithText("Scan your boxes to take stock in a few minutes.").assertIsDisplayed()
        compose.onNodeWithTag("cabinet.empty.scan").assertHasClickAction().performClick()
        assertEquals(1, scanOpened)
    }

    @Test
    fun anUnreadableCabinetShowsAnErrorWithRetry() {
        runBlocking { kit.open() }
        show(kit.failingEnvironment())
        compose.onNodeWithText("Can't read the cabinet").assertIsDisplayed()
        compose.onNodeWithText("Your data has not been changed.").assertIsDisplayed()
        compose.onNodeWithTag("cabinet.retry").assertHasClickAction().performClick()
        compose.onNodeWithText("Can't read the cabinet").assertIsDisplayed()
    }

    @Test
    fun whileTheCabinetLoadsALoadingMessageIsShown() {
        runBlocking { kit.open() }
        val never = object : com.medicabinet.core.BoxRepository by kit.store {
            override fun observeCabinet(): Flow<List<com.medicabinet.core.Box>> = flow { awaitCancellation() }
        }
        show(AppEnvironment(kit.store, never, kit.clock, kit.environment.reference, kit.environment.zone))
        compose.onNodeWithText("Loading the cabinet…").assertIsDisplayed()
    }

    @Test
    fun everythingStaysReadableAtTwiceTheFontSizeInTheDarkTheme() {
        seed()
        RuntimeEnvironment.setFontScale(2f)
        show(dark = true)
        compose.onNodeWithText("Pain and fever").assertIsDisplayed()
        compose.onNodeWithTag("cabinet.filter.location").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("cabinet.list").performScrollToNode(hasText("Pansements"))
        compose.onNodeWithText("Pansements").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Expiry unknown").assertIsDisplayed()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "fr-w360dp-h2400dp")
class CabinetScreenFrenchTest {
    @get:Rule
    val compose = createComposeRule()

    private val kit = CabinetTestKit()

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun theCabinetSpeaksFrenchWithPluralsAndStatusWords() {
        runBlocking {
            kit.open()
            kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.EXPIRED)
            kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE)
            kit.box("Pansements")
        }
        val viewModel = CabinetViewModel(kit.environment, UnconfinedTestDispatcher())
        compose.setContent {
            MediCabinetTheme { CabinetScreen(viewModel, onOpenProduct = {}, onOpenScan = {}) }
        }
        compose.onNodeWithTag("cabinet.count").assertTextEquals("3 boîtes")
        compose.onNodeWithText("2 boîtes").assertIsDisplayed()
        compose.onNodeWithText("Non classé").assertIsDisplayed()
        compose.onNodeWithText("Périmé").assertIsDisplayed()
        compose.onNodeWithText("Péremption inconnue").assertIsDisplayed()
        compose.onNodeWithText("Rechercher un médicament").assertIsDisplayed()
    }
}
