package com.medicabinet.app.persistence.reference

import android.content.res.AssetManager
import com.medicabinet.core.reference.ReferenceUnavailableReason
import java.io.FileNotFoundException
import java.io.InputStream

/** Why the reference data cannot be used. The message is the reason only: never a path, a code or a product. */
class ReferenceUnavailableException(val reason: ReferenceUnavailableReason, cause: Throwable? = null) :
    Exception(reason.rawValue, cause)

/** Where the bundled reference data comes from: the APK assets in the app, plain files or fakes in tests. */
interface ReferenceAssets {
    /** The manifest JSON, or `null` when the app has none. */
    fun manifest(): String?

    /** A fresh stream of the bundled database, or `null` when the app has none. Closed by the caller. */
    fun database(): InputStream?

    companion object {
        const val DATABASE_NAME = "reference.sqlite"
        const val MANIFEST_NAME = "reference-manifest.json"
    }
}

class AndroidReferenceAssets(private val assets: AssetManager) : ReferenceAssets {
    override fun manifest(): String? =
        open(ReferenceAssets.MANIFEST_NAME)?.use { it.readBytes().toString(Charsets.UTF_8) }

    override fun database(): InputStream? = open(ReferenceAssets.DATABASE_NAME)

    private fun open(name: String): InputStream? = try {
        assets.open(name)
    } catch (_: FileNotFoundException) {
        null
    }
}
