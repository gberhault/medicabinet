package com.medicabinet.app.features.cabinet

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
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

    private fun pill(name: String) = compose.onNode(
        hasTestTag("cabinet.pill") and hasContentDescription(name, substring = true)
    )

    private fun pillCount(name: String) = compose.onAllNodes(
        hasTestTag("cabinet.pill") and hasContentDescription(name, substring = true)
    ).fetchSemanticsNodes().size

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    @Test
    fun cardsShowCategoryTotalsAndPillsMarkOnlyAlertStatuses() {
        seed()
        show()
        compose.onNodeWithText("Pain and fever").assertIsDisplayed()
        compose.onNodeWithText("Uncategorized").assertIsDisplayed()
        compose.onNodeWithTag("cabinet.count").assertTextEquals("4 boxes")
        compose.onNodeWithText("3 boxes").assertIsDisplayed()
        // The spoken label has the full name, the count and, for an alert, the status; an ok pill says nothing more.
        pill("Doliprane 1000, 2 boxes, Expired").assertIsDisplayed()
        pill("EFFÉRALGAN 500, 1 box, Expiring soon").assertIsDisplayed()
        pill("Pansements, 1 box, Expiry unknown").assertIsDisplayed()
        // No date and no OK anywhere in the list.
        assertEquals(0, compose.onAllNodesWithTextCount("OK"))
        assertEquals(0, compose.onAllNodesWithTextCount("Expires"))
    }

    @Test
    fun anOkPillSaysNothingMoreThanItsNameAndCount() {
        runBlocking {
            kit.open()
            kit.box("Aspirine", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE, category = CategoryID.PAIN_FEVER)
        }
        show()
        pill("Aspirine").assert(hasContentDescription("Aspirine, 1 box"))
    }

    @Test
    fun tappingAPillOpensIt() {
        seed()
        show()
        pill("Pansements").assertHasClickAction().performClick()
        assertEquals("Pansements", assertNotNull(opened).name)
    }

    @Test
    fun aPillTakesTapsOver48Dp() {
        seed()
        show()
        pill("Pansements").assertTouchHeightIsEqualTo(48.dp).assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun searchingNarrowsTheListAndTheHeaderCount() {
        seed()
        show()
        compose.onNodeWithTag("cabinet.search").performTextInput("efferalgan")
        settle()
        compose.onNodeWithTag("cabinet.count").assertTextEquals("1 box")
        pill("EFFÉRALGAN 500").assertIsDisplayed()
        assertEquals(0, pillCount("Pansements"))
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
    fun theLocationMenuFiltersAndAChipShowsItAndClearsOnlyThatFilter() {
        val kitchen = seed()
        show()
        compose.onNodeWithTag("cabinet.filter.button").performClick()
        compose.onNodeWithTag("cabinet.filter.location.${kitchen.id}").performClick()
        settle()
        compose.onNodeWithTag("cabinet.count").assertTextEquals("2 boxes")
        compose.onNodeWithContentDescription("Remove filter Kitchen").assertHasClickAction()

        compose.onNodeWithTag("cabinet.filter.button").performClick()
        compose.onNodeWithTag("cabinet.filter.status.expiringSoon").performClick()
        settle()
        compose.onNodeWithTag("cabinet.count").assertTextEquals("1 box")
        compose.onNodeWithContentDescription("Remove filter Expiring soon").assertIsDisplayed()

        compose.onNodeWithContentDescription("Remove filter Kitchen").performClick()
        settle()
        assertEquals(0, compose.onAllNodesWithTextCount("Kitchen"))
        compose.onNodeWithContentDescription("Remove filter Expiring soon").assertIsDisplayed()
        compose.onNodeWithTag("cabinet.count").assertTextEquals("1 box")
    }

    @Test
    fun theStatusMenuKeepsRowsWithABoxInThatStatus() {
        seed()
        show()
        compose.onNodeWithTag("cabinet.filter.button").performClick()
        compose.onNodeWithTag("cabinet.filter.status.expired").performClick()
        settle()
        compose.onNodeWithTag("cabinet.count").assertTextEquals("1 box")
        compose.onNodeWithContentDescription("Remove filter Expired").assertIsDisplayed()
    }

    @Test
    fun clearFiltersInTheMenuClearsLocationAndStatusButKeepsTheSearch() {
        val kitchen = seed()
        show()
        compose.onNodeWithTag("cabinet.search").performTextInput("a")
        compose.onNodeWithTag("cabinet.filter.button").performClick()
        compose.onNodeWithTag("cabinet.filter.location.${kitchen.id}").performClick()
        settle()
        compose.onNodeWithContentDescription("Remove filter Kitchen").assertIsDisplayed()

        compose.onNodeWithTag("cabinet.filter.button").performClick()
        compose.onNodeWithTag("cabinet.filter.clear").performClick()
        settle()
        assertEquals(0, compose.onAllNodesWithTextCount("Kitchen"))
        compose.onNodeWithTag("cabinet.search").assertTextContains("a")
    }

    @Test
    fun noFilterMenuChipsAreShownWhileNoFilterIsOn() {
        seed()
        show()
        assertEquals(0, compose.onAllNodesWithTag("cabinet.filter.chip").fetchSemanticsNodes().size)
    }

    @Test
    fun theSearchFieldAndTheFilterButtonShareOneRowAndTheButtonIsOnTheRight() {
        seed()
        show()
        val search = compose.onNodeWithTag("cabinet.search").getBoundsInRoot()
        val button = compose.onNodeWithTag("cabinet.filter.button").getBoundsInRoot()
        assert(button.left >= search.right - 1.dp) { "the button sits right of the field" }
        assert(kotlin.math.abs((button.top + button.bottom).value - (search.top + search.bottom).value) < 8f) {
            "on the same line"
        }
    }

    @Test
    fun theFilterButtonSaysHowManyFiltersAreOn() {
        val kitchen = seed()
        show()
        compose.onNodeWithTag("cabinet.filter.button")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        compose.onNodeWithTag("cabinet.filter.button").performClick()
        compose.onNodeWithTag("cabinet.filter.location.${kitchen.id}").performClick()
        settle()
        compose.onNodeWithTag("cabinet.filter.button")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1 active filter"))
    }

    @Test
    fun clearingTheSearchFieldWithItsButtonShowsEverythingAgain() {
        seed()
        show()
        compose.onNodeWithTag("cabinet.search").performTextInput("zzzz")
        settle()
        compose.onNodeWithText("No results").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").performClick()
        settle()
        compose.onNodeWithTag("cabinet.count").assertTextEquals("4 boxes")
    }

    private fun categoryLabelsTopToBottom(): List<String> = compose.onAllNodes(hasTestTag("cabinet.category"))
        .fetchSemanticsNodes()
        .sortedBy { it.boundsInRoot.top }
        .map { it.config[SemanticsProperties.Text].joinToString(" ") }

    @Test
    fun theCardsAreAToZByTheEnglishLabelsWithUncategorizedLast() {
        runBlocking {
            kit.open()
            kit.box("Yeux gouttes", "03400930000014", CabinetTestKit.FINE, category = CategoryID.EYES)
            kit.box("Gaviscon", "03400930000021", CabinetTestKit.FINE, category = CategoryID.DIGESTION)
            kit.box("Doliprane", "03400930000038", CabinetTestKit.FINE, category = CategoryID.PAIN_FEVER)
            kit.box("Divers", "03400930000045", CabinetTestKit.FINE, category = CategoryID.OTHER)
            kit.box("Mystery", expiry = CabinetTestKit.FINE)
        }
        show()
        settle()
        val labels = categoryLabelsTopToBottom().map { it.substringBefore(" 1 box") }
        assertEquals(listOf("Digestion", "Eyes", "Pain and fever", "Other", "Uncategorized"), labels)
    }

    @Test
    fun aThousandBoxesInOneCategoryStillShowTheirPills() {
        runBlocking {
            kit.open()
            repeat(PRODUCTS) { index ->
                repeat(BOXES_PER_PRODUCT) {
                    kit.box(
                        "Medicine " + index,
                        "0340093" + index.toString().padStart(GTIN_PAD, '0'),
                        CabinetTestKit.FINE,
                        category = CategoryID.PAIN_FEVER
                    )
                }
            }
        }
        show()
        settle()
        compose.onNodeWithTag("cabinet.count").assertTextEquals((PRODUCTS * BOXES_PER_PRODUCT).toString() + " boxes")
        pill("Medicine 0, 4 boxes").assertExists()
    }

    private companion object {
        const val PRODUCTS = 250
        const val BOXES_PER_PRODUCT = 4
        const val GTIN_PAD = 7
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
        compose.onNodeWithTag("cabinet.filter.button").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("cabinet.list").performScrollToNode(hasText("Uncategorized"))
        pill("Pansements, 1 box, Expiry unknown").assertIsDisplayed().assertHasClickAction()
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
        compose.onNodeWithText("Non classé").assertIsDisplayed()
        compose.onNode(hasContentDescription("Doliprane, 2 boîtes, Périmé")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Pansements, 1 boîte, Péremption inconnue")).assertIsDisplayed()
        compose.onNodeWithText("Rechercher un médicament").assertIsDisplayed()
    }
}
