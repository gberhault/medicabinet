package com.medicabinet.app.features.settings.namedproducts

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.medicabinet.app.features.common.LocalUndoController
import com.medicabinet.app.features.common.UndoController
import com.medicabinet.app.ui.theme.MediCabinetTheme
import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.FixedClock
import com.medicabinet.core.NamedProductRepository
import com.medicabinet.core.NamedProductSummary
import com.medicabinet.core.ProductRef
import com.medicabinet.core.testing.InMemoryCabinetStore
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

internal const val SUN = "03401900000003"
internal const val ARNICA = "03401900000010"

/** Seeds the in-memory cabinet shared by the screen tests: [boxes] boxes in the cabinet per named code. */
internal class NamedProductsKit {
    val clock = FixedClock(Instant.ofEpochSecond(1_800_000_000))
    val store = InMemoryCabinetStore(clock)

    fun name(gtin: String, name: String, boxes: Int = 0, category: CategoryID = CategoryID.UNCATEGORIZED) =
        runBlocking {
            store.named.remember(gtin, name, category, categoryOverridden = category != CategoryID.UNCATEGORIZED)
            val location = store.ensureDefault("Cabinet")
            repeat(boxes) {
                store.insert(
                    Box(
                        product = ProductRef(gtin, name),
                        locationId = location.id,
                        serial = UUID.randomUUID().toString().take(12),
                        createdAt = clock.now()
                    )
                )
            }
        }
}

@Composable
internal fun NamedProductsHost(viewModel: NamedProductsViewModel) {
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val controller = remember { UndoController(snackbars, scope) }
    MediCabinetTheme {
        CompositionLocalProvider(LocalUndoController provides controller) {
            Scaffold(snackbarHost = { SnackbarHost(snackbars) }) { padding ->
                NamedProductsScreen(viewModel, onBack = {}, modifier = Modifier.padding(padding))
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en")
class NamedProductsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val kit = NamedProductsKit()

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun show(repository: NamedProductRepository = kit.store.named): NamedProductsViewModel {
        val viewModel = NamedProductsViewModel(repository)
        compose.setContent { NamedProductsHost(viewModel) }
        return viewModel
    }

    @Test
    fun everyRowShowsTheNameTheCodeTheCategoryAndTheBoxesInTheCabinet() {
        kit.name(SUN, "Crème solaire", boxes = 2, category = CategoryID.SKIN_WOUND)
        kit.name(ARNICA, "Arnica")
        show()
        compose.onNodeWithText("Crème solaire").assertIsDisplayed()
        compose.onNodeWithText("3401900000003 · Skin and wounds").assertIsDisplayed()
        compose.onNodeWithText("2 boxes in the cabinet").assertIsDisplayed()
        compose.onNodeWithText("Arnica").assertIsDisplayed()
        compose.onNodeWithText("3401900000010 · Uncategorized").assertIsDisplayed()
        compose.onNodeWithText("Not in the cabinet").assertIsDisplayed()
    }

    @Test
    fun theRowsAreOrderedByName() {
        kit.name(SUN, "Zinc")
        kit.name(ARNICA, "Arnica")
        val viewModel = show()
        assertEquals(
            listOf("Arnica", "Zinc"),
            (viewModel.state.value as NamedProductsUiState.Loaded).rows.map { it.product.name }
        )
        compose.onNodeWithTag("namedProducts.list").assertIsDisplayed()
    }

    @Test
    fun anEmptyListSaysWhatTheScreenIsFor() {
        show()
        compose.onNodeWithText("No named product").assertIsDisplayed()
        compose.onNodeWithText(
            "When you scan a product the medicines database does not know, the name you give it appears here."
        ).assertIsDisplayed()
    }

    @Test
    fun aListThatCannotBeReadShowsAnErrorWithRetry() {
        kit.name(SUN, "Crème solaire")
        var broken = true
        val flaky = object : NamedProductRepository by kit.store.named {
            override fun observeSummaries(): Flow<List<NamedProductSummary>> =
                if (broken) flow { error("unreadable") } else kit.store.named.observeSummaries()
        }
        show(flaky)
        compose.onNodeWithText("List unavailable").assertIsDisplayed()
        compose.onNodeWithText("The named products could not be read.").assertIsDisplayed()
        broken = false
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Crème solaire").assertIsDisplayed()
    }

    @Test
    fun theRowActionsAreReachableWithAssistiveTechnology() {
        kit.name(SUN, "Crème solaire")
        show()
        val config = compose.onNodeWithText("Crème solaire").fetchSemanticsNode().config
        val actions = assertNotNull(config.getOrNull(SemanticsActions.CustomActions))
        assertEquals(listOf("Edit", "Forget"), actions.map { it.label })
    }

    @Test
    fun theMenuButtonAndTheRowAreAtLeast48dp() {
        kit.name(SUN, "Crème solaire")
        show()
        compose.onAllNodesWithTag("namedProducts.menu")[0]
            .assertTouchHeightIsEqualTo(48.dp).assertTouchWidthIsEqualTo(48.dp).assertHasClickAction()
        compose.onNodeWithText("Crème solaire").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun theScreenStaysUsableAtTwiceTheFontSize() {
        RuntimeEnvironment.setFontScale(2f)
        kit.name(SUN, "Crème solaire", boxes = 1)
        show()
        compose.onNodeWithText("Crème solaire").assertIsDisplayed()
        compose.onNodeWithText("1 box in the cabinet").assertIsDisplayed()
        compose.onNodeWithTag("namedProducts.menu").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("namedProducts.menu").performClick()
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithTag("namedProducts.edit.name").assertIsDisplayed()
        compose.onNodeWithTag("namedProducts.edit.save").assertIsDisplayed()
    }

    // edit

    private fun openEdit() {
        compose.onAllNodesWithTag("namedProducts.menu")[0].performClick()
        compose.onNodeWithText("Edit").performClick()
    }

    @Test
    fun renamingThroughTheDialogShowsTheNewNameAndRenamesTheBoxes() {
        kit.name(SUN, "Test", boxes = 1)
        show()
        openEdit()
        compose.onNodeWithText("Edit product").assertIsDisplayed()
        // Below the fold of a small screen: the dialog scrolls.
        compose.onNodeWithText(
            "The name and category apply to every box of this product, including used-up and thrown-away ones."
        ).assertExists()
        compose.onNodeWithTag("namedProducts.edit.name").performTextClearance()
        compose.onNodeWithTag("namedProducts.edit.name").performTextInput("Crème solaire")
        compose.onNodeWithTag("namedProducts.edit.save").performClick()
        compose.onNodeWithText("Crème solaire").assertIsDisplayed()
        assertEquals(
            "Crème solaire",
            runBlocking { kit.store.activeBoxes() }.single().product.name
        )
    }

    @Test
    fun anEmptyNameIsExplainedAndCannotBeSaved() {
        kit.name(SUN, "Test")
        show()
        openEdit()
        compose.onNodeWithTag("namedProducts.edit.name").performTextClearance()
        compose.onNodeWithText("The name can't be empty.").assertIsDisplayed()
        compose.onNodeWithTag("namedProducts.edit.save").assertIsNotEnabled()
    }

    @Test
    fun choosingACategoryThroughTheSharedPickerRecategorisesTheProduct() {
        kit.name(SUN, "Collyre")
        val viewModel = show()
        openEdit()
        compose.onNodeWithTag("namedProducts.edit.category.choose").performClick()
        compose.onNodeWithTag("namedProducts.edit.category.eyes").performClick()
        compose.onNodeWithTag("namedProducts.edit.save").performClick()
        val product = (viewModel.state.value as NamedProductsUiState.Loaded).rows.single().product
        assertEquals(CategoryID.EYES, product.category)
        compose.onNodeWithText("3401900000003 · Eyes").assertIsDisplayed()
    }

    @Test
    fun aFailedSaveSaysSoAndKeepsTheDialogOpen() {
        kit.name(SUN, "Test")
        val failing = object : NamedProductRepository by kit.store.named {
            override suspend fun rename(id: UUID, name: String) = error("disk full")
        }
        show(failing)
        openEdit()
        compose.onNodeWithTag("namedProducts.edit.name").performTextInput(" 2")
        compose.onNodeWithTag("namedProducts.edit.save").performClick()
        compose.onNodeWithText("Can't save the change.").assertExists()
        compose.onNodeWithTag("namedProducts.edit.name").assertIsDisplayed()
    }

    @Test
    fun cancellingTheDialogChangesNothing() {
        kit.name(SUN, "Test")
        show()
        openEdit()
        compose.onNodeWithTag("namedProducts.edit.name").performTextInput(" 2")
        compose.onNodeWithTag("namedProducts.edit.cancel").performClick()
        compose.onNodeWithText("Test").assertIsDisplayed()
    }

    // forget

    private fun openForget() {
        compose.onAllNodesWithTag("namedProducts.menu")[0].performClick()
        compose.onNodeWithText("Forget").performClick()
    }

    @Test
    fun theForgetQuestionNamesTheProductAndTheBoxesThatKeepTheirName() {
        kit.name(SUN, "Test", boxes = 2)
        show()
        openForget()
        compose.onNodeWithText("Forget “Test”?").assertIsDisplayed()
        compose.onNodeWithText(
            "2 boxes are still in the cabinet and keep their name. The next scan of this code will ask you for a name."
        ).assertIsDisplayed()
    }

    @Test
    fun theForgetQuestionHasASingularAndANoBoxVariant() {
        kit.name(SUN, "One", boxes = 1)
        kit.name(ARNICA, "Zero")
        show()
        openForget()
        compose.onNodeWithText(
            "1 box is still in the cabinet and keeps its name. The next scan of this code will ask you for a name."
        ).assertIsDisplayed()
        compose.onNodeWithTag("namedProducts.forget.cancel").performClick()
        compose.onAllNodesWithTag("namedProducts.menu")[1].performClick()
        compose.onNodeWithText("Forget").performClick()
        compose.onNodeWithText("The next scan of this code will ask you for a name.").assertIsDisplayed()
    }

    @Test
    fun forgettingRemovesTheRowKeepsTheBoxesAndUndoBringsItBack() {
        kit.name(SUN, "Test", boxes = 1)
        show()
        openForget()
        compose.onNodeWithTag("namedProducts.forget.confirm").performClick()
        compose.onNodeWithText("“Test” forgotten").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("Test", substring = false)).fetchSemanticsNodes().size)
        assertEquals("Test", runBlocking { kit.store.activeBoxes() }.single().product.name)
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Test").assertIsDisplayed()
    }

    @Test
    fun undoIsRefusedWithAMessageWhenTheCodeWasNamedAgain() {
        kit.name(SUN, "Old")
        show()
        openForget()
        compose.onNodeWithTag("namedProducts.forget.confirm").performClick()
        runBlocking { kit.store.named.remember(SUN, "New", CategoryID.UNCATEGORIZED, categoryOverridden = false) }
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Cannot restore: this code has been named again.").assertIsDisplayed()
    }

    @Test
    fun cancellingTheForgetQuestionKeepsTheProduct() {
        kit.name(SUN, "Test")
        show()
        openForget()
        compose.onNodeWithTag("namedProducts.forget.cancel").performClick()
        compose.onNodeWithText("Test").assertIsDisplayed()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "fr")
class NamedProductsScreenFrenchTest {
    @get:Rule
    val compose = createComposeRule()

    private val kit = NamedProductsKit()

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun theScreenSpeaksFrenchWithPluralBoxCounts() {
        kit.name(SUN, "Un", boxes = 1)
        kit.name(ARNICA, "Deux", boxes = 2)
        kit.name("03401900000027", "Zéro")
        compose.setContent { NamedProductsHost(NamedProductsViewModel(kit.store.named)) }
        compose.onNodeWithText("1 boîte dans l'armoire").assertIsDisplayed()
        compose.onNodeWithText("2 boîtes dans l'armoire").assertIsDisplayed()
        compose.onNodeWithText("Pas dans l'armoire").assertIsDisplayed()
        compose.onNodeWithText("Produits nommés par vous").assertIsDisplayed()
    }

    @Test
    fun theForgetQuestionIsFrenchWithItsPlurals() {
        kit.name(SUN, "Deux", boxes = 2)
        compose.setContent { NamedProductsHost(NamedProductsViewModel(kit.store.named)) }
        compose.onAllNodesWithTag("namedProducts.menu")[0].performClick()
        compose.onNodeWithText("Oublier").performClick()
        compose.onNodeWithText("Oublier « Deux » ?").assertIsDisplayed()
        compose.onNodeWithText(
            "2 boîtes sont encore dans l'armoire et gardent leur nom. " +
                "Le prochain scan de ce code vous demandera un nom."
        ).assertIsDisplayed()
    }
}
