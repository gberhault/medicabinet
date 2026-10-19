package com.medicabinet.app.features.scan.camera

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.medicabinet.core.Clock
import java.io.Closeable
import java.util.concurrent.Executors
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import zxingcpp.BarcodeReader

/**
 * Continuous GS1 DataMatrix / EAN-13 recognition: CameraX preview + analysis (keep-only-latest) feeding zxing-cpp
 * on a background thread, never the main thread. Offline, no permission beyond the camera. Never logs a payload.
 */
class CameraXScanner(private val clock: Clock) :
    CodeScanner,
    Closeable {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val reader = BarcodeReader(
        BarcodeReader.Options(
            formats = setOf(BarcodeReader.Format.DATA_MATRIX, BarcodeReader.Format.EAN_13),
            tryHarder = true,
            tryRotate = true,
            tryDownscale = true,
            textMode = BarcodeReader.TextMode.PLAIN
        )
    )
    private val frames = MutableSharedFlow<VisibleCodes>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override val snapshots: Flow<VisibleCodes> get() = frames

    @Composable
    override fun Preview(modifier: Modifier, onFailure: (CodeScannerError) -> Unit) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val previewView = remember { PreviewView(context) }
        DisposableEffect(lifecycleOwner) {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, ::analyze) }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception) {
                    onFailure(CodeScannerError.FAILED_TO_START)
                }
            }, ContextCompat.getMainExecutor(context))
            onDispose { if (providerFuture.isDone) providerFuture.get().unbindAll() }
        }
        AndroidView(factory = { previewView }, modifier = modifier)
    }

    private fun analyze(image: androidx.camera.core.ImageProxy) {
        image.use {
            val payloads = reader.read(it).mapNotNull { result ->
                ScanPayload.of(result.symbologyIdentifier, result.bytes, result.text)
            }
            if (payloads.isNotEmpty()) frames.tryEmit(VisibleCodes(payloads, clock.now()))
        }
    }

    override fun close() = analysisExecutor.shutdown()
}
