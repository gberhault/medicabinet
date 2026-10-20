package com.medicabinet.app.features.cabinet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.app.AppLog
import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ExpiryStatus
import com.medicabinet.core.Location
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import com.medicabinet.core.cabinet.BoxEdit
import com.medicabinet.core.cabinet.BoxEditError
import com.medicabinet.core.cabinet.BoxEditException
import com.medicabinet.core.reference.LeafletLink
import com.medicabinet.core.reference.ReferenceProduct
import com.medicabinet.core.scan.ProductResolver
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** What the box detail shows. */
sealed interface BoxDetailState {
    data object Loading : BoxDetailState

    /** [status] is the expiry status today; an unknown expiry is [ExpiryStatus.UNKNOWN], never OK. */
    data class Loaded(val box: Box, val locations: List<Location>, val locationName: String, val status: ExpiryStatus) :
        BoxDetailState

    /** The box is no longer in the cabinet (taken out, deleted, imported over): the screen goes back. */
    data object Gone : BoxDetailState

    data object Failed : BoxDetailState
}

/** The ways to take a box out of the cabinet (B5, B6). */
enum class TakeOut { USED_UP, THROWN_AWAY, MISTAKE }

/** How a **Save** ended. */
sealed interface SaveResult {
    data object Saved : SaveResult

    /** The form is invalid; nothing was written. */
    data class Invalid(val error: BoxEditError) : SaveResult

    /** The write failed; the box is unchanged. */
    data object Failed : SaveResult
}

/** How an **Undo** ended. */
enum class UndoOutcome {
    RESTORED,

    /** Another box took the serial number meanwhile (deleted boxes only). */
    SERIAL_TAKEN,
    FAILED
}

/** A take-out that can be undone: shown once as a message with **Undo**. The name is the box's, never logged. */
data class UndoOffer(val action: TakeOut, val productName: String, val undo: suspend () -> UndoOutcome)

private const val STOP_TIMEOUT_MS = 5_000L

/**
 * Presentation state of one box: it follows the cabinet (through the port) so an edit, a take-out or an import shows
 * at once, and says [BoxDetailState.Gone] when the box leaves. Writes go through the repository ports only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoxDetailViewModel(
    private val boxId: UUID,
    private val environment: AppEnvironment,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {
    private val boxes = environment.boxes
    private val attempt = MutableStateFlow(0)
    private val mutableWriteFailed = MutableStateFlow(false)

    /** A write (the **Opened** switch, a take-out) failed and nothing was changed: the screen says so. */
    val writeFailed: StateFlow<Boolean> = mutableWriteFailed.asStateFlow()

    val state: StateFlow<BoxDetailState> = attempt.flatMapLatest { load(it) }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        BoxDetailState.Loading
    )

    fun retry() {
        attempt.update { it + 1 }
    }

    fun acknowledgeWriteFailure() {
        mutableWriteFailed.value = false
    }

    /**
     * The official BDPM page of the box's medicine (it carries the leaflet), or `null` while the code is looked up and
     * when there is none to show: no code, a code the reference does not know, a withdrawn presentation. The code is
     * looked up again only when it changes, so an edit of the box does not query the reference; the lookup runs off the
     * main thread and never delays the box itself.
     */
    val leaflet: StateFlow<String?> = boxes.observeCabinet()
        .map { all -> all.firstOrNull { it.id == boxId }?.product?.gtin }
        .distinctUntilChanged()
        .mapLatest { gtin -> gtin?.let { LeafletLink.address(referenceOf(it)) } }
        .catch { error ->
            AppLog.cabinetFailed("leaflet", error)
            emit(null)
        }
        .flowOn(dispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private suspend fun referenceOf(gtin: String): ReferenceProduct? = try {
        environment.reference.product(gtin)
    } catch (error: CancellationException) {
        throw error
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        AppLog.cabinetFailed("leaflet", error)
        null
    }

    private fun load(attempt: Int): Flow<BoxDetailState> = observe()
        .onStart { if (attempt > 0) emit(BoxDetailState.Loading) }
        .catch { error ->
            AppLog.cabinetFailed("detail", error)
            emit(BoxDetailState.Failed)
        }
        .flowOn(dispatcher)

    /** The box follows the cabinet, the locations, today and the "expiring soon" window. */
    private fun observe(): Flow<BoxDetailState> = combine(
        boxes.observeCabinet(),
        environment.locations.observeAll(),
        environment.todaySource.days(),
        environment.expiryPreferences.windowChanges()
    ) { all, locations, today, _ ->
        val box = all.firstOrNull { it.id == boxId }
        if (box == null) {
            BoxDetailState.Gone
        } else {
            val place = locations.firstOrNull { it.id == box.locationId }?.name.orEmpty()
            BoxDetailState.Loaded(box, locations, place, environment.expiryStatus(box.expiry, today))
        }
    }

    /** The **Opened** switch: saved at once; toggling again undoes it (B3). */
    suspend fun setOpened(opened: Boolean) {
        val box = (state.value as? BoxDetailState.Loaded)?.box ?: return
        if (box.isOpened == opened) return
        write("opened") { boxes.update(box.copy(isOpened = opened)) }
    }

    /** Saves the edit form (B2). Nothing is written when the form is invalid. */
    suspend fun save(edit: BoxEdit): SaveResult {
        val box = (state.value as? BoxDetailState.Loaded)?.box ?: return SaveResult.Failed
        return try {
            val edited = edit.applyTo(box)
            if (write("save") { boxes.update(edited) }) SaveResult.Saved else SaveResult.Failed
        } catch (error: BoxEditException) {
            SaveResult.Invalid(error.error)
        }
    }

    /**
     * Takes the box out (B5, B6): used up / thrown away are stock movements dated now; added by mistake deletes it.
     * Returns the offer to undo it, or `null` when the write failed (the box stays, [writeFailed] is raised).
     */
    suspend fun takeOut(action: TakeOut): UndoOffer? {
        val box = (state.value as? BoxDetailState.Loaded)?.box ?: return null
        val written = write("takeOut") {
            when (action) {
                TakeOut.USED_UP -> boxes.markRemoved(box.id, RemovalReason.USED_UP, environment.clock.now())
                TakeOut.THROWN_AWAY -> boxes.markRemoved(box.id, RemovalReason.THROWN_AWAY, environment.clock.now())
                TakeOut.MISTAKE -> boxes.delete(box.id)
            }
        }
        return if (written) {
            UndoOffer(action, box.product.name) {
                undoOutcome("undo") {
                    if (action == TakeOut.MISTAKE) boxes.undoDelete(box.id) else boxes.undoRemoval(box.id)
                }
            }
        } else {
            null
        }
    }

    /** The category the classifier gives this box (B4 **Automatic category**): its reference category, else none. */
    suspend fun automaticCategory(): CategoryID {
        val box = (state.value as? BoxDetailState.Loaded)?.box ?: return CategoryID.UNCATEGORIZED
        val reference = try {
            box.product.gtin?.let { environment.reference.product(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            AppLog.cabinetFailed("category", error)
            null
        }
        return ProductResolver.resolve(reference, previousBox = null).category
    }

    private suspend fun write(what: String, action: suspend () -> Unit): Boolean = try {
        action()
        true
    } catch (error: CancellationException) {
        throw error
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        AppLog.cabinetFailed(what, error)
        mutableWriteFailed.value = true
        false
    }

    private suspend fun undoOutcome(what: String, action: suspend () -> Unit): UndoOutcome = try {
        action()
        UndoOutcome.RESTORED
    } catch (error: RepositoryException) {
        AppLog.cabinetFailed(what, error)
        if (error.error == RepositoryError.DUPLICATE_SERIAL) UndoOutcome.SERIAL_TAKEN else UndoOutcome.FAILED
    } catch (error: CancellationException) {
        throw error
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        AppLog.cabinetFailed(what, error)
        UndoOutcome.FAILED
    }
}
