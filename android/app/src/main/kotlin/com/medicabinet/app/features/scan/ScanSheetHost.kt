package com.medicabinet.app.features.scan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/** The sheets and dialogs that sit on top of the Scan tab: prompts, candidates, session list, manual entry, summary. */
@Composable
internal fun ScanSheetHost(
    model: ScanViewModel,
    state: ScanUiState,
    showsSessionList: Boolean,
    showsManualEntry: Boolean,
    onCloseSessionList: () -> Unit,
    onCloseManual: () -> Unit
) {
    val scope = rememberCoroutineScope()
    state.completion?.let { completion ->
        CompletionSheet(
            completion,
            onConfirm = { name, expiry -> scope.launch { model.confirmCompletion(name, expiry) } },
            onCancel = model::cancelCompletion
        )
    }
    state.candidates?.let { candidates ->
        RemovalCandidatesSheet(
            candidates,
            state.locations,
            onRemove = { scope.launch { model.removeCandidate(it) } },
            onCancel = model::cancelCandidates
        )
    }
    if (showsSessionList && state.mode == ScanMode.REMOVE) {
        RemovalSessionSheet(
            state.removalSession,
            state.locations,
            onUndo = { scope.launch { model.undoRemoval(it) } },
            onClose = { onCloseSessionList() }
        )
    } else if (showsSessionList) {
        SessionListSheet(
            state.session,
            state.locations,
            state.notice,
            onEdit = { box, name, expiry, location -> scope.launch { model.update(box, name, expiry, location) } },
            onDelete = { scope.launch { model.delete(it) } },
            onUndoDelete = { scope.launch { model.undoDelete(it) } },
            onClose = { onCloseSessionList() }
        )
    }
    if (showsManualEntry) {
        ManualEntrySheet(
            state.locations,
            state.sessionLocation?.id,
            onAdd = { code, name, expiry, location -> model.addManually(code, name, expiry, location) },
            onClose = { onCloseManual() }
        )
    }
    state.summary?.let { SummaryDialog(it, onClose = model::closeSummary) }
}
