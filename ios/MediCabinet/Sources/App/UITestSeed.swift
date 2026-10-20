import Foundation
import MediCabinetCore

/// UI tests only: a seeded cabinet, so the Cabinet screens have something to show without scanning. Honoured only with
/// `-UITestingTemporaryStorage` (a throwaway database); a real launch never reads it.
///
/// `MEDICABINET_UI_TEST_CABINET` is a JSON array of boxes; `expiryInDays` is relative to today (nil = unknown expiry),
/// so statuses do not rot with the calendar.
enum UITestSeed {
    static let environmentKey = "MEDICABINET_UI_TEST_CABINET"
    /// `MEDICABINET_UI_TEST_NAMED_PRODUCTS`: a JSON array of products the household named (`category` set = chosen).
    static let namedProductsKey = "MEDICABINET_UI_TEST_NAMED_PRODUCTS"

    struct NamedItem: Decodable {
        var gtin: String
        var name: String
        var category: String?
    }

    struct Item: Decodable {
        var name: String
        var gtin: String?
        var category: String?
        var location: String?
        var expiryInDays: Int?
        /// With `expiryInDays`: keep only the month and year of that day (a GS1 `DD = 00` date).
        var monthPrecision: Bool?
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

    static func namedProducts(from environment: [String: String], arguments: [String]) -> [NamedItem] {
        guard arguments.contains(AppLaunch.temporaryStorageArgument),
              let text = environment[namedProductsKey], let data = text.data(using: .utf8),
              let items = try? JSONDecoder().decode([NamedItem].self, from: data)
        else { return [] }
        return items
    }

    static func apply(_ items: [Item], namedProducts: [NamedItem] = [], to environment: AppEnvironment) async {
        guard !items.isEmpty || !namedProducts.isEmpty else { return }
        do {
            var locations = try await environment.locations.all()
            for item in items {
                let location = try await location(named: item.location, among: &locations, environment: environment)
                let box = Box(
                    product: ProductRef(gtin: item.gtin, name: item.name),
                    category: item.category.map { CategoryID(rawValue: $0) } ?? .uncategorized,
                    categoryOverridden: false,
                    locationID: location.id,
                    expiry: expiry(inDays: item.expiryInDays, monthOnly: item.monthPrecision ?? false, environment: environment),
                    lot: item.lot,
                    serial: item.serial,
                    isOpened: item.opened ?? false,
                    createdAt: environment.clock.now
                )
                try await environment.boxes.insert(box)
            }
            for product in namedProducts {
                try await environment.named.remember(
                    gtin: product.gtin, name: product.name,
                    category: product.category.map { CategoryID(rawValue: $0) } ?? .uncategorized,
                    categoryOverridden: product.category != nil
                )
            }
            // The seeded boxes are the fixture: the upgrade fill must not turn them into named products behind a test.
            try await environment.named.completeBackfill([])
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

    private static func expiry(inDays days: Int?, monthOnly: Bool, environment: AppEnvironment) -> ExpiryDate? {
        guard let days else { return nil }
        let day = environment.today().adding(days: days)
        return try? ExpiryDate.make(year: day.year, month: day.month, day: monthOnly ? nil : day.day)
    }
}
