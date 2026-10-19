/// String lexing for `JSONReader`: quotes, escapes and `\u` surrogate pairs.
extension JSONReader {
    private static let simpleEscapes: [UInt8: UInt8] = [
        UInt8(ascii: "\""): UInt8(ascii: "\""), UInt8(ascii: "\\"): UInt8(ascii: "\\"), UInt8(ascii: "/"): UInt8(ascii: "/"),
        UInt8(ascii: "b"): 0x08, UInt8(ascii: "f"): 0x0C, UInt8(ascii: "n"): 0x0A, UInt8(ascii: "r"): 0x0D, UInt8(ascii: "t"): 0x09
    ]

    /// Reads a string starting at the opening quote. `path` is where a too-long string is reported (`nil` for a key).
    mutating func readString(path: FieldPath?) throws(ArchiveError) -> String {
        index += 1  // opening quote
        var decoded: [UInt8] = []
        while index < bytes.count {
            let byte = bytes[index]
            index += 1
            switch byte {
            case UInt8(ascii: "\""):
                // The bytes are valid UTF-8 by construction (the input was validated, escapes are encoded here).
                // swiftlint:disable:next optional_data_string_conversion
                return String(decoding: decoded, as: UTF8.self)
            case 0x00..<0x20:
                throw ArchiveError(.notJSON)
            case UInt8(ascii: "\\"):
                try appendEscape(to: &decoded)
            default:
                decoded.append(byte)
            }
            if decoded.count > maxStringBytes { throw ArchiveError(.invalidValue, path: path?.text) }
        }
        throw ArchiveError(.notJSON)  // the closing quote never came
    }

    private mutating func appendEscape(to decoded: inout [UInt8]) throws(ArchiveError) {
        guard index < bytes.count else { throw ArchiveError(.notJSON) }
        let byte = bytes[index]
        index += 1
        if let simple = Self.simpleEscapes[byte] {
            decoded.append(simple)
            return
        }
        guard byte == UInt8(ascii: "u") else { throw ArchiveError(.notJSON) }
        var scalar = try readHex4()
        if (0xD800..<0xDC00).contains(scalar) {
            scalar = try combine(highSurrogate: scalar)
        } else if (0xDC00..<0xE000).contains(scalar) {
            throw ArchiveError(.notJSON)  // a low surrogate with no high surrogate before it
        }
        Self.appendUTF8(scalar, to: &decoded)
    }

    /// A high surrogate must be followed by an escaped low surrogate; together they are one scalar.
    private mutating func combine(highSurrogate high: UInt32) throws(ArchiveError) -> UInt32 {
        guard index + 1 < bytes.count, bytes[index] == UInt8(ascii: "\\"), bytes[index + 1] == UInt8(ascii: "u") else {
            throw ArchiveError(.notJSON)
        }
        index += 2
        let low = try readHex4()
        guard (0xDC00..<0xE000).contains(low) else { throw ArchiveError(.notJSON) }
        return 0x10000 + ((high - 0xD800) << 10) + (low - 0xDC00)
    }

    private mutating func readHex4() throws(ArchiveError) -> UInt32 {
        guard index + 4 <= bytes.count else { throw ArchiveError(.notJSON) }
        var value: UInt32 = 0
        for _ in 0..<4 {
            guard let digit = Self.hexValue(bytes[index]) else { throw ArchiveError(.notJSON) }
            index += 1
            value = value * 16 + digit
        }
        return value
    }

    private static func hexValue(_ byte: UInt8) -> UInt32? {
        switch byte {
        case UInt8(ascii: "0")...UInt8(ascii: "9"): UInt32(byte - UInt8(ascii: "0"))
        case UInt8(ascii: "a")...UInt8(ascii: "f"): UInt32(byte - UInt8(ascii: "a")) + 10
        case UInt8(ascii: "A")...UInt8(ascii: "F"): UInt32(byte - UInt8(ascii: "A")) + 10
        default: nil
        }
    }

    /// `scalar` is a valid Unicode scalar value here: surrogates were refused above.
    private static func appendUTF8(_ scalar: UInt32, to out: inout [UInt8]) {
        let continuation: (UInt32) -> UInt8 = { UInt8(truncatingIfNeeded: 0x80 | $0 & 0x3F) }
        if scalar < 0x80 {
            out.append(UInt8(truncatingIfNeeded: scalar))
        } else if scalar < 0x800 {
            out.append(UInt8(truncatingIfNeeded: 0xC0 | scalar >> 6))
            out.append(continuation(scalar))
        } else if scalar < 0x10000 {
            out.append(UInt8(truncatingIfNeeded: 0xE0 | scalar >> 12))
            out.append(continuation(scalar >> 6))
            out.append(continuation(scalar))
        } else {
            out.append(UInt8(truncatingIfNeeded: 0xF0 | scalar >> 18))
            out.append(continuation(scalar >> 12))
            out.append(continuation(scalar >> 6))
            out.append(continuation(scalar))
        }
    }
}
