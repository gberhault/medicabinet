package com.medicabinet.app.app

import com.medicabinet.core.named.NamedProductBackfillRunner
import com.medicabinet.core.named.NamedProductBackfillRunner.Outcome
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Starts the one-time fill of the named products in the background once the cabinet is open. It runs on [io], never on
 * the launch path: a scan in the first moments after an upgrade may still ask for a name (the name typed wins).
 * Nothing is written unless the whole fill can be computed, so a launch where the reference is unavailable or a lookup
 * fails simply tries again at the next launch.
 */
class NamedProductBackfillLauncher(private val scope: CoroutineScope, private val io: CoroutineDispatcher) {
    fun start(environment: AppEnvironment): Job = scope.launch(io) {
        val runner = NamedProductBackfillRunner(
            environment.named,
            environment.boxes,
            environment.reference,
            environment.clock
        )
        try {
            when (runner.run()) {
                Outcome.REFERENCE_UNAVAILABLE -> AppLog.backfillStopped("reference-unavailable")
                Outcome.LOOKUP_FAILED -> AppLog.backfillStopped("lookup-failed")
                Outcome.NOT_PENDING, Outcome.DONE -> Unit
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            AppLog.backfillStopped("error", failure)
        }
    }
}
