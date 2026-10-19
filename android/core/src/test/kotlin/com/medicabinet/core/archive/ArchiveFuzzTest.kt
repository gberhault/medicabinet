package com.medicabinet.core.archive

import com.medicabinet.core.testing.SharedFixtures
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * The decoder meets untrusted bytes: whatever they are, it returns an archive or throws an [ArchiveException],
 * never anything else (V4). An unexpected exception fails the run, so reaching the end of these loops is the proof.
 */
class ArchiveFuzzTest {
    private fun accepts(bytes: ByteArray): Boolean = try {
        ArchiveDecoder.decode(bytes)
        true
    } catch (@Suppress("SwallowedException") error: ArchiveException) {
        error.kind.rawValue.isNotEmpty() && false
    }

    @Test
    fun everyTruncationOfTheGoldenFileIsRefusedExceptTheWholeFile() {
        val golden = SharedFixtures.goldenArchive()
        // Without its final newline the file is still the same JSON document.
        for (length in 0 until golden.size - 1) {
            assertFalse(accepts(golden.copyOf(length)), "a $length-byte prefix was accepted")
        }
        assertTrue(accepts(golden.copyOf(golden.size - 1)))
        assertTrue(accepts(golden))
    }

    @Test
    fun everySingleByteMutationOfTheGoldenFileIsHandled() {
        val golden = SharedFixtures.goldenArchive()
        val structural = intArrayOf(0x00, 0x22, 0x5C, 0x7B, 0x7D, 0x2C, 0x3A, 0x30, 0x80, 0xFF)
        val random = Random(99)
        var accepted = 0
        for (position in golden.indices) {
            // One structural byte (rotating) and one random byte at every position.
            for (replacement in listOf(structural[position % structural.size], random.nextInt(256))) {
                val mutated = golden.copyOf()
                mutated[position] = replacement.toByte()
                if (accepts(mutated)) accepted++
            }
        }
        // Many mutations are harmless (a letter inside a name); the point is that none crashed.
        assertTrue(accepted > 0)
    }

    @TestFactory
    fun randomBytesAreRefused(): List<DynamicTest> = (1..300).map { seed ->
        dynamicTest("seed $seed") {
            val random = Random(seed)
            assertFalse(accepts(random.nextBytes(random.nextInt(0, 401))))
        }
    }

    @TestFactory
    fun randomBytesAfterAValidHeaderAreHandled(): List<DynamicTest> = (1..60).map { seed ->
        dynamicTest("seed $seed") {
            val random = Random(seed)
            val tail = ByteArray(random.nextInt(0, 301)) { random.nextInt(0x20, 0x7F).toByte() }
            assertFalse(accepts("{\"archive\":\"medicabinet\",\"formatVersion\":1,".toByteArray() + tail))
        }
    }

    @Test
    fun deeplyNestedValuesAreNeverRead() {
        val nested = "[".repeat(100_000) + "]".repeat(100_000)
        val bytes = "{\"archive\":\"medicabinet\",\"formatVersion\":1,\"exportedAt\":$nested}".toByteArray()
        val error = assertFailsWith<ArchiveException> { ArchiveDecoder.decode(bytes) }
        assertEquals(ArchiveErrorKind.INVALID_STRUCTURE, error.kind)
        assertEquals("exportedAt", error.path)
    }
}
