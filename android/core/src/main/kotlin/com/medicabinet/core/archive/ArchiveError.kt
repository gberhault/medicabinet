package com.medicabinet.core.archive

/**
 * Why an archive was refused. [rawValue] is the platform-neutral name shared with the iOS app
 * (`fixtures/README.md`, `fixtures/archive-cases.json`).
 */
enum class ArchiveErrorKind(val rawValue: String) {
    TOO_LARGE("tooLarge"),
    NOT_JSON("notJSON"),
    NOT_ARCHIVE("notArchive"),
    UNSUPPORTED_VERSION("unsupportedVersion"),
    NEWER_VERSION("newerVersion"),
    INVALID_STRUCTURE("invalidStructure"),
    INVALID_VALUE("invalidValue"),
    TOO_MANY_ITEMS("tooManyItems"),
    DUPLICATE_ID("duplicateId"),
    UNKNOWN_LOCATION("unknownLocation"),
    DEFAULT_LOCATION("defaultLocation"),
    DUPLICATE_SERIAL("duplicateSerial"),
    DUPLICATE_NAMED_PRODUCT("duplicateNamedProduct")
}

/**
 * A refused archive. [path] names the field (`boxes[12].expiry`): keys and list indexes only, never a
 * value, so the error can be logged without leaking the cabinet. `null` when no single field is at
 * fault (syntax, size, version). The message carries the same two facts and nothing else.
 */
class ArchiveException(val kind: ArchiveErrorKind, val path: String? = null) :
    Exception(if (path == null) kind.rawValue else "${kind.rawValue} at $path")

/**
 * A field's place in the file: the enclosing object's path (`boxes[3]`) and the key. The text is only
 * made when an error is thrown.
 */
internal class FieldPath(private val prefix: String, private val key: String) {
    constructor(key: String) : this("", key)

    val text: String get() = if (prefix.isEmpty()) key else "$prefix.$key"

    fun isKey(name: String): Boolean = key == name
}
