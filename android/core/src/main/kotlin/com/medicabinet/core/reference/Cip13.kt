package com.medicabinet.core.reference

/**
 * The French medicine presentation code. A GS1 DataMatrix carries it as a GTIN-14 (`0` + CIP13); a 1D barcode carries
 * the 13 digits.
 */
object Cip13 {
    private const val GTIN_LENGTH = 14
    private const val MEDICINE_PREFIX = "03400"
    private const val ODD_WEIGHT = 3
    private const val DECIMAL_BASE = 10

    /**
     * The 13-digit CIP13 of a GTIN-14, or `null` when the GTIN is not a French medicine presentation: it must be
     * exactly 14 ASCII digits, start with `03400` (so parapharmacy `3401…` and foreign GTINs are refused) and have a
     * valid GS1 check digit. Nothing is trimmed or corrected.
     */
    fun fromGtin(gtin: String): String? {
        val isMedicineGtin = isFourteenAsciiDigits(gtin) && gtin.startsWith(MEDICINE_PREFIX)
        return if (isMedicineGtin && hasValidCheckDigit(gtin)) gtin.substring(1) else null
    }

    private fun isFourteenAsciiDigits(text: String) = text.length == GTIN_LENGTH && text.all { it in '0'..'9' }

    /** GS1 mod-10: from the right, the digit before the check digit weighs 3, then 1, 3, … */
    private fun hasValidCheckDigit(digits: String): Boolean {
        val weighted = digits.dropLast(1).reversed().mapIndexed { index, digit ->
            (digit - '0') * (if (index % 2 == 0) ODD_WEIGHT else 1)
        }.sum()
        return (DECIMAL_BASE - weighted % DECIMAL_BASE) % DECIMAL_BASE == digits.last() - '0'
    }
}
