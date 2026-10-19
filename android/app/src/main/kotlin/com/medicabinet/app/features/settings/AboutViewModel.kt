package com.medicabinet.app.features.settings

import androidx.lifecycle.ViewModel

/** The values About shows. The version line itself is formatted by the screen from `about_version` (fr/en). */
class AboutViewModel(info: AppInfo) : ViewModel() {
    val version: String = info.version
    val build: String = info.build
}
