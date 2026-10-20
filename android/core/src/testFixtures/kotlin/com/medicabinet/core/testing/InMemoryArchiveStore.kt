package com.medicabinet.core.testing

import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import com.medicabinet.core.archive.ArchiveBackupEntry
import com.medicabinet.core.archive.ArchiveBackupStore
import com.medicabinet.core.archive.ArchiveDecoder
import com.medicabinet.core.archive.ArchiveException
import com.medicabinet.core.archive.BackupReason
import com.medicabinet.core.archive.CabinetArchiveStore
import com.medicabinet.core.archive.CabinetContents
import java.time.Instant

/**
 * In-memory fake of [CabinetArchiveStore]; also documents the expected behaviour (the contract in
 * [ArchiveStoreContract] runs against it and against every real store).
 */
class InMemoryArchiveStore(initial: CabinetContents = CabinetContents(emptyList(), emptyList())) :
    ArchiveStoreUnderContract {
    private var cabinet = initial.copy(namedProductsNeedFill = false)

    /** The named-product backfill marker (`cabinetMeta` in the database): set by a replace that needs the fill. */
    private var fillIsPending = initial.namedProductsNeedFill

    /** Always `namedProductsNeedFill == false`: the marker is state of the store, not part of the cabinet. */
    override suspend fun snapshot(): CabinetContents = cabinet

    override suspend fun backfillPending(): Boolean = fillIsPending

    override suspend fun replace(snapshot: CabinetContents, beforeReplacing: (CabinetContents) -> Unit) {
        beforeReplacing(cabinet)
        requireHoldable(snapshot)
        cabinet = snapshot.copy(namedProductsNeedFill = false)
        fillIsPending = snapshot.namedProductsNeedFill
    }

    /**
     * The constraints a database enforces: unique ids, one default location, boxes in known locations, one live
     * named product per GTIN.
     */
    private fun requireHoldable(snapshot: CabinetContents) {
        val known = snapshot.locations.map { it.location.id }.toSet()
        val boxIds = snapshot.boxes.map { it.box.id }.toSet()
        val productIds = snapshot.namedProducts.map { it.product.id }.toSet()
        val liveGtins = snapshot.namedProducts.filter { it.deletedAt == null }.map { it.product.gtin }
        val problem = when {
            known.size != snapshot.locations.size || boxIds.size != snapshot.boxes.size -> RepositoryError.DUPLICATE
            productIds.size != snapshot.namedProducts.size || liveGtins.toSet().size != liveGtins.size ->
                RepositoryError.DUPLICATE
            snapshot.locations.count { it.location.isDefault } > 1 -> RepositoryError.DUPLICATE_DEFAULT
            snapshot.boxes.any { it.box.locationId !in known } -> RepositoryError.UNKNOWN_LOCATION
            else -> null
        }
        if (problem != null) throw RepositoryException(problem)
    }
}

/** In-memory fake of [ArchiveBackupStore], with switches to make the next operations fail. */
class InMemoryArchiveBackupStore : ArchiveBackupStore {
    class Failure : Exception("in-memory backup store failure")

    private class Stored(var bytes: ByteArray, val reason: BackupReason, val takenAt: Instant)

    private val files = LinkedHashMap<String, Stored>()
    private var counter = 0
    private var failWrites = false
    private var failPrunes = false

    /** Makes `write` throw, as a full disk would. */
    @Synchronized
    fun setFailingWrites(failing: Boolean) {
        failWrites = failing
    }

    @Synchronized
    fun setFailingPrunes(failing: Boolean) {
        failPrunes = failing
    }

    /** Damages a stored file so it no longer decodes. */
    @Synchronized
    fun corrupt(id: String) {
        files[id]?.bytes = byteArrayOf('{'.code.toByte())
    }

    val count: Int @Synchronized get() = files.size

    @Synchronized
    override fun write(bytes: ByteArray, reason: BackupReason, takenAt: Instant): ArchiveBackupEntry {
        if (failWrites) throw Failure()
        // Like the real store: what was written must read back as an archive.
        val archive = ArchiveDecoder.decode(bytes)
        counter++
        val id = "${reason.rawValue}-$counter"
        files[id] = Stored(bytes, reason, takenAt)
        return ArchiveBackupEntry(id, takenAt, reason, archive.cabinet.summary)
    }

    override suspend fun list(): List<ArchiveBackupEntry> = newestFirst().map { (id, stored) ->
        val summary = try {
            ArchiveDecoder.decode(stored.bytes).cabinet.summary
        } catch (@Suppress("SwallowedException") error: ArchiveException) {
            null
        }
        ArchiveBackupEntry(id, stored.takenAt, stored.reason, summary)
    }

    override suspend fun read(id: String): ByteArray = files[id]?.bytes ?: throw Failure()

    override suspend fun prune(count: Int) {
        if (failPrunes) throw Failure()
        newestFirst().drop(count.coerceAtLeast(0)).forEach { (id, _) -> files.remove(id) }
    }

    private fun newestFirst() = files.entries.map { it.key to it.value }
        .sortedWith(compareByDescending<Pair<String, Stored>> { it.second.takenAt }.thenByDescending { it.first })
}
