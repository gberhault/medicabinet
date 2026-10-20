import Foundation
import MediCabinetCore

/// UI tests only: a seeded cabinet, so the Cabinet screens have something to show without scanning. Honoured only with
/// `-UITestingTemporaryStorage` (a throwaway database); a real launch never reads it.
///
/// `MEDICABINET_UI_TEST_CABINET` is a JSON array of boxes; `expiryInDays` is relative to today (nil = unknown expiry),
/// so statuses do not rot with the calendar.
enum UITestSeed {
    static let environmentKey = "MEDICABINET_UI_TEST_CABINET"

    struct Item: Decodable {
        var name: String
        var gtin: String?
        var category: String?
        var location: String?
        var expiryInDays: Int?
        var opened: Bool?
        var lot: String?
        var serial: String?
    }

    static func items(from environment: [String: String], arguments: [String]) -> [Item] {
        guard arguments.contains(AppLaunch.temporaryStorageArgument),
              let text = environment[environmentKey], let data = text.data(using: .utf8),
              let items = try? JSONDecoder().decode([Item].self, from: data)
        else { return [] }
        return items
    }

    static func apply(_ items: [Item], to environment: AppEnvironment) async {
        guard !items.isEmpty else { return }
        do {
            var locations = try await environment.locations.all()
            for item in items {
                let location = try await location(named: item.location, among: &locations, environment: environment)
                let box = Box(
                    product: ProductRef(gtin: item.gtin, name: item.name),
                    category: item.category.map { CategoryID(rawValue: $0) } ?? .uncategorized,
                    categoryOverridden: false,
                    locationID: location.id,
                    expiry: expiry(inDays: item.expiryInDays, environment: environment),
                    lot: item.lot,
                    serial: item.serial,
                    isOpened: item.opened ?? false,
                    createdAt: environment.clock.now
                )
                try await environment.boxes.insert(box)
            }
        } catch {
            Log.persistence.error("The UI test cabinet could not be seeded: \(String(describing: type(of: error)))")
        }
    }

    private static func location(
        named name: String?, among locations: inout [Location], environment: AppEnvironment
    ) async throws -> Location {
        guard let name else {
            if let found = locations.first(where: \.isDefault) ?? locations.first { return found }
            throw RepositoryError.unknownLocation
        }
        if let found = locations.first(where: { $0.name == name }) { return found }
        let created = try await environment.locations.create(named: name)
        locations.append(created)
        return created
    }

    private static func expiry(inDays days: Int?, environment: AppEnvironment) -> ExpiryDate? {
        guard let days else { return nil }
        let day = environment.today().adding(days: days)
        return try? ExpiryDate.make(year: day.year, month: day.month, day: day.day)
    }
}
