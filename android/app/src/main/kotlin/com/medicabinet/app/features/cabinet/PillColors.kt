package com.medicabinet.app.features.cabinet

import androidx.compose.ui.graphics.Color
import com.medicabinet.core.ExpiryStatus

/**
 * The tint and text colours of a product pill in the Cabinet. Only the alert statuses (expired, expiring soon) have
 * colours of their own; an unknown expiry is a neutral outlined pill and `ok` a neutral one, both taken from the
 * Material theme. The status is never carried by colour alone: each alert has its own icon. The same values as the
 * iOS asset colours (`PillStyle`); `PillColorsTest` holds every pair to 4.5:1 in light and dark.
 */
object PillColors {
    private val EXPIRED_LIGHT = Color(0xFFFCEBEB) to Color(0xFF791F1F)
    private val EXPIRED_DARK = Color(0xFF501313) to Color(0xFFF7C1C1)
    private val SOON_LIGHT = Color(0xFFFAEEDA) to Color(0xFF633806)
    private val SOON_DARK = Color(0xFF412402) to Color(0xFFFAC775)

    /** Container and content colours of an alert status, or `null` when the status uses the theme colours. */
    fun alert(status: ExpiryStatus, dark: Boolean): Pair<Color, Color>? = when (status) {
        ExpiryStatus.EXPIRED -> if (dark) EXPIRED_DARK else EXPIRED_LIGHT
        ExpiryStatus.EXPIRING_SOON -> if (dark) SOON_DARK else SOON_LIGHT
        ExpiryStatus.UNKNOWN, ExpiryStatus.OK -> null
    }
}
