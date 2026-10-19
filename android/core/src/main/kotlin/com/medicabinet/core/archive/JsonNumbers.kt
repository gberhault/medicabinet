package com.medicabinet.core.archive

private const val MINUS = '-'.code
private const val PLUS = '+'.code
private const val DOT = '.'.code
private const val LOWER_E = 'e'.code
private const val UPPER_E = 'E'.code
private const val DIGIT_ZERO = '0'.code
private const val DIGIT_NINE = '9'.code

/** [value] is the integer's value, `null` when it does not fit a Long or [isInteger] is false. */
internal class LexedNumber(val isInteger: Boolean, val value: Long?)

/**
 * Lexes `-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?` and interprets it as an integer when it has
 * no fraction or exponent. Anything that is not that grammar is a syntax fault.
 */
internal fun JsonReader.lexNumber(): LexedNumber {
    val start = index
    if (hasByte(MINUS)) index++
    val digitsStart = index
    skipDigits()
    if (byteAt(digitsStart) == DIGIT_ZERO && index - digitsStart > 1) throw notJson() // a leading zero
    val digitsEnd = index
    val hasFraction = skipFraction()
    val hasExponent = skipExponent()
    if (hasFraction || hasExponent) return LexedNumber(isInteger = false, value = null)
    val text = String(bytes, start, digitsEnd - start, Charsets.US_ASCII)
    return LexedNumber(isInteger = true, value = text.toLongOrNull())
}

/**
 * A number as an integer, or `null` when it has a fraction or exponent or does not fit a Long. Syntax faults still
 * throw. For a value whose type the caller handles itself (the archive's `formatVersion`).
 */
internal fun JsonReader.readIntegerIfRepresentable(): Long? = lexNumber().value

private fun isDigit(byte: Int): Boolean = byte in DIGIT_ZERO..DIGIT_NINE

/** One or more digits. */
private fun JsonReader.skipDigits() {
    if (index >= bytes.size || !isDigit(byteAt(index))) throw notJson()
    while (index < bytes.size && isDigit(byteAt(index))) index++
}

private fun JsonReader.skipFraction(): Boolean {
    if (!hasByte(DOT)) return false
    index++
    skipDigits()
    return true
}

private fun JsonReader.skipExponent(): Boolean {
    if (!hasByte(LOWER_E) && !hasByte(UPPER_E)) return false
    index++
    if (hasByte(PLUS) || hasByte(MINUS)) index++
    skipDigits()
    return true
}
