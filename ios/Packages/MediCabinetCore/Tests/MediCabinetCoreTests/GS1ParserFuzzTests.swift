import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

/// P13 — whatever the input, the parser returns a code or a typed error and never traps.
@Suite struct GS1ParserFuzzTests {
    private struct SplitMix64 {
        var state: UInt64
        mutating func next() -> UInt64 {
            state &+= 0x9E37_79B9_7F4A_7C15
            var mixed = state
            mixed = (mixed ^ (mixed >> 30)) &* 0xBF58_476D_1CE4_E5B9
            mixed = (mixed ^ (mixed >> 27)) &* 0x94D0_49BB_1331_11EB
            return mixed ^ (mixed >> 31)
        }
    }

    @Test func everyPrefixOfEverySharedPayloadIsHandled() {
        for fixtureCase in SharedFixtures.gs1Cases() {
            let scalars = Array(fixtureCase.payload.unicodeScalars)
            for length in 0...scalars.count {
                var text = String.UnicodeScalarView()
                text.append(contentsOf: scalars.prefix(length))
                let payload = String(text)
                _ = try? GS1Parser.parse(payload)
                _ = GS1Parser.shape(of: payload)
            }
        }
    }

    @Test func randomPayloadsAreHandled() {
        let alphabet = Array("0123456789]dQC1ABZ#- \u{1D}\u{0}\n\u{E9}".unicodeScalars)
        var random = SplitMix64(state: 12)
        for _ in 0..<5_000 {
            let length = Int(random.next() % 90)
            var text = String.UnicodeScalarView()
            for _ in 0..<length { text.append(alphabet[Int(random.next() % UInt64(alphabet.count))]) }
            let payload = String(text)
            _ = try? GS1Parser.parse(payload)
            _ = GS1Parser.shape(of: payload)
        }
    }

    @Test func randomDigitStringsStartingWithKnownAIsAreHandled() {
        var random = SplitMix64(state: 99)
        let starts = ["01", "17", "10", "21", "240", "99", "01034009300000141"]
        for _ in 0..<5_000 {
            var payload = starts[Int(random.next() % UInt64(starts.count))]
            for _ in 0..<Int(random.next() % 40) {
                payload += random.next() % 7 == 0 ? "\u{1D}" : String(random.next() % 10)
            }
            _ = try? GS1Parser.parse(payload)
        }
    }
}
