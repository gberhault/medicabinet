package com.medicabinet.core.archive

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ArchiveErrorTest {
    @Test
    fun kindsUseThePlatformNeutralNames() {
        val expected = listOf(
            "tooLarge", "notJSON", "notArchive", "unsupportedVersion", "newerVersion", "invalidStructure",
            "invalidValue", "tooManyItems", "duplicateId", "unknownLocation", "defaultLocation", "duplicateSerial",
            "duplicateNamedProduct"
        )
        assertEquals(expected, ArchiveErrorKind.entries.map { it.rawValue })
    }

    @Test
    fun theMessageCarriesTheKindAndThePathOnly() {
        assertEquals("notJSON", ArchiveException(ArchiveErrorKind.NOT_JSON).message)
        val error = ArchiveException(ArchiveErrorKind.INVALID_VALUE, "boxes[3].lot")
        assertEquals("invalidValue at boxes[3].lot", error.message)
    }

    @Test
    fun aFieldPathJoinsItsPrefixAndKey() {
        assertEquals("boxes[0].lot", FieldPath("boxes[0]", "lot").text)
        assertEquals("exportedAt", FieldPath("exportedAt").text)
        assertEquals("exportedAt", FieldPath("", "exportedAt").text)
    }
}
