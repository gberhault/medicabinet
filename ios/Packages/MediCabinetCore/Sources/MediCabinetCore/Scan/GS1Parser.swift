/// A barcode type the scanner recognises.
public enum Symbology: String, Hashable, Sendable {
    case dataMatrix
    case ean13
}

/// What the code says about the expiry. A date that the code holds but that is not a real
/// date is `invalid`, never repaired; a code without a date is `missing`.
public enum ExpiryReading: Hashable, Sendable {
    case date(ExpiryDate)
    case missing
    /// An `ExpiryDateError` kind, or `invalidFormat` when AI 17 is not six digits.
    case invalid(kind: String)
}

public struct ScannedCode: Hashable, Sendable {
    /// GTIN-14, check digit verified.
    public let gtin: String
    public let expiry: ExpiryReading
    public let lot: String?
    public let serial: String?
    public let symbology: Symbology

    public init(gtin: String, expiry: ExpiryReading, lot: String?, serial: String?, symbology: Symbology) {
        self.gtin = gtin
        self.expiry = expiry
        self.lot = lot
        self.serial = serial
        self.symbology = symbology
    }
}

public enum ScanParseError: Error, Equatable, Sendable {
    case empty
    case tooLong
    case invalidCharacters
    case missingGTIN
    case invalidGTIN

    /// Platform-neutral name, shared with the Android app and `fixtures/gs1-datamatrix.json`.
    public var kind: String {
        switch self {
        case .empty: "empty"
        case .tooLong: "tooLong"
        case .invalidCharacters: "invalidCharacters"
        case .missingGTIN: "missingGTIN"
        case .invalidGTIN: "invalidGTIN"
        }
    }
}

public enum GTIN {
    /// A 13-digit EAN or a 14-digit GTIN as a GTIN-14, or `nil` when the length, the digits or the
    /// check digit are wrong.
    public static func normalized(_ digits: String) -> String? {
        let scalars = Array(digits.unicodeScalars)
        guard scalars.allSatisfy({ ("0"..."9").contains($0) }) else { return nil }
        let values = scalars.map { Int($0.value) - 48 }
        let padded: [Int]
        switch values.count {
        case 13: padded = [0] + values
        case 14: padded = values
        default: return nil
        }
        var sum = 0
        for (index, value) in padded.dropLast().reversed().enumerated() {
            sum += value * (index.isMultiple(of: 2) ? 3 : 1)
        }
        guard (10 - sum % 10) % 10 == padded[13] else { return nil }
        return padded.map(String.init).joined()
    }
}

/// Reads what a French medicine box's code holds. Pure: no camera, no clock, never logs.
///
/// GS1 quirks this handles: the group separator (GS, ASCII 29) ends variable-length fields but
/// scanners may drop it, keep it, or put it first (FNC1); a symbology identifier (`]d2`) may be
/// prepended; AI 17 day `00` means "end of that month". When the separator is missing, a
/// variable-length field swallows what follows — so the expiry is then reported `missing`, never
/// searched for inside another field.
public enum GS1Parser {
    public static let maxPayloadLength = 512

    private static let groupSeparator: UInt8 = 0x1D
    private static let maxFieldLength = 20
    private static let fixedLengths: [String: Int] = [
        "00": 18, "01": 14, "02": 14, "11": 6, "12": 6, "13": 6, "15": 6, "16": 6, "17": 6
    ]
    private static let variableAIs: Set<String> = ["10", "21", "22", "30"]
    private static let longVariableAIs: Set<String> = ["240", "241", "710", "711", "712", "713", "714", "715"]
    /// GS1 character set 82.
    private static let allowedFieldBytes: Set<UInt8> =
        Set("!\"%&'()*+,-./0123456789:;<=>?ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz".utf8)

    public static func parse(_ payload: String) throws(ScanParseError) -> ScannedCode {
        let walk = Self.walk(try bytes(of: payload))
        if walk.gtinIsInvalid { throw .invalidGTIN }
        guard let gtin = walk.gtin else { throw .missingGTIN }
        return ScannedCode(
            gtin: gtin, expiry: walk.expiry, lot: walk.lot, serial: walk.serial,
            symbology: walk.isEAN13 ? .ean13 : .dataMatrix
        )
    }

    /// The structure of a payload — prefix, AIs in order, the length of variable fields, GS — with
    /// no lot or serial value, so it can be shown or shared safely. `?(n)` is an unknown AI
    /// followed by `n` characters; `EAN13` a 13-digit code.
    public static func shape(of payload: String) -> String {
        do {
            return Self.walk(try bytes(of: payload)).tokens.joined(separator: " ")
        } catch {
            switch error {
            case .tooLong: return "tooLong(\(payload.unicodeScalars.count))"
            default: return error.kind
            }
        }
    }

    // MARK: - Reading

    private static func bytes(of payload: String) throws(ScanParseError) -> [UInt8] {
        let scalars = Array(payload.unicodeScalars)
        if scalars.isEmpty { throw .empty }
        if scalars.count > maxPayloadLength { throw .tooLong }
        var bytes: [UInt8] = []
        bytes.reserveCapacity(scalars.count)
        for scalar in scalars {
            let value = scalar.value
            guard value == UInt32(groupSeparator) || (0x20...0x7E).contains(value) else { throw .invalidCharacters }
            bytes.append(UInt8(value))
        }
        return bytes
    }

    private struct Walk {
        var tokens: [String] = []
        var gtin: String?
        var gtinIsInvalid = false
        var expiry: ExpiryReading = .missing
        var lot: String?
        var serial: String?
        var isEAN13 = false
    }

    private static func isDigit(_ byte: UInt8) -> Bool { (48...57).contains(byte) }

    private static func isLetter(_ byte: UInt8) -> Bool { (65...90).contains(byte) || (97...122).contains(byte) }

    private static func text(_ bytes: ArraySlice<UInt8>) -> String {
        String(bytes.map { Character(Unicode.Scalar($0)) })
    }

    private static func walk(_ bytes: [UInt8]) -> Walk {
        var walk = Walk()
        var index = readLeading(bytes, into: &walk)
        if bytes.count - index == 13, bytes[index...].allSatisfy(isDigit) {
            walk.tokens.append("EAN13")
            walk.isEAN13 = true
            walk.gtin = GTIN.normalized(text(bytes[index...]))
            walk.gtinIsInvalid = walk.gtin == nil
            return walk
        }
        var seen = Set<String>()
        while index < bytes.count {
            if bytes[index] == groupSeparator {
                walk.tokens.append("GS")
                index += 1
                continue
            }
            guard let identifier = identifier(in: bytes, at: index) else {
                walk.tokens.append("?(\(bytes.count - index))")
                break
            }
            let field = readField(identifier, in: bytes, from: index + identifier.count, into: &walk)
            index = field.end
            if seen.insert(identifier).inserted { read(identifier, field.value, into: &walk) }
            if field.truncated { break }
        }
        return walk
    }

    /// Skips a symbology identifier (`]d2`) and leading FNC1; returns the index of the first AI.
    private static func readLeading(_ bytes: [UInt8], into walk: inout Walk) -> Int {
        var index = 0
        if bytes.count >= 3, bytes[0] == UInt8(ascii: "]"), isLetter(bytes[1]), isDigit(bytes[2]) || isLetter(bytes[2]) {
            walk.tokens.append(text(bytes[0..<3]))
            index = 3
        }
        if index < bytes.count, bytes[index] == groupSeparator {
            while index < bytes.count, bytes[index] == groupSeparator { index += 1 }
            walk.tokens.append("FNC1")
        }
        return index
    }

    /// The known AI at `index`, or `nil` for an unknown one.
    private static func identifier(in bytes: [UInt8], at index: Int) -> String? {
        let two = text(bytes[index..<min(index + 2, bytes.count)])
        let three = text(bytes[index..<min(index + 3, bytes.count)])
        if two == "24" || two == "71", longVariableAIs.contains(three) { return three }
        return fixedLengths[two] != nil || variableAIs.contains(two) ? two : nil
    }

    private struct Field {
        let value: ArraySlice<UInt8>
        let end: Int
        let truncated: Bool
    }

    private static func readField(
        _ identifier: String, in bytes: [UInt8], from start: Int, into walk: inout Walk
    ) -> Field {
        if let length = fixedLengths[identifier] {
            let available = bytes.count - start
            let truncated = available < length
            let end = truncated ? bytes.count : start + length
            walk.tokens.append(truncated ? "\(identifier)(\(available))" : identifier)
            return Field(value: bytes[start..<end], end: end, truncated: truncated)
        }
        var end = start
        while end < bytes.count, bytes[end] != groupSeparator { end += 1 }
        walk.tokens.append("\(identifier)(\(end - start))")
        return Field(value: bytes[start..<end], end: end, truncated: false)
    }

    private static func read(_ identifier: String, _ value: ArraySlice<UInt8>, into walk: inout Walk) {
        switch identifier {
        case "01":
            walk.gtin = value.count == 14 ? GTIN.normalized(text(value)) : nil
            walk.gtinIsInvalid = walk.gtin == nil
        case "17":
            walk.expiry = expiry(value)
        case "10":
            walk.lot = fieldValue(value)
        case "21":
            walk.serial = fieldValue(value)
        default:
            break
        }
    }

    private static func fieldValue(_ value: ArraySlice<UInt8>) -> String? {
        guard (1...maxFieldLength).contains(value.count), value.allSatisfy(allowedFieldBytes.contains) else {
            return nil
        }
        return text(value)
    }

    private static func expiry(_ value: ArraySlice<UInt8>) -> ExpiryReading {
        guard value.count == 6, value.allSatisfy(isDigit) else { return .invalid(kind: "invalidFormat") }
        let digits = value.map { Int($0) - 48 }
        let year = 2000 + digits[0] * 10 + digits[1]
        let month = digits[2] * 10 + digits[3]
        let day = digits[4] * 10 + digits[5]
        do {
            return .date(try ExpiryDate.make(year: year, month: month, day: day == 0 ? nil : day))
        } catch {
            return .invalid(kind: error.kind)
        }
    }
}
