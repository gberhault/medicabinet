package com.medicabinet.core.archive

private const val BYTE_MASK = 0xFF
private const val ASCII_LIMIT = 0x80
private const val CONTINUATION_MASK = 0xC0
private const val CONTINUATION_MARK = 0x80
private const val FIRST_CONTINUATION_OFFSET = 2

/**
 * Strict UTF-8 validation (RFC 3629): no overlong forms, no surrogate code points, nothing above
 * U+10FFFF. Written out so the Swift and Kotlin readers refuse exactly the same bytes.
 */
internal object StrictUtf8 {
    /**
     * [second] narrows for E0, ED, F0 and F4 to exclude overlong forms, surrogates and values above U+10FFFF.
     */
    private class Rule(val lead: IntRange, val length: Int, val second: IntRange)

    @Suppress("MagicNumber") // The byte ranges of RFC 3629, table 3-7 of the Unicode standard.
    private val rules = listOf(
        Rule(0xC2..0xDF, 2, 0x80..0xBF),
        Rule(0xE0..0xE0, 3, 0xA0..0xBF),
        Rule(0xE1..0xEC, 3, 0x80..0xBF),
        Rule(0xED..0xED, 3, 0x80..0x9F),
        Rule(0xEE..0xEF, 3, 0x80..0xBF),
        Rule(0xF0..0xF0, 4, 0x90..0xBF),
        Rule(0xF1..0xF3, 4, 0x80..0xBF),
        Rule(0xF4..0xF4, 4, 0x80..0x8F)
    )

    fun isValid(bytes: ByteArray, from: Int): Boolean {
        var position = from
        while (position < bytes.size) {
            position += sequenceLength(bytes, position) ?: return false
        }
        return true
    }

    /** The length of the well-formed sequence starting at [position], or `null`. */
    private fun sequenceLength(bytes: ByteArray, position: Int): Int? {
        val lead = unsigned(bytes, position)
        val rule = rules.firstOrNull { lead in it.lead }
        return when {
            lead < ASCII_LIMIT -> 1
            rule != null && isComplete(bytes, position, rule) -> rule.length
            else -> null
        }
    }

    private fun isComplete(bytes: ByteArray, position: Int, rule: Rule): Boolean =
        position + rule.length <= bytes.size &&
            unsigned(bytes, position + 1) in rule.second &&
            (FIRST_CONTINUATION_OFFSET until rule.length).all {
                unsigned(bytes, position + it) and CONTINUATION_MASK == CONTINUATION_MARK
            }

    private fun unsigned(bytes: ByteArray, position: Int): Int = bytes[position].toInt() and BYTE_MASK
}
