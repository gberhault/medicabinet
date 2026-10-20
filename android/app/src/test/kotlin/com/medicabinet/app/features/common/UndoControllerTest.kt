package com.medicabinet.app.features.common

import androidx.compose.material3.SnackbarHostState
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UndoControllerTest {
    private val snackbars = SnackbarHostState()

    @Test
    fun showsTheMessageWithItsActionLabel() = runTest(UnconfinedTestDispatcher()) {
        UndoController(snackbars, this).show("Removed", "Undo") {}
        val data = assertNotNull(snackbars.currentSnackbarData)
        assertEquals("Removed", data.visuals.message)
        assertEquals("Undo", data.visuals.actionLabel)
        data.dismiss()
    }

    @Test
    fun performingTheActionRunsTheUndoOnce() = runTest(UnconfinedTestDispatcher()) {
        var undone = 0
        UndoController(snackbars, this).show("Removed", "Undo") { undone++ }
        snackbars.currentSnackbarData?.performAction()
        assertEquals(1, undone)
        assertNull(snackbars.currentSnackbarData)
    }

    @Test
    fun dismissingTheMessageNeverRunsTheUndo() = runTest(UnconfinedTestDispatcher()) {
        var undone = 0
        UndoController(snackbars, this).show("Removed", "Undo") { undone++ }
        snackbars.currentSnackbarData?.dismiss()
        assertEquals(0, undone)
    }

    @Test
    fun aNewActionReplacesThePreviousOneWithoutRunningItsUndo() = runTest(UnconfinedTestDispatcher()) {
        var first = 0
        var second = 0
        val controller = UndoController(snackbars, this)
        controller.show("First", "Undo") { first++ }
        controller.show("Second", "Undo") { second++ }
        assertEquals("Second", snackbars.currentSnackbarData?.visuals?.message)
        snackbars.currentSnackbarData?.performAction()
        assertEquals(0, first)
        assertEquals(1, second)
    }

    @Test
    fun aPlainMessageHasNoActionAndReplacesTheCurrentOne() = runTest(UnconfinedTestDispatcher()) {
        val controller = UndoController(snackbars, this)
        controller.show("Removed", "Undo") {}
        controller.notify("Can't restore")
        val data = assertNotNull(snackbars.currentSnackbarData)
        assertEquals("Can't restore", data.visuals.message)
        assertNull(data.visuals.actionLabel)
        data.dismiss()
    }
}
