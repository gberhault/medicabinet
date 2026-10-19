import Foundation
import MediCabinetCore
import Testing
@testable import MediCabinet

@MainActor
@Suite struct InjectedCodeScannerTests {
    private let clock = FixedClock(Date(timeIntervalSince1970: 1_800_000_000))

    @Test func theEnvironmentVariableListsPayloadsAndWritesGroupSeparatorsAsGS() throws {
        let scanner = try #require(InjectedCodeScanner.fromEnvironment(
            [InjectedCodeScanner.environmentKey: "010340093000001417270300<GS>21S1|3400930000014"], clock: clock
        ))
        #expect(scanner.scriptForTesting == ["010340093000001417270300\u{1D}21S1", "3400930000014"])
    }

    @Test func withoutTheVariableThereIsNoInjectedScanner() {
        #expect(InjectedCodeScanner.fromEnvironment([:], clock: clock) == nil)
    }

    @Test func itReplaysEachPayloadThenAnEmptyFrame() async throws {
        let scanner = InjectedCodeScanner(script: ["A", "B"], interval: .milliseconds(30), clock: clock)
        try scanner.start()
        var seen: [[String]] = []
        for await snapshot in scanner.snapshots {
            seen.append(snapshot.payloads)
            if seen.count == 4 { break }
        }
        scanner.stop()
        #expect(seen == [["A"], [], ["B"], []])
    }

    @Test func startingTwiceDoesNotReplayTwice() async throws {
        let scanner = InjectedCodeScanner(script: ["A"], interval: .milliseconds(30), clock: clock)
        try scanner.start()
        try scanner.start()
        var firsts = 0
        for await snapshot in scanner.snapshots {
            if snapshot.payloads == ["A"] { firsts += 1 }
            if snapshot.payloads.isEmpty { break }
        }
        #expect(firsts == 1)
    }

    @Test func aFixedPermissionAnswersWithoutAPrompt() async {
        let granted = FixedCameraPermission(access: .notDetermined, grantsOnRequest: true)
        #expect(await granted.request())
        #expect(granted.access == .authorized)
        let refused = FixedCameraPermission(access: .notDetermined, grantsOnRequest: false)
        #expect(await refused.request() == false)
        #expect(refused.access == .denied)
        let already = FixedCameraPermission(access: .restricted)
        #expect(await already.request() == false)
        #expect(already.access == .restricted)
    }

    @Test func theCameraUsageDescriptionIsDeclared() throws {
        let text = try #require(Bundle.main.object(forInfoDictionaryKey: "NSCameraUsageDescription") as? String)
        #expect(!text.isEmpty)
    }
}
