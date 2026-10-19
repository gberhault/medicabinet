package com.medicabinet.app.features.scan.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** The codes in view at one instant, as the scanner reported them. */
data class VisibleCodes(val payloads: List<String>, val instant: Instant)

enum class CodeScannerError {
    /** The camera could not be started (no camera, or it is in use). */
    FAILED_TO_START
}

/**
 * The camera behind an interface, so UI tests inject payloads without a camera. Only the adapters in this
 * package know CameraX and zxing-cpp.
 */
interface CodeScanner {
    /** One snapshot per analysed frame that shows at least one code (hot flow; slow collectors skip frames). */
    val snapshots: Flow<VisibleCodes>

    /**
     * The live camera view. The camera runs only while this is in the composition and the lifecycle is at least
     * started, so it stops when the user leaves the screen or the app. [onStarted] fires once frames are streaming.
     */
    @Composable
    fun Preview(modifier: Modifier, onFailure: (CodeScannerError) -> Unit, onStarted: () -> Unit = {})
}

/** What the scanner decoded, as the text the GS1 parser expects: the symbology identifier (`]d2`) then the data. */
internal object ScanPayload {
    /** Raw bytes are read as ISO-8859-1 so the GS separator (ASCII 29) survives unchanged. */
    fun of(symbologyIdentifier: String?, bytes: ByteArray?, text: String?): String? {
        val body = bytes?.let { String(it, Charsets.ISO_8859_1) } ?: text
        return body?.let { symbologyIdentifier.orEmpty() + it }
    }
}
