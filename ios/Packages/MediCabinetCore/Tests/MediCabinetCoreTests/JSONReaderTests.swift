import Foundation
import Testing
@testable import MediCabinetCore

@Suite struct JSONReaderTests {
    private let field = FieldPath("boxes[0]", "lot")

    private func reader(_ text: String, maxStringBytes: Int = 4_096) throws -> JSONReader {
        try JSONReader(Array(text.utf8), maxStringBytes: maxStringBytes)
    }

    private func reader(bytes: [UInt8]) throws -> JSONReader {
        try JSONReader(bytes, maxStringBytes: 4_096)
    }

    // MARK: UTF-8

    @Test(arguments: [
        [0x61, 0x80],                      // stray continuation byte
        [0xC3],                            // truncated two-byte sequence
        [0xC0, 0x80],                      // overlong NUL
        [0xC1, 0xBF],                      // overlong
        [0xE0, 0x80, 0x80],                // overlong three-byte
        [0xE2, 0x82],                      // truncated three-byte sequence
        [0xED, 0xA0, 0x80],                // encoded surrogate
        [0xF0, 0x80, 0x80, 0x80],          // overlong four-byte
        [0xF0, 0x9F, 0xA7],                // truncated four-byte sequence
        [0xF4, 0x90, 0x80, 0x80],          // above U+10FFFF
        [0xF5, 0x80, 0x80, 0x80],          // lead byte that never occurs
        [0xFF]
    ] as [[UInt8]])
    func invalidUTF8IsNotJSON(_ bytes: [UInt8]) {
        #expect(throws: ArchiveError(.notJSON)) { try reader(bytes: bytes) }
    }

    @Test func validUTF8OfEveryLengthIsAccepted() throws {
        var parsed = try reader("\"a é € 🧳\"")
        #expect(try parsed.readString(field) == "a é € 🧳")
    }

    @Test func aByteOrderMarkIsSkipped() throws {
        var parsed = try reader(bytes: [0xEF, 0xBB, 0xBF] + Array("true".utf8))
        #expect(try parsed.readBool(field) == true)
    }

    @Test func aByteOrderMarkAloneIsNotAValue() throws {
        var parsed = try reader(bytes: [0xEF, 0xBB, 0xBF])
        #expect(throws: ArchiveError(.notJSON)) { try parsed.peek() }
    }

    // MARK: peek

    @Test(arguments: [
        ("{}", JSONReader.Value.object), ("[]", .array), ("\"x\"", .string), ("12", .number), ("-1", .number),
        ("true", .bool), ("false", .bool), ("null", .null), ("  \n\t\r{}", .object)
    ])
    func peekTellsTheKindOfTheNextValue(_ text: String, _ kind: JSONReader.Value) throws {
        var parsed = try reader(text)
        #expect(try parsed.peek() == kind)
    }

    @Test(arguments: ["", "   ", "x", "/*c*/{}", "'a'", "+1", ".5", "]"])
    func peekRejectsWhatCannotStartAValue(_ text: String) throws {
        var parsed = try reader(text)
        #expect(throws: ArchiveError(.notJSON)) { try parsed.peek() }
    }

    // MARK: strings

    @Test func stringEscapesAreDecoded() throws {
        var parsed = try reader(#""q\" b\\ s\/ \b\f\n\r\t A é € 🧳 \u0000""#)
        #expect(try parsed.readString(field) == "q\" b\\ s/ \u{08}\u{0C}\n\r\t A é € 🧳 \u{0}")
    }

    @Test(arguments: [
        #""\ud83e""#,              // high surrogate at the end of the string
        #""\ud83ex""#,             // high surrogate followed by a plain character
        #""\ud83e\n""#,            // ... by another escape
        #""\ud83eA""#,        // ... by an escaped non-surrogate
        #""\ud83e\ud83e""#,        // ... by another high surrogate
        #""\udd0f""#,              // lone low surrogate
        #""\q""#, #""\u12""#, #""\u12G4""#, #""abc"#, "\"a\u{01}b\"", #""\"#, #""\u"#
    ])
    func malformedStringsAreNotJSON(_ text: String) throws {
        var parsed = try reader(text)
        #expect(throws: ArchiveError(.notJSON)) { try parsed.readString(field) }
    }

    @Test func aStringOverTheLimitIsAnInvalidValueAtItsField() throws {
        var parsed = try reader("\"abcdef\"", maxStringBytes: 5)
        #expect(throws: ArchiveError(.invalidValue, path: "boxes[0].lot")) { try parsed.readString(field) }
        var exact = try reader("\"abcde\"", maxStringBytes: 5)
        #expect(try exact.readString(field) == "abcde")
    }

    @Test func aKeyOverTheLimitIsAnInvalidValueWithoutAPath() throws {
        var parsed = try reader("{\"abcdef\":1}", maxStringBytes: 5)
        try parsed.beginObject()
        #expect(throws: ArchiveError(.invalidValue)) { try parsed.nextKey(isFirst: true) }
    }

    @Test func readingAStringWhereAnotherTypeIsFoundIsAStructureError() throws {
        var parsed = try reader("12")
        #expect(throws: ArchiveError(.invalidStructure, path: "boxes[0].lot")) { try parsed.readString(field) }
    }

    @Test func anOptionalStringIsNilForNullAndAStringOtherwise() throws {
        var parsed = try reader("null \"x\" 5")
        #expect(try parsed.readOptionalString(field) == nil)
        #expect(try parsed.readOptionalString(field) == "x")
        #expect(throws: ArchiveError(.invalidStructure, path: "boxes[0].lot")) { try parsed.readOptionalString(field) }
    }

    // MARK: numbers

    @Test(arguments: [
        ("0", Int64(0)), ("-0", 0), ("7", 7), ("-7", -7), ("123456", 123_456),
        ("9223372036854775807", Int64.max), ("-9223372036854775808", Int64.min)
    ])
    func integersAreRead(_ text: String, _ expected: Int64) throws {
        var parsed = try reader(text)
        #expect(try parsed.readInteger(field) == expected)
    }

    @Test(arguments: ["9223372036854775808", "-9223372036854775809", "99999999999999999999"])
    func integersBeyondInt64AreInvalidValues(_ text: String) throws {
        var parsed = try reader(text)
        #expect(throws: ArchiveError(.invalidValue, path: "boxes[0].lot")) { try parsed.readInteger(field) }
    }

    @Test(arguments: ["1.0", "1.5", "1e3", "1E3", "1e+3", "1e-3", "-0.0", "2.027e3"])
    func fractionsAndExponentsAreStructureErrorsWhereAnIntegerIsExpected(_ text: String) throws {
        var parsed = try reader(text)
        #expect(throws: ArchiveError(.invalidStructure, path: "boxes[0].lot")) { try parsed.readInteger(field) }
    }

    @Test(arguments: ["01", "-", "-x", "1.", "1.x", "1e", "1e+", "1ex", "00", "-01"])
    func malformedNumbersAreNotJSON(_ text: String) throws {
        var parsed = try reader(text)
        #expect(throws: ArchiveError(.notJSON)) { try parsed.readInteger(field) }
    }

    @Test func readingAnIntegerWhereAnotherTypeIsFoundIsAStructureError() throws {
        var parsed = try reader("\"7\"")
        #expect(throws: ArchiveError(.invalidStructure, path: "boxes[0].lot")) { try parsed.readInteger(field) }
    }

    // MARK: booleans and null

    @Test func booleansAreRead() throws {
        var parsed = try reader("true false")
        #expect(try parsed.readBool(field) == true)
        #expect(try parsed.readBool(field) == false)
    }

    @Test(arguments: ["tru", "fals", "t", "f", "trux", "falsy"])
    func misspelledBooleansAreNotJSON(_ text: String) throws {
        var parsed = try reader(text)
        #expect(throws: ArchiveError(.notJSON)) { try parsed.readBool(field) }
    }

    @Test func readingABooleanWhereAnotherTypeIsFoundIsAStructureError() throws {
        var parsed = try reader("1")
        #expect(throws: ArchiveError(.invalidStructure, path: "boxes[0].lot")) { try parsed.readBool(field) }
    }

    @Test func nullIsConsumedOnlyWhenPresent() throws {
        var parsed = try reader("null 5")
        #expect(try parsed.readNullIfPresent() == true)
        #expect(try parsed.readNullIfPresent() == false)
        #expect(try parsed.readInteger(field) == 5)
    }

    @Test(arguments: ["nul", "nulL", "n"])
    func misspelledNullIsNotJSON(_ text: String) throws {
        var parsed = try reader(text)
        #expect(throws: ArchiveError(.notJSON)) { try parsed.readNullIfPresent() }
    }

    // MARK: objects, arrays, end of input

    @Test func objectKeysAreReadInOrder() throws {
        var parsed = try reader(" { \"a\" : 1 , \"b\":2 } ")
        try parsed.beginObject()
        #expect(try parsed.nextKey(isFirst: true) == "a")
        #expect(try parsed.readInteger(field) == 1)
        #expect(try parsed.nextKey(isFirst: false) == "b")
        #expect(try parsed.readInteger(field) == 2)
        #expect(try parsed.nextKey(isFirst: false) == nil)
        try parsed.finish()
    }

    @Test func anEmptyObjectHasNoKeys() throws {
        var parsed = try reader("{ }")
        try parsed.beginObject()
        #expect(try parsed.nextKey(isFirst: true) == nil)
    }

    @Test(arguments: [
        "{", "{\"a\"", "{\"a\":", "{1:2}", "{,}", "{\"a\" 1}", "{'a':1}"
    ])
    func malformedObjectsAreNotJSON(_ text: String) throws {
        var parsed = try reader(text)
        try parsed.beginObject()
        #expect(throws: ArchiveError(.notJSON)) {
            var first = true
            while try parsed.nextKey(isFirst: first) != nil {
                first = false
                _ = try parsed.readInteger(field)
            }
        }
    }

    @Test(arguments: ["{\"a\":1 \"b\":2}", "{\"a\":1,}", "{\"a\":1", "{\"a\":1,"])
    func malformedObjectEndingsAreNotJSON(_ text: String) throws {
        var parsed = try reader(text)
        try parsed.beginObject()
        #expect(try parsed.nextKey(isFirst: true) == "a")
        _ = try parsed.readInteger(field)
        #expect(throws: ArchiveError(.notJSON)) { try parsed.nextKey(isFirst: false) }
    }

    @Test func arrayElementsAreWalked() throws {
        var parsed = try reader("[ 1 , 2 ]")
        try parsed.beginArray()
        #expect(try parsed.hasNextElement(isFirst: true))
        #expect(try parsed.readInteger(field) == 1)
        #expect(try parsed.hasNextElement(isFirst: false))
        #expect(try parsed.readInteger(field) == 2)
        #expect(try parsed.hasNextElement(isFirst: false) == false)
    }

    @Test func anEmptyArrayHasNoElement() throws {
        var parsed = try reader("[]")
        try parsed.beginArray()
        #expect(try parsed.hasNextElement(isFirst: true) == false)
    }

    @Test(arguments: ["[", "[1", "[1 2]", "[1,]", "[,1]"])
    func malformedArraysAreNotJSON(_ text: String) throws {
        var parsed = try reader(text)
        try parsed.beginArray()
        #expect(throws: ArchiveError(.notJSON)) {
            var first = true
            while try parsed.hasNextElement(isFirst: first) {
                first = false
                _ = try parsed.readInteger(field)
            }
        }
    }

    @Test func beginningTheWrongContainerIsNotJSON() throws {
        var object = try reader("[")
        #expect(throws: ArchiveError(.notJSON)) { try object.beginObject() }
        var array = try reader("{")
        #expect(throws: ArchiveError(.notJSON)) { try array.beginArray() }
    }

    @Test func onlyWhitespaceMayFollowTheRoot() throws {
        var clean = try reader("1 \n")
        _ = try clean.readInteger(field)
        try clean.finish()
        var dirty = try reader("1 x")
        _ = try dirty.readInteger(field)
        #expect(throws: ArchiveError(.notJSON)) { try dirty.finish() }
    }
}
