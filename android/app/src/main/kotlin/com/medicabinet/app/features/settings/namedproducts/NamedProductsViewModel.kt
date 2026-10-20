package com.medicabinet.app.features.settings.namedproducts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medicabinet.app.app.AppLog
import com.medicabinet.core.NamedProductRepository
import com.medicabinet.core.NamedProductSummary
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import com.medicabinet.core.named.NamedProductEdit
import com.medicabinet.core.named.NamedProductEditError
import com.medicabinet.core.named.NamedProductEditException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface NamedProductsUiState {
    data object Loading : NamedProductsUiState

    /** Ordered by normalized name, then code; every row carries the boxes of its code still in the cabinet. */
    data class Loaded(val rows: List<NamedProductSummary>) : NamedProductsUiState

    data object Failed : NamedProductsUiState
}

/** How **Save** of the edit form ended. */
sealed interface SaveOutcome {
    /** Written, or nothing had changed. */
    data object Saved : SaveOutcome

    /** The name is refused; nothing was written. */
    data class Invalid(val error: NamedProductEditError) : SaveOutcome

    /** A write failed; the dialog says so and the data is as it was (or partly changed: the list shows the truth). */
    data object Failed : SaveOutcome
}

/** The row the user asked to forget, waiting for the confirmation. [boxesInCabinet] picks the message. */
data class ForgetRequest(val row: NamedProductSummary) {
    val boxesInCabinet: Int get() = row.boxesInCabinet
}

/** How **Undo** of a forget ended. */
enum class ForgetUndoOutcome {
    RESTORED,

    /** The code was named again meanwhile: the forgotten name is not brought back over it (F3). */
    NAMED_AGAIN,
    FAILED
}

/** A forget that can be undone; shown once as a message with **Undo**. The name is the product's, never logged. */
data class ForgetUndoOffer(val productName: String, val undo: suspend () -> ForgetUndoOutcome)

/**
 * Presentation state of Settings → Products you named. Every change goes through the repository; the list follows its
 * observation, so a rename also shows in the Cabinet tab and nothing here keeps a stale copy.
 */
class NamedProductsViewModel(private val repository: NamedProductRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<NamedProductsUiState>(NamedProductsUiState.Loading)
    val state: StateFlow<NamedProductsUiState> = mutableState.asStateFlow()

    private val mutableForgetRequest = MutableStateFlow<ForgetRequest?>(null)
    val forgetRequest: StateFlow<ForgetRequest?> = mutableForgetRequest.asStateFlow()

    private val mutableUndoOffer = MutableStateFlow<ForgetUndoOffer?>(null)
    val undoOffer: StateFlow<ForgetUndoOffer?> = mutableUndoOffer.asStateFlow()

    private val mutableForgetFailed = MutableStateFlow(false)

    /** Forgetting failed and nothing was changed: the screen says so until [acknowledgeForgetFailure]. */
    val forgetFailed: StateFlow<Boolean> = mutableForgetFailed.asStateFlow()

    init {
        observe()
    }

    /** Follows the repository until the screen goes away; also the **Retry** of the error state. */
    fun observe() {
        mutableState.value = NamedProductsUiState.Loading
        viewModelScope.launch {
            try {
                repository.observeSummaries().collect { mutableState.value = NamedProductsUiState.Loaded(it) }
            } catch (error: CancellationException) {
                throw error
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                AppLog.namedProductsFailed("observe", error)
                mutableState.value = NamedProductsUiState.Failed
            }
        }
    }

    /** What is wrong with the name of [edit], or `null` when it can be saved (for the field's message). */
    fun nameProblem(edit: NamedProductEdit): NamedProductEditError? = try {
        edit.validated()
        null
    } catch (error: NamedProductEditException) {
        error.error
    }

    /** Writes what the form changed: the name and the category apply to the product and all its boxes (E3, E4). */
    suspend fun save(edit: NamedProductEdit): SaveOutcome {
        val changes = try {
            edit.validated()
        } catch (error: NamedProductEditException) {
            return SaveOutcome.Invalid(error.error)
        }
        val id = edit.product.id
        return try {
            changes.name?.let { repository.rename(id, it) }
            changes.category?.let { repository.setCategory(id, it.id, it.overridden) }
            SaveOutcome.Saved
        } catch (error: CancellationException) {
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            AppLog.namedProductsFailed("save", error)
            SaveOutcome.Failed
        }
    }

    fun requestForget(row: NamedProductSummary) {
        mutableForgetRequest.value = ForgetRequest(row)
    }

    fun cancelForget() {
        mutableForgetRequest.value = null
    }

    /** Forgets the product (its boxes stay as they are) and offers **Undo**. */
    fun confirmForget() {
        val request = mutableForgetRequest.value ?: return
        mutableForgetRequest.value = null
        val product = request.row.product
        viewModelScope.launch {
            try {
                repository.forget(product.id)
                mutableUndoOffer.value = ForgetUndoOffer(product.name) { undoForget(product.id) }
            } catch (error: CancellationException) {
                throw error
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                AppLog.namedProductsFailed("forget", error)
                mutableForgetFailed.value = true
            }
        }
    }

    fun undoOfferShown() {
        mutableUndoOffer.value = null
    }

    fun acknowledgeForgetFailure() {
        mutableForgetFailed.value = false
    }

    private suspend fun undoForget(id: UUID): ForgetUndoOutcome = try {
        repository.undoForget(id)
        ForgetUndoOutcome.RESTORED
    } catch (error: RepositoryException) {
        AppLog.namedProductsFailed("undo", error)
        if (error.error == RepositoryError.DUPLICATE) ForgetUndoOutcome.NAMED_AGAIN else ForgetUndoOutcome.FAILED
    } catch (error: CancellationException) {
        throw error
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        AppLog.namedProductsFailed("undo", error)
        ForgetUndoOutcome.FAILED
    }
}
