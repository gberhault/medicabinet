package com.medicabinet.app.features.scan.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisSettingsTest {
    @Test
    fun theAnalysisResolutionIsNotLeftAtCameraXsTinyDefault() {
        // Regression: at the 640×480 default a medicine box's DataMatrix was never decoded on a real phone.
        assertTrue(AnalysisSettings.LONG_SIDE >= 1280 && AnalysisSettings.SHORT_SIDE >= 720)
        assertTrue(AnalysisSettings.LONG_SIDE.toFloat() / AnalysisSettings.SHORT_SIDE > 1.7f, "16:9")
    }

    @Test
    fun theCropIsTheCentralSquareOfTheShorterSide() {
        assertEquals(CropBox(582, 162, 1338, 918), centeredCrop(1920, 1080, 0.7f))
        assertEquals(756, centeredCrop(1920, 1080, 0.7f).width)
    }

    @Test
    fun theCropIsTheSameWhateverTheRotationOfTheFrame() {
        val landscape = centeredCrop(1920, 1080)
        val portrait = centeredCrop(1080, 1920)
        assertEquals(landscape.width, portrait.width)
        assertEquals(landscape.height, portrait.height)
        assertEquals((1920 - landscape.width) / 2, landscape.left)
        assertEquals((1920 - portrait.height) / 2, portrait.top)
    }

    @Test
    fun theCropStaysInsideTheFrameWhateverTheFraction() {
        for (fraction in listOf(-1f, 0f, 0.5f, 1f, 4f)) {
            val box = centeredCrop(1280, 720, fraction)
            assertTrue(box.left >= 0 && box.top >= 0 && box.right <= 1280 && box.bottom <= 720, "$fraction -> $box")
            assertEquals(box.width, box.height)
        }
        assertEquals(CropBox(280, 0, 1000, 720), centeredCrop(1280, 720, 1f))
        assertEquals(0, centeredCrop(1280, 720, -1f).width)
    }

    @Test
    fun theRollingAverageForgetsOldValues() {
        val average = RollingAverage(window = 3)
        assertEquals(0.0, average.average)
        listOf(10.0, 20.0, 30.0).forEach(average::add)
        assertEquals(20.0, average.average)
        average.add(60.0)
        assertEquals(110.0 / 3, average.average, 1e-9)
    }
}
