package com.medicabinet.app.reminders

import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.app.BootstrapFailure
import com.medicabinet.app.app.BootstrapState
import com.medicabinet.app.app.InMemoryExpiryPreferences
import com.medicabinet.core.Box
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.Location
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.expiry.DigestCounts
import com.medicabinet.core.expiry.ExpiryWindow
import com.medicabinet.core.reference.ReferenceStatus
import com.medicabinet.core.reference.ReferenceUnavailableReason
import com.medicabinet.core.testing.InMemoryCabinetStore
import com.medicabinet.core.testing.InMemoryReferenceData
import com.medicabinet.core.testing.MutableClock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking

class FakeNotificationAccess(var canPostNow: Boolean = false, var permissionMissing: Boolean = true) :
    NotificationAccess {
    override fun canPost(): Boolean = canPostNow

    override fun needsRuntimePermission(): Boolean = permissionMissing

    /** The user grants the permission in the system prompt. */
    fun grant() {
        canPostNow = true
        permissionMissing = false
    }
}

class RecordingScheduling : ReminderScheduling {
    /** The `notBefore` of each call, in order. */
    val calls = mutableListOf<Instant?>()
    val arms: Int get() = calls.size

    override fun arm(notBefore: Instant?) {
        calls += notBefore
    }
}

class RecordingNotifier(var failure: Exception? = null) : DigestNotifier {
    val posted = mutableListOf<Pair<DigestCounts, ExpiryWindow>>()

    override fun post(counts: DigestCounts, window: ExpiryWindow) {
        failure?.let { throw it }
        posted += counts to window
    }
}

/** A reminder model on fakes; "now" is 2027-01-15 12:00 UTC unless the test moves the clock. */
class ReminderTestKit(
    val access: FakeNotificationAccess = FakeNotificationAccess(),
    now: Instant = Instant.parse("2027-01-15T12:00:00Z")
) {
    val preferences = InMemoryExpiryPreferences()
    val scheduling = RecordingScheduling()
    val clock = MutableClock(now)

    fun model() = ReminderModel(preferences, access, scheduling, clock) { ZoneOffset.UTC }
}

/**
 * The digest delivery on an in-memory cabinet, a recording notifier and scheduling. Reminders are on and notifications
 * allowed; "now" is the argument.
 */
class DeliveryKit(now: Instant, var zone: ZoneId = ZoneOffset.UTC, val notifier: DigestNotifier = RecordingNotifier()) {
    val clock = MutableClock(now)
    val store = InMemoryCabinetStore(clock)
    val preferences = InMemoryExpiryPreferences().also { it.reminderEnabled = true }
    val access = FakeNotificationAccess(canPostNow = true, permissionMissing = false)
    val scheduling = RecordingScheduling()

    /** Replaces the opened cabinet (a failure, for instance). */
    var state: BootstrapState? = null
    var openFailure: Exception? = null
    private lateinit var home: Location
    private val environment = AppEnvironment(
        store,
        store,
        clock,
        InMemoryReferenceData(status = ReferenceStatus.Unavailable(ReferenceUnavailableReason.MISSING))
    )

    val delivery = DigestDelivery(
        openCabinet = {
            openFailure?.let { throw it }
            state ?: BootstrapState.Ready(environment)
        },
        preferences = preferences,
        access = access,
        notifier = notifier,
        scheduling = scheduling,
        clock = clock,
        zone = { zone }
    )

    /** The notifier as a recorder; only valid when it is one. */
    val recorded: RecordingNotifier get() = notifier as RecordingNotifier

    init {
        runBlocking { home = store.ensureDefault("Home") }
    }

    fun box(name: String, expiry: ExpiryDate?, lot: String? = null, serial: String? = null): Box = runBlocking {
        val box = Box(
            product = ProductRef(null, name),
            locationId = home.id,
            expiry = expiry,
            lot = lot,
            serial = serial,
            createdAt = clock.now()
        )
        store.insert(box)
        box
    }

    fun remove(id: UUID) = runBlocking { store.markRemoved(id, RemovalReason.THROWN_AWAY, clock.now()) }

    fun failToOpen(failure: BootstrapFailure = BootstrapFailure.STORAGE) {
        state = BootstrapState.Failed(failure)
    }
}
