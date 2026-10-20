package com.medicabinet.core.cabinet

/**
 * The short form of a medicine name shown on a Cabinet pill: BDPM names read "NAME DOSE, pharmaceutical form", so
 * the cut is before the first ", ". A decimal comma ("0,05 %") has no space after it and is never cut. Display
 * only — a stored name is never rewritten. Pinned by `fixtures/short-names.json`; the Swift twin is `ShortName`.
 */
object ShortName {
    fun of(name: String): String {
        val trimmed = TextNormalizer.trimmed(name)
        // U+002C then U+0020 only: a no-break space after the comma is part of the name.
        val cut = trimmed.indexOf(", ")
        if (cut < 0) return trimmed
        val short = TextNormalizer.trimmed(trimmed.substring(0, cut))
        return short.ifEmpty { trimmed }
    }
}
