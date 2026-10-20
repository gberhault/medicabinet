package com.medicabinet.app.features.scan

import android.content.Context
import com.medicabinet.core.RemovalReason
import java.util.UUID

/** UI preferences of the scanner. Not cabinet data: never exported, never part of the cabinet backup. */
interface ScanPreferences {
    /** The location of the last scan session, preselected next time while it still exists. */
    var lastLocationId: UUID?

    /** Off by default. */
    var soundEnabled: Boolean

    /** Whether the camera permission was ever requested (Android cannot tell "never asked" from "refused for good"). */
    var cameraRequested: Boolean

    /** The reason last chosen in Remove mode (`scan.removalReason`); Used up until one was chosen. */
    var removalReason: RemovalReason
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

    override var removalReason: RemovalReason
        get() = RemovalReason.entries.firstOrNull { it.rawValue == store.getString(REMOVAL_REASON_KEY, null) }
            ?: RemovalReason.USED_UP
        set(value) = store.edit().putString(REMOVAL_REASON_KEY, value.rawValue).apply()

    companion object {
        const val FILE = "scan_preferences"
        const val LOCATION_KEY = "scan.lastLocationID"
        const val SOUND_KEY = "scan.soundEnabled"
        const val CAMERA_KEY = "scan.cameraRequested"
        const val REMOVAL_REASON_KEY = "scan.removalReason"
    }
}

class InMemoryScanPreferences : ScanPreferences {
    override var lastLocationId: UUID? = null
    override var soundEnabled: Boolean = false
    override var cameraRequested: Boolean = false
    override var removalReason: RemovalReason = RemovalReason.USED_UP
}
