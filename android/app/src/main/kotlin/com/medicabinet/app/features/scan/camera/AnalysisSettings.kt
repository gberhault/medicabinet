package com.medicabinet.app.features.scan.camera

/**
 * How the camera frames are analysed. CameraX analyses at 640×480 unless told otherwise, which is far too few pixels
 * for the 8–12 mm DataMatrix on a medicine box (regression: nothing was decoded on a real phone).
 */
object AnalysisSettings {
    /** Requested analysis size (sensor orientation); the closest available one is used. */
    const val LONG_SIDE = 1920
    const val SHORT_SIDE = 1080

    /** Only the central share of the shorter side is decoded: full resolution where the user aims, less work. */
    const val CROP_FRACTION = 0.7f

    /** Decode times are averaged over this many frames for the debug diagnostics. */
    const val STATS_WINDOW = 30
}

/** A rectangle in frame pixels. */
data class CropBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** The central square of side `fraction × min(width, height)`; the same for any rotation of the frame. */
fun centeredCrop(width: Int, height: Int, fraction: Float = AnalysisSettings.CROP_FRACTION): CropBox {
    val side = (minOf(width, height) * fraction.coerceIn(0f, 1f)).toInt()
    val left = (width - side) / 2
    val top = (height - side) / 2
    return CropBox(left, top, left + side, top + side)
}

/** Average of the last [window] values. */
class RollingAverage(private val window: Int = AnalysisSettings.STATS_WINDOW) {
    private val values = ArrayDeque<Double>()

    fun add(value: Double) {
        values.addLast(value)
        if (values.size > window) values.removeFirst()
    }

    val average: Double get() = if (values.isEmpty()) 0.0 else values.sum() / values.size
}

/** What the debug capture screen shows about the analysis, to tune the decoder on a real phone. */
data class ScanDiagnostics(
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val cropSide: Int = 0,
    val frames: Long = 0,
    val averageDecodeMillis: Double = 0.0,
    val framesWithCodes: Long = 0
)
