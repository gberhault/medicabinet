package com.medicabinet.app.features.scan

enum class CameraAccess {
    /** Not decided yet, or refused once and can be asked again: explain, then ask. */
    NOT_DETERMINED,
    AUTHORIZED,

    /** Refused for good: only the app's settings can change it. */
    DENIED;

    companion object {
        /**
         * Android never says "never asked", so this app remembers it: refused with no rationale to show after a
         * request means the system will not prompt again.
         */
        fun resolve(granted: Boolean, requestedBefore: Boolean, showsRationale: Boolean): CameraAccess = when {
            granted -> AUTHORIZED
            !requestedBefore || showsRationale -> NOT_DETERMINED
            else -> DENIED
        }
    }
}
