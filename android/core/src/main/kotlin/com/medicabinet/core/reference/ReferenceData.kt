package com.medicabinet.core.reference

import com.medicabinet.core.ExpiryDate

/** The date of the bundled BDPM data, read from the reference database (`YYYY-MM-DD`, a real calendar day). */
data class ReferenceDataDate(val year: Int, val month: Int, val day: Int) {
    companion object {
        private val ISO = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})")

        /** `null` unless [iso] is exactly `YYYY-MM-DD` naming a real day. */
        fun parse(iso: String): ReferenceDataDate? {
            val parts = ISO.matchEntire(iso)?.groupValues?.drop(1)?.map { it.toInt() } ?: return null
            val (year, month, day) = parts
            return if (ExpiryDate.validate(year, month, day) == null) ReferenceDataDate(year, month, day) else null
        }
    }
}

/** Why the reference data cannot be used. Raw values are shared with the iOS app. */
enum class ReferenceUnavailableReason(val rawValue: String) {
    /** The bundled database is not there. */
    MISSING("missing"),

    /** It could not be opened, failed its integrity check or has no valid data date. */
    UNREADABLE("unreadable"),

    /** Its schema version is not one this app version understands. */
    UNSUPPORTED_SCHEMA("unsupportedSchema")
}

sealed interface ReferenceStatus {
    data class Available(val dataDate: ReferenceDataDate) : ReferenceStatus

    data class Unavailable(val reason: ReferenceUnavailableReason) : ReferenceStatus
}

/**
 * The bundled reference data as the app sees it: product lookup plus the state shown in About. When it is unavailable
 * every product is unknown; the cabinet keeps working.
 */
interface ReferenceDataSource : ProductLookup {
    suspend fun status(): ReferenceStatus
}
