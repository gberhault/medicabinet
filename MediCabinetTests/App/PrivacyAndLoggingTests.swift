import Foundation
import Testing
@testable import MediCabinet

@Suite struct PrivacyAndLoggingTests {
    private func manifest() throws -> [String: Any] {
        let url = try #require(
            Bundle.main.url(forResource: "PrivacyInfo", withExtension: "xcprivacy"),
            "PrivacyInfo.xcprivacy is not in the app bundle"
        )
        let plist = try PropertyListSerialization.propertyList(from: Data(contentsOf: url), format: nil)
        return try #require(plist as? [String: Any])
    }

    @Test func manifestDeclaresNoTrackingAndNoCollectedData() throws {
        let manifest = try manifest()
        #expect(manifest["NSPrivacyTracking"] as? Bool == false)
        #expect((manifest["NSPrivacyTrackingDomains"] as? [Any])?.isEmpty == true)
        #expect((manifest["NSPrivacyCollectedDataTypes"] as? [Any])?.isEmpty == true)
    }

    @Test func manifestDeclaresItsRequiredReasonApiListEvenWhenEmpty() throws {
        // Add an entry here (with its reason code) the day the app uses UserDefaults, file
        // timestamps, disk space or system boot time.
        #expect(try manifest()["NSPrivacyAccessedAPITypes"] is [Any])
    }

    @Test func appHasNoNetworkPermissionOrAnalyticsKeysInItsInfoPlist() {
        let info = Bundle.main.infoDictionary ?? [:]
        #expect(info["NSAppTransportSecurity"] == nil, "no ATS exceptions: the app makes no network request")
        #expect(info["NSUserTrackingUsageDescription"] == nil)
    }

    @Test func loggersUseTheAppSubsystem() {
        #expect(Log.subsystem == Bundle.main.bundleIdentifier)
    }
}
