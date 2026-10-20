package com.medicabinet.app.app

import com.medicabinet.core.BoxRepository
import com.medicabinet.core.Clock
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ExpiryStatus
import com.medicabinet.core.LocationRepository
import com.medicabinet.core.NamedProductRepository
import com.medicabinet.core.archive.CabinetTransfer
import com.medicabinet.core.expiry.CalendarDay
import com.medicabinet.core.expiry.ExpiryRule
import com.medicabinet.core.expiry.ExpiryWindow
import com.medicabinet.core.named.ProductMemory
import com.medicabinet.core.reference.ReferenceDataSource
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Everything the UI layer needs, built once at launch. Screens see repository interfaces only. [reference] is the
 * bundled BDPM data; it is opened lazily, never during launch.
 *
 * [zone] turns the [clock]'s instant into "today" (the device time zone); [todaySource] says when "today" changes.
 * [expiryPreferences] holds the "expiring soon" window and the reminder choices.
 *
 * [named] holds the names the household gave to codes the reference does not know; [productMemory] resolves a scan
 * against the reference, then [named], and remembers a saved box (it needs [reference] to tell the two apart).
 *
 * [transfer] exports, imports and restores the whole cabinet (`null` only in tests that do not use it). [revision]
 * changes each time the whole cabinet was replaced (an import or a restore): screens that keep cabinet data of their
 * own, like the scan session, start again from the new cabinet (design §1.6).
 */
class AppEnvironment(
    val locations: LocationRepository,
    val boxes: BoxRepository,
    val named: NamedProductRepository,
    val clock: Clock,
    val reference: ReferenceDataSource,
    val zone: ZoneId = ZoneId.systemDefault(),
    val expiryPreferences: ExpiryPreferences = InMemoryExpiryPreferences(),
    val todaySource: TodaySource = DayTicker(clock, zone),
    val transfer: CabinetTransfer? = null,
    val productMemory: ProductMemory = ProductMemory(named, reference)
) {
    private val revisionCounter = MutableStateFlow(0L)

    /** Bumped by [cabinetReplaced]; starts at 0 for each launch. */
    val revision: StateFlow<Long> = revisionCounter.asStateFlow()

    /** The whole cabinet was replaced: every screen that caches cabinet data must reload. */
    fun cabinetReplaced() = revisionCounter.update { it + 1 }

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
