package com.medicabinet.core.archive

import java.time.Instant
import java.util.UUID

private const val UUID_LENGTH = 36
private val uuidHyphens = setOf(8, 13, 18, 23)

/**
 * How an object of a known shape is read: one entry per key, each reading its value into a draft.
 * Every key is required (an absent value is an explicit `null`), unknown and duplicate keys are
 * refused, and the first missing key is named once the object ends.
 */
internal class ObjectField<Draft>(val key: String, val read: (Draft, JsonReader, FieldPath) -> Unit)

/**
 * Reads the object at the reader's position into [draft]. [path] is the object's own path (`boxes[3]`,
 * empty for the root); [opened] is true when its `{` and first members were already consumed.
 */
internal fun <Draft> JsonReader.readObject(
    path: String,
    draft: Draft,
    fields: List<ObjectField<Draft>>,
    opened: Boolean = false
) {
    if (!opened) beginObjectOrFail(path)
    var seen = 0L // which fields were read, one bit each (an object has far fewer than 64)
    var isFirst = !opened
    while (true) {
        val key = nextKey(isFirst) ?: break
        isFirst = false
        val index = fieldIndex(fields, seen, FieldPath(path, key))
        seen = seen or (1L shl index)
        fields[index].read(draft, this, FieldPath(path, key))
    }
    requireAllSeen(fields, seen, path)
}

private fun JsonReader.beginObjectOrFail(path: String) {
    if (peek() != JsonReader.Value.OBJECT) throw ArchiveException(ArchiveErrorKind.INVALID_STRUCTURE, path)
    beginObject()
}

/** The index of [field]'s entry, which must be known and not seen before. */
private fun <Draft> fieldIndex(fields: List<ObjectField<Draft>>, seen: Long, field: FieldPath): Int {
    val index = fields.indexOfFirst { field.isKey(it.key) }
    if (index < 0 || seen and (1L shl index) != 0L) throw ArchiveException(ArchiveErrorKind.INVALID_STRUCTURE, field.text)
    return index
}

private fun <Draft> requireAllSeen(fields: List<ObjectField<Draft>>, seen: Long, path: String) {
    val missing = fields.indices.firstOrNull { seen and (1L shl it) == 0L }
    if (missing != null) {
        throw ArchiveException(ArchiveErrorKind.INVALID_STRUCTURE, FieldPath(path, fields[missing].key).text)
    }
}

/**
 * Walks a JSON array: [next] yields the index of each element (the caller reads it), and fails with
 * [ArchiveErrorKind.TOO_MANY_ITEMS] as soon as an item beyond the limit starts.
 */
internal class ListCursor(reader: JsonReader, private val path: String, private val limit: Int) {
    private var count = 0

    init {
        if (reader.peek() != JsonReader.Value.ARRAY) throw ArchiveException(ArchiveErrorKind.INVALID_STRUCTURE, path)
        reader.beginArray()
    }

    fun next(reader: JsonReader): Int? {
        if (!reader.hasNextElement(count == 0)) return null
        if (count >= limit) throw ArchiveException(ArchiveErrorKind.TOO_MANY_ITEMS, path)
        count++
        return count - 1
    }
}

internal fun JsonReader.readUuid(field: FieldPath): UUID {
    val text = readString(field)
    if (!isCanonicalUuid(text)) throw ArchiveException(ArchiveErrorKind.INVALID_VALUE, field.text)
    return UUID.fromString(text)
}

internal fun JsonReader.readDate(field: FieldPath): Instant =
    ArchiveDates.parse(readString(field)) ?: throw ArchiveException(ArchiveErrorKind.INVALID_VALUE, field.text)

/** A date, or `null` for a JSON `null`. */
internal fun JsonReader.readOptionalDate(field: FieldPath): Instant? =
    if (readNullIfPresent()) null else readDate(field)

/** An integer that fits 32 bits (the database's `Int`, and Swift's). */
internal fun JsonReader.readInt32(field: FieldPath): Int {
    val value = readInteger(field)
    if (value !in Int.MIN_VALUE..Int.MAX_VALUE) throw ArchiveException(ArchiveErrorKind.INVALID_VALUE, field.text)
    return value.toInt()
}

/**
 * 8-4-4-4-12 hexadecimal digits, either case: stricter than Java's `UUID.fromString` (which accepts `1-1-1-1-1`)
 * and than `UUID(uuidString:)` on some platforms, so both platforms accept exactly the same text.
 */
private fun isCanonicalUuid(text: String): Boolean {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val matchesShape = bytes.indices.all { index ->
        val char = bytes[index].toInt().toChar()
        if (index in uuidHyphens) char == '-' else char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
    }
    return bytes.size == UUID_LENGTH && matchesShape
}
