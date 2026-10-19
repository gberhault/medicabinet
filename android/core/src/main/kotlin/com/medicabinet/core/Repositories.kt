package com.medicabinet.core

import java.time.Instant
import java.util.UUID

/** [rawValue] is the platform-neutral name shared with iOS (`fixtures/README.md`). */
enum class RepositoryError(val rawValue: String) {
    NOT_FOUND("notFound"),
    UNKNOWN_LOCATION("unknownLocation"),
    DUPLICATE("duplicate"),
    DUPLICATE_DEFAULT("duplicateDefault"),

    /** Another box that is not deleted already has this GTIN and serial. */
    DUPLICATE_SERIAL("duplicateSerial")
}

class RepositoryException(val error: RepositoryError, cause: Throwable? = null) : Exception(error.rawValue, cause)

/**
 * Repositories stamp `updatedAt` from their injected [Clock] on every write after insert;
 * callers never set it.
 */
interface LocationRepository {
    /** Ordered by `sortOrder`, then name. */
    suspend fun all(): List<Location>

    suspend fun defaultLocation(): Location?

    /**
     * Throws [RepositoryError.DUPLICATE] for a known id and [RepositoryError.DUPLICATE_DEFAULT] when a
     * default already exists.
     */
    suspend fun insert(location: Location)

    /** Returns the default location, creating it with [name] when none exists. Idempotent. */
    suspend fun ensureDefault(name: String): Location
}

/**
 * A *deleted* box is one added by mistake (never used up or thrown away): kept as a tombstone for the future
 * sync and export, and invisible to every operation below except [undoDelete]. A *removed* box is a stock
 * movement and stays visible to [box].
 */
interface BoxRepository {
    /** Boxes still in the cabinet (`removal == null`, not deleted). */
    suspend fun activeBoxes(): List<Box>

    /** Returns removed boxes too, never deleted ones. */
    suspend fun box(id: UUID): Box?

    /** Non-deleted box (in the cabinet or removed) with this GTIN and serial. */
    suspend fun box(gtin: String, serial: String): Box?

    /** Most recently created non-deleted box with this GTIN. */
    suspend fun latestBox(gtin: String): Box?

    /** Boxes in the cabinet (not removed, not deleted) with this GTIN, oldest first (`createdAt`, then id). */
    suspend fun cabinetBoxes(gtin: String): List<Box>

    /**
     * Throws [RepositoryError.DUPLICATE] for a known id (deleted or not), [RepositoryError.UNKNOWN_LOCATION] for a
     * missing location and [RepositoryError.DUPLICATE_SERIAL] when another non-deleted box has the same GTIN and
     * serial.
     */
    suspend fun insert(box: Box)

    /**
     * Throws [RepositoryError.NOT_FOUND] (unknown or deleted) / [RepositoryError.UNKNOWN_LOCATION] /
     * [RepositoryError.DUPLICATE_SERIAL]. Preserves `createdAt` and `removal`.
     */
    suspend fun update(box: Box)

    /** Throws [RepositoryError.NOT_FOUND] for an unknown or deleted box. */
    suspend fun markRemoved(id: UUID, reason: RemovalReason, at: Instant)

    /** Throws [RepositoryError.NOT_FOUND] for an unknown or deleted box. */
    suspend fun undoRemoval(id: UUID)

    /** Stamps the tombstone. Throws [RepositoryError.NOT_FOUND] for an unknown or already deleted box. */
    suspend fun delete(id: UUID)

    /**
     * Throws [RepositoryError.NOT_FOUND] when the box is unknown or not deleted, [RepositoryError.DUPLICATE_SERIAL]
     * when its serial was taken meanwhile.
     */
    suspend fun undoDelete(id: UUID)
}
