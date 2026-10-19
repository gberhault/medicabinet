package com.medicabinet.core.archive

/**
 * Archive lengths are counted in Unicode code points, like SQLite's `length()`: not grapheme
 * clusters and not UTF-16 units (`String.length`). `fixtures/archive-code-points.json` proves both
 * platforms agree.
 */
internal fun codePointCount(text: String): Int = text.codePointCount(0, text.length)
