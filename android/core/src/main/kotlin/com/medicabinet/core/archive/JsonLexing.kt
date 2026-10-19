package com.medicabinet.core.archive

private const val BYTE_MASK = 0xFF
private const val CONTROL_LIMIT = 0x20
private const val SPACE = ' '.code
private const val TAB = '\t'.code
private const val LINE_FEED = '\n'.code
private const val CARRIAGE_RETURN = '\r'.code

/** A syntax fault: the bytes are not JSON. */
internal fun notJson() = ArchiveException(ArchiveErrorKind.NOT_JSON)

/** Throws unless there is at least one more byte. */
internal fun JsonReader.requireMoreBytes() {
    if (index >= bytes.size) throw notJson()
}

/** A raw control character may not appear inside a string. */
internal fun requirePrintable(byte: Int) {
    if (byte < CONTROL_LIMIT) throw notJson()
}

internal fun JsonReader.byteAt(position: Int): Int = bytes[position].toInt() and BYTE_MASK

/** Whether the next byte, with no whitespace skipped, is [byte]. */
internal fun JsonReader.hasByte(byte: Int): Boolean = index < bytes.size && byteAt(index) == byte

internal fun JsonReader.skipWhitespace() {
    while (index < bytes.size && byteAt(index) in whitespace) index++
}

/** Skips whitespace, then consumes [byte] or throws. */
internal fun JsonReader.expect(byte: Int) {
    skipWhitespace()
    if (!hasByte(byte)) throw notJson()
    index++
}

internal fun JsonReader.readLiteral(literal: String) {
    val matches = index + literal.length <= bytes.size && literal.indices.all { byteAt(index + it) == literal[it].code }
    if (!matches) throw notJson()
    index += literal.length
}

private val whitespace = setOf(SPACE, TAB, LINE_FEED, CARRIAGE_RETURN)
