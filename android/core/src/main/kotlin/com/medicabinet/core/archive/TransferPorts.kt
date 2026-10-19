package com.medicabinet.core.archive

import java.time.Instant

/**
 * The whole cabinet, for export and import. Implementations: an in-memory fake (test fixtures), plain SQLite in the
 * app. The shared `fixtures/archive-store-contract.json` lists the checks each must pass.
 */
interface CabinetArchiveStore {
    /** Every location and every box (in the cabinet, removed, deleted), read in one transaction. */
    suspend fun snapshot(): CabinetSnapshot

    /**
     * In **one write transaction**: read the current cabinet, call [beforeReplacing] with it (anything it throws rolls
     * everything back and is rethrown), replace all rows with [snapshot], recompute derived values. Ids, fields and
     * dates are kept as given. A snapshot the store cannot hold (a box in an unknown location, a duplicate id, two
     * default locations) throws the matching `RepositoryException` and changes nothing.
     */
    suspend fun replace(snapshot: CabinetSnapshot, beforeReplacing: (CabinetSnapshot) -> Unit)
}

/** Why an automatic backup was taken. */
enum class BackupReason(val rawValue: String) {
    BEFORE_IMPORT("beforeImport"),
    BEFORE_RESTORE("beforeRestore")
}

/** One automatic backup file, as listed in Settings. [summary] is `null` when the file cannot be read any more. */
data class ArchiveBackupEntry(
    val id: String,
    val takenAt: Instant,
    val reason: BackupReason,
    val summary: CabinetSummary?
)

/** The automatic backup files (archives in private storage). Implementations: an in-memory fake, the file system. */
interface ArchiveBackupStore {
    /**
     * Writes the archive, reads it back and decodes it; throws if any step fails. Not `suspend` on purpose: it runs
     * inside the replace transaction.
     */
    fun write(bytes: ByteArray, reason: BackupReason, takenAt: Instant): ArchiveBackupEntry

    /** Newest first. */
    suspend fun list(): List<ArchiveBackupEntry>

    suspend fun read(id: String): ByteArray

    /** Deletes all but the [count] newest. */
    suspend fun prune(count: Int)
}
