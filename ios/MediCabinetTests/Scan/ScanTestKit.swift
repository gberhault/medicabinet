import Foundation
import MediCabinetCore
import MediCabinetCoreTestSupport
import SwiftUI
@testable import MediCabinet

/// Records what the scanner would have played.
@MainActor
final class RecordingFeedback: ScanFeedback {
    private(set) var events: [FeedbackEvent] = []
    private(set) var soundFlags: [Bool] = []

    func play(_ event: FeedbackEvent, sound: Bool) {
        events.append(event)
        soundFlags.append(sound)
    }
}

/// A scanner the test pushes snapshots into.
@MainActor
final class PushScanner: CodeScanner {
    let snapshots: AsyncStream<VisibleCodes>
    private let continuation: AsyncStream<VisibleCodes>.Continuation
    private(set) var started = false
    private(set) var stopped = false
    var startError: CodeScannerError?

    init() {
        (snapshots, continuation) = AsyncStream.makeStream(of: VisibleCodes.self)
    }

    var preview: AnyView { AnyView(Color.black) }

    func start() throws(CodeScannerError) {
        if let startError { throw startError }
        started = true
    }

    func stop() {
        stopped = true
        continuation.finish()
    }

    func push(_ payloads: [String], at seconds: Double) {
        continuation.yield(VisibleCodes(payloads: payloads, instant: ScanTestKit.start.addingTimeInterval(seconds)))
    }
}

struct StubLookup: ReferenceDataSource {
    var products: [String: ReferenceProduct] = [:]
    func product(gtin: String) async throws -> ReferenceProduct? { products[gtin] }

    func status() async -> ReferenceStatus {
        ReferenceDataDate(iso: "2026-01-01").map { .available(dataDate: $0) } ?? .unavailable(.unreadable)
    }
}

/// A model on a real (in-memory) GRDB cabinet with a default location, and a scanner and camera permission
/// the test controls.
@MainActor
struct ScanTestKit {
    static let start = Date(timeIntervalSince1970: 1_800_000_000)
    static let gtin = "03400930000014"
    static let groupSeparator = "\u{1D}"

    let clock: MutableClock
    let locations: GRDBLocationRepository
    let boxes: GRDBBoxRepository
    let named: GRDBNamedProductRepository
    let feedback = RecordingFeedback()
    let preferences = InMemoryScanPreferences()
    let permission: FixedCameraPermission
    let scanner = PushScanner()
    let model: ScanViewModel
    let defaultLocation: Location

    static func make(
        access: CameraAccess = .authorized,
        lookup: StubLookup = StubLookup(products: [ScanTestKit.gtin: ReferenceProduct(name: "DOLIPRANE")]),
        loadsLocations: Bool = true
    ) async throws -> ScanTestKit {
        let clock = MutableClock(start)
        let database = try AppDatabase.inMemory()
        let locations = GRDBLocationRepository(database: database, clock: clock)
        let boxes = GRDBBoxRepository(database: database, clock: clock)
        let named = GRDBNamedProductRepository(database: database, clock: clock)
        let location = try await locations.ensureDefault(named: "Cabinet")
        let kit = ScanTestKit(
            clock: clock, locations: locations, boxes: boxes, named: named, access: access, lookup: lookup, location: location
        )
        if loadsLocations { await kit.model.loadLocations() }
        return kit
    }

    private init(
        clock: MutableClock, locations: GRDBLocationRepository, boxes: GRDBBoxRepository, named: GRDBNamedProductRepository,
        access: CameraAccess, lookup: StubLookup, location: Location
    ) {
        self.clock = clock
        self.locations = locations
        self.boxes = boxes
        self.named = named
        defaultLocation = location
        permission = FixedCameraPermission(access: access)
        let services = ScanServices(
            permission: permission, makeScanner: { [scanner] in scanner }, preferences: preferences,
            feedback: feedback, lookup: lookup
        )
        let environment = AppEnvironment(
            locations: locations, boxes: boxes, named: named, clock: clock,
            reference: InMemoryReferenceData(status: .unavailable(.missing))
        )
        model = ScanViewModel(environment: environment, services: services)
    }

    /// A GS1 DataMatrix payload.
    static func payload(
        gtin: String = ScanTestKit.gtin, expiry: String? = "270331", lot: String? = "LOT1", serial: String? = "SER1"
    ) -> String {
        var text = "01" + gtin
        if let expiry { text += "17" + expiry }
        if let lot { text += "10" + lot + (serial == nil ? "" : groupSeparator) }
        if let serial { text += "21" + serial }
        return text
    }

    /// One frame with these codes in view, `seconds` after the start.
    func see(_ payloads: String..., at seconds: Double = 0) async {
        await model.handle(VisibleCodes(payloads: payloads, instant: Self.start.addingTimeInterval(seconds)))
    }

    func storedBoxes() async throws -> [Box] {
        try await boxes.activeBoxes()
    }
}

extension ExpiryDate {
    static func date(_ year: Int, _ month: Int, _ day: Int?) -> ExpiryDate? {
        try? ExpiryDate.make(year: year, month: month, day: day)
    }
}
