package com.medicabinet.app.features.transfer

import com.medicabinet.core.archive.ArchiveErrorKind
import com.medicabinet.core.archive.ArchiveException
import com.medicabinet.core.archive.TransferErrorKind
import com.medicabinet.core.archive.TransferException

/**
 * What the import screen tells the user (strings.md, "Errors"): the many validation kinds of an archive collapse into
 * a few messages, the same on both platforms.
 */
enum class ImportFailure {
    TOO_LARGE,
    NOT_ARCHIVE,
    DAMAGED,
    NEWER_VERSION,
    READ_FAILED,
    BACKUP_FAILED,
    REPLACE_FAILED;

    /** A failure of the apply step can be tried again; a refused file cannot (it would be refused again). */
    val canRetry: Boolean get() = this == BACKUP_FAILED || this == REPLACE_FAILED

    companion object {
        /** A file refused by the decoder. */
        fun of(kind: ArchiveErrorKind): ImportFailure = when (kind) {
            ArchiveErrorKind.TOO_LARGE, ArchiveErrorKind.TOO_MANY_ITEMS -> TOO_LARGE
            ArchiveErrorKind.NOT_JSON, ArchiveErrorKind.NOT_ARCHIVE -> NOT_ARCHIVE
            ArchiveErrorKind.NEWER_VERSION -> NEWER_VERSION
            else -> DAMAGED
        }

        /** Anything [com.medicabinet.core.archive.CabinetTransfer.preview] throws: refused, or the file unreadable. */
        fun ofPreview(error: Throwable): ImportFailure = if (error is ArchiveException) of(error.kind) else READ_FAILED

        /** Anything `apply` throws: the backup failed, or the replace (rolled back). */
        fun ofApply(error: Throwable): ImportFailure = if (error is TransferException &&
            error.kind == TransferErrorKind.BACKUP_FAILED
        ) {
            BACKUP_FAILED
        } else {
            REPLACE_FAILED
        }
    }
}
