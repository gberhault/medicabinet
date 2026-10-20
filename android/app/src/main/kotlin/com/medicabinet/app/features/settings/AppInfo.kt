package com.medicabinet.app.features.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import com.medicabinet.core.archive.ArchiveApp

/** App identity shown in About. Behind an interface so tests do not depend on the real package. */
interface AppInfo {
    val version: String
    val build: String
}

/** Who writes an archive: this app, on Android, at this version (F3). The name is a proper noun, never translated. */
fun AppInfo.archiveApp(): ArchiveApp = ArchiveApp(ARCHIVE_APP_NAME, ARCHIVE_PLATFORM, version, build)

private const val ARCHIVE_APP_NAME = "MediCabinet"
private const val ARCHIVE_PLATFORM = "android"

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
