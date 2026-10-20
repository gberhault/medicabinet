import Foundation
import MediCabinetCore
import Testing
@testable import MediCabinet

/// The name typed for a code the reference does not know is remembered and used by the next scan (#57).
@MainActor
@Suite struct ScanViewModelMemoryTests {
    private let unknownProduct = StubLookup()

    @Test func theNameTypedInTheCompletionIsRememberedAndTheNextScanDoesNotAskAgain() async throws {
        let kit = try await ScanTestKit.make(lookup: unknownProduct)
        await kit.see(ScanTestKit.payload())
        await kit.model.confirmCompletion(name: "Mon sirop", expiry: nil)
        #expect(try await kit.named.namedProduct(gtin: ScanTestKit.gtin)?.name == "Mon sirop")

        await kit.see(ScanTestKit.payload(serial: "SER2"), at: 10)
        #expect(kit.model.completion == nil)
        #expect(try await kit.storedBoxes().map(\.product.name) == ["Mon sirop", "Mon sirop"])
    }

    @Test func aProductTheReferenceKnowsIsNeverRemembered() async throws {
        let kit = try await ScanTestKit.make()
        await kit.see(ScanTestKit.payload())
        #expect(try await kit.storedBoxes().count == 1)
        #expect(try await kit.named.namedProduct(gtin: ScanTestKit.gtin) == nil)
    }

    @Test func aBoxTypedByHandWithACodeIsRemembered() async throws {
        let kit = try await ScanTestKit.make(lookup: unknownProduct)
        let error = await kit.model.addManually(
            code: "3400930000014", name: "Thermomètre", expiry: nil, locationID: kit.defaultLocation.id
        )
        #expect(error == nil)
        #expect(try await kit.named.namedProduct(gtin: ScanTestKit.gtin)?.name == "Thermomètre")
    }
}
