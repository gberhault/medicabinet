package com.medicabinet.app.app

import android.util.Log

/**
 * The only logger. It records the failure kind and the exception class — never `message`, which could carry
 * data (Android has no private redaction like iOS `os.Logger`).
 */
object AppLog {
    private const val TAG = "MediCabinet"

    fun bootstrapFailed(stage: String, error: Throwable) {
        Log.e(TAG, "bootstrap failed at $stage: ${error::class.java.simpleName}")
    }
}
