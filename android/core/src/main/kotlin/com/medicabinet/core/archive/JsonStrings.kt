package com.medicabinet.core.archive

import java.io.ByteArrayOutputStream

private const val QUOTE = '"'.code
private const val BACKSLASH = '\\'.code
private const val SLASH = '/'.code
private const val LOWER_U = 'u'.code
private const val HEX_DIGITS = 4
private const val HEX_BASE = 16
private const val LETTER_DIGIT_OFFSET = 10
private const val BACKSPACE = 0x08
private const val FORM_FEED = 0x0C
private const val LINE_FEED = 0x0A
private const val CARRIAGE_RETURN = 0x0D
private const val TAB = 0x09
private const val HIGH_SURROGATE_START = 0xD800
private const val LOW_SURROGATE_START = 0xDC00
private const val SURROGATE_END = 0xE000
private const val ASTRAL_OFFSET = 0x10000
private const val SURROGATE_SHIFT = 10

private val highSurrogates = HIGH_SURROGATE_START until LOW_SURROGATE_START
private val lowSurrogates = LOW_SURROGATE_START until SURROGATE_END

private val simpleEscapes = mapOf(
    QUOTE to QUOTE,
    BACKSLASH to BACKSLASH,
    SLASH to SLASH,
    'b'.code to BACKSPACE,
    'f'.code to FORM_FEED,
    'n'.code to LINE_FEED,
    'r'.code to CARRIAGE_RETURN,
    't'.code to TAB
)

/** Reads a string starting at the opening quote. [path] is where a too-long string is reported (`null` for a key). */
internal fun JsonReader.readStringToken(path: FieldPath?): String {
    index++ // opening quote
    val decoded = ByteArrayOutputStream()
    while (index < bytes.size) {
        val byte = byteAt(index)
        index++
        when (byte) {
            // The bytes are valid UTF-8 by construction: the input was validated and escapes are encoded here.
            QUOTE -> return String(decoded.toByteArray(), Charsets.UTF_8)
            BACKSLASH -> appendEscape(decoded)
            else -> {
                requirePrintable(byte)
                decoded.write(byte)
            }
        }
        if (decoded.size() > maxStringBytes) throw ArchiveException(ArchiveErrorKind.INVALID_VALUE, path?.text)
    }
    throw notJson() // the closing quote never came
}

private fun JsonReader.appendEscape(decoded: ByteArrayOutputStream) {
    requireMoreBytes()
    val byte = byteAt(index)
    index++
    val simple = simpleEscapes[byte]
    if (simple != null) {
        decoded.write(simple)
        return
    }
    if (byte != LOWER_U) throw notJson()
    var scalar = readHex4()
    if (scalar in highSurrogates) {
        scalar = combine(scalar)
    } else if (scalar in lowSurrogates) {
        throw notJson() // a low surrogate with no high surrogate before it
    }
    appendUtf8(scalar, decoded)
}

/** A high surrogate must be followed by an escaped low surrogate; together they are one scalar. */
private fun JsonReader.combine(high: Int): Int {
    if (!(index + 1 < bytes.size && byteAt(index) == BACKSLASH && byteAt(index + 1) == LOWER_U)) throw notJson()
    index += 2
    val low = readHex4()
    if (low !in lowSurrogates) throw notJson()
    return ASTRAL_OFFSET + ((high - HIGH_SURROGATE_START) shl SURROGATE_SHIFT) + (low - LOW_SURROGATE_START)
}

private fun JsonReader.readHex4(): Int {
    if (index + HEX_DIGITS > bytes.size) throw notJson()
    var value = 0
    repeat(HEX_DIGITS) {
        value = value * HEX_BASE + (hexValue(byteAt(index)) ?: throw notJson())
        index++
    }
    return value
}

private fun hexValue(byte: Int): Int? = when (byte) {
    in '0'.code..'9'.code -> byte - '0'.code
    in 'a'.code..'f'.code -> byte - 'a'.code + LETTER_DIGIT_OFFSET
    in 'A'.code..'F'.code -> byte - 'A'.code + LETTER_DIGIT_OFFSET
    else -> null
}

/** [scalar] is a valid Unicode scalar value here: surrogates were refused above. */
@Suppress("MagicNumber") // The bit layout of UTF-8 (RFC 3629).
private fun appendUtf8(scalar: Int, out: ByteArrayOutputStream) {
    fun continuation(bits: Int) = out.write(0x80 or (bits and 0x3F))
    when {
        scalar < 0x80 -> out.write(scalar)
        scalar < 0x800 -> {
            out.write(0xC0 or (scalar shr 6))
            continuation(scalar)
        }
        scalar < ASTRAL_OFFSET -> {
            out.write(0xE0 or (scalar shr 12))
            continuation(scalar shr 6)
            continuation(scalar)
        }
        else -> {
            out.write(0xF0 or (scalar shr 18))
            continuation(scalar shr 12)
            continuation(scalar shr 6)
            continuation(scalar)
        }
    }
}
