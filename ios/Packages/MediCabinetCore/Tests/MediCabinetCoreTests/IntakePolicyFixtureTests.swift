import Foundation
import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

/// Runs every case of the repository-wide `fixtures/scan-intake.json`, which the Android core also runs.
@Suite struct IntakePolicyFixtureTests {
    private static func code(_ scan: IntakeCase.Scan) throws -> ScannedCode {
        let expiry: ExpiryReading = switch scan.expiry {
        case "known": .date(try ExpiryDate.make(year: 2027, month: 3, day: nil))
        case "missing": .missing
        default: .invalid(kind: "invalidMonth")
        }
        return ScannedCode(
            gtin: "03400930000014", expiry: expiry, lot: nil, serial: scan.hasSerial ? "SER1" : nil,
            symbology: .dataMatrix
        )
    }

    private static func box(_ match: String?) -> Box? {
        guard let match else { return nil }
        let removal = match == "removed"
            ? Box.Removal(reason: .usedUp, date: Date(timeIntervalSince1970: 10)) : nil
        return Box(
            product: ProductRef(gtin: "03400930000014", name: "X"), locationID: UUID(),
            serial: "SER1", createdAt: Date(timeIntervalSince1970: 0), removal: removal
        )
    }

    @Test func theSharedFixtureLoadsAndHasCases() {
        #expect(SharedFixtures.intakeCases().count >= 20)
    }

    @Test(arguments: SharedFixtures.intakeCases())
    func caseMatchesTheSharedExpectation(_ fixtureCase: IntakeCase) throws {
        let decision = IntakePolicy.decide(
            code: try Self.code(fixtureCase.scan), productKnown: fixtureCase.productKnown,
            cabinetMatch: Self.box(fixtureCase.cabinetMatch), sessionHasGTIN: fixtureCase.sessionHasGTIN
        )
        let expect = fixtureCase.expect
        #expect(decision.kind == expect.decision)
        switch decision {
        case let .complete(missing, unreadable):
            #expect(Set(missing.map(\.rawValue)) == Set(expect.missing ?? []))
            #expect(unreadable == expect.expiryUnreadable)
        case let .confirmAnother(thenMissing, unreadable):
            #expect(Set(thenMissing.map(\.rawValue)) == Set(expect.thenMissing ?? []))
            #expect(unreadable == expect.expiryUnreadable)
        case .add, .alreadyInCabinet, .removedEarlier:
            #expect(expect.missing == nil && expect.thenMissing == nil)
        }
    }

    @Test func fieldNamesUseThePlatformNeutralNames() {
        #expect(IntakeField.name.rawValue == "name")
        #expect(IntakeField.expiry.rawValue == "expiry")
    }
}
