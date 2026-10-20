package com.medicabinet.app.features.scan

import android.content.Context
import android.content.pm.PackageManager
import com.medicabinet.app.features.scan.camera.CameraXScanner
import com.medicabinet.app.features.scan.camera.CodeScanner
import com.medicabinet.core.SystemClock
import com.medicabinet.core.reference.ProductLookup

/** The device-facing parts of the scan screen, injected so tests run without a camera. */
class ScanServices(
    val makeScanner: () -> CodeScanner,
    val preferences: ScanPreferences,
    val lookup: ProductLookup,
    /** `false` on a device with no camera: the scanner is replaced by manual entry. */
    val hasCamera: Boolean = true
) {
    companion object {
        /** [lookup] is the bundled BDPM reference data (`AppEnvironment.reference`). */
        fun live(context: Context, lookup: ProductLookup): ScanServices = ScanServices(
            makeScanner = { CameraXScanner(SystemClock) },
            preferences = SharedScanPreferences(context),
            lookup = lookup,
            hasCamera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        )
    }
}
