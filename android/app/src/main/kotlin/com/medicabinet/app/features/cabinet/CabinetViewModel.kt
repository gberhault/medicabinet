package com.medicabinet.app.features.cabinet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.app.AppLog
import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ExpiryStatus
import com.medicabinet.core.Location
import com.medicabinet.core.cabinet.CabinetFilter
import com.medicabinet.core.cabinet.CabinetRows
import com.medicabinet.core.cabinet.CabinetSnapshot
import com.medicabinet.core.expiry.CalendarDay
import com.medicabinet.core.expiry.ExpiryWindow
import com.medicabinet.core.reference.Taxonomy
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** What the Cabinet tab shows. */
sealed interface CabinetUiState {
    data object Loading : CabinetUiState

    /**
     * The list for the current filters. [isCabinetEmpty] tells "nothing was ever added" (the empty state) from "nothing
     * matches" (the no-results state).
     */
    data class Loaded(
        val snapshot: CabinetSnapshot,
        val filter: CabinetFilter,
        val locations: List<Location>,
        val isCabinetEmpty: Boolean,
        /** The day the statuses were evaluated for. */
        val today: CalendarDay,
        /** The "expiring soon" window they were evaluated with: a new window is a new state. */
        val window: ExpiryWindow
    ) : CabinetUiState {
        /** How many of the location and status filters are on; the search text has its own clear button. */
        val activeFilterCount: Int get() = listOf(filter.locationId, filter.status).count { it != null }
    }

    data object Failed : CabinetUiState
}

private const val SEARCH_DEBOUNCE_MS = 150L
private const val STOP_TIMEOUT_MS = 5_000L

/**
 * Presentation state of the Cabinet tab. It follows the cabinet and the locations through the repository ports, applies
 * the search (debounced), location and status filters, and builds the rows with the core's `CabinetRows` off the main
 * thread. Collected only while a Cabinet screen is visible, so scanning on another tab never re-queries the cabinet.
 * The filters last while the app runs and are never stored or logged.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class CabinetViewModel(
    private val environment: AppEnvironment,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {
    private val queryInput = MutableStateFlow("")
    private val locationFilter = MutableStateFlow<UUID?>(null)
    private val statusFilter = MutableStateFlow<ExpiryStatus?>(null)
    private val attempt = MutableStateFlow(0)
    private val foreground = MutableStateFlow(0)

    /** The category cards' order, A–Z by the localized labels; the screen sets it, the taxonomy's order until then. */
    private val categoryOrder = MutableStateFlow(Taxonomy.ordered)

    /** The text in the search field, as typed (the list follows it 150 ms later). */
    val query: StateFlow<String> = queryInput.asStateFlow()

    val state: StateFlow<CabinetUiState> = attempt.flatMapLatest { load(it) }.stateIn(
        viewModelScope,
        // Back to Loading after the pause, so a stale list (boxes taken out meanwhile) is never shown on return.
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS, replayExpirationMillis = 0),
        CabinetUiState.Loading
    )

    fun setQuery(text: String) {
        queryInput.value = text
    }

    fun setLocation(id: UUID?) {
        locationFilter.value = id
    }

    fun setStatus(status: ExpiryStatus?) {
        statusFilter.value = status
    }

    fun setCategoryOrder(order: List<CategoryID>) {
        categoryOrder.value = order
    }

    fun clearFilters() {
        queryInput.value = ""
        locationFilter.value = null
        statusFilter.value = null
    }

    /** The **Retry** of the error state. */
    fun retry() {
        attempt.update { it + 1 }
    }

    /** The app came back to the foreground: "today" may have changed, so statuses are evaluated again. */
    fun onForeground() {
        foreground.update { it + 1 }
    }

    private class Source(
        val boxes: List<Box>,
        val locations: List<Location>,
        val filter: CabinetFilter,
        val today: CalendarDay,
        val categoryOrder: List<CategoryID>
    )

    private fun load(attempt: Int): Flow<CabinetUiState> {
        val filters = combine(searchText(), locationFilter, statusFilter) { query, location, status ->
            CabinetFilter(query, location, status)
        }
        // Statuses depend on today and on the "expiring soon" window: either changing evaluates them again.
        val days = combine(
            foreground.flatMapLatest { environment.todaySource.days() },
            environment.expiryPreferences.windowChanges()
        ) { today, _ -> today }
        val boxes = environment.boxes.observeCabinet()
        val locations = environment.locations.observeAll()
        return combine(boxes, locations, filters, days, categoryOrder, ::Source)
            .mapLatest(::build)
            .onStart { if (attempt > 0) emit(CabinetUiState.Loading) }
            .catch { error ->
                AppLog.cabinetFailed("observe", error)
                emit(CabinetUiState.Failed)
            }
            .flowOn(dispatcher)
    }

    /** Typing waits [SEARCH_DEBOUNCE_MS]; emptying the field (and the first value) applies at once. */
    private fun searchText(): Flow<String> = queryInput.debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }

    private fun build(source: Source): CabinetUiState {
        // A location that was deleted meanwhile no longer filters anything (L10).
        val known = source.filter.locationId?.takeIf { id -> source.locations.any { it.id == id } }
        if (known != source.filter.locationId) locationFilter.compareAndSet(source.filter.locationId, null)
        val filter = source.filter.copy(locationId = known)
        val snapshot = CabinetRows.build(source.boxes, filter, source.categoryOrder) { box ->
            environment.expiryStatus(box.expiry, source.today)
        }
        return CabinetUiState.Loaded(
            snapshot,
            filter,
            source.locations,
            isCabinetEmpty = source.boxes.isEmpty(),
            today = source.today,
            window = environment.expiryWindow
        )
    }
}
