package com.medicabinet.app.features.scan

import android.content.Context
import java.util.UUID

/** UI preferences of the scanner. Not cabinet data: never exported, never part of the cabinet backup. */
interface ScanPreferences {
    /** The location of the last scan session, preselected next time while it still exists. */
    var lastLocationId: UUID?

    /** Off by default. */
    var soundEnabled: Boolean

    /** Whether the camera permission was ever requested (Android cannot tell "never asked" from "refused for good"). */
    var cameraRequested: Boolean
}

class SharedScanPreferences(context: Context) : ScanPreferences {
    private val store = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var lastLocationId: UUID?
        get() = store.getString(LOCATION_KEY, null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        set(value) = store.edit().putString(LOCATION_KEY, value?.toString()).apply()

    override var soundEnabled: Boolean
        get() = store.getBoolean(SOUND_KEY, false)
        set(value) = store.edit().putBoolean(SOUND_KEY, value).apply()

    override var cameraRequested: Boolean
        get() = store.getBoolean(CAMERA_KEY, false)
        set(value) = store.edit().putBoolean(CAMERA_KEY, value).apply()

    companion object {
        const val FILE = "scan_preferences"
        const val LOCATION_KEY = "scan.lastLocationID"
        const val SOUND_KEY = "scan.soundEnabled"
        const val CAMERA_KEY = "scan.cameraRequested"
    }
}

class InMemoryScanPreferences : ScanPreferences {
    override var lastLocationId: UUID? = null
    override var soundEnabled: Boolean = false
    override var cameraRequested: Boolean = false
}
