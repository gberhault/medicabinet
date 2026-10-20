package com.medicabinet.app.features.settings.namedproducts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.medicabinet.app.R
import com.medicabinet.app.features.common.LocalUndoController
import com.medicabinet.core.NamedProductSummary

/**
 * The dialogs, the **Undo** offer and the failure message of the screen, driven by the view model. The message and its
 * Undo show above the navigation bar (the shared `UndoController`).
 */
@Composable
internal fun NamedProductsOverlays(
    viewModel: NamedProductsViewModel,
    editing: NamedProductSummary?,
    onEditDone: () -> Unit
) {
    val forgetRequest by viewModel.forgetRequest.collectAsStateWithLifecycle()
    val offer by viewModel.undoOffer.collectAsStateWithLifecycle()
    val forgetFailed by viewModel.forgetFailed.collectAsStateWithLifecycle()
    val controller = LocalUndoController.current
    val forgotten = stringResource(R.string.namedProducts_forgotten, offer?.productName.orEmpty())
    val undoLabel = stringResource(R.string.undo_action)
    val namedAgain = stringResource(R.string.namedProducts_undoFailed)
    val failed = stringResource(R.string.box_error)
    LaunchedEffect(offer) {
        offer?.let { shown ->
            controller?.show(forgotten, undoLabel) {
                when (shown.undo()) {
                    ForgetUndoOutcome.RESTORED -> Unit
                    ForgetUndoOutcome.NAMED_AGAIN -> controller.notify(namedAgain)
                    ForgetUndoOutcome.FAILED -> controller.notify(failed)
                }
            }
            viewModel.undoOfferShown()
        }
    }
    LaunchedEffect(forgetFailed) {
        if (forgetFailed) {
            controller?.notify(failed)
            viewModel.acknowledgeForgetFailure()
        }
    }
    editing?.let { NamedProductEditDialog(viewModel, it, onEditDone) }
    forgetRequest?.let {
        ForgetNamedProductDialog(it, onConfirm = viewModel::confirmForget, onDismiss = viewModel::cancelForget)
    }
}
