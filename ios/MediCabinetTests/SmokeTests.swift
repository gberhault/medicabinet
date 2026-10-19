import Foundation
import Testing
@testable import MediCabinet

@Test func appModuleLoads() {
    #expect(Bundle.main.bundleIdentifier != nil)
}
