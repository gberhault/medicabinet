package com.medicabinet.core.archive

/** What the header's version says about whether this app can read the file. */
private sealed interface VersionCheck {
    class Readable(val version: Int) : VersionCheck

    class Refused(val kind: ArchiveErrorKind) : VersionCheck
}

/**
 * Reads an archive of any `formatVersion` this app knows (design §2.5, §2.7). The file is untrusted:
 * whatever the bytes, the result is an [Archive] that passed [ArchiveValidator], or an [ArchiveException].
 */
object ArchiveDecoder {
    /**
     * One reader per `formatVersion`, index 0 being version 1. A reader is never edited once shipped (F9);
     * a new version appends its reader, and migrates what it reads into the current model there.
     */
    internal val readers: List<ArchiveVersionReader> = listOf(ArchiveV1Reader)

    fun decode(bytes: ByteArray, limits: ArchiveLimits = ArchiveLimits.DEFAULT): Archive {
        if (bytes.size > limits.maxFileBytes) throw ArchiveException(ArchiveErrorKind.TOO_LARGE)
        val reader = JsonReader(bytes, limits.maxStringBytes)
        val version = readVersion(reader)
        val body = readers[version - 1].read(reader, limits)
        reader.finish()
        val archive = Archive(
            exportedAt = body.exportedAt,
            app = body.app,
            cabinet = CabinetSnapshot(body.locations, body.boxes),
            sourceFormatVersion = version
        )
        ArchiveValidator.validate(archive, limits)
        return archive
    }

    /** The header's version, which is one this app can read. */
    private fun readVersion(reader: JsonReader): Int = when (val check = check(readDeclaredVersion(reader))) {
        is VersionCheck.Readable -> check.version
        is VersionCheck.Refused -> throw ArchiveException(check.kind)
    }

    /**
     * The first two members must be `archive` = "medicabinet" and `formatVersion`; `null` when the header is
     * anything else (not an object, wrong marker, version missing or not an integer).
     */
    private fun readDeclaredVersion(reader: JsonReader): Long? {
        val isObject = reader.peek() == JsonReader.Value.OBJECT
        if (isObject) reader.beginObject()
        val hasMarker = isObject && reader.nextKey(true) == "archive" && isMarker(reader)
        return if (hasMarker && reader.nextKey(false) == "formatVersion") readNumber(reader) else null
    }

    private fun check(version: Long?): VersionCheck = when {
        version == null -> VersionCheck.Refused(ArchiveErrorKind.NOT_ARCHIVE)
        version < 1 -> VersionCheck.Refused(ArchiveErrorKind.UNSUPPORTED_VERSION)
        version > readers.size -> VersionCheck.Refused(ArchiveErrorKind.NEWER_VERSION)
        else -> VersionCheck.Readable(version.toInt())
    }

    private fun isMarker(reader: JsonReader): Boolean =
        reader.peek() == JsonReader.Value.STRING && reader.readString(FieldPath("archive")) == ArchiveFormat.MAGIC

    /** The version, or `null` when it is not an integer a Long can hold (a syntax fault still throws). */
    private fun readNumber(reader: JsonReader): Long? =
        if (reader.peek() == JsonReader.Value.NUMBER) reader.readIntegerIfRepresentable() else null
}
