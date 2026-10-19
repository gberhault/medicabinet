package com.medicabinet.app

import android.app.Application
import com.medicabinet.app.persistence.Bootstrap

/** Manual dependency-injection root (no Hilt/Koin): builds the launch bootstrap once. */
class MediCabinetApplication : Application() {
    val bootstrap: Bootstrap by lazy {
        Bootstrap(this, defaultLocationName = getString(R.string.location_default_name))
    }
}
