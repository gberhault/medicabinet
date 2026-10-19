package com.medicabinet.app.features.scan

import android.content.Context
import android.content.pm.PackageManager
import com.medicabinet.app.features.scan.camera.CameraXScanner
import com.medicabinet.app.features.scan.camera.CodeScanner
import com.medicabinet.core.SystemClock
import com.medicabinet.core.scan.ProductLookup
import com.medicabinet.core.scan.ReferenceProduct

/** The device-facing parts of the scan screen, injected so tests run without a camera. */
class ScanServices(
    val makeScanner: () -> CodeScanner,
    val preferences: ScanPreferences,
    val lookup: ProductLookup,
    /** `false` on a device with no camera: the scanner is replaced by manual entry. */
    val hasCamera: Boolean = true
) {
    companion object {
        fun live(context: Context): ScanServices = ScanServices(
            makeScanner = { CameraXScanner(SystemClock) },
            preferences = SharedScanPreferences(context),
            lookup = UnknownProductLookup,
            hasCamera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        )
    }
}

/**
 * Until the BDPM reference data is bound (epic #7, `ProductLookup` from #11), every product is unknown and the
 * app asks for its name.
 */
object UnknownProductLookup : ProductLookup {
    override suspend fun product(gtin: String): ReferenceProduct? = null
}
