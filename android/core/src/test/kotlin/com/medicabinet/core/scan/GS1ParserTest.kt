package com.medicabinet.core.scan

import com.medicabinet.core.ExpiryDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GS1ParserTest {
    @Test
    fun gtinNormalizesAnEan13ToFourteenDigits() {
        assertEquals("03400930000014", GTIN.normalized("3400930000014"))
        assertEquals("03400930000014", GTIN.normalized("03400930000014"))
    }

    @Test
    fun gtinRejectsWrongLengthDigitsOrCheckDigit() {
        listOf("", "3400930000015", "340093000001", "034009300000145", "34009300000X4").forEach {
            assertNull(GTIN.normalized(it), it)
        }
    }

    @Test
    fun parseErrorsUseThePlatformNeutralNames() {
        assertEquals(
            listOf("empty", "tooLong", "invalidCharacters", "missingGTIN", "invalidGTIN"),
            ScanParseError.entries.map { it.kind }
        )
    }

    @Test
    fun aSymbologyLookalikeThatIsNotAPrefixIsNotStripped() {
        assertEquals("?(2)", GS1Parser.shape("]d"))
        assertEquals("?(3)", GS1Parser.shape("]!2"))
    }

    @Test
    fun theMaximumLengthIsFiveHundredAndTwelve() {
        assertEquals(512, GS1Parser.MAX_PAYLOAD_LENGTH)
    }

    @Test
    fun anExpiryIsAKnownDateOnlyWhenTheCodeHoldsOne() {
        val code = GS1Parser.parse("010340093000001417270300")
        assertEquals(ExpiryReading.Date(ExpiryDate.of(2027, 3, null)), code.expiry)
        assertEquals(Symbology.DATA_MATRIX, code.symbology)
    }
}
