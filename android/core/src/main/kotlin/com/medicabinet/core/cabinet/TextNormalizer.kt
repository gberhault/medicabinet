package com.medicabinet.core.cabinet

import java.text.Normalizer

/**
 * Text comparison for search, location-name uniqueness and name ordering — the Kotlin twin of the Swift
 * `TextNormalizer`. The steps are spelled out (not `Collator`) so both platforms produce the same text,
 * pinned by `fixtures/text-normalization.json`.
 */
object TextNormalizer {
    /** The Unicode `White_Space` property; `Character.isWhitespace` excludes the no-break spaces. */
    private const val WHITESPACE =
        "\u0009\u000A\u000B\u000C\u000D \u0085  " +
            "           " +
            "    　"

    fun isWhitespace(codePoint: Int): Boolean =
        codePoint <= Char.MAX_VALUE.code && WHITESPACE.indexOf(codePoint.toChar()) >= 0

    /** Removes leading and trailing `White_Space`; inner text is untouched. */
    fun trimmed(text: String): String {
        val points = text.codePoints().toArray()
        val first = points.indexOfFirst { !isWhitespace(it) }
        if (first < 0) return ""
        val last = points.indexOfLast { !isWhitespace(it) }
        return String(points, first, last - first + 1)
    }

    /**
     * NFD, lower-case per code point (locale-independent), drop non-spacing marks, fold `œ æ ß ’ ‘`, collapse
     * whitespace runs to one space, trim.
     */
    fun normalize(text: String): String {
        val result = StringBuilder()
        var pendingSpace = false
        Normalizer.normalize(text, Normalizer.Form.NFD).codePoints().forEach { original ->
            val lowered = Character.toLowerCase(original)
            when {
                Character.getType(lowered) == Character.NON_SPACING_MARK.toInt() -> Unit
                isWhitespace(lowered) -> pendingSpace = true
                else -> {
                    if (pendingSpace && result.isNotEmpty()) result.append(' ')
                    pendingSpace = false
                    result.append(fold(lowered) ?: String(Character.toChars(lowered)))
                }
            }
        }
        return result.toString()
    }

    /**
     * Every whitespace-separated word of [query] is a substring of [text] (both normalized); an empty query
     * matches everything.
     */
    fun matches(text: String, query: String): Boolean = matchesNormalized(normalize(text), query)

    /** Same as [matches] for a text that is already normalized (the list normalizes each row once). */
    fun matchesNormalized(normalizedText: String, query: String): Boolean =
        normalize(query).split(' ').filter { it.isNotEmpty() }.all { normalizedText.contains(it) }

    /** Orders by Unicode code point (not UTF-16 unit, not locale collation), as the Swift twin does by scalar. */
    fun compare(lhs: String, rhs: String): Int {
        val left = lhs.codePoints().toArray()
        val right = rhs.codePoints().toArray()
        for (index in 0 until minOf(left.size, right.size)) {
            if (left[index] != right[index]) return if (left[index] < right[index]) -1 else 1
        }
        return left.size.compareTo(right.size)
    }

    private fun fold(codePoint: Int): String? = when (codePoint) {
        'œ'.code -> "oe"
        'æ'.code -> "ae"
        'ß'.code -> "ss"
        '’'.code, '‘'.code -> "'"
        else -> null
    }
}
