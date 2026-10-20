package com.medicabinet.app.features.transfer

import android.net.Uri
import com.medicabinet.core.archive.ArchiveApp
import com.medicabinet.core.archive.ArchiveDecoder
import com.medicabinet.core.archive.CabinetArchiveStore
import com.medicabinet.core.archive.CabinetContents
import com.medicabinet.core.archive.CabinetTransfer
import com.medicabinet.core.testing.InMemoryArchiveBackupStore
import com.medicabinet.core.testing.InMemoryArchiveStore
import com.medicabinet.core.testing.MutableClock
import com.medicabinet.core.testing.SyntheticArchive
import java.io.IOException
import java.time.Instant

/** In-memory cabinet, backups and documents for the transfer view models and screens. */
class TransferTestKit(cabinet: CabinetContents = sampleCabinet()) {
    val clock = MutableClock(Instant.parse("2027-01-15T23:30:00Z"))
    val app = ArchiveApp("MediCabinet", "android", "0.5.0", "6")
    val store = InMemoryArchiveStore(cabinet)
    val backups = InMemoryArchiveBackupStore()
    val documents = FakeDocuments()
    val transfer = CabinetTransfer(store, backups, clock, app)

    fun transferOn(store: CabinetArchiveStore) = CabinetTransfer(store, backups, clock, app)

    companion object {
        /** Two locations, three boxes in the cabinet. */
        fun sampleCabinet(): CabinetContents =
            ArchiveDecoder.decode(SyntheticArchive.json(locations = 2, boxes = 3).toByteArray()).cabinet
                // As a store snapshot holds it: the fill marker is the store's, not the cabinet's.
                .copy(namedProductsNeedFill = false)
    }
}

/** Records what the export writes; can be made to fail. */
class FakeDocuments : ArchiveDocuments {
    val written = mutableMapOf<Uri, ByteArray>()
    val deleted = mutableListOf<Uri>()
    val shared = mutableListOf<Pair<String, ByteArray>>()
    var failWrites = false
    var failShares = false
    val shareUri: Uri by lazy { Uri.parse("content://com.medicabinet.app.export/export/file.medicabinet") }

    override fun writeDocument(uri: Uri, bytes: ByteArray) {
        if (failWrites) throw IOException("disk full")
        written[uri] = bytes
    }

    override fun deleteDocument(uri: Uri) {
        deleted += uri
    }

    override fun shareFile(name: String, bytes: ByteArray): Uri {
        if (failShares) throw IOException("disk full")
        shared += name to bytes
        return shareUri
    }
}

/** A store whose reads fail, as an unreadable database would. */
class FailingArchiveStore : CabinetArchiveStore {
    override suspend fun snapshot(): CabinetContents = throw IOException("unreadable")

    override suspend fun replace(snapshot: CabinetContents, beforeReplacing: (CabinetContents) -> Unit): Unit =
        throw IOException("unreadable")
}
