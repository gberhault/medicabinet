package com.medicabinet.app.features.scan.camera

import android.graphics.Rect
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.medicabinet.core.Clock
import java.io.Closeable
import java.util.concurrent.Executors
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private val decodeTimes = RollingAverage()
    private val mutableDiagnostics = MutableStateFlow(ScanDiagnostics())
    override val diagnostics: StateFlow<ScanDiagnostics> get() = mutableDiagnostics

    /** Same 16:9 size for the preview and the analysis, so what the user sees is what is decoded. */
    private val resolution = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(
            ResolutionStrategy(
                Size(AnalysisSettings.LONG_SIDE, AnalysisSettings.SHORT_SIDE),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
            )
        )
        .build()

    @Composable
    override fun Preview(modifier: Modifier, onFailure: (CodeScannerError) -> Unit, onStarted: () -> Unit) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val previewView = remember { PreviewView(context) }
        DisposableEffect(lifecycleOwner) {
            val streaming = Observer<PreviewView.StreamState> {
                if (it ==
                    PreviewView.StreamState.STREAMING
                ) {
                    onStarted()
                }
            }
            previewView.previewStreamState.observe(lifecycleOwner, streaming)
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().setResolutionSelector(resolution).build().also {
                        it.surfaceProvider =
                            previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolution)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, ::analyze) }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception) {
                    onFailure(CodeScannerError.FAILED_TO_START)
                }
            }, ContextCompat.getMainExecutor(context))
            onDispose {
                previewView.previewStreamState.removeObserver(streaming)
                if (providerFuture.isDone) providerFuture.get().unbindAll()
            }
        }
        AndroidView(factory = { previewView }, modifier = modifier)
    }

    private fun analyze(image: ImageProxy) {
        image.use {
            val crop = centeredCrop(it.width, it.height)
            it.setCropRect(Rect(crop.left, crop.top, crop.right, crop.bottom))
            val started = System.nanoTime()
            val payloads = reader.read(it).mapNotNull { result ->
                ScanPayload.of(result.symbologyIdentifier, result.bytes, result.text)
            }
            decodeTimes.add((System.nanoTime() - started) / NANOS_PER_MILLI)
            val previous = mutableDiagnostics.value
            mutableDiagnostics.value = ScanDiagnostics(
                frameWidth = it.width,
                frameHeight = it.height,
                cropSide = crop.width,
                frames = previous.frames + 1,
                averageDecodeMillis = decodeTimes.average,
                framesWithCodes = previous.framesWithCodes + if (payloads.isEmpty()) 0 else 1
            )
            if (payloads.isNotEmpty()) frames.tryEmit(VisibleCodes(payloads, clock.now()))
        }
    }

    override fun close() = analysisExecutor.shutdown()

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000.0
    }
}
