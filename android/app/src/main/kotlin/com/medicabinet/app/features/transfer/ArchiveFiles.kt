package com.medicabinet.app.features.transfer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.medicabinet.app.app.readAtMost
import com.medicabinet.core.archive.ArchiveFormat
import com.medicabinet.core.archive.ArchiveLimits
import java.io.File
import java.io.IOException

/** The file side of export: behind an interface so the view models are tested without a device. */
interface ArchiveDocuments {
    /** Writes [bytes] to a document the user created with the system picker, replacing anything in it. */
    fun writeDocument(uri: Uri, bytes: ByteArray)

    /** Best effort: removes a document whose writing failed, so no truncated file stays behind (E7). */
    fun deleteDocument(uri: Uri)

    /**
     * Puts [bytes] in the export folder as [name] (emptying it first: the file of a previous share is no longer
     * needed) and returns the provider address the share sheet hands to the chosen app.
     */
    fun shareFile(name: String, bytes: ByteArray): Uri
}

/**
 * The only place that writes documents, hands files to other apps, or empties the export folder (an architecture rule
 * keeps `FileProvider`, `DocumentsContract` and `openOutputStream` here). Blocking: callers run it on the IO
 * dispatcher.
 */
class ArchiveFiles(
    private val context: Context,
    private val exportDirectory: File,
    private val limits: ArchiveLimits = ArchiveLimits.DEFAULT,
    private val authority: String = authorityOf(context)
) : ArchiveDocuments {
    /**
     * Reads an opened or picked file, at most `maxFileBytes + 1` bytes: a larger file is refused by the decoder
     * (`tooLarge`) without ever being loaded whole (§1.5).
     */
    fun read(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("no input stream")
        return input.use { it.readAtMost(limits.maxFileBytes + 1) }
    }

    override fun writeDocument(uri: Uri, bytes: ByteArray) {
        // "wt": truncate, so a shorter archive written over an older file leaves nothing of it behind.
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("no output stream")
        output.use { it.write(bytes) }
    }

    override fun deleteDocument(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception) {
            // Best effort only: some providers cannot delete; the error the user sees is the write failure.
        }
    }

    override fun shareFile(name: String, bytes: ByteArray): Uri {
        clearExports()
        if (!exportDirectory.isDirectory && !exportDirectory.mkdirs()) throw IOException("cannot create the folder")
        val file = File(exportDirectory, name)
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(context, authority, file)
    }

    /** Empties the export folder (at the next export; launch does the same through `Bootstrap`). */
    fun clearExports() {
        exportDirectory.deleteRecursively()
    }

    companion object {
        /** `com.medicabinet.app.export` in the shipped app (the manifest declares `${applicationId}.export`). */
        fun authorityOf(context: Context): String = context.packageName + ".export"

        /**
         * The share sheet's request: one archive, read-only access granted to the app the user picks, and nothing
         * else (E5). The chooser has no title of its own: the system one says "Share".
         */
        fun shareIntent(uri: Uri): Intent {
            val send = Intent(Intent.ACTION_SEND)
                .setType(ArchiveFormat.MIME_TYPE)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(null, uri)
            return Intent.createChooser(send, null)
        }
    }
}
