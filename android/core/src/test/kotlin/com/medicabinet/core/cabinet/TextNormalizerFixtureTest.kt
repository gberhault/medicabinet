package com.medicabinet.core.cabinet

import com.medicabinet.core.testing.SharedFixtures
import com.medicabinet.core.testing.textNormalization
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Runs every case of `fixtures/text-normalization.json`, which the Swift core also runs. */
class TextNormalizerFixtureTest {
    @Test
    fun theSharedFixtureLoadsAndHasCases() {
        val fixture = SharedFixtures.textNormalization()
        assertEquals(1, fixture.formatVersion)
        assertTrue(fixture.cases.size >= 25, "expected at least 25 cases, found ${fixture.cases.size}")
    }

    @TestFactory
    fun caseMatchesTheSharedExpectation(): List<DynamicTest> = SharedFixtures.textNormalization().cases.map { case ->
        dynamicTest(case.name) {
            when (case.kind) {
                "normalize" -> assertEquals(case.normalized, TextNormalizer.normalize(assertNotNull(case.input)))
                "match" -> assertEquals(
                    case.matches,
                    TextNormalizer.matches(assertNotNull(case.text), assertNotNull(case.query))
                )
                else -> error("unknown case kind ${case.kind}")
            }
        }
    }
}

class TextNormalizerTest {
    @Test
    fun normalizingTwiceChangesNothing() {
        for (text in listOf("Œdème  ÉFFÉRALGAN 500", "İstanbul", "Straße", "  x  ")) {
            val once = TextNormalizer.normalize(text)
            assertEquals(once, TextNormalizer.normalize(once))
        }
    }

    @Test
    fun trimmedRemovesOnlyTheEdgesAndKeepsInnerText() {
        assertEquals("a  b", TextNormalizer.trimmed("  a  b \t"))
        assertEquals("", TextNormalizer.trimmed(" \t　"))
        assertEquals("", TextNormalizer.trimmed(""))
    }

    @Test
    fun matchesNormalizedAgreesWithMatches() {
        val text = "Doliprane 1000 mg"
        val normalized = TextNormalizer.normalize(text)
        for (query in listOf("doli", "1000 DOLI", "aspirine", " ")) {
            assertEquals(TextNormalizer.matches(text, query), TextNormalizer.matchesNormalized(normalized, query))
        }
    }

    @Test
    fun aNeedleLongerThanTheTextNeverMatches() {
        assertFalse(TextNormalizer.matches("ab", "abc"))
    }

    @Test
    fun compareOrdersByCodePointThenLength() {
        assertEquals(-1, TextNormalizer.compare("a", "b"))
        assertEquals(1, TextNormalizer.compare("b", "a"))
        assertEquals(0, TextNormalizer.compare("a", "a"))
        assertEquals(-1, TextNormalizer.compare("a", "ab"))
        assertEquals(1, TextNormalizer.compare("ab", "a"))
        // U+FF5E (BMP) sorts after U+1F48A (supplementary) in UTF-16 but before it by code point.
        assertEquals(-1, TextNormalizer.compare("～", "💊"))
    }

    @Test
    fun noBreakSpacesCountAsWhitespaceUnlikeCharacterIsWhitespace() {
        assertTrue(TextNormalizer.isWhitespace(0xA0))
        assertTrue(TextNormalizer.isWhitespace(0x202F))
        assertFalse(TextNormalizer.isWhitespace('a'.code))
        assertFalse(TextNormalizer.isWhitespace(0x1F48A))
    }
}
