package com.medicabinet.app.features.scan

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import com.medicabinet.app.R
import java.io.Closeable

/**
 * Plays what the view model decided: a haptic on the screen's own view (no VIBRATE permission) and, when sound is
 * on, a short tone that follows the device volume.
 */
class ScanFeedbackPlayer(context: Context) : Closeable {
    private val pool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val tone = pool.load(context, R.raw.scan_confirm, 1)
    private var loaded = false

    init {
        pool.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
    }

    fun play(view: View, request: FeedbackRequest) {
        view.performHapticFeedback(constant(request.event))
        if (request.sound && request.event in setOf(FeedbackEvent.ADDED, FeedbackEvent.REMOVED) && loaded) {
            pool.play(tone, VOLUME, VOLUME, 1, 0, 1f)
        }
    }

    override fun close() = pool.release()

    companion object {
        private const val VOLUME = 1f

        /** The confirm / reject haptics exist from Android 11; older versions use the closest standard ones. */
        fun constant(event: FeedbackEvent): Int {
            val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            return when {
                modern && event == FeedbackEvent.ADDED -> HapticFeedbackConstants.CONFIRM
                modern && event == FeedbackEvent.REJECTED -> HapticFeedbackConstants.REJECT
                event == FeedbackEvent.ATTENTION -> HapticFeedbackConstants.LONG_PRESS
                event == FeedbackEvent.REMOVED -> HapticFeedbackConstants.CONTEXT_CLICK
                else -> HapticFeedbackConstants.KEYBOARD_TAP
            }
        }
    }
}
