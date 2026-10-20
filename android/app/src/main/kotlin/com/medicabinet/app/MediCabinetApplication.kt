package com.medicabinet.app

import android.app.Application
import com.medicabinet.app.app.TabRouter
import com.medicabinet.app.persistence.Bootstrap
import com.medicabinet.app.reminders.ReminderChannel

/** Manual dependency-injection root (no Hilt/Koin): builds the launch bootstrap once. */
class MediCabinetApplication : Application() {
    /** Lets `MainActivity` switch tab for a notification tap, even before the main screen exists. */
    val tabRouter = TabRouter()

    val bootstrap: Bootstrap by lazy {
        Bootstrap(this, defaultLocationName = getString(R.string.location_default_name))
    }

    override fun onCreate() {
        super.onCreate()
        // The channel the monthly digest uses; creating it again is harmless and keeps its name in the user's language.
        ReminderChannel.ensure(this)
    }
}
