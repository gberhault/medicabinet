import Testing
import MediCabinetCore

@Suite struct GS1ParserTests {
    @Test func gtinNormalizesAnEan13ToFourteenDigits() {
        #expect(GTIN.normalized("3400930000014") == "03400930000014")
        #expect(GTIN.normalized("03400930000014") == "03400930000014")
    }

    @Test(arguments: ["", "3400930000015", "340093000001", "034009300000145", "34009300000X4", "٣٤٠٠٩٣٠٠٠٠٠١٤"])
    func gtinRejectsWrongLengthDigitsOrCheckDigit(_ digits: String) {
        #expect(GTIN.normalized(digits) == nil)
    }

    @Test func parseErrorsUseThePlatformNeutralNames() {
        #expect(ScanParseError.empty.kind == "empty")
        #expect(ScanParseError.tooLong.kind == "tooLong")
        #expect(ScanParseError.invalidCharacters.kind == "invalidCharacters")
        #expect(ScanParseError.missingGTIN.kind == "missingGTIN")
        #expect(ScanParseError.invalidGTIN.kind == "invalidGTIN")
    }

    @Test func aSymbologyLookalikeThatIsNotAPrefixIsNotStripped() {
        #expect(GS1Parser.shape(of: "]d") == "?(2)")
        #expect(GS1Parser.shape(of: "]!2") == "?(3)")
    }

    @Test func theMaximumLengthIsFiveHundredAndTwelve() {
        #expect(GS1Parser.maxPayloadLength == 512)
    }

    @Test func anExpiryIsAKnownDateOnlyWhenTheCodeHoldsOne() throws {
        let code = try GS1Parser.parse("010340093000001417270300")
        #expect(code.expiry == .date(try ExpiryDate.make(year: 2027, month: 3, day: nil)))
        #expect(code.symbology == .dataMatrix)
    }
}
