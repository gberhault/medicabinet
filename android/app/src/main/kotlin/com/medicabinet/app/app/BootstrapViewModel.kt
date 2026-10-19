package com.medicabinet.app.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Presentation state of app launch: loading → ready, or a blocking failure with Retry. */
sealed interface BootstrapUiState {
    data object Loading : BootstrapUiState

    data class Ready(val environment: AppEnvironment) : BootstrapUiState

    data class Failed(val failure: BootstrapFailure) : BootstrapUiState
}

class BootstrapViewModel(private val run: suspend () -> BootstrapState) : ViewModel() {
    private val mutableState = MutableStateFlow<BootstrapUiState>(BootstrapUiState.Loading)
    val state: StateFlow<BootstrapUiState> = mutableState.asStateFlow()

    private var running = false

    init {
        start()
    }

    /** Runs the bootstrap; also used by Retry. Ignored while one is in progress or once ready. */
    fun start() {
        if (running || mutableState.value is BootstrapUiState.Ready) return
        running = true
        mutableState.value = BootstrapUiState.Loading
        viewModelScope.launch {
            mutableState.value = when (val result = run()) {
                is BootstrapState.Ready -> BootstrapUiState.Ready(result.environment)
                is BootstrapState.Failed -> BootstrapUiState.Failed(result.failure)
            }
            running = false
        }
    }
}
