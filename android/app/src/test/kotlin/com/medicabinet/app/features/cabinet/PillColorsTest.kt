package com.medicabinet.app.features.cabinet

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.medicabinet.core.ExpiryStatus
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class PillColorsTest {
    /** WCAG 2 contrast ratio between two opaque colours. */
    private fun contrast(first: Color, second: Color): Double {
        val high = maxOf(first.luminance(), second.luminance()).toDouble()
        val low = minOf(first.luminance(), second.luminance()).toDouble()
        return (high + 0.05) / (low + 0.05)
    }

    @Test
    fun alertColoursMeetWcagAaInLightAndDark() {
        for (status in listOf(ExpiryStatus.EXPIRED, ExpiryStatus.EXPIRING_SOON)) {
            for (dark in listOf(false, true)) {
                val (container, content) = assertNotNull(PillColors.alert(status, dark))
                val ratio = contrast(container, content)
                assertTrue(ratio >= 4.5, "$status dark=$dark: contrast $ratio")
            }
        }
    }

    @Test
    fun onlyAlertStatusesHaveColoursOfTheirOwn() {
        assertNull(PillColors.alert(ExpiryStatus.UNKNOWN, dark = false))
        assertNull(PillColors.alert(ExpiryStatus.OK, dark = true))
    }
}
