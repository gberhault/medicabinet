package com.medicabinet.app

import android.app.Application
import com.medicabinet.app.app.TabRouter
import com.medicabinet.app.persistence.Bootstrap

/** Manual dependency-injection root (no Hilt/Koin): builds the launch bootstrap once. */
class MediCabinetApplication : Application() {
    /** Lets `MainActivity` switch tab for a notification tap, even before the main screen exists. */
    val tabRouter = TabRouter()

    val bootstrap: Bootstrap by lazy {
        Bootstrap(this, defaultLocationName = getString(R.string.location_default_name))
    }
}
