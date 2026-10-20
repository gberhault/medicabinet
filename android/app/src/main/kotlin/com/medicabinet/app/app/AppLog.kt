package com.medicabinet.app.app

import android.util.Log
import com.medicabinet.core.reference.ReferenceUnavailableReason

/**
 * The only logger. It records the failure kind and the exception class — never `message`, which could carry
 * data (Android has no private redaction like iOS `os.Logger`).
 */
object AppLog {
    private const val TAG = "MediCabinet"

    fun bootstrapFailed(stage: String, error: Throwable) {
        Log.e(TAG, "bootstrap failed at $stage: ${error::class.java.simpleName}")
    }

    /** The reason (a fixed word) and the exception class, never a path, a code or a product. */
    fun referenceUnavailable(reason: ReferenceUnavailableReason, error: Throwable?) {
        val cause = error?.let { ", ${it::class.java.simpleName}" }.orEmpty()
        Log.e(TAG, "reference data unavailable: ${reason.rawValue}$cause")
    }

    /** A change to the locations failed; [what] is a fixed word such as `rename`, never a name. */
    fun locationsFailed(what: String, error: Throwable) {
        Log.e(TAG, "locations $what failed: ${error::class.java.simpleName}")
    }

    /** The Cabinet list or a box could not be read or written; [what] is a fixed word, never a name or a code. */
    fun cabinetFailed(what: String, error: Throwable) {
        Log.e(TAG, "cabinet $what failed: ${error::class.java.simpleName}")
    }

    /** A step of the monthly digest failed; [what] is a fixed word such as `read` or `notify`. */
    fun reminderFailed(what: String, error: Throwable) {
        Log.e(TAG, "reminder $what failed: ${error::class.java.simpleName}")
    }

    /** The cabinet could not be opened for the digest; only the fixed failure kind is logged. */
    fun reminderUnavailable(failure: BootstrapFailure) {
        Log.e(TAG, "reminder cabinet unavailable: ${failure.name}")
    }
}
