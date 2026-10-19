package com.medicabinet.app.features.scan.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first

/** A scanner whose "camera" is a tagged box and whose codes the test emits: no camera involved. */
class FakeCodeScanner : CodeScanner {
    private val frames = MutableSharedFlow<VisibleCodes>(extraBufferCapacity = 16)
    var previewsShown = 0
        private set
    var previewsDisposed = 0
        private set
    var failure: CodeScannerError? = null

    /** Whether the fake camera reports that frames are streaming. */
    var starts = true

    override val snapshots: Flow<VisibleCodes> get() = frames

    /** Suspends until the screen collects the frames (a frame emitted earlier is lost, like on a real camera). */
    suspend fun awaitCollector() {
        frames.subscriptionCount.first { it > 0 }
    }

    suspend fun see(vararg payloads: String, at: Instant = Instant.EPOCH) {
        frames.emit(VisibleCodes(payloads.toList(), at))
    }

    @Composable
    override fun Preview(modifier: Modifier, onFailure: (CodeScannerError) -> Unit, onStarted: () -> Unit) {
        DisposableEffect(Unit) {
            previewsShown++
            val failed = failure
            if (failed != null) {
                onFailure(failed)
            } else if (starts) {
                onStarted()
            }
            onDispose { previewsDisposed++ }
        }
        Box(modifier.testTag(PREVIEW_TAG))
    }

    companion object {
        const val PREVIEW_TAG = "camera-preview"
    }
}
