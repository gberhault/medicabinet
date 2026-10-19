package com.medicabinet.app.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medicabinet.core.reference.ReferenceDataDate
import com.medicabinet.core.reference.ReferenceDataSource
import com.medicabinet.core.reference.ReferenceStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What About shows about the bundled BDPM data: loading, its date, or that it is unavailable. */
sealed interface ReferenceUiState {
    data object Loading : ReferenceUiState

    data class Available(val dataDate: ReferenceDataDate) : ReferenceUiState

    data object Unavailable : ReferenceUiState
}

/**
 * The values About shows. The version line itself is formatted by the screen from `about_version` (fr/en). The
 * reference data status is asked of [reference] (which opens the database if it was not yet) when About is created;
 * `null` means there is no environment, so unavailable.
 */
class AboutViewModel(info: AppInfo, reference: ReferenceDataSource? = null) : ViewModel() {
    val version: String = info.version
    val build: String = info.build

    private val mutableReference = MutableStateFlow<ReferenceUiState>(ReferenceUiState.Loading)
    val referenceState: StateFlow<ReferenceUiState> = mutableReference

    init {
        if (reference == null) {
            mutableReference.value = ReferenceUiState.Unavailable
        } else {
            viewModelScope.launch {
                mutableReference.value = when (val status = reference.status()) {
                    is ReferenceStatus.Available -> ReferenceUiState.Available(status.dataDate)
                    is ReferenceStatus.Unavailable -> ReferenceUiState.Unavailable
                }
            }
        }
    }

    companion object {
        /** Where the data comes from (the licence asks for the source to be cited). */
        const val REFERENCE_ADDRESS = "base-donnees-publique.medicaments.gouv.fr"

        /** The date in the user's locale. It is a calendar day, not an instant: no time zone is involved. */
        fun formatDate(date: ReferenceDataDate, locale: Locale): String =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                .withLocale(locale)
                .format(LocalDate.of(date.year, date.month, date.day))
    }
}
