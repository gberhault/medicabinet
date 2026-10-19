package com.medicabinet.app.features.scan.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** A scanner whose "camera" is a tagged box and whose codes the test emits: no camera involved. */
class FakeCodeScanner : CodeScanner {
    private val frames = MutableSharedFlow<VisibleCodes>(extraBufferCapacity = 16)
    var previewsShown = 0
        private set
    var previewsDisposed = 0
        private set
    var failure: CodeScannerError? = null

    override val snapshots: Flow<VisibleCodes> get() = frames

    suspend fun see(vararg payloads: String, at: Instant = Instant.EPOCH) {
        frames.emit(VisibleCodes(payloads.toList(), at))
    }

    @Composable
    override fun Preview(modifier: Modifier, onFailure: (CodeScannerError) -> Unit) {
        DisposableEffect(Unit) {
            previewsShown++
            failure?.let(onFailure)
            onDispose { previewsDisposed++ }
        }
        Box(modifier.testTag(PREVIEW_TAG))
    }

    companion object {
        const val PREVIEW_TAG = "camera-preview"
    }
}
