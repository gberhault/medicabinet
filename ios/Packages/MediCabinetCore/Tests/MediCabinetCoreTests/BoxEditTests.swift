import Foundation
import Testing
import MediCabinetCore

@Suite struct BoxEditTests {
    private let kitchen = UUID()
    private let bathroom = UUID()

    private func box(expiry: ExpiryDate? = nil) throws -> Box {
        Box(
            product: ProductRef(gtin: "03400930000014", name: "Doliprane"), category: CategoryID(rawValue: "pain-fever"),
            categoryOverridden: true, locationID: kitchen, expiry: expiry, lot: "LOT1", serial: "SER1", isOpened: true,
            createdAt: Date(timeIntervalSince1970: 100), updatedAt: Date(timeIntervalSince1970: 200),
            removal: nil)
    }

    @Test func theFormStartsFromTheBoxAndAnUnknownExpiryStaysUnknown() throws {
        let unknown = try box()
        #expect(BoxEdit(from: unknown).expiry == nil)
        let known = try box(expiry: ExpiryDate.make(year: 2027, month: 3, day: nil))
        let edit = BoxEdit(from: known)
        #expect(edit.name == "Doliprane")
        #expect(edit.expiry == known.expiry)
        #expect(edit.locationID == kitchen)
    }

    @Test func applyingChangesTheNameExpiryAndLocationOnly() throws {
        let original = try box()
        let expiry = try ExpiryDate.make(year: 2028, month: 2, day: 29)
        let edited = try BoxEdit(name: "  Doliprane 500 mg ", expiry: expiry, locationID: bathroom).applied(to: original)
        #expect(edited.product.name == "Doliprane 500 mg")
        #expect(edited.expiry == expiry)
        #expect(edited.locationID == bathroom)
        // Everything else is untouched, updatedAt included (the repository stamps it).
        #expect(edited.id == original.id)
        #expect(edited.product.gtin == original.product.gtin)
        #expect(edited.category == original.category)
        #expect(edited.categoryOverridden)
        #expect(edited.lot == "LOT1")
        #expect(edited.serial == "SER1")
        #expect(edited.isOpened)
        #expect(edited.createdAt == original.createdAt)
        #expect(edited.updatedAt == original.updatedAt)
    }

    @Test func anExpiryCanBeMadeUnknownOnlyByAnExplicitNil() throws {
        let original = try box(expiry: ExpiryDate.make(year: 2027, month: 3, day: nil))
        let edited = try BoxEdit(name: "Doliprane", expiry: nil, locationID: kitchen).applied(to: original)
        #expect(edited.expiry == nil)
    }

    @Test func anEmptyOrBlankNameIsRejected() throws {
        let original = try box()
        for name in ["", "   ", "\u{A0}\t"] {
            #expect(throws: BoxEditError.emptyName) {
                try BoxEdit(name: name, expiry: nil, locationID: kitchen).applied(to: original)
            }
        }
    }

    @Test func theNameLimitIsCountedInCodePointsAfterTrimming() throws {
        let original = try box()
        let longest = String(repeating: "a", count: ScanIntake.maxNameLength)
        #expect(try BoxEdit(name: " \(longest) ", expiry: nil, locationID: kitchen).applied(to: original)
            .product.name == longest)
        #expect(throws: BoxEditError.nameTooLong) {
            try BoxEdit(name: longest + "a", expiry: nil, locationID: kitchen).applied(to: original)
        }
        // 200 emoji are 200 code points (400 UTF-16 units) and fit.
        let emoji = String(repeating: "💊", count: ScanIntake.maxNameLength)
        #expect(try BoxEdit(name: emoji, expiry: nil, locationID: kitchen).applied(to: original).product.name == emoji)
    }

    @Test func errorKindsUseThePlatformNeutralNames() {
        #expect(BoxEditError.emptyName.kind == "emptyName")
        #expect(BoxEditError.nameTooLong.kind == "nameTooLong")
    }
}
