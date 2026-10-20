import Testing
@testable import MediCabinetCore

@Suite struct ArchiveErrorTests {
    @Test func kindsUseThePlatformNeutralNames() {
        let expected = [
            "tooLarge", "notJSON", "notArchive", "unsupportedVersion", "newerVersion", "invalidStructure",
            "invalidValue", "tooManyItems", "duplicateId", "unknownLocation", "defaultLocation", "duplicateSerial",
            "duplicateNamedProduct"
        ]
        #expect(ArchiveErrorKind.allCases.map(\.rawValue) == expected)
    }

    @Test func theDescriptionCarriesTheKindAndThePathOnly() {
        #expect(ArchiveError(.notJSON).description == "notJSON")
        #expect(ArchiveError(.invalidValue, path: "boxes[3].lot").description == "invalidValue at boxes[3].lot")
    }

    @Test func aFieldPathJoinsItsPrefixAndKey() {
        #expect(FieldPath("boxes[0]", "lot").text == "boxes[0].lot")
        #expect(FieldPath("", "exportedAt").text == "exportedAt")
    }
}
