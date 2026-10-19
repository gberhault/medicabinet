package com.medicabinet.core.scan

import com.medicabinet.core.ExpiryDate

/** A barcode type the scanner recognises. [rawValue] is the platform-neutral name shared with iOS. */
enum class Symbology(val rawValue: String) {
    DATA_MATRIX("dataMatrix"),
    EAN_13("ean13")
}

/**
 * What the code says about the expiry. A date that the code holds but that is not a real date is
 * [Invalid], never repaired; a code without a date is [Missing].
 */
sealed interface ExpiryReading {
    data class Date(val value: ExpiryDate) : ExpiryReading

    data object Missing : ExpiryReading

    /** An `ExpiryDateError` kind, or `invalidFormat` when AI 17 is not six digits. */
    data class Invalid(val kind: String) : ExpiryReading
}

data class ScannedCode(
    /** GTIN-14, check digit verified. */
    val gtin: String,
    val expiry: ExpiryReading,
    val lot: String?,
    val serial: String?,
    val symbology: Symbology
)

/** [kind] is the platform-neutral name shared with iOS and `fixtures/gs1-datamatrix.json`. */
enum class ScanParseError(val kind: String) {
    EMPTY("empty"),
    TOO_LONG("tooLong"),
    INVALID_CHARACTERS("invalidCharacters"),
    MISSING_GTIN("missingGTIN"),
    INVALID_GTIN("invalidGTIN")
}

class ScanParseException(val error: ScanParseError) : Exception(error.kind)

object GTIN {
    private const val EAN_13_LENGTH = 13
    private const val GTIN_14_LENGTH = 14
    private const val HEAVY_WEIGHT = 3
    private const val LIGHT_WEIGHT = 1
    private const val DECIMAL = 10

    /**
     * A 13-digit EAN or a 14-digit GTIN as a GTIN-14, or `null` when the length, the digits or the check
     * digit are wrong.
     */
    fun normalized(digits: String): String? {
        val padded = when {
            digits.any { it !in '0'..'9' } -> null
            digits.length == EAN_13_LENGTH -> "0$digits"
            digits.length == GTIN_14_LENGTH -> digits
            else -> null
        }
        return padded?.takeIf { checkDigit(it.dropLast(1)) == it.last() - '0' }
    }

    private fun checkDigit(body: String): Int {
        val sum = body.reversed().withIndex().sumOf { (index, char) ->
            (char - '0') * if (index % 2 == 0) HEAVY_WEIGHT else LIGHT_WEIGHT
        }
        return (DECIMAL - sum % DECIMAL) % DECIMAL
    }
}

private const val GROUP_SEPARATOR = '\u001D'

/**
 * Reads what a French medicine box's code holds. Pure: no camera, no clock, never logs.
 *
 * GS1 quirks this handles: the group separator (GS, ASCII 29) ends variable-length fields but scanners may
 * drop it, keep it, or put it first (FNC1); a symbology identifier (`]d2`) may be prepended; AI 17 day `00`
 * means "end of that month". When the separator is missing, a variable-length field swallows what follows —
 * so the expiry is then reported [ExpiryReading.Missing], never searched for inside another field.
 */
object GS1Parser {
    const val MAX_PAYLOAD_LENGTH = 512

    fun parse(payload: String): ScannedCode {
        val walk = GS1Walker.walk(checked(payload))
        if (walk.gtinIsInvalid) throw ScanParseException(ScanParseError.INVALID_GTIN)
        val gtin = walk.gtin ?: throw ScanParseException(ScanParseError.MISSING_GTIN)
        return ScannedCode(
            gtin = gtin,
            expiry = walk.expiry,
            lot = walk.lot,
            serial = walk.serial,
            symbology = if (walk.isEan13) Symbology.EAN_13 else Symbology.DATA_MATRIX
        )
    }

    /**
     * The structure of a payload — prefix, AIs in order, the length of variable fields, GS — with no lot or
     * serial value, so it can be shown or shared safely. `?(n)` is an unknown AI followed by `n` characters;
     * `EAN13` a 13-digit code.
     */
    fun shape(payload: String): String = try {
        GS1Walker.walk(checked(payload)).tokens.joinToString(" ")
    } catch (e: ScanParseException) {
        if (e.error == ScanParseError.TOO_LONG) {
            "tooLong(${payload.codePointCount(0, payload.length)})"
        } else {
            e.error.kind
        }
    }

    private fun rejection(payload: String): ScanParseError? = when {
        payload.isEmpty() -> ScanParseError.EMPTY
        payload.codePointCount(0, payload.length) > MAX_PAYLOAD_LENGTH -> ScanParseError.TOO_LONG
        payload.any { it != GROUP_SEPARATOR && it !in ' '..'~' } -> ScanParseError.INVALID_CHARACTERS
        else -> null
    }

    private fun checked(payload: String): String {
        rejection(payload)?.let { throw ScanParseException(it) }
        return payload
    }
}

/** Walks a validated payload AI by AI. */
private object GS1Walker {
    private const val MAX_FIELD_LENGTH = 20
    private const val PREFIX_LENGTH = 3
    private const val EAN_13_LENGTH = 13
    private const val GTIN_14_LENGTH = 14
    private const val DATE_LENGTH = 6
    private const val CENTURY = 2000
    private val fixedLengths = mapOf(
        "00" to 18, "01" to GTIN_14_LENGTH, "02" to GTIN_14_LENGTH, "11" to DATE_LENGTH, "12" to DATE_LENGTH,
        "13" to DATE_LENGTH, "15" to DATE_LENGTH, "16" to DATE_LENGTH, "17" to DATE_LENGTH
    )
    private val variableAIs = setOf("10", "21", "22", "30")
    private val longVariableAIs = setOf("240", "241", "710", "711", "712", "713", "714", "715")

    /** GS1 character set 82. */
    private val allowedFieldChars =
        "!\"%&'()*+,-./0123456789:;<=>?ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz".toSet()

    class Walk {
        val tokens = mutableListOf<String>()
        var gtin: String? = null
        var gtinIsInvalid = false
        var expiry: ExpiryReading = ExpiryReading.Missing
        var lot: String? = null
        var serial: String? = null
        var isEan13 = false
        var finished = false
    }

    private class Field(val value: String, val end: Int, val truncated: Boolean)

    private fun isAsciiLetter(char: Char) = char in 'A'..'Z' || char in 'a'..'z'

    fun walk(payload: String): Walk {
        val walk = Walk()
        var index = readLeading(payload, walk)
        if (payload.length - index == EAN_13_LENGTH && payload.substring(index).all { it in '0'..'9' }) {
            walk.tokens += "EAN13"
            walk.isEan13 = true
            walk.gtin = GTIN.normalized(payload.substring(index))
            walk.gtinIsInvalid = walk.gtin == null
            return walk
        }
        val seen = mutableSetOf<String>()
        while (index < payload.length && !walk.finished) index = step(payload, index, seen, walk)
        return walk
    }

    /** Reads one separator or one field at [index]; returns the index after it. */
    private fun step(payload: String, index: Int, seen: MutableSet<String>, walk: Walk): Int {
        val isSeparator = payload[index] == GROUP_SEPARATOR
        val identifier = if (isSeparator) null else identifier(payload, index)
        return when {
            isSeparator -> {
                walk.tokens += "GS"
                index + 1
            }
            identifier == null -> {
                walk.tokens += "?(${payload.length - index})"
                walk.finished = true
                payload.length
            }
            else -> {
                val field = readField(identifier, payload, index + identifier.length, walk)
                if (seen.add(identifier)) read(identifier, field.value, walk)
                walk.finished = field.truncated
                field.end
            }
        }
    }

    private fun hasSymbologyPrefix(payload: String): Boolean {
        if (payload.length < PREFIX_LENGTH || payload[0] != ']') return false
        return isAsciiLetter(payload[1]) && (payload[2] in '0'..'9' || isAsciiLetter(payload[2]))
    }

    /** Skips a symbology identifier (`]d2`) and leading FNC1; returns the index of the first AI. */
    private fun readLeading(payload: String, walk: Walk): Int {
        var index = 0
        if (hasSymbologyPrefix(payload)) {
            walk.tokens += payload.substring(0, PREFIX_LENGTH)
            index = PREFIX_LENGTH
        }
        if (index < payload.length && payload[index] == GROUP_SEPARATOR) {
            while (index < payload.length && payload[index] == GROUP_SEPARATOR) index++
            walk.tokens += "FNC1"
        }
        return index
    }

    /** The known AI at [index], or `null` for an unknown one. */
    private fun identifier(payload: String, index: Int): String? {
        val two = payload.substring(index, minOf(index + 2, payload.length))
        val three = payload.substring(index, minOf(index + 3, payload.length))
        if ((two == "24" || two == "71") && three in longVariableAIs) return three
        return if (two in fixedLengths || two in variableAIs) two else null
    }

    private fun readField(identifier: String, payload: String, start: Int, walk: Walk): Field {
        val length = fixedLengths[identifier]
        if (length != null) {
            val available = payload.length - start
            val truncated = available < length
            val end = if (truncated) payload.length else start + length
            walk.tokens += if (truncated) "$identifier($available)" else identifier
            return Field(payload.substring(start, end), end, truncated)
        }
        var end = start
        while (end < payload.length && payload[end] != GROUP_SEPARATOR) end++
        walk.tokens += "$identifier(${end - start})"
        return Field(payload.substring(start, end), end, truncated = false)
    }

    private fun read(identifier: String, value: String, walk: Walk) {
        when (identifier) {
            "01" -> {
                walk.gtin = if (value.length == GTIN_14_LENGTH) GTIN.normalized(value) else null
                walk.gtinIsInvalid = walk.gtin == null
            }
            "17" -> walk.expiry = expiry(value)
            "10" -> walk.lot = fieldValue(value)
            "21" -> walk.serial = fieldValue(value)
        }
    }

    private fun fieldValue(value: String): String? =
        value.takeIf { it.length in 1..MAX_FIELD_LENGTH && it.all(allowedFieldChars::contains) }

    private fun expiry(value: String): ExpiryReading {
        if (value.length != DATE_LENGTH || value.any { it !in '0'..'9' }) return ExpiryReading.Invalid("invalidFormat")
        val year = CENTURY + value.substring(0, 2).toInt()
        val month = value.substring(2, 4).toInt()
        val day = value.substring(4, 6).toInt().takeIf { it != 0 }
        val error = ExpiryDate.validate(year, month, day)
        return if (error == null) {
            ExpiryReading.Date(ExpiryDate.of(year, month, day))
        } else {
            ExpiryReading.Invalid(error.kind)
        }
    }
}
