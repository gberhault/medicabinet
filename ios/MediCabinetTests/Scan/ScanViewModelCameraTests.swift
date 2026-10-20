import Foundation
import MediCabinetCore
import Testing
@testable import MediCabinet

/// Camera lifecycle, permission states and the session location (requirements C1–C5, C7, C8).
@MainActor
@Suite struct ScanViewModelCameraTests {
    private func waitUntil(_ condition: () -> Bool) async {
        for _ in 0..<500 where !condition() { try? await Task.sleep(for: .milliseconds(5)) }
    }

    @Test func withoutADecisionTheCameraExplainsWhyAndDoesNotStart() async throws {
        let kit = try await ScanTestKit.make(access: .notDetermined)
        await kit.model.run()
        #expect(kit.model.cameraState == .needsPermission)
        #expect(!kit.scanner.started)
    }

    @Test func allowingTheCameraRestartsTheScanner() async throws {
        let kit = try await ScanTestKit.make(access: .notDetermined)
        await kit.model.run()
        let before = kit.model.attempt
        await kit.model.requestPermission()
        #expect(kit.permission.access == .authorized)
        #expect(kit.model.attempt == before + 1)
    }

    @Test func aDeniedCameraOffersSettings() async throws {
        let kit = try await ScanTestKit.make(access: .denied)
        await kit.model.run()
        #expect(kit.model.cameraState == .denied(canOpenSettings: true))
        #expect(!kit.scanner.started)
    }

    @Test func aRestrictedCameraCannotBeChangedInSettings() async throws {
        let kit = try await ScanTestKit.make(access: .restricted)
        await kit.model.run()
        #expect(kit.model.cameraState == .denied(canOpenSettings: false))
    }

    @Test func aDeviceThatCannotScanIsReportedAsUnsupported() async throws {
        let kit = try await ScanTestKit.make()
        kit.scanner.startError = .unsupported
        await kit.model.run()
        #expect(kit.model.cameraState == .unsupported)
    }

    @Test func aCameraThatCannotStartCanBeRetried() async throws {
        let kit = try await ScanTestKit.make()
        kit.scanner.startError = .failedToStart
        await kit.model.run()
        #expect(kit.model.cameraState == .failed)
        let before = kit.model.attempt
        kit.model.retry()
        #expect(kit.model.cameraState == .starting)
        #expect(kit.model.attempt == before + 1)
    }

    @Test func aScannerThatBecomesUnavailableFallsBackToThePermissionState() async throws {
        let kit = try await ScanTestKit.make()
        kit.scanner.startError = .unavailable
        await kit.model.run()
        #expect(kit.model.cameraState == .denied(canOpenSettings: true) || kit.model.cameraState == .starting)
    }

    @Test func theCameraRunsOnlyWhileTheTaskDoesAndStopsOnCancel() async throws {
        let kit = try await ScanTestKit.make()
        let task = Task { await kit.model.run() }
        await waitUntil { kit.model.cameraState == .scanning }
        #expect(kit.scanner.started)
        #expect(kit.model.scanner != nil)
        task.cancel()
        await task.value
        #expect(kit.scanner.stopped)
        #expect(kit.model.scanner == nil)
    }

    @Test func aCodePushedThroughTheRunningScannerIsAdded() async throws {
        let kit = try await ScanTestKit.make()
        let task = Task { await kit.model.run() }
        await waitUntil { kit.model.cameraState == .scanning }
        kit.scanner.push([ScanTestKit.payload()], at: 0)
        await waitUntil { kit.model.session.count == 1 }
        task.cancel()
        await task.value
        #expect(try await kit.storedBoxes().count == 1)
    }

    // MARK: location

    @Test func theDefaultLocationIsPreselected() async throws {
        let kit = try await ScanTestKit.make()
        await kit.model.loadLocations()
        #expect(kit.model.sessionLocation == kit.defaultLocation)
    }

    @Test func theLastLocationIsPreselectedWhileItStillExists() async throws {
        let kit = try await ScanTestKit.make(loadsLocations: false)
        let kitchen = Location(name: "Kitchen", createdAt: ScanTestKit.start)
        try await kit.locations.insert(kitchen)
        kit.preferences.lastLocationID = kitchen.id
        await kit.model.loadLocations()
        #expect(kit.model.sessionLocation?.id == kitchen.id)

        let other = try await ScanTestKit.make(loadsLocations: false)
        other.preferences.lastLocationID = UUID()
        await other.model.loadLocations()
        #expect(other.model.sessionLocation == other.defaultLocation)
    }

    @Test func changingTheLocationAppliesToBoxesAddedAfterwardsAndIsRemembered() async throws {
        let kit = try await ScanTestKit.make()
        let kitchen = Location(name: "Kitchen", createdAt: ScanTestKit.start)
        try await kit.locations.insert(kitchen)
        await kit.model.loadLocations()
        await kit.see(ScanTestKit.payload(serial: "A"), at: 0)
        kit.model.selectLocation(kitchen.id)
        await kit.see(ScanTestKit.payload(serial: "B"), at: 0.1)

        let stored = try await kit.storedBoxes()
        #expect(stored.first { $0.serial == "A" }?.locationID == kit.defaultLocation.id)
        #expect(stored.first { $0.serial == "B" }?.locationID == kitchen.id)
        #expect(kit.preferences.lastLocationID == kitchen.id)
        #expect(kit.model.locationName(kitchen.id) == "Kitchen")
        kit.model.selectLocation(UUID())
        #expect(kit.model.sessionLocation?.id == kitchen.id)
    }

    @Test func soundIsOffByDefaultAndRemembered() async throws {
        let kit = try await ScanTestKit.make()
        #expect(!kit.model.soundEnabled)
        kit.model.soundEnabled = true
        #expect(kit.preferences.soundEnabled)
        await kit.see(ScanTestKit.payload(), at: 0)
        #expect(kit.feedback.soundFlags == [true])
    }
}
