package com.medicabinet.app.features.settings.locations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medicabinet.app.app.AppLog
import com.medicabinet.core.Location
import com.medicabinet.core.LocationRepository
import com.medicabinet.core.cabinet.LocationName
import com.medicabinet.core.cabinet.LocationNameError
import com.medicabinet.core.cabinet.LocationNameException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LocationsUiState {
    data object Loading : LocationsUiState

    data class Loaded(val locations: List<Location>) : LocationsUiState

    data object Failed : LocationsUiState
}

/** Why a typed name was refused; the dialog shows the matching message. */
enum class NameProblem { EMPTY, TOO_LONG, DUPLICATE }

/**
 * Presentation state of the Locations screen. Every change goes through the repository; the list follows the
 * repository's observation, so nothing here caches a stale copy.
 */
class LocationsViewModel(private val repository: LocationRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<LocationsUiState>(LocationsUiState.Loading)
    val state: StateFlow<LocationsUiState> = mutableState.asStateFlow()

    /** Runs the writes; its `failed` flag raises the "nothing was changed" alert. */
    val writes = LocationWrites()

    /** Deleting a location, with the destination question and the Undo offer. */
    val deletions = LocationDeletions(repository, writes, viewModelScope) { locations }

    private val locations: List<Location> get() = (state.value as? LocationsUiState.Loaded)?.locations.orEmpty()

    init {
        observe()
    }

    /** Follows the repository until the screen goes away; also the **Retry** of the error state. */
    fun observe() {
        mutableState.value = LocationsUiState.Loading
        viewModelScope.launch {
            try {
                repository.observeAll().collect { mutableState.value = LocationsUiState.Loaded(it) }
            } catch (error: CancellationException) {
                throw error
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                AppLog.locationsFailed("observe", error)
                mutableState.value = LocationsUiState.Failed
            }
        }
    }

    /** `null` when [raw] is acceptable for a new location, or for renaming [renaming]. */
    fun problem(raw: String, renaming: Location? = null): NameProblem? = try {
        LocationName.validate(raw, locations.filter { it.id != renaming?.id }.map { it.name })
        null
    } catch (error: LocationNameException) {
        when (error.error) {
            LocationNameError.EMPTY -> NameProblem.EMPTY
            LocationNameError.TOO_LONG -> NameProblem.TOO_LONG
            LocationNameError.DUPLICATE_NAME -> NameProblem.DUPLICATE
        }
    }

    /** Returns the problem when the name is refused, `null` when the location was added. */
    suspend fun add(raw: String): NameProblem? = problem(raw) ?: writes.run("add") { repository.create(raw) }.problem

    suspend fun rename(location: Location, raw: String): NameProblem? =
        problem(raw, location) ?: writes.run("rename") { repository.rename(location.id, raw) }.problem

    /** Moves the row at [from] to position [to]; a move outside the list is ignored. */
    fun move(from: Int, to: Int) {
        val ids = locations.map { it.id }.toMutableList()
        if (from !in ids.indices || to !in ids.indices || from == to) return
        ids.add(to, ids.removeAt(from))
        viewModelScope.launch { writes.run("reorder") { repository.reorder(ids) } }
    }

    /** One step up (`-1`) or down (`+1`): the accessible alternative to dragging. */
    fun shift(location: Location, by: Int) {
        val index = locations.indexOf(location)
        if (index >= 0) move(index, index + by)
    }

    fun makeDefault(location: Location) {
        viewModelScope.launch { writes.run("default") { repository.setDefault(location.id) } }
    }
}
