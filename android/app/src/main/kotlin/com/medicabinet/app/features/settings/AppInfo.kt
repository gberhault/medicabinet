package com.medicabinet.app.features.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat

/** App identity shown in About. Behind an interface so tests do not depend on the real package. */
interface AppInfo {
    val version: String
    val build: String
}

/** Reads the version name and code from the installed package; `?` when they cannot be read. */
class PackageAppInfo(context: Context, packageName: String = context.packageName) : AppInfo {
    override val version: String
    override val build: String

    init {
        val info = try {
            context.packageManager.getPackageInfo(packageName, 0)
        } catch (@Suppress("SwallowedException") error: PackageManager.NameNotFoundException) {
            null
        }
        version = info?.versionName ?: UNKNOWN
        build = info?.let { PackageInfoCompat.getLongVersionCode(it).toString() } ?: UNKNOWN
    }

    private companion object {
        const val UNKNOWN = "?"
    }
}
