package com.medicabinet.core.archive

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class JsonReaderTest {
    private val field = FieldPath("boxes[0]", "lot")

    private fun reader(text: String, maxStringBytes: Int = 4_096) = JsonReader(text.toByteArray(), maxStringBytes)

    private fun reader(bytes: IntArray) = JsonReader(ByteArray(bytes.size) { bytes[it].toByte() }, 4_096)

    private fun expectError(kind: ArchiveErrorKind, path: String? = null, block: () -> Unit) {
        val error = assertFailsWith<ArchiveException> { block() }
        assertEquals(kind, error.kind)
        assertEquals(path, error.path)
    }

    private fun notJson(text: String, read: JsonReader.() -> Unit) =
        expectError(ArchiveErrorKind.NOT_JSON) { reader(text).read() }

    // MARK: UTF-8

    @TestFactory
    fun invalidUtf8IsNotJson(): List<DynamicTest> = listOf(
        intArrayOf(0x61, 0x80), // stray continuation byte
        intArrayOf(0xC3), // truncated two-byte sequence
        intArrayOf(0xC0, 0x80), // overlong NUL
        intArrayOf(0xC1, 0xBF), // overlong
        intArrayOf(0xE0, 0x80, 0x80), // overlong three-byte
        intArrayOf(0xE2, 0x82), // truncated three-byte sequence
        intArrayOf(0xED, 0xA0, 0x80), // encoded surrogate
        intArrayOf(0xF0, 0x80, 0x80, 0x80), // overlong four-byte
        intArrayOf(0xF0, 0x9F, 0xA7), // truncated four-byte sequence
        intArrayOf(0xF4, 0x90, 0x80, 0x80), // above U+10FFFF
        intArrayOf(0xF5, 0x80, 0x80, 0x80), // lead byte that never occurs
        intArrayOf(0xFF),
        intArrayOf(0xE2, 0x28, 0xA1), // bad second byte
        intArrayOf(0xE2, 0x82, 0x28), // bad third byte
        intArrayOf(0xF0, 0x9F, 0x28, 0xBC), // bad third byte of four
        intArrayOf(0xF0, 0x9F, 0xA7, 0x28) // bad fourth byte
    ).map { bytes ->
        dynamicTest(bytes.joinToString(" ") { "%02X".format(it) }) {
            expectError(ArchiveErrorKind.NOT_JSON) { reader(bytes) }
        }
    }

    @Test
    fun validUtf8OfEveryLengthIsAccepted() {
        assertEquals("a é € 🧳", reader("\"a é € 🧳\"").readString(field))
    }

    @Test
    fun aByteOrderMarkIsSkipped() {
        val bytes = intArrayOf(0xEF, 0xBB, 0xBF) + "true".map { it.code }
        assertTrue(reader(bytes).readBoolean(field))
    }

    @Test
    fun aByteOrderMarkAloneIsNotAValue() {
        val parsed = reader(intArrayOf(0xEF, 0xBB, 0xBF))
        expectError(ArchiveErrorKind.NOT_JSON) { parsed.peek() }
    }

    @Test
    fun twoBytesThatStartLikeABomAreNotOne() {
        expectError(ArchiveErrorKind.NOT_JSON) { reader(intArrayOf(0xEF, 0xBB)) }
    }

    // MARK: peek

    @TestFactory
    fun peekTellsTheKindOfTheNextValue(): List<DynamicTest> = listOf(
        "{}" to JsonReader.Value.OBJECT,
        "[]" to JsonReader.Value.ARRAY,
        "\"x\"" to JsonReader.Value.STRING,
        "12" to JsonReader.Value.NUMBER,
        "-1" to JsonReader.Value.NUMBER,
        "true" to JsonReader.Value.BOOL,
        "false" to JsonReader.Value.BOOL,
        "null" to JsonReader.Value.NULL,
        "  \n\t\r{}" to JsonReader.Value.OBJECT
    ).map { (text, kind) -> dynamicTest("'$text'") { assertEquals(kind, reader(text).peek()) } }

    @TestFactory
    fun peekRejectsWhatCannotStartAValue(): List<DynamicTest> =
        listOf("", "   ", "x", "/*c*/{}", "'a'", "+1", ".5", "]").map { text ->
            dynamicTest("'$text'") { notJson(text) { peek() } }
        }

    // MARK: strings

    /**
     * The escape tests write `¤` where JSON needs a backslash: the source never holds a `\uXXXX` sequence,
     * which editors and tools tend to turn into the character it names and so silently change the test.
     */
    private fun json(text: String) = text.replace('¤', '\\')

    @Test
    fun stringEscapesAreDecoded() {
        val text = json("\"q¤\" b¤¤ s¤/ ¤b¤f¤n¤r¤t ¤u0041 ¤u00e9 ¤u20AC ¤ud83e¤uddf3 ¤u0000\"")
        val expected = "q\" b\\ s/ \b\u000C\n\r\t A ${0xE9.toChar()} ${0x20AC.toChar()} " +
            "${String(Character.toChars(0x1F9F3))} ${0.toChar()}"
        assertEquals(expected, reader(text).readString(field))
    }

    @Test
    fun anEscapedScalarJustAboveTheSurrogatesIsAnOrdinaryCharacter() {
        assertEquals("${0xE000.toChar()}${0xFFFF.toChar()}", reader(json("\"¤ue000¤uFFFF\"")).readString(field))
    }

    @TestFactory
    fun malformedStringsAreNotJson(): List<DynamicTest> = listOf(
        "\"¤ud83e\"", // high surrogate at the end of the string
        "\"¤ud83ex\"", // high surrogate followed by a plain character
        "\"¤ud83e¤n\"", // ... by another escape
        "\"¤ud83e¤u0041\"", // ... by an escaped non-surrogate
        "\"¤ud83e¤ud83e\"", // ... by another high surrogate
        "\"¤udd0f\"", // lone low surrogate
        "\"¤q\"",
        "\"¤u12\"",
        "\"¤u12G4\"",
        "\"¤u12g4\"", // the characters just outside each hexadecimal range
        "\"¤u12/4\"",
        "\"¤u12:4\"",
        "\"¤u12@4\"",
        "\"¤u12`4\"",
        "\"¤ud83e¤ue000\"", // a high surrogate followed by an escape above the low surrogates
        "\"abc",
        "\"a${1.toChar()}b\"",
        "\"¤",
        "\"¤u"
    ).map { text -> dynamicTest(text) { notJson(json(text)) { readString(field) } } }

    @Test
    fun aStringOverTheLimitIsAnInvalidValueAtItsField() {
        expectError(ArchiveErrorKind.INVALID_VALUE, "boxes[0].lot") { reader("\"abcdef\"", 5).readString(field) }
        assertEquals("abcde", reader("\"abcde\"", 5).readString(field))
    }

    @Test
    fun aKeyOverTheLimitIsAnInvalidValueWithoutAPath() {
        val parsed = reader("{\"abcdef\":1}", 5)
        parsed.beginObject()
        expectError(ArchiveErrorKind.INVALID_VALUE) { parsed.nextKey(true) }
    }

    @Test
    fun readingAStringWhereAnotherTypeIsFoundIsAStructureError() {
        expectError(ArchiveErrorKind.INVALID_STRUCTURE, "boxes[0].lot") { reader("12").readString(field) }
    }

    @Test
    fun anOptionalStringIsNullForNullAndAStringOtherwise() {
        val parsed = reader("null \"x\" 5")
        assertNull(parsed.readOptionalString(field))
        assertEquals("x", parsed.readOptionalString(field))
        expectError(ArchiveErrorKind.INVALID_STRUCTURE, "boxes[0].lot") { parsed.readOptionalString(field) }
    }

    // MARK: numbers

    @TestFactory
    fun integersAreRead(): List<DynamicTest> = listOf(
        "0" to 0L,
        "-0" to 0L,
        "7" to 7L,
        "-7" to -7L,
        "123456" to 123_456L,
        "9223372036854775807" to Long.MAX_VALUE,
        "-9223372036854775808" to Long.MIN_VALUE
    ).map { (text, expected) -> dynamicTest(text) { assertEquals(expected, reader(text).readInteger(field)) } }

    @TestFactory
    fun integersBeyondLongAreInvalidValues(): List<DynamicTest> =
        listOf("9223372036854775808", "-9223372036854775809", "99999999999999999999").map { text ->
            dynamicTest(text) {
                expectError(ArchiveErrorKind.INVALID_VALUE, "boxes[0].lot") { reader(text).readInteger(field) }
            }
        }

    @TestFactory
    fun fractionsAndExponentsAreStructureErrorsWhereAnIntegerIsExpected(): List<DynamicTest> =
        listOf("1.0", "1.5", "1e3", "1E3", "1e+3", "1e-3", "-0.0", "2.027e3").map { text ->
            dynamicTest(text) {
                expectError(ArchiveErrorKind.INVALID_STRUCTURE, "boxes[0].lot") { reader(text).readInteger(field) }
            }
        }

    @TestFactory
    fun malformedNumbersAreNotJson(): List<DynamicTest> =
        listOf("01", "-", "-x", "1.", "1.x", "1e", "1e+", "1ex", "00", "-01").map { text ->
            dynamicTest(text) { notJson(text) { readInteger(field) } }
        }

    @Test
    fun readingAnIntegerWhereAnotherTypeIsFoundIsAStructureError() {
        expectError(ArchiveErrorKind.INVALID_STRUCTURE, "boxes[0].lot") { reader("\"7\"").readInteger(field) }
    }

    // MARK: booleans and null

    @Test
    fun booleansAreRead() {
        val parsed = reader("true false")
        assertTrue(parsed.readBoolean(field))
        assertFalse(parsed.readBoolean(field))
    }

    @TestFactory
    fun misspelledBooleansAreNotJson(): List<DynamicTest> =
        listOf("tru", "fals", "t", "f", "trux", "falsy").map { text ->
            dynamicTest(text) { notJson(text) { readBoolean(field) } }
        }

    @Test
    fun readingABooleanWhereAnotherTypeIsFoundIsAStructureError() {
        expectError(ArchiveErrorKind.INVALID_STRUCTURE, "boxes[0].lot") { reader("1").readBoolean(field) }
    }

    @Test
    fun nullIsConsumedOnlyWhenPresent() {
        val parsed = reader("null 5")
        assertTrue(parsed.readNullIfPresent())
        assertFalse(parsed.readNullIfPresent())
        assertEquals(5, parsed.readInteger(field))
    }

    @TestFactory
    fun misspelledNullIsNotJson(): List<DynamicTest> = listOf("nul", "nulL", "n").map { text ->
        dynamicTest(text) { notJson(text) { readNullIfPresent() } }
    }

    // MARK: objects, arrays, end of input

    @Test
    fun objectKeysAreReadInOrder() {
        val parsed = reader(" { \"a\" : 1 , \"b\":2 } ")
        parsed.beginObject()
        assertEquals("a", parsed.nextKey(true))
        assertEquals(1, parsed.readInteger(field))
        assertEquals("b", parsed.nextKey(false))
        assertEquals(2, parsed.readInteger(field))
        assertNull(parsed.nextKey(false))
        parsed.finish()
    }

    @Test
    fun anEmptyObjectHasNoKeys() {
        val parsed = reader("{ }")
        parsed.beginObject()
        assertNull(parsed.nextKey(true))
    }

    @TestFactory
    fun malformedObjectsAreNotJson(): List<DynamicTest> =
        listOf("{", "{\"a\"", "{\"a\":", "{1:2}", "{,}", "{\"a\" 1}", "{'a':1}").map { text ->
            dynamicTest(text) {
                notJson(text) {
                    beginObject()
                    var first = true
                    while (nextKey(first) != null) {
                        first = false
                        readInteger(field)
                    }
                }
            }
        }

    @TestFactory
    fun malformedObjectEndingsAreNotJson(): List<DynamicTest> =
        listOf("{\"a\":1 \"b\":2}", "{\"a\":1,}", "{\"a\":1", "{\"a\":1,").map { text ->
            dynamicTest(text) {
                val parsed = reader(text)
                parsed.beginObject()
                assertEquals("a", parsed.nextKey(true))
                parsed.readInteger(field)
                expectError(ArchiveErrorKind.NOT_JSON) { parsed.nextKey(false) }
            }
        }

    @Test
    fun arrayElementsAreWalked() {
        val parsed = reader("[ 1 , 2 ]")
        parsed.beginArray()
        assertTrue(parsed.hasNextElement(true))
        assertEquals(1, parsed.readInteger(field))
        assertTrue(parsed.hasNextElement(false))
        assertEquals(2, parsed.readInteger(field))
        assertFalse(parsed.hasNextElement(false))
    }

    @Test
    fun anEmptyArrayHasNoElement() {
        val parsed = reader("[]")
        parsed.beginArray()
        assertFalse(parsed.hasNextElement(true))
    }

    @TestFactory
    fun malformedArraysAreNotJson(): List<DynamicTest> = listOf("[", "[1", "[1 2]", "[1,]", "[,1]").map { text ->
        dynamicTest(text) {
            notJson(text) {
                beginArray()
                var first = true
                while (hasNextElement(first)) {
                    first = false
                    readInteger(field)
                }
            }
        }
    }

    @Test
    fun beginningTheWrongContainerIsNotJson() {
        notJson("[") { beginObject() }
        notJson("{") { beginArray() }
    }

    @Test
    fun onlyWhitespaceMayFollowTheRoot() {
        val clean = reader("1 \n")
        clean.readInteger(field)
        clean.finish()
        val dirty = reader("1 x")
        dirty.readInteger(field)
        expectError(ArchiveErrorKind.NOT_JSON) { dirty.finish() }
    }
}
