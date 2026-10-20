package com.medicabinet.app.features.transfer

import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.medicabinet.app.app.BootstrapFailure
import com.medicabinet.app.app.BootstrapState
import com.medicabinet.app.app.BootstrapViewModel
import com.medicabinet.app.app.RootScreen
import com.medicabinet.app.ui.theme.MediCabinetTheme
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** O4, O5 on screen: forms hold an incoming file back with a banner; a cabinet that cannot open refuses it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en")
class ImportGatingTest {
    @get:Rule
    val compose = createComposeRule()

    private val dispatcher = UnconfinedTestDispatcher()
    private val coordinator = ImportCoordinator(TestScope(dispatcher), dispatcher) { "archive".toByteArray() }

    @Test
    fun aFormHoldsTheFileOnlyWhileItIsOpenAndTheBannerSaysSo() {
        val editing = mutableStateOf(true)
        compose.setContent {
            MediCabinetTheme {
                CompositionLocalProvider(LocalImportCoordinator provides coordinator) {
                    HoldsUnsavedInput(editing.value)
                    PendingImportBanner(coordinator)
                }
            }
        }
        assertTrue(coordinator.busy.value)
        coordinator.receive(Uri.parse("content://downloads/1"))
        compose.onNodeWithText("A cabinet file is waiting. Finish or cancel the current entry.").assertIsDisplayed()
        editing.value = false
        compose.waitForIdle()
        assertFalse(coordinator.busy.value)
        compose.onNodeWithTag("import.pendingBanner").assertDoesNotExist()
    }

    @Test
    fun withoutACoordinatorFormsHoldNothing() {
        compose.setContent { MediCabinetTheme { HoldsUnsavedInput(true) } }
        assertFalse(coordinator.busy.value)
    }

    @Test
    fun aCabinetThatCannotOpenRefusesTheFileWithTheReason() {
        coordinator.receive(Uri.parse("content://downloads/1"))
        val viewModel = BootstrapViewModel { BootstrapState.Failed(BootstrapFailure.STORAGE) }
        compose.setContent { MediCabinetTheme { RootScreen(viewModel, importCoordinator = coordinator) } }
        compose.onNodeWithText("Cannot import: the cabinet storage is unavailable.").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.waitForIdle()
        assertNull(coordinator.incoming.value)
        compose.onNodeWithText("Storage unavailable").assertIsDisplayed()
    }
}
