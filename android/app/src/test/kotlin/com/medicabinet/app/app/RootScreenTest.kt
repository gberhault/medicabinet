package com.medicabinet.app.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.medicabinet.app.ui.theme.MediCabinetTheme
import com.medicabinet.core.reference.ReferenceStatus
import com.medicabinet.core.reference.ReferenceUnavailableReason
import com.medicabinet.core.testing.InMemoryReferenceData
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en")
class BootstrapErrorScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun showError(failure: BootstrapFailure, onRetry: () -> Unit = {}) =
        compose.setContent { MediCabinetTheme { BootstrapErrorScreen(failure, onRetry) } }

    @Test
    fun backupFailureShowsItsTitleMessageAndRetry() {
        var retries = 0
        showError(BootstrapFailure.BACKUP) { retries++ }
        compose.onNodeWithText("Backup failed").assertIsDisplayed()
        compose.onNodeWithText("Your data has not been changed.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun migrationFailureSaysNothingIsLost() {
        showError(BootstrapFailure.MIGRATION)
        compose.onNodeWithText("Update failed").assertIsDisplayed()
        compose.onNodeWithText("nothing is lost", substring = true).assertIsDisplayed()
    }

    @Test
    fun storageFailureShowsItsOwnTitle() {
        showError(BootstrapFailure.STORAGE)
        compose.onNodeWithText("Storage unavailable").assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en")
class RootScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun loadingShowsAProgressMessage() {
        val viewModel = BootstrapViewModel { kotlinx.coroutines.CompletableDeferred<BootstrapState>().await() }
        compose.setContent { MediCabinetTheme { RootScreen(viewModel) } }
        compose.onNodeWithText("Opening your cabinet…").assertIsDisplayed()
    }

    @Test
    fun aFailureShowsTheBlockingErrorScreen() {
        val viewModel = BootstrapViewModel { BootstrapState.Failed(BootstrapFailure.MIGRATION) }
        compose.setContent { MediCabinetTheme { RootScreen(viewModel) } }
        compose.onNodeWithText("Update failed").assertIsDisplayed()
    }

    @Test
    fun readyShowsTheMainScaffold() {
        val store = com.medicabinet.core.testing.InMemoryCabinetStore(com.medicabinet.core.SystemClock)
        val reference = InMemoryReferenceData(status = ReferenceStatus.Unavailable(ReferenceUnavailableReason.MISSING))
        val environment = AppEnvironment(store, store, store.named, com.medicabinet.core.SystemClock, reference)
        val viewModel = BootstrapViewModel { BootstrapState.Ready(environment) }
        compose.setContent { MediCabinetTheme { RootScreen(viewModel) } }
        compose.onNode(hasText("Cabinet") and isSelectable()).assertIsDisplayed()
    }
}
