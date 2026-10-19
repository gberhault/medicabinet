/// A strict, pull-style JSON reader over UTF-8 bytes (RFC 8259), written so that the iOS and
/// Android cores accept and refuse exactly the same files (design §1.1):
/// - the whole input must be valid UTF-8 (no overlong forms, no surrogates), a leading BOM is skipped;
/// - no comments, no trailing commas, no leading zeros, no NaN; nothing may follow the root value;
/// - strings are limited to `maxStringBytes` once decoded; `\u` surrogates must pair;
/// - integers must fit Int64; a fraction or exponent where an integer is expected is a type error.
///
/// It never builds a tree: the caller asks for the next value of the type it expects, so a
/// value the schema does not expect is never read (an unknown key is refused at once).
/// Syntax faults throw `.notJSON`; a valid value of the wrong type throws `.invalidStructure`;
/// a value that is valid JSON but unreadable (too long, too big) throws `.invalidValue`.
///
/// String lexing lives in `JSONReader+Strings.swift`, which is why the state is not `private`.
struct JSONReader {
    enum Value: Equatable {
        case object, array, string, number, bool, null
    }

    let bytes: [UInt8]
    var index: Int
    let maxStringBytes: Int

    init(_ bytes: [UInt8], maxStringBytes: Int) throws(ArchiveError) {
        self.bytes = bytes
        self.maxStringBytes = maxStringBytes
        index = bytes.starts(with: [0xEF, 0xBB, 0xBF]) ? 3 : 0
        guard StrictUTF8.isValid(bytes, from: index) else { throw ArchiveError(.notJSON) }
    }

    // MARK: Structure

    /// The kind of the next value, without consuming it. Anything that cannot start a JSON value is a syntax fault.
    mutating func peek() throws(ArchiveError) -> Value {
        skipWhitespace()
        guard index < bytes.count else { throw ArchiveError(.notJSON) }
        switch bytes[index] {
        case UInt8(ascii: "{"): return .object
        case UInt8(ascii: "["): return .array
        case UInt8(ascii: "\""): return .string
        case UInt8(ascii: "-"), UInt8(ascii: "0")...UInt8(ascii: "9"): return .number
        case UInt8(ascii: "t"), UInt8(ascii: "f"): return .bool
        case UInt8(ascii: "n"): return .null
        default: throw ArchiveError(.notJSON)
        }
    }

    mutating func beginObject() throws(ArchiveError) {
        try expect(UInt8(ascii: "{"))
    }

    /// The next key of the current object, or `nil` at its end (consumed). `isFirst` is true for the first call.
    mutating func nextKey(isFirst: Bool) throws(ArchiveError) -> String? {
        skipWhitespace()
        guard index < bytes.count else { throw ArchiveError(.notJSON) }
        if bytes[index] == UInt8(ascii: "}") {
            index += 1
            return nil
        }
        if !isFirst {
            try expect(UInt8(ascii: ","))
            skipWhitespace()
        }
        guard index < bytes.count, bytes[index] == UInt8(ascii: "\"") else { throw ArchiveError(.notJSON) }
        let key = try readString(path: nil)
        try expect(UInt8(ascii: ":"))
        return key
    }

    mutating func beginArray() throws(ArchiveError) {
        try expect(UInt8(ascii: "["))
    }

    /// Whether another element follows in the current array (the closing bracket is consumed when not).
    mutating func hasNextElement(isFirst: Bool) throws(ArchiveError) -> Bool {
        skipWhitespace()
        guard index < bytes.count else { throw ArchiveError(.notJSON) }
        if bytes[index] == UInt8(ascii: "]") {
            index += 1
            return false
        }
        if !isFirst { try expect(UInt8(ascii: ",")) }
        return true
    }

    /// Only whitespace may remain after the root value.
    mutating func finish() throws(ArchiveError) {
        skipWhitespace()
        guard index == bytes.count else { throw ArchiveError(.notJSON) }
    }

    // MARK: Values

    mutating func readString(_ field: FieldPath) throws(ArchiveError) -> String {
        guard try peek() == .string else { throw ArchiveError(.invalidStructure, path: field.text) }
        return try readString(path: field)
    }

    /// A string, or `nil` for a JSON `null`.
    mutating func readOptionalString(_ field: FieldPath) throws(ArchiveError) -> String? {
        if try readNullIfPresent() { return nil }
        return try readString(field)
    }

    mutating func readBool(_ field: FieldPath) throws(ArchiveError) -> Bool {
        guard try peek() == .bool else { throw ArchiveError(.invalidStructure, path: field.text) }
        if bytes[index] == UInt8(ascii: "t") {
            try readLiteral("true")
            return true
        }
        try readLiteral("false")
        return false
    }

    mutating func readInteger(_ field: FieldPath) throws(ArchiveError) -> Int64 {
        guard try peek() == .number else { throw ArchiveError(.invalidStructure, path: field.text) }
        let number = try lexNumber()
        guard number.isInteger else { throw ArchiveError(.invalidStructure, path: field.text) }
        guard let value = number.value else { throw ArchiveError(.invalidValue, path: field.text) }
        return value
    }

    /// Consumes a JSON `null` and returns true, or returns false when the next value is something else.
    mutating func readNullIfPresent() throws(ArchiveError) -> Bool {
        guard try peek() == .null else { return false }
        try readLiteral("null")
        return true
    }

    // MARK: Lexing

    mutating func skipWhitespace() {
        while index < bytes.count {
            switch bytes[index] {
            case 0x20, 0x09, 0x0A, 0x0D: index += 1
            default: return
            }
        }
    }

    /// Skips whitespace, then consumes `byte` or throws.
    mutating func expect(_ byte: UInt8) throws(ArchiveError) {
        skipWhitespace()
        guard index < bytes.count, bytes[index] == byte else { throw ArchiveError(.notJSON) }
        index += 1
    }

    private mutating func readLiteral(_ literal: StaticString) throws(ArchiveError) {
        let start = index
        let matches = literal.withUTF8Buffer { expected in
            start + expected.count <= bytes.count && zip(expected, bytes[start...]).allSatisfy { $0 == $1 }
        }
        guard matches else { throw ArchiveError(.notJSON) }
        index += literal.utf8CodeUnitCount
    }

    private struct Number {
        let isInteger: Bool
        /// The integer's value, `nil` when it does not fit Int64.
        let value: Int64?
    }

    /// Lexes `-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?` and interprets it as an integer when it has no fraction or exponent.
    private mutating func lexNumber() throws(ArchiveError) -> Number {
        let negative = bytes[index] == UInt8(ascii: "-")
        if negative { index += 1 }
        let digitsStart = index
        guard index < bytes.count, isDigit(bytes[index]) else { throw ArchiveError(.notJSON) }
        if bytes[index] == UInt8(ascii: "0") {
            index += 1
            if index < bytes.count, isDigit(bytes[index]) { throw ArchiveError(.notJSON) }
        } else {
            while index < bytes.count, isDigit(bytes[index]) { index += 1 }
        }
        let digits = bytes[digitsStart..<index]
        var isInteger = true
        if index < bytes.count, bytes[index] == UInt8(ascii: ".") {
            isInteger = false
            index += 1
            try skipDigits()
        }
        if index < bytes.count, bytes[index] == UInt8(ascii: "e") || bytes[index] == UInt8(ascii: "E") {
            isInteger = false
            index += 1
            if index < bytes.count, bytes[index] == UInt8(ascii: "+") || bytes[index] == UInt8(ascii: "-") { index += 1 }
            try skipDigits()
        }
        guard isInteger else { return Number(isInteger: false, value: nil) }
        return Number(isInteger: true, value: Self.int64(digits, negative: negative))
    }

    /// One or more digits.
    private mutating func skipDigits() throws(ArchiveError) {
        guard index < bytes.count, isDigit(bytes[index]) else { throw ArchiveError(.notJSON) }
        while index < bytes.count, isDigit(bytes[index]) { index += 1 }
    }

    private func isDigit(_ byte: UInt8) -> Bool {
        (UInt8(ascii: "0")...UInt8(ascii: "9")).contains(byte)
    }

    /// Accumulates negatively so Int64.min is representable; `nil` on overflow.
    private static func int64(_ digits: ArraySlice<UInt8>, negative: Bool) -> Int64? {
        var value: Int64 = 0
        for digit in digits {
            let (shifted, overflowMul) = value.multipliedReportingOverflow(by: 10)
            let (next, overflowSub) = shifted.subtractingReportingOverflow(Int64(digit - UInt8(ascii: "0")))
            if overflowMul || overflowSub { return nil }
            value = next
        }
        if negative { return value }
        return value == Int64.min ? nil : -value
    }
}
