package com.medicabinet.app

import android.app.Application
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.app.BootstrapState
import com.medicabinet.app.app.ExpiryPreferences
import com.medicabinet.app.app.NamedProductBackfillLauncher
import com.medicabinet.app.app.SharedExpiryPreferences
import com.medicabinet.app.app.TabRouter
import com.medicabinet.app.features.transfer.ArchiveFiles
import com.medicabinet.app.features.transfer.ImportCoordinator
import com.medicabinet.app.persistence.AppPaths
import com.medicabinet.app.persistence.Bootstrap
import com.medicabinet.app.reminders.ReminderChannel
import com.medicabinet.app.reminders.ReminderServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Manual dependency-injection root (no Hilt/Koin): builds the launch bootstrap once. */
class MediCabinetApplication : Application() {
    /** Lets `MainActivity` switch tab for a notification tap, even before the main screen exists. */
    val tabRouter = TabRouter()

    /**
     * Archives opened or shared with the app wait here (read into memory at once) until the cabinet is open and no form
     * holds unsaved input. Process-wide, like [tabRouter], so a recreated activity keeps the waiting file.
     */
    val importCoordinator: ImportCoordinator by lazy {
        val files = ArchiveFiles(this, AppPaths(this).exportDirectory)
        ImportCoordinator(MainScope(), Dispatchers.IO, files::read)
    }

    /** The one set of expiry preferences: the screens, the alarm and the receivers all read this file. */
    val expiryPreferences: ExpiryPreferences by lazy { SharedExpiryPreferences(this) }

    val bootstrap: Bootstrap by lazy {
        Bootstrap(
            this,
            defaultLocationName = getString(R.string.location_default_name),
            expiryPreferences = expiryPreferences
        )
    }

    /** Fills the named products once after the upgrade, in the background (never on the launch path). */
    private val namedProductBackfill by lazy { NamedProductBackfillLauncher(MainScope(), Dispatchers.IO) }

    /** After an import or a restore: a version 1 file or an old backup left the fill pending, so run it again. */
    fun startNamedProductBackfill(environment: AppEnvironment) {
        namedProductBackfill.start(environment)
    }

    private val openLock = Mutex()
    private var opened: BootstrapState.Ready? = null

    /**
     * Opens the cabinet once per process and shares it: the launch screen and the digest receiver (which may start the
     * process alone, with no screen) get the same database, never two. A failure is not remembered, so Retry works.
     */
    suspend fun openCabinet(): BootstrapState = openLock.withLock {
        opened ?: bootstrap.run().also {
            if (it is BootstrapState.Ready) {
                opened = it
                namedProductBackfill.start(it.environment)
            }
        }
    }

    private val liveReminders: ReminderServices by lazy {
        ReminderServices.live(this, expiryPreferences) { openCabinet() }
    }

    /** Tests replace the device-facing parts with fakes. */
    var reminderServicesOverride: ReminderServices? = null

    val reminderServices: ReminderServices get() = reminderServicesOverride ?: liveReminders

    override fun onCreate() {
        super.onCreate()
        // The channel the monthly digest uses; creating it again is harmless and keeps its name in the user's language.
        ReminderChannel.ensure(this)
    }
}
