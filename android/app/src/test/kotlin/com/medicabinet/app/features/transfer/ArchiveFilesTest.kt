package com.medicabinet.app.features.transfer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.medicabinet.app.persistence.AppPaths
import com.medicabinet.core.archive.ArchiveFormat
import com.medicabinet.core.archive.ArchiveLimits
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** The file side of the export on a (Robolectric) device: documents, the provider, the share request. */
@RunWith(RobolectricTestRunner::class)
class ArchiveFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val exports = AppPaths(context).exportDirectory
    private val files = ArchiveFiles(context, exports)
    private val bytes = "archive".toByteArray()

    /**
     * `FileProvider` caches its roots in a static map, but Robolectric gives every test a new data folder: the cache
     * would point at the first test's folder.
     */
    @Before
    fun forgetProviderRoots() = clearFileProviderCache()

    @After
    fun cleanUp() {
        exports.deleteRecursively()
    }

    @Test
    fun aChosenDocumentReceivesTheBytesReplacingWhatWasThere() {
        val target = File(context.filesDir, "chosen.medicabinet").apply { writeText("a much longer older file") }
        files.writeDocument(Uri.fromFile(target), bytes)
        assertContentEquals(bytes, target.readBytes())
    }

    @Test
    fun anOpenedFileIsReadUpToOneByteOverTheLimitOnly() {
        val source = File(context.filesDir, "picked.medicabinet").apply {
            writeBytes(ByteArray(5_000) { 'x'.code.toByte() })
        }
        val small = ArchiveFiles(context, exports, ArchiveLimits(maxFileBytes = 100))
        assertEquals(101, small.read(Uri.fromFile(source)).size)
        assertEquals(5_000, files.read(Uri.fromFile(source)).size)
        assertFailsWith<Exception> { files.read(Uri.parse("content://nobody/document/1")) }
    }

    @Test
    fun aDocumentThatCannotBeOpenedFailsTheWrite() {
        assertFailsWith<Exception> { files.writeDocument(Uri.parse("content://nobody/document/1"), bytes) }
    }

    /** A documents provider that records the delete requests the system picker's provider would receive. */
    class RecordingDocuments : ContentProvider() {
        val deleted = mutableListOf<Uri?>()

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (method == "android:deleteDocument") {
                @Suppress("DEPRECATION")
                deleted += extras?.getParcelable<Uri>("uri")
            }
            return Bundle()
        }

        override fun onCreate() = true

        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? =
            null

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    @Test
    fun aDocumentLeftByAFailedWriteIsDeleted() {
        val provider = Robolectric.setupContentProvider(RecordingDocuments::class.java, "test.documents")
        val document = DocumentsContract.buildDocumentUri("test.documents", "doc-1")
        files.deleteDocument(document)
        assertEquals(listOf<Uri?>(document), provider.deleted)
    }

    @Test
    fun deletingIsBestEffortAndNeverThrows() {
        files.deleteDocument(Uri.parse("content://nobody/document/1"))
    }

    @Test
    fun aSharedFileGoesThroughTheExportProviderAndReplacesThePreviousOne() {
        exports.mkdirs()
        File(exports, "MediCabinet-2027-01-14.medicabinet").writeText("yesterday")

        val uri = files.shareFile("MediCabinet-2027-01-15.medicabinet", bytes)

        assertEquals("content", uri.scheme)
        assertEquals("com.medicabinet.app.export", uri.authority)
        assertEquals(listOf("MediCabinet-2027-01-15.medicabinet"), exports.list()?.toList())
        assertContentEquals(bytes, File(exports, "MediCabinet-2027-01-15.medicabinet").readBytes())
    }

    @Test
    fun clearingTheExportsRemovesTheFolder() {
        files.shareFile("MediCabinet-2027-01-15.medicabinet", bytes)
        files.clearExports()
        assertFalse(exports.exists())
    }

    @Test
    fun theShareRequestGrantsReadOnTheOneFileOnly() {
        val uri = Uri.parse("content://com.medicabinet.app.export/export/MediCabinet-2027-01-15.medicabinet")
        val chooser = ArchiveFiles.shareIntent(uri)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        requireNotNull(send)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(ArchiveFormat.MIME_TYPE, send.type)
        @Suppress("DEPRECATION")
        assertEquals(uri, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(uri, send.clipData?.getItemAt(0)?.uri)
        assertEquals(1, send.clipData?.itemCount)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }
}

/** Clears `FileProvider`'s static root cache (see [ArchiveFilesTest.forgetProviderRoots]). */
fun clearFileProviderCache() {
    val field = FileProvider::class.java.getDeclaredField("sCache")
    field.isAccessible = true
    synchronized(field.get(null) as Any) { (field.get(null) as MutableMap<*, *>).clear() }
}
