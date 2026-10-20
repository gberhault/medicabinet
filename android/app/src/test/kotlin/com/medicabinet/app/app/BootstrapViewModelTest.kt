package com.medicabinet.app.app

import app.cash.turbine.test
import com.medicabinet.core.SystemClock
import com.medicabinet.core.reference.ReferenceStatus
import com.medicabinet.core.reference.ReferenceUnavailableReason
import com.medicabinet.core.testing.InMemoryCabinetStore
import com.medicabinet.core.testing.InMemoryReferenceData
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BootstrapViewModelTest {
    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun environment(): AppEnvironment {
        val store = InMemoryCabinetStore(SystemClock)
        val reference = InMemoryReferenceData(status = ReferenceStatus.Unavailable(ReferenceUnavailableReason.MISSING))
        return AppEnvironment(store, store, store.named, SystemClock, reference)
    }

    @Test
    fun startsInLoadingThenBecomesReady() = runTest {
        val gate = CompletableDeferred<BootstrapState>()
        val viewModel = BootstrapViewModel { gate.await() }
        viewModel.state.test {
            assertEquals(BootstrapUiState.Loading, awaitItem())
            gate.complete(BootstrapState.Ready(environment()))
            assertIs<BootstrapUiState.Ready>(awaitItem())
        }
    }

    @Test
    fun exposesEachFailureKind() = runTest {
        BootstrapFailure.entries.forEach { failure ->
            val viewModel = BootstrapViewModel { BootstrapState.Failed(failure) }
            assertEquals(BootstrapUiState.Failed(failure), viewModel.state.value)
        }
    }

    @Test
    fun retryAfterAFailureCanSucceed() = runTest {
        val results =
            ArrayDeque(
                listOf<BootstrapState>(
                    BootstrapState.Failed(BootstrapFailure.MIGRATION),
                    BootstrapState.Ready(environment())
                )
            )
        var calls = 0
        val viewModel = BootstrapViewModel {
            calls++
            results.removeFirst()
        }
        assertEquals(BootstrapUiState.Failed(BootstrapFailure.MIGRATION), viewModel.state.value)
        viewModel.start()
        assertIs<BootstrapUiState.Ready>(viewModel.state.value)
        assertEquals(2, calls)
    }

    @Test
    fun startingAgainOnceReadyDoesNotRunBootstrapTwice() = runTest {
        var calls = 0
        val viewModel = BootstrapViewModel {
            calls++
            BootstrapState.Ready(environment())
        }
        viewModel.start()
        viewModel.start()
        assertEquals(1, calls)
    }

    @Test
    fun startingWhileARunIsInProgressIsIgnored() = runTest {
        val gate = CompletableDeferred<BootstrapState>()
        var calls = 0
        val viewModel = BootstrapViewModel {
            calls++
            gate.await()
        }
        viewModel.start()
        viewModel.start()
        assertEquals(1, calls)
        gate.complete(BootstrapState.Failed(BootstrapFailure.STORAGE))
        assertEquals(BootstrapUiState.Failed(BootstrapFailure.STORAGE), viewModel.state.value)
    }
}
