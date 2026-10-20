package com.medicabinet.app.app

import com.medicabinet.core.BoxRepository
import com.medicabinet.core.CategoryID
import com.medicabinet.core.Clock
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ExpiryStatus
import com.medicabinet.core.LocationRepository
import com.medicabinet.core.expiry.CalendarDay
import com.medicabinet.core.expiry.ExpiryRule
import com.medicabinet.core.expiry.ExpiryWindow
import com.medicabinet.core.reference.ReferenceDataSource
import com.medicabinet.core.reference.Taxonomy
import java.time.ZoneId

/**
 * Everything the UI layer needs, built once at launch. Screens see repository interfaces only. [reference] is the
 * bundled BDPM data; it is opened lazily, never during launch.
 *
 * [zone] turns the [clock]'s instant into "today" (the device time zone); [todaySource] says when "today" changes.
 * [expiryPreferences] holds the "expiring soon" window and the reminder choices. [categoryOrder] is the taxonomy's
 * order of the Cabinet sections.
 */
class AppEnvironment(
    val locations: LocationRepository,
    val boxes: BoxRepository,
    val clock: Clock,
    val reference: ReferenceDataSource,
    val zone: ZoneId = ZoneId.systemDefault(),
    val categoryOrder: List<CategoryID> = Taxonomy.ordered,
    val expiryPreferences: ExpiryPreferences = InMemoryExpiryPreferences(),
    val todaySource: TodaySource = DayTicker(clock, zone)
) {
    /** The "expiring soon" window right now (the user's setting). */
    val expiryWindow: ExpiryWindow get() = expiryPreferences.window

    /**
     * The core expiry rule bound to the current window. It reads the setting at call time; the screens that show
     * statuses also follow `expiryPreferences.windowChanges()`, so they evaluate again when the setting changes.
     */
    val expiryStatus: (ExpiryDate?, CalendarDay) -> ExpiryStatus = { expiry, today ->
        ExpiryRule.assess(expiry, today, expiryWindow).status
    }
}

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
