package com.medicabinet.app.app

import com.medicabinet.core.BoxRepository
import com.medicabinet.core.Clock
import com.medicabinet.core.LocationRepository
import com.medicabinet.core.reference.ReferenceDataSource

/**
 * Everything the UI layer needs, built once at launch. Screens see repository interfaces only. [reference] is the
 * bundled BDPM data; it is opened lazily, never during launch.
 */
class AppEnvironment(
    val locations: LocationRepository,
    val boxes: BoxRepository,
    val clock: Clock,
    val reference: ReferenceDataSource
)

enum class BootstrapFailure {
    /** The database could not be opened, read or written (e.g. no disk space, unreadable file). */
    STORAGE,

    /** The pre-migration backup could not be written; nothing was migrated. */
    BACKUP,

    /** A migration failed; the database was left as it was. */
    MIGRATION
}

sealed interface BootstrapState {
    data class Ready(val environment: AppEnvironment) : BootstrapState

    data class Failed(val failure: BootstrapFailure) : BootstrapState
}
