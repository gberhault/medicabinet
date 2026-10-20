package com.medicabinet.app.features.scan.camera

import com.medicabinet.core.scan.GS1Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScanPayloadTest {
    @Test
    fun theSymbologyIdentifierPrecedesTheBytesAndTheGroupSeparatorSurvives() {
        val bytes = "010340093000001417270300\u001D21S1".toByteArray(Charsets.ISO_8859_1)
        val payload = ScanPayload.of("]d2", bytes, "ignored")
        assertEquals("]d2010340093000001417270300\u001D21S1", payload)
        assertEquals("21", GS1Parser.shape(payload!!).split(" ").last().take(2))
    }

    @Test
    fun theTextIsUsedWhenThereAreNoBytes() {
        assertEquals("]E03400930000014", ScanPayload.of("]E0", null, "3400930000014"))
    }

    @Test
    fun aMissingIdentifierIsNotInvented() {
        assertEquals("3400930000014", ScanPayload.of(null, null, "3400930000014"))
    }

    @Test
    fun nothingDecodedGivesNoPayload() {
        assertNull(ScanPayload.of("]d2", null, null))
    }

    @Test
    fun bytesAreReadOneToOneWhateverTheirValue() {
        val bytes = byteArrayOf(0x30, 0xE9.toByte(), 0x1D)
        assertEquals("0é\u001D", ScanPayload.of(null, bytes, null))
    }
}
