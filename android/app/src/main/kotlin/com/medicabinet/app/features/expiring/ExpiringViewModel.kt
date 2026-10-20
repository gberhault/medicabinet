package com.medicabinet.app.features.expiring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.app.AppLog
import com.medicabinet.core.Box
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.expiry.ExpiringOverview
import com.medicabinet.core.expiry.ExpiryWindow
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** What the Expiring tab shows. */
sealed interface ExpiringUiState {
    data object Loading : ExpiringUiState

    /** At least one box is expired, expiring soon or of unknown expiry. [locationNames] maps location ids to names. */
    class Content(val overview: ExpiringOverview, val window: ExpiryWindow, val locationNames: Map<UUID, String>) :
        ExpiringUiState

    /** Nothing to look at: the message names the [window]. */
    data class Empty(val window: ExpiryWindow) : ExpiringUiState

    data object Failed : ExpiringUiState
}

private const val STOP_TIMEOUT_MS = 5_000L

/**
 * Presentation state of the Expiring tab and of its tab badge, from one model so they never disagree. It follows the
 * cabinet, the locations, today and the "expiring soon" window, and builds the overview with the core's
 * `ExpiringOverview` off the main thread. Writes go through the repository port only. The state is kept while the app
 * is in the background and replaced by a fresh one on return.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpiringViewModel(
    private val environment: AppEnvironment,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {
    private val attempt = MutableStateFlow(0)

    val state: StateFlow<ExpiringUiState> = attempt.flatMapLatest { load(it) }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        ExpiringUiState.Loading
    )

    /** Expired plus expiring-soon boxes: the tab badge (unknown boxes do not count). */
    val attentionCount: StateFlow<Int> = state
        .map { (it as? ExpiringUiState.Content)?.overview?.attentionCount ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    /** The **Retry** of the error state. */
    fun retry() {
        attempt.update { it + 1 }
    }

    private fun load(attempt: Int): Flow<ExpiringUiState> = combine(
        environment.boxes.observeCabinet(),
        environment.locations.observeAll(),
        environment.todaySource.days(),
        environment.expiryPreferences.windowChanges()
    ) { boxes, locations, today, window ->
        val overview = ExpiringOverview.make(boxes, today, window)
        if (overview.isEmpty) {
            ExpiringUiState.Empty(window)
        } else {
            ExpiringUiState.Content(overview, window, locations.associate { it.id to it.name })
        }
    }
        .onStart { if (attempt > 0) emit(ExpiringUiState.Loading) }
        .catch { error ->
            AppLog.cabinetFailed("expiring", error)
            emit(ExpiringUiState.Failed)
        }
        .flowOn(dispatcher)

    /**
     * **Thrown away**: marks the box removed with that reason, dated now. Returns `false` when the write failed: the
     * box is then unchanged and still listed.
     */
    suspend fun throwAway(box: Box): Boolean = attemptWrite("throwAway") {
        environment.boxes.markRemoved(box.id, RemovalReason.THROWN_AWAY, environment.clock.now())
    }

    /** **Undo** of [throwAway]: puts the box back. Returns `false` when it could not be restored. */
    suspend fun undoThrowAway(id: UUID): Boolean = attemptWrite("undoThrowAway") {
        environment.boxes.undoRemoval(id)
    }

    private suspend fun attemptWrite(what: String, action: suspend () -> Unit): Boolean = try {
        action()
        true
    } catch (error: CancellationException) {
        throw error
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        AppLog.cabinetFailed(what, error)
        false
    }
}
