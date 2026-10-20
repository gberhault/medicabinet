package com.medicabinet.app.persistence

import com.medicabinet.app.app.readAtMost
import com.medicabinet.core.archive.ArchiveBackupEntry
import com.medicabinet.core.archive.ArchiveBackupStore
import com.medicabinet.core.archive.ArchiveDecoder
import com.medicabinet.core.archive.ArchiveException
import com.medicabinet.core.archive.ArchiveFormat
import com.medicabinet.core.archive.ArchiveLimits
import com.medicabinet.core.archive.BackupReason
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A backup file could not be written, read back, found or deleted. The message names no content. */
class ArchiveBackupException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The automatic backups (design §1.3, §5.2): archives in [directory] (`files/backups/`, internal storage, in the Auto
 * Backup rules), next to the pre-migration copies of `BackupService`, which this store never touches.
 *
 * Names are `pre-import-<UTC yyyyMMdd-HHmmss-SSS>.medicabinet` and `pre-restore-<…>.medicabinet`: the date and the
 * reason are read from the name, never from file dates. The file name is the entry's id.
 */
class FileArchiveBackupStore(
    private val directory: File,
    private val limits: ArchiveLimits = ArchiveLimits.DEFAULT,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : ArchiveBackupStore {
    /** Written to `<name>.partial`, flushed to disk, read back and decoded, then renamed: never a half file. */
    override fun write(bytes: ByteArray, reason: BackupReason, takenAt: Instant): ArchiveBackupEntry {
        val target = uniqueTarget(reason, takenAt)
        val partial = File(directory, target.name + PARTIAL_SUFFIX)
        try {
            FileOutputStream(partial).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            val summary = decodedSummary(partial) ?: throw ArchiveBackupException("the backup does not read back")
            if (!partial.renameTo(target)) throw ArchiveBackupException("cannot finish the backup")
            return ArchiveBackupEntry(target.name, takenAt, reason, summary)
        } finally {
            partial.delete()
        }
    }

    /** Newest first. A file that no longer decodes is listed with no summary (B4), never hidden. */
    override suspend fun list(): List<ArchiveBackupEntry> = withContext(io) {
        entries().map { (file, name) -> ArchiveBackupEntry(file.name, name.takenAt, name.reason, decodedSummary(file)) }
    }

    /** Only an id this store lists is read: an id is a bare file name, never a path. */
    override suspend fun read(id: String): ByteArray = withContext(io) {
        val file = entries().firstOrNull { it.first.name == id }?.first
            ?: throw ArchiveBackupException("no such backup")
        readCapped(file)
    }

    /** Over both prefixes together; the oldest go first. */
    override suspend fun prune(count: Int) {
        withContext(io) {
            entries().drop(count.coerceAtLeast(0)).forEach { (file, _) ->
                if (!file.delete() && file.exists()) throw ArchiveBackupException("cannot delete an old backup")
            }
        }
    }

    /** The backup files with their parsed names, newest first. */
    private fun entries(): List<Pair<File, BackupName>> = directory.listFiles().orEmpty()
        .mapNotNull { file -> BackupName.parse(file.name)?.let { file to it } }
        .filter { it.first.isFile }
        .sortedWith(
            compareByDescending<Pair<File, BackupName>> {
                it.second.takenAt
            }.thenByDescending { it.first.name }
        )

    /** At most `maxFileBytes + 1` bytes are read, so a file grown too large is refused by the decoder, not loaded. */
    /** The summary of the archive in [file], or `null` when it cannot be read or decoded. */
    private fun decodedSummary(file: File) = try {
        ArchiveDecoder.decode(readCapped(file), limits).cabinet.summary
    } catch (@Suppress("SwallowedException") error: ArchiveException) {
        null
    } catch (@Suppress("SwallowedException") error: IOException) {
        null
    }

    private fun readCapped(file: File): ByteArray = file.inputStream().use { it.readAtMost(limits.maxFileBytes + 1) }

    private fun uniqueTarget(reason: BackupReason, takenAt: Instant): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw ArchiveBackupException("cannot create the folder")
        val base = BackupName.base(reason, takenAt)
        var candidate = File(directory, base + EXTENSION)
        var counter = 2
        while (candidate.exists() || File(directory, candidate.name + PARTIAL_SUFFIX).exists()) {
            candidate = File(directory, "$base-$counter$EXTENSION")
            counter++
        }
        return candidate
    }

    /** What a backup file name says: why and when it was taken. */
    private class BackupName(val reason: BackupReason, val takenAt: Instant) {
        companion object {
            private val FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            private val PATTERN = Regex(
                "^($IMPORT_PREFIX|$RESTORE_PREFIX)(\\d{8}-\\d{6}-\\d{3})(-\\d{1,4})?\\.${ArchiveFormat.FILE_EXTENSION}$"
            )

            fun base(reason: BackupReason, takenAt: Instant): String =
                prefix(reason) + FORMAT.format(LocalDateTime.ofInstant(takenAt, ZoneOffset.UTC))

            fun parse(name: String): BackupName? {
                val match = PATTERN.matchEntire(name) ?: return null
                val reason = if (match.groupValues[1] == IMPORT_PREFIX) {
                    BackupReason.BEFORE_IMPORT
                } else {
                    BackupReason.BEFORE_RESTORE
                }
                return try {
                    BackupName(reason, LocalDateTime.parse(match.groupValues[2], FORMAT).toInstant(ZoneOffset.UTC))
                } catch (@Suppress("SwallowedException") error: DateTimeParseException) {
                    null
                }
            }

            private fun prefix(reason: BackupReason) = when (reason) {
                BackupReason.BEFORE_IMPORT -> IMPORT_PREFIX
                BackupReason.BEFORE_RESTORE -> RESTORE_PREFIX
            }
        }
    }

    companion object {
        const val IMPORT_PREFIX = "pre-import-"
        const val RESTORE_PREFIX = "pre-restore-"
        const val EXTENSION = "." + ArchiveFormat.FILE_EXTENSION
        private const val PARTIAL_SUFFIX = ".partial"
    }
}
