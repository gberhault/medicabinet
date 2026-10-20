package com.medicabinet.app.features.settings.locations

import com.medicabinet.app.app.AppLog
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How a repository write ended. */
sealed interface WriteOutcome {
    data object Done : WriteOutcome

    /** The name is already taken (decided by the repository, e.g. after a race). */
    data class Refused(val reason: NameProblem) : WriteOutcome

    /** Nothing was changed; [LocationWrites.failed] is raised. */
    data object Failed : WriteOutcome

    val problem: NameProblem? get() = (this as? Refused)?.reason
}

/**
 * Runs the repository writes of the Locations screen. A refused duplicate name is reported to the caller; any other
 * failure leaves the data as it was and raises [failed] (the failure kind is logged, never a name).
 */
class LocationWrites {
    private val mutableFailed = MutableStateFlow(false)

    /** A write failed and nothing was changed: the screen shows an alert until [acknowledge]. */
    val failed: StateFlow<Boolean> = mutableFailed.asStateFlow()

    /** [what] is a fixed word such as `rename`, used in the log. */
    suspend fun run(what: String, work: suspend () -> Unit): WriteOutcome = try {
        work()
        WriteOutcome.Done
    } catch (error: RepositoryException) {
        val duplicate = error.error == RepositoryError.DUPLICATE_NAME
        if (duplicate) WriteOutcome.Refused(NameProblem.DUPLICATE) else fail(what, error)
    } catch (error: CancellationException) {
        throw error
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        fail(what, error)
    }

    /** Raises the alert for a failure that did not come from [run] (e.g. a failed read before a deletion). */
    fun fail(what: String, error: Throwable): WriteOutcome {
        AppLog.locationsFailed(what, error)
        mutableFailed.value = true
        return WriteOutcome.Failed
    }

    fun acknowledge() {
        mutableFailed.value = false
    }
}
