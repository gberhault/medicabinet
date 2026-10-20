import Foundation
import MediCabinetCore
import MediCabinetCoreTestSupport
@testable import MediCabinet

/// The real GRDB repositories on an in-memory database, a clock the test moves, and an environment pinned to UTC.
@MainActor
struct CabinetTestKit {
    /// 2027-01-15 08:00 UTC.
    static let start = Date(timeIntervalSince1970: 1_800_000_000)
    static let gtin = "03400930000014"

    let database: AppDatabase
    let clock: MutableClock
    let locations: GRDBLocationRepository
    let boxes: GRDBBoxRepository
    let environment: AppEnvironment
    let home: Location

    init(reference: any ReferenceDataSource = InMemoryReferenceData(status: .unavailable(.missing))) async throws {
        database = try AppDatabase.inMemory()
        clock = MutableClock(Self.start)
        locations = GRDBLocationRepository(database: database, clock: clock)
        boxes = GRDBBoxRepository(database: database, clock: clock)
        home = try await locations.ensureDefault(named: "Home")
        environment = AppEnvironment(
            locations: locations, boxes: boxes, clock: clock, reference: reference, timeZone: { .gmt })
    }

    /// An expiry `days` from the clock's today (day precision).
    func expiry(inDays days: Int) -> ExpiryDate? {
        let day = environment.today().adding(days: days)
        return try? ExpiryDate.make(year: day.year, month: day.month, day: day.day)
    }

    @discardableResult
    func add(
        _ name: String, gtin: String? = CabinetTestKit.gtin, category: CategoryID = .uncategorized,
        in location: Location? = nil, expiryInDays days: Int? = nil, serial: String? = nil, lot: String? = nil
    ) async throws -> Box {
        let box = Box(
            product: ProductRef(gtin: gtin, name: name), category: category,
            locationID: (location ?? home).id, expiry: days.flatMap { expiry(inDays: $0) }, lot: lot, serial: serial,
            createdAt: clock.now)
        try await boxes.insert(box)
        return box
    }
}

/// Waits (up to five seconds) for a condition that a background observation will make true.
@MainActor
func eventually(_ condition: () -> Bool) async -> Bool {
    for _ in 0..<500 {
        if condition() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return false
}

/// As `eventually`, for a condition that has to `await` (an actor's state).
@MainActor
func eventuallyAsync(_ condition: () async -> Bool) async -> Bool {
    for _ in 0..<500 {
        if await condition() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return false
}
