package com.medicabinet.core

import java.time.Instant
import java.util.UUID

/** [rawValue] is the platform-neutral name shared with iOS (`fixtures/README.md`). */
enum class RepositoryError(val rawValue: String) {
    NOT_FOUND("notFound"),
    UNKNOWN_LOCATION("unknownLocation"),
    DUPLICATE("duplicate"),
    DUPLICATE_DEFAULT("duplicateDefault")
}

class RepositoryException(val error: RepositoryError) : Exception(error.rawValue)

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

interface BoxRepository {
    /** Boxes still in the cabinet (`removal == null`). */
    suspend fun activeBoxes(): List<Box>

    /** Returns removed boxes too. */
    suspend fun box(id: UUID): Box?

    /**
     * Throws [RepositoryError.DUPLICATE] for a known id and [RepositoryError.UNKNOWN_LOCATION] for a
     * missing location.
     */
    suspend fun insert(box: Box)

    /** Throws [RepositoryError.NOT_FOUND] / [RepositoryError.UNKNOWN_LOCATION]. Preserves `createdAt` and `removal`. */
    suspend fun update(box: Box)

    suspend fun markRemoved(id: UUID, reason: RemovalReason, at: Instant)

    suspend fun undoRemoval(id: UUID)
}
