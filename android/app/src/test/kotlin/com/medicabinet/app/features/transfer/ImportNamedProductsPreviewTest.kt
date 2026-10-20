package com.medicabinet.app.features.transfer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import com.medicabinet.app.ui.theme.MediCabinetTheme
import com.medicabinet.core.testing.ArchiveFixtureFiles
import com.medicabinet.core.testing.SyntheticArchive
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The import preview counts the products the household named: a version 2 file only, and only when it holds some. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en")
class ImportNamedProductsPreviewTest {
    @get:Rule
    val compose = createComposeRule()

    private val line = "transfer.import.preview.namedProducts"

    private fun show(bytes: ByteArray) {
        val model = ImportViewModel(
            TransferTestKit().transfer,
            ImportRequest.File(Incoming.Ready(bytes)),
            {},
            Dispatchers.Unconfined
        )
        compose.setContent { MediCabinetTheme { ImportScreen(model, onClose = {}) } }
    }

    @Test
    fun aVersionTwoFileShowsItsLiveProductsOnly() {
        show(ArchiveFixtureFiles.goldenArchive())
        // Three products in the file, one of them forgotten: the line counts the two the household still has.
        compose.onNodeWithTag(line).performScrollTo().assertIsDisplayed().assertTextEquals("Products you named: 2")
    }

    @Test
    fun aVersionOneFileHasNoSuchLine() {
        show(ArchiveFixtureFiles.goldenArchive(1))
        compose.onNodeWithTag("import.file").assertIsDisplayed()
        compose.onNodeWithTag(line).assertDoesNotExist()
    }

    @Test
    fun aVersionTwoFileWithoutProductsHasNoSuchLineEither() {
        show(SyntheticArchive.json(locations = 1, boxes = 2, namedProducts = 0).toByteArray())
        compose.onNodeWithTag("import.file").assertIsDisplayed()
        compose.onNodeWithTag(line).assertDoesNotExist()
    }
}
