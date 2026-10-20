package com.medicabinet.core.scan

import com.medicabinet.core.testing.SharedFixtures
import kotlin.random.Random
import kotlin.test.Test

/** P13 — whatever the input, the parser returns a code or a typed error and never throws anything else. */
class GS1ParserFuzzTest {
    private fun exercise(payload: String) {
        try {
            GS1Parser.parse(payload)
        } catch (_: ScanParseException) {
            // typed error: fine
        }
        GS1Parser.shape(payload)
    }

    @Test
    fun everyPrefixOfEverySharedPayloadIsHandled() {
        for (case in SharedFixtures.gs1Datamatrix().cases) {
            for (length in 0..case.payload.length) exercise(case.payload.substring(0, length))
        }
    }

    @Test
    fun randomPayloadsAreHandled() {
        val alphabet = "0123456789]dQC1ABZ#- \u001D\u0000\né"
        val random = Random(12)
        repeat(5_000) {
            exercise(String(CharArray(random.nextInt(90)) { alphabet[random.nextInt(alphabet.length)] }))
        }
    }

    @Test
    fun randomDigitStringsStartingWithKnownAIsAreHandled() {
        val random = Random(99)
        val starts = listOf("01", "17", "10", "21", "240", "99", "01034009300000141")
        repeat(5_000) {
            val payload = StringBuilder(starts[random.nextInt(starts.size)])
            repeat(random.nextInt(40)) {
                payload.append(if (random.nextInt(7) == 0) "\u001D" else random.nextInt(10).toString())
            }
            exercise(payload.toString())
        }
    }
}
