import Foundation
import Testing
import MediCabinetCore

@Suite struct NamedProductEditTests {
    private static let skin = CategoryID(rawValue: "skin-wound")

    private func product(category: CategoryID = .uncategorized, overridden: Bool = false) -> NamedProduct {
        NamedProduct(
            gtin: "03400930000014", name: "Test", category: category, categoryOverridden: overridden,
            createdAt: Date(timeIntervalSince1970: 0)
        )
    }

    @Test func aNewNameIsTrimmedAndIsTheOnlyChange() throws {
        var edit = NamedProductEdit(from: product())
        edit.name = "  Crème solaire \n"
        let change = try edit.validated()
        #expect(change.name == "Crème solaire")
        #expect(change.category == nil)
    }

    @Test func nothingChangedGivesNothingToSave() throws {
        let change = try NamedProductEdit(from: product()).validated()
        #expect(change.name == nil && change.category == nil)
        var spaced = NamedProductEdit(from: product())
        spaced.name = " Test "
        #expect(try spaced.validated().name == nil)
    }

    @Test(arguments: ["", "   ", "\n\t \u{A0}"])
    func anEmptyNameIsRefused(_ name: String) {
        var edit = NamedProductEdit(from: product())
        edit.name = name
        #expect(throws: NamedProductEditError.emptyName) { try edit.validated() }
    }

    @Test func twoHundredCodePointsAreAcceptedAndTwoHundredAndOneAreRefused() throws {
        var edit = NamedProductEdit(from: product())
        edit.name = String(repeating: "x", count: 200)
        #expect(try edit.validated().name?.unicodeScalars.count == 200)
        edit.name = String(repeating: "x", count: 201)
        #expect(throws: NamedProductEditError.nameTooLong) { try edit.validated() }
    }

    @Test func theLimitCountsCodePointsNotCharacters() throws {
        var edit = NamedProductEdit(from: product())
        // "e" + a combining accent is one character but two code points.
        edit.name = String(repeating: "e\u{301}", count: 100)
        #expect(edit.name.count == 100)
        #expect(try edit.validated().name != nil)
        edit.name = String(repeating: "e\u{301}", count: 101)
        #expect(throws: NamedProductEditError.nameTooLong) { try edit.validated() }
    }

    @Test func aChosenCategoryIsChangedWithTheFlagOn() throws {
        var edit = NamedProductEdit(from: product())
        try edit.chooseCategory(Self.skin)
        let change = try edit.validated()
        #expect(change.name == nil)
        #expect(change.category?.0 == Self.skin && change.category?.1 == true)
    }

    @Test(arguments: [CategoryID.uncategorized, CategoryID(rawValue: "no-such-category")])
    func aCategoryTheUserMayNotPickIsRefusedAndChangesNothing(_ category: CategoryID) throws {
        var edit = NamedProductEdit(from: product())
        #expect(throws: NamedProductEditError.categoryNotSelectable) { try edit.chooseCategory(category) }
        #expect(try edit.validated().category == nil)
    }

    @Test func resettingGoesBackToUncategorizedWithTheFlagOff() throws {
        var edit = NamedProductEdit(from: product(category: Self.skin, overridden: true))
        edit.resetCategory()
        let change = try edit.validated()
        #expect(change.category?.0 == .uncategorized && change.category?.1 == false)
    }

    @Test func aCategoryThatIsAlreadyTheProductsIsNotAChange() throws {
        var chosen = NamedProductEdit(from: product(category: Self.skin, overridden: true))
        try chosen.chooseCategory(Self.skin)
        #expect(try chosen.validated().category == nil)
        var reset = NamedProductEdit(from: product())
        reset.resetCategory()
        #expect(try reset.validated().category == nil)
    }

    @Test func theSameCategoryWithTheFlagOffIsAChangeBecauseTheFlagTurnsOn() throws {
        var edit = NamedProductEdit(from: product(category: Self.skin, overridden: false))
        try edit.chooseCategory(Self.skin)
        #expect(try edit.validated().category?.1 == true)
    }

    @Test func aNameAndACategoryChangeTogether() throws {
        var edit = NamedProductEdit(from: product())
        edit.name = "Crème"
        try edit.chooseCategory(Self.skin)
        let change = try edit.validated()
        #expect(change.name == "Crème" && change.category?.0 == Self.skin)
    }

    @Test func aNameErrorWinsEvenWhenTheCategoryIsValid() throws {
        var edit = NamedProductEdit(from: product())
        try edit.chooseCategory(Self.skin)
        edit.name = " "
        #expect(throws: NamedProductEditError.emptyName) { try edit.validated() }
    }

    @Test func errorsUseThePlatformNeutralNames() {
        #expect(NamedProductEditError.emptyName.kind == "emptyName")
        #expect(NamedProductEditError.nameTooLong.kind == "nameTooLong")
        #expect(NamedProductEditError.categoryNotSelectable.kind == "categoryNotSelectable")
    }
}
