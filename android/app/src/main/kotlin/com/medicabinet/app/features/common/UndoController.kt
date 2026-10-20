package com.medicabinet.app.features.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The one pending "message + Undo" the app offers after a removal or a deletion, shown as a Material 3 snackbar
 * (long duration, readable by TalkBack). A new action replaces the previous one: its undo is no longer offered.
 */
class UndoController(private val snackbars: SnackbarHostState, private val scope: CoroutineScope) {
    fun show(message: String, actionLabel: String, undo: suspend () -> Unit) {
        scope.launch {
            snackbars.currentSnackbarData?.dismiss()
            val result = snackbars.showSnackbar(message, actionLabel, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) undo()
        }
    }

    /** A message with no action (e.g. an undo that could not be done); replaces the current one. */
    fun notify(message: String) {
        scope.launch {
            snackbars.currentSnackbarData?.dismiss()
            snackbars.showSnackbar(message, duration = SnackbarDuration.Long)
        }
    }
}

/** Set by the main scaffold; `null` only outside it (isolated tests). */
val LocalUndoController = staticCompositionLocalOf<UndoController?> { null }
