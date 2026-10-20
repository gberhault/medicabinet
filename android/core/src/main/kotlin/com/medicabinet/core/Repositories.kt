package com.medicabinet.core

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** [rawValue] is the platform-neutral name shared with iOS (`fixtures/README.md`). */
enum class RepositoryError(val rawValue: String) {
    NOT_FOUND("notFound"),
    UNKNOWN_LOCATION("unknownLocation"),
    DUPLICATE("duplicate"),
    DUPLICATE_DEFAULT("duplicateDefault"),

    /** Another box that is not deleted already has this GTIN and serial. */
    DUPLICATE_SERIAL("duplicateSerial"),

    /** Another location that is not deleted has the same name once accents, case and spacing are ignored. */
    DUPLICATE_NAME("duplicateName"),

    /** The default location cannot be deleted. */
    DEFAULT_LOCATION("defaultLocation"),

    /** The location still holds boxes in the cabinet and no destination was given. */
    LOCATION_NOT_EMPTY("locationNotEmpty")
}

class RepositoryException(val error: RepositoryError, cause: Throwable? = null) : Exception(error.rawValue, cause)

/**
 * What the user does with the list of locations: add, rename, reorder, choose the default, delete and undo.
 * Locations that are *deleted* (tombstoned by [deleteLocation]) are invisible to every operation of the repositories,
 * except [undoDeleteLocation]. A role interface of [LocationRepository] (the detekt limit on functions per interface).
 */
interface LocationEditing {
    /**
     * Adds a location after the others. The name is validated by `LocationName`: throws `LocationNameException`
     * (empty, too long) or [RepositoryError.DUPLICATE_NAME].
     */
    suspend fun create(name: String): Location

    /** Throws [RepositoryError.NOT_FOUND] (unknown or deleted), [RepositoryError.DUPLICATE_NAME], or the name error. */
    suspend fun rename(id: UUID, name: String)

    /**
     * [ids] must be exactly the locations that are not deleted, in the wanted order (else
     * [RepositoryError.NOT_FOUND]); `sortOrder` becomes the index. Only locations whose position changed are stamped.
     */
    suspend fun reorder(ids: List<UUID>)

    /** Throws [RepositoryError.NOT_FOUND]. Afterwards exactly one location is the default. */
    suspend fun setDefault(id: UUID)

    /**
     * Tombstones the location and, in the same transaction, moves its boxes in the cabinet to [movingBoxesTo].
     * Removed and deleted boxes keep pointing at it. Throws [RepositoryError.NOT_FOUND] (unknown or already
     * deleted), [RepositoryError.DEFAULT_LOCATION], [RepositoryError.UNKNOWN_LOCATION] (destination missing,
     * deleted or the location itself), [RepositoryError.LOCATION_NOT_EMPTY] (boxes in the cabinet and no
     * destination).
     */
    suspend fun deleteLocation(id: UUID, movingBoxesTo: UUID?)

    /**
     * Restores a deleted location in its former place. Throws [RepositoryError.NOT_FOUND] when it is not deleted
     * and [RepositoryError.DUPLICATE_NAME] when its name was taken meanwhile.
     */
    suspend fun undoDeleteLocation(id: UUID)
}

/**
 * Repositories stamp `updatedAt` from their injected [Clock] on every write after insert;
 * callers never set it.
 */
interface LocationRepository : LocationEditing {
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

    /** Boxes in the cabinet (not removed, not deleted) in this location; 0 for an unknown location. */
    suspend fun cabinetBoxCount(id: UUID): Int

    /** The locations (as [all]) now, then after every change. */
    fun observeAll(): Flow<List<Location>>
}

/** The reads of [BoxRepository] (a role interface: the detekt limit on functions per interface). */
interface BoxLookups {
    /** Boxes still in the cabinet (`removal == null`, not deleted). */
    suspend fun activeBoxes(): List<Box>

    /** Returns removed boxes too, never deleted ones. */
    suspend fun box(id: UUID): Box?

    /** Non-deleted box (in the cabinet or removed) with this GTIN and serial. */
    suspend fun box(gtin: String, serial: String): Box?

    /** Most recently created non-deleted box with this GTIN. */
    suspend fun latestBox(gtin: String): Box?

    /**
     * The newest non-deleted box (`createdAt`, then id) of every GTIN, themselves ordered newest first
     * (`createdAt DESC, id DESC`). Boxes without a GTIN are left out. Feeds the one-time named-product fill.
     */
    suspend fun latestBoxPerGTIN(): List<Box>

    /** Boxes in the cabinet (not removed, not deleted) with this GTIN, oldest first (`createdAt`, then id). */
    suspend fun cabinetBoxes(gtin: String): List<Box>

    /** The boxes in the cabinet (as [activeBoxes]) now, then after every change. */
    fun observeCabinet(): Flow<List<Box>>
}

/**
 * A *deleted* box is one added by mistake (never used up or thrown away): kept as a tombstone for the future
 * sync and export, and invisible to every operation below except [undoDelete]. A *removed* box is a stock
 * movement and stays visible to [box].
 */
interface BoxRepository : BoxLookups {
    /**
     * Throws [RepositoryError.DUPLICATE] for a known id (deleted or not), [RepositoryError.UNKNOWN_LOCATION] for a
     * missing or deleted location and [RepositoryError.DUPLICATE_SERIAL] when another non-deleted box has the same
     * GTIN and serial.
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

/**
 * What the household does with a named product: rename, recategorise, forget and undo. A role interface of
 * [NamedProductRepository] (the detekt limit on functions per interface). A *forgotten* product keeps its row
 * (tombstone) and is invisible to every operation except [undoForget]; its boxes are never touched by forgetting.
 */
interface NamedProductEditing {
    /**
     * Renames the product and, in the same transaction, every box with its GTIN that is not deleted (in the cabinet
     * or removed). Throws [RepositoryError.NOT_FOUND] for an unknown or forgotten product.
     */
    suspend fun rename(id: UUID, name: String)

    /**
     * Sets the category and its user-chosen flag of the product and, in the same transaction, of every box with its
     * GTIN that is not deleted. Throws [RepositoryError.NOT_FOUND] for an unknown or forgotten product.
     */
    suspend fun setCategory(id: UUID, category: CategoryID, overridden: Boolean)

    /** Throws [RepositoryError.NOT_FOUND] when the product is unknown or already forgotten. */
    suspend fun forget(id: UUID)

    /**
     * Throws [RepositoryError.NOT_FOUND] when the product is not forgotten and [RepositoryError.DUPLICATE] when
     * another live named product holds its GTIN meanwhile.
     */
    suspend fun undoForget(id: UUID)
}

interface NamedProductRepository : NamedProductEditing {
    /** The live named product of [gtin]. */
    suspend fun namedProduct(gtin: String): NamedProduct?

    /**
     * Creates or updates the live named product of [gtin]; no write (so `updatedAt` stays) when name, category and
     * flag are equal.
     */
    suspend fun remember(gtin: String, name: String, category: CategoryID, categoryOverridden: Boolean)

    /**
     * Live named products with their boxes in the cabinet, ordered by normalized name then GTIN
     * ([com.medicabinet.core.named.NamedProductOrdering]); now, then after every change of either table.
     */
    fun observeSummaries(): Flow<List<NamedProductSummary>>

    /** `true` until the one-time fill from the boxes after the schema v4 upgrade is complete. */
    suspend fun backfillPending(): Boolean

    /**
     * Inserts [products] and clears the pending marker in one transaction. A GTIN that already has a live named
     * product is skipped: what was created meanwhile wins.
     */
    suspend fun completeBackfill(products: List<NamedProduct>)
}
