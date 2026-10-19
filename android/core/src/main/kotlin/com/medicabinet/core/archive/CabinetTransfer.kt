package com.medicabinet.core.archive

import com.medicabinet.core.Clock
import java.time.Instant
import java.time.ZoneId

/** What an import is about to do, shown before the user confirms (I1). */
class ImportPreview(
    val archive: Archive,
    /** The file's cabinet. */
    val file: CabinetSummary,
    /** The phone's cabinet when the preview was made (informative: `apply` backs up the cabinet as it is then). */
    val current: CabinetSummary,
    /** False when the phone's cabinet is empty, so there is nothing to lose (B3). */
    val willBackUp: Boolean
)

class ImportResult(
    val imported: CabinetSummary,
    /** The automatic backup of the replaced cabinet, `null` when there was nothing to back up. */
    val backup: ArchiveBackupEntry?,
    /** True when old backups could not be deleted afterwards: the import is done, the app should only log it (B2). */
    val pruneFailed: Boolean
)

enum class TransferErrorKind { BACKUP_FAILED }

/** [TransferErrorKind.BACKUP_FAILED]: the automatic backup could not be written (or read back); nothing was changed. */
class TransferException(val kind: TransferErrorKind) : Exception(kind.name)

/**
 * The export / import / restore use case, stateless and testable on the desktop: ports for the cabinet and the
 * automatic backups, an injected clock and the identity written into archives.
 */
class CabinetTransfer(
    private val store: CabinetArchiveStore,
    private val backupStore: ArchiveBackupStore,
    private val clock: Clock,
    private val app: ArchiveApp,
    private val limits: ArchiveLimits = ArchiveLimits.DEFAULT,
    private val keepCount: Int = DEFAULT_KEEP_COUNT
) {
    suspend fun currentSummary(): CabinetSummary = store.snapshot().summary

    /** The whole cabinet as an archive: one snapshot, validated and encoded. */
    suspend fun exportArchive(): ByteArray = ArchiveEncoder.encode(Archive(clock.now(), app, store.snapshot()), limits)

    /**
     * Reads and validates a file (any known version) and compares it with the cabinet on the phone. Throws an
     * [ArchiveException] for a file that must be refused; nothing is written.
     */
    suspend fun preview(bytes: ByteArray): ImportPreview {
        val archive = ArchiveDecoder.decode(bytes, limits)
        val current = store.snapshot().summary
        return ImportPreview(archive, archive.cabinet.summary, current, current.needsBackup)
    }

    /**
     * Replaces the cabinet with the previewed archive in one transaction, after backing up the cabinet as it is when
     * the transaction starts (so nothing can land between the backup and the replace, I5). A backup failure stops
     * everything ([TransferErrorKind.BACKUP_FAILED]) and leaves the cabinet as it was.
     */
    suspend fun apply(preview: ImportPreview, reason: BackupReason): ImportResult {
        val now = clock.now()
        var written: ArchiveBackupEntry? = null
        store.replace(preview.archive.cabinet) { current ->
            if (current.needsBackup) written = backUp(current, reason, now)
        }
        return ImportResult(preview.archive.cabinet.summary, written, pruneFailed = !prune())
    }

    suspend fun backups(): List<ArchiveBackupEntry> = backupStore.list()

    suspend fun readBackup(id: String): ByteArray = backupStore.read(id)

    private fun backUp(current: CabinetSnapshot, reason: BackupReason, now: Instant): ArchiveBackupEntry = try {
        backupStore.write(ArchiveEncoder.encode(Archive(now, app, current), limits), reason, now)
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception) {
        // Whatever went wrong (encoding, disk full), the import must not go on without its backup.
        throw TransferException(TransferErrorKind.BACKUP_FAILED)
    }

    /** Old backups go only after the new one is written and the replace committed; a failure is reported. */
    private suspend fun prune(): Boolean = try {
        backupStore.prune(keepCount)
        true
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception) {
        false
    }

    companion object {
        /** How many automatic backups are kept (B2). */
        const val DEFAULT_KEEP_COUNT = 3

        /** `MediCabinet-YYYY-MM-DD.medicabinet`, the date being the day of the export where the user is (E4). */
        fun fileName(date: Instant, zone: ZoneId): String {
            val day = date.atZone(zone).toLocalDate()
            val text = "%04d-%02d-%02d".format(day.year, day.monthValue, day.dayOfMonth)
            return "MediCabinet-$text.${ArchiveFormat.FILE_EXTENSION}"
        }
    }
}
