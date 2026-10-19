import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

/// Runs every case of `fixtures/text-normalization.json`, which the Android core also runs.
@Suite struct TextNormalizerFixtureTests {
    @Test func theSharedFixtureLoadsAndHasCases() throws {
        let fixture = try SharedFixtures.textNormalization()
        #expect(fixture.formatVersion == 1)
        #expect(fixture.cases.count >= 25)
    }

    @Test(arguments: SharedFixtures.textNormalizationCases())
    func caseMatchesTheSharedExpectation(_ fixtureCase: TextNormalizationCase) throws {
        switch fixtureCase.kind {
        case "normalize":
            let input = try #require(fixtureCase.input)
            let expected = try #require(fixtureCase.normalized)
            // Compared by scalars: Swift's == treats canonically equivalent strings as equal.
            #expect(Array(TextNormalizer.normalize(input).unicodeScalars) == Array(expected.unicodeScalars))
        case "match":
            let text = try #require(fixtureCase.text)
            let query = try #require(fixtureCase.query)
            #expect(TextNormalizer.matches(text, query: query) == fixtureCase.matches)
        default:
            Issue.record("unknown case kind \(fixtureCase.kind)")
        }
    }
}

@Suite struct TextNormalizerTests {
    @Test func normalizingTwiceChangesNothing() {
        for text in ["Œdème  ÉFFÉRALGAN\u{A0}500", "İstanbul", "Straße", "  x  "] {
            let once = TextNormalizer.normalize(text)
            #expect(TextNormalizer.normalize(once) == once)
        }
    }

    @Test func trimmedRemovesOnlyTheEdgesAndKeepsInnerText() {
        #expect(TextNormalizer.trimmed("\u{A0} a  b \t") == "a  b")
        #expect(TextNormalizer.trimmed(" \t\u{3000}") == "")
        #expect(TextNormalizer.trimmed("") == "")
    }

    @Test func matchesOnAnAlreadyNormalizedTextAgreesWithMatches() {
        let text = "Doliprane 1000 mg"
        let normalized = TextNormalizer.normalize(text)
        for query in ["doli", "1000 DOLI", "aspirine", " "] {
            #expect(TextNormalizer.matches(normalizedText: normalized, query: query)
                == TextNormalizer.matches(text, query: query))
        }
    }

    @Test func aNeedleLongerThanTheTextNeverMatches() {
        #expect(!TextNormalizer.matches("ab", query: "abc"))
    }

    @Test func compareOrdersByScalarValueThenLength() {
        #expect(TextNormalizer.compare("a", "b") == -1)
        #expect(TextNormalizer.compare("b", "a") == 1)
        #expect(TextNormalizer.compare("a", "a") == 0)
        #expect(TextNormalizer.compare("a", "ab") == -1)
        #expect(TextNormalizer.compare("ab", "a") == 1)
        // U+FF5E (BMP) sorts after U+1F48A (supplementary) in UTF-16 but before it by code point.
        #expect(TextNormalizer.compare("\u{FF5E}", "💊") == -1)
    }
}
