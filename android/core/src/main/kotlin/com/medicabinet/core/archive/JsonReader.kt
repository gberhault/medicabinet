package com.medicabinet.core.archive

private const val OPEN_BRACE = '{'.code
private const val CLOSE_BRACE = '}'.code
private const val OPEN_BRACKET = '['.code
private const val CLOSE_BRACKET = ']'.code
private const val QUOTE = '"'.code
private const val COMMA = ','.code
private const val COLON = ':'.code
private const val MINUS = '-'.code
private const val DIGIT_ZERO = '0'.code
private const val DIGIT_NINE = '9'.code
private const val LOWER_T = 't'.code
private const val LOWER_F = 'f'.code
private const val LOWER_N = 'n'.code
private val byteOrderMark = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

/**
 * A strict, pull-style JSON reader over UTF-8 bytes (RFC 8259), written so that the iOS and Android
 * cores accept and refuse exactly the same files (design §1.1):
 * - the whole input must be valid UTF-8 (no overlong forms, no surrogates), a leading BOM is skipped;
 * - no comments, no trailing commas, no leading zeros, no NaN; nothing may follow the root value;
 * - strings are limited to [maxStringBytes] once decoded; `\u` surrogates must pair;
 * - integers must fit a Long; a fraction or exponent where an integer is expected is a type error.
 *
 * It never builds a tree: the caller asks for the next value of the type it expects, so a value the
 * schema does not expect is never read (an unknown key is refused at once). Syntax faults throw
 * [ArchiveErrorKind.NOT_JSON]; a valid value of the wrong type throws [ArchiveErrorKind.INVALID_STRUCTURE];
 * a value that is valid JSON but unreadable (too long, too big) throws [ArchiveErrorKind.INVALID_VALUE].
 *
 * Lexing helpers live in `JsonLexing.kt`, `JsonStrings.kt` and `JsonNumbers.kt` as extensions, which is
 * why the cursor is `internal`.
 */
internal class JsonReader(internal val bytes: ByteArray, internal val maxStringBytes: Int) {
    enum class Value { OBJECT, ARRAY, STRING, NUMBER, BOOL, NULL }

    internal var index: Int = if (startsWithByteOrderMark(bytes)) byteOrderMark.size else 0

    init {
        if (!StrictUtf8.isValid(bytes, index)) throw notJson()
    }

    /** The kind of the next value, without consuming it. Anything that cannot start a JSON value is a syntax fault. */
    fun peek(): Value {
        skipWhitespace()
        if (index >= bytes.size) throw notJson()
        return when (byteAt(index)) {
            OPEN_BRACE -> Value.OBJECT
            OPEN_BRACKET -> Value.ARRAY
            QUOTE -> Value.STRING
            MINUS, in DIGIT_ZERO..DIGIT_NINE -> Value.NUMBER
            LOWER_T, LOWER_F -> Value.BOOL
            LOWER_N -> Value.NULL
            else -> throw notJson()
        }
    }

    fun beginObject() = expect(OPEN_BRACE)

    /** The next key of the current object, or `null` at its end (consumed). [isFirst] is true for the first call. */
    fun nextKey(isFirst: Boolean): String? {
        skipWhitespace()
        if (index >= bytes.size) throw notJson()
        if (byteAt(index) == CLOSE_BRACE) {
            index++
            return null
        }
        if (!isFirst) {
            expect(COMMA)
            skipWhitespace()
        }
        if (index >= bytes.size || byteAt(index) != QUOTE) throw notJson()
        val key = readStringToken(null)
        expect(COLON)
        return key
    }

    fun beginArray() = expect(OPEN_BRACKET)

    /** Whether another element follows in the current array (the closing bracket is consumed when not). */
    fun hasNextElement(isFirst: Boolean): Boolean {
        skipWhitespace()
        if (index >= bytes.size) throw notJson()
        if (byteAt(index) == CLOSE_BRACKET) {
            index++
            return false
        }
        if (!isFirst) expect(COMMA)
        return true
    }

    /** Only whitespace may remain after the root value. */
    fun finish() {
        skipWhitespace()
        if (index != bytes.size) throw notJson()
    }

    fun readString(field: FieldPath): String {
        if (peek() != Value.STRING) throw ArchiveException(ArchiveErrorKind.INVALID_STRUCTURE, field.text)
        return readStringToken(field)
    }

    fun readBoolean(field: FieldPath): Boolean {
        if (peek() != Value.BOOL) throw ArchiveException(ArchiveErrorKind.INVALID_STRUCTURE, field.text)
        val isTrue = byteAt(index) == LOWER_T
        readLiteral(if (isTrue) "true" else "false")
        return isTrue
    }

    fun readInteger(field: FieldPath): Long {
        val number = if (peek() == Value.NUMBER) lexNumber() else null
        if (number == null || !number.isInteger) throw ArchiveException(ArchiveErrorKind.INVALID_STRUCTURE, field.text)
        return number.value ?: throw ArchiveException(ArchiveErrorKind.INVALID_VALUE, field.text)
    }

    /** Consumes a JSON `null` and returns true, or returns false when the next value is something else. */
    fun readNullIfPresent(): Boolean {
        if (peek() != Value.NULL) return false
        readLiteral("null")
        return true
    }
}

private fun startsWithByteOrderMark(bytes: ByteArray): Boolean =
    bytes.size >= byteOrderMark.size && byteOrderMark.indices.all { bytes[it] == byteOrderMark[it] }

/** A string, or `null` for a JSON `null`. */
internal fun JsonReader.readOptionalString(field: FieldPath): String? =
    if (readNullIfPresent()) null else readString(field)
