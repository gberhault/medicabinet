/// The French medicine presentation code. A GS1 DataMatrix carries it as a GTIN-14 (`0` + CIP13); a 1D barcode carries
/// the 13 digits.
public enum CIP13 {
    /// The 13-digit CIP13 of a GTIN-14, or `nil` when the GTIN is not a French medicine presentation: it must be
    /// exactly 14 ASCII digits, start with `03400` (so parapharmacy `3401…` and foreign GTINs are refused) and have a
    /// valid GS1 check digit. Nothing is trimmed or corrected.
    public static func from(gtin: String) -> String? {
        let digits = Array(gtin.utf8)
        guard digits.count == 14, digits.allSatisfy({ (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0) }) else {
            return nil
        }
        guard gtin.hasPrefix("03400"), hasValidCheckDigit(digits) else { return nil }
        return String(gtin.dropFirst())
    }

    /// GS1 mod-10: from the right, the digit before the check digit weighs 3, then 1, 3, …
    private static func hasValidCheckDigit(_ digits: [UInt8]) -> Bool {
        let values = digits.map { Int($0) - Int(UInt8(ascii: "0")) }
        let body = values.dropLast().reversed().enumerated().reduce(0) { sum, pair in
            sum + pair.element * (pair.offset.isMultiple(of: 2) ? 3 : 1)
        }
        return (10 - body % 10) % 10 == values[values.count - 1]
    }
}
