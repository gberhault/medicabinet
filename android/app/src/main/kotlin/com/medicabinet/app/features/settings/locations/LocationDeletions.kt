package com.medicabinet.app.features.settings.locations

import com.medicabinet.core.Location
import com.medicabinet.core.LocationRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A deletion that needs the user to say where the boxes go. */
data class Deletion(val location: Location, val boxCount: Int, val destinations: List<Location>) {
    /** The pre-selected destination: the default location when it is not the one deleted. */
    val suggestedDestination: UUID?
        get() {
            val preferred = destinations.firstOrNull { it.isDefault } ?: destinations.firstOrNull()
            return preferred?.id
        }
}

/** A location was deleted and can be brought back; shown once, as a snackbar with Undo. */
data class UndoOffer(val locationName: String, val undo: suspend () -> Unit)

/**
 * Deleting a location. An empty one goes at once (with Undo); one that holds boxes asks where they go. The default
 * location is never deleted.
 */
class LocationDeletions(
    private val repository: LocationRepository,
    private val writes: LocationWrites,
    private val scope: CoroutineScope,
    private val locations: () -> List<Location>
) {
    private val mutableDeletion = MutableStateFlow<Deletion?>(null)
    val deletion: StateFlow<Deletion?> = mutableDeletion.asStateFlow()

    private val mutableUndoOffer = MutableStateFlow<UndoOffer?>(null)
    val undoOffer: StateFlow<UndoOffer?> = mutableUndoOffer.asStateFlow()

    fun request(location: Location) {
        if (location.isDefault) return
        scope.launch {
            val count = try {
                repository.cabinetBoxCount(location.id)
            } catch (error: CancellationException) {
                throw error
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                writes.fail("count", error)
                return@launch
            }
            if (count == 0) {
                deleteEmpty(location)
            } else {
                mutableDeletion.value = Deletion(location, count, locations().filter { it.id != location.id })
            }
        }
    }

    fun cancel() {
        mutableDeletion.value = null
    }

    fun confirm(request: Deletion, destination: UUID) {
        mutableDeletion.value = null
        scope.launch { writes.run("delete") { repository.deleteLocation(request.location.id, destination) } }
    }

    fun undoOfferShown() {
        mutableUndoOffer.value = null
    }

    private suspend fun deleteEmpty(location: Location) {
        val outcome = writes.run("delete") { repository.deleteLocation(location.id, null) }
        if (outcome is WriteOutcome.Done) {
            mutableUndoOffer.value = UndoOffer(location.name) { repository.undoDeleteLocation(location.id) }
        }
    }
}
