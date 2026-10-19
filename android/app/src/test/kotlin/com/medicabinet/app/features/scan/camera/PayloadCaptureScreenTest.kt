package com.medicabinet.app.features.scan.camera

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PayloadCaptureScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val gs = "\u001D"

    @Test
    fun shapesAreListedNewestFirstWithoutAnyValue() {
        shadowOf(
            ApplicationProvider.getApplicationContext<android.app.Application>()
        ).grantPermissions(Manifest.permission.CAMERA)
        val scanner = FakeCodeScanner()
        compose.setContent { PayloadCaptureScreen(scanner) }
        compose.onNodeWithTag(FakeCodeScanner.PREVIEW_TAG).assertIsDisplayed()

        runBlocking {
            scanner.see("]d2010340093000001417270300101A2B3C${gs}21SECRET123")
            scanner.see("3400930000014")
        }
        compose.waitForIdle()
        compose.onNodeWithText("EAN13").assertIsDisplayed()
        compose.onNodeWithText("]d2 01 17 10(6) GS 21(9)").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("SECRET123", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun withoutThePermissionOnlyTheAllowButtonShows() {
        val scanner = FakeCodeScanner()
        compose.setContent { PayloadCaptureScreen(scanner) }
        compose.onNodeWithText("Allow camera").assertIsDisplayed().performClick()
        assertEquals(0, scanner.previewsShown)
    }

    @Test
    fun aCameraFailureIsShown() {
        shadowOf(
            ApplicationProvider.getApplicationContext<android.app.Application>()
        ).grantPermissions(Manifest.permission.CAMERA)
        val scanner = FakeCodeScanner().apply { failure = CodeScannerError.FAILED_TO_START }
        compose.setContent { PayloadCaptureScreen(scanner) }
        compose.waitForIdle()
        compose.onNodeWithText("Camera failed to start").assertIsDisplayed()
    }

    @Test
    fun consecutiveDuplicatesCollapseAndTheListIsBounded() {
        assertEquals(listOf("a"), listOf("a").withShape("a"))
        assertEquals(listOf("b", "a"), listOf("a").withShape("b"))
        val full = (1..50).map { "s$it" }
        val next = full.withShape("new")
        assertEquals(50, next.size)
        assertEquals("new", next.first())
    }
}
