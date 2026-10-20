import Foundation
import MediCabinetCoreTestSupport
@testable import MediCabinetCore

/// SplitMix64: a small deterministic generator, so a failing random case can be replayed from its seed.
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64

    init(seed: UInt64) {
        state = seed &+ 0x9E37_79B9_7F4A_7C15
    }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var value = state
        value = (value ^ (value >> 30)) &* 0xBF58_476D_1CE4_E5B9
        value = (value ^ (value >> 27)) &* 0x94D0_49BB_1331_11EB
        return value ^ (value >> 31)
    }
}

extension Location {
    /// `createdAt` is immutable on the model; tests that need another one rebuild the value.
    func with(createdAt: Date) -> Location {
        Location(id: id, name: name, isDefault: isDefault, sortOrder: sortOrder, createdAt: createdAt, updatedAt: updatedAt)
    }
}

extension NamedProduct {
    func with(createdAt: Date) -> NamedProduct {
        NamedProduct(
            id: id, gtin: gtin, name: name, category: category, categoryOverridden: categoryOverridden, createdAt: createdAt,
            updatedAt: updatedAt
        )
    }

    func with(gtin: String) -> NamedProduct {
        NamedProduct(
            id: id, gtin: gtin, name: name, category: category, categoryOverridden: categoryOverridden, createdAt: createdAt,
            updatedAt: updatedAt
        )
    }
}

extension Box {
    func with(createdAt: Date) -> Box {
        Box(
            id: id, product: product, category: category, categoryOverridden: categoryOverridden, locationID: locationID,
            expiry: expiry, lot: lot, serial: serial, isOpened: isOpened, createdAt: createdAt, updatedAt: updatedAt,
            removal: removal
        )
    }
}

/// Valid random cabinets covering every field variant, with awkward text (quotes, backslashes, control
/// characters, combining marks, emoji, noncharacters).
enum RandomCabinet {
    private static let scalarPool: [UInt32] = Array(0x61...0x7A) + Array(0x30...0x39) + [
        0x20, 0x22, 0x5C, 0x2F, 0x00, 0x01, 0x1F, 0x7F, 0xE9, 0x301, 0x20AC, 0x1F9F3, 0x65E5, 0xE000, 0xFFFF, 0x10FFFF
    ]

    static func text(_ range: ClosedRange<Int>, using random: inout SeededGenerator) -> String {
        let count = Int.random(in: range, using: &random)
        var scalars = String.UnicodeScalarView()
        for _ in 0..<count {
            if let scalar = Unicode.Scalar(scalarPool.randomElement(using: &random) ?? 0x61) { scalars.append(scalar) }
        }
        return String(scalars)
    }

    private static func date(using random: inout SeededGenerator) -> Date {
        ArchiveDates.date(milliseconds: Int64.random(in: 946_684_800_000...4_102_444_799_999, using: &random))
    }

    private static func id(using random: inout SeededGenerator) -> UUID {
        UUID(uuid: (
            UInt8.random(in: 0...255, using: &random), UInt8.random(in: 0...255, using: &random),
            UInt8.random(in: 0...255, using: &random), UInt8.random(in: 0...255, using: &random),
            UInt8.random(in: 0...255, using: &random), UInt8.random(in: 0...255, using: &random),
            UInt8.random(in: 0...255, using: &random), UInt8.random(in: 0...255, using: &random),
            UInt8.random(in: 0...255, using: &random), UInt8.random(in: 0...255, using: &random),
            UInt8.random(in: 0...255, using: &random), UInt8.random(in: 0...255, using: &random),
            UInt8.random(in: 0...255, using: &random), UInt8.random(in: 0...255, using: &random),
            UInt8.random(in: 0...255, using: &random), UInt8.random(in: 0...255, using: &random)
        ))
    }

    static func make(using random: inout SeededGenerator) -> Archive {
        let locations = (0..<Int.random(in: 1...4, using: &random)).map { index in
            let location = Location(
                id: id(using: &random), name: text(1...100, using: &random), isDefault: index == 0,
                sortOrder: Int.random(in: Int(Int32.min)...Int(Int32.max), using: &random),
                createdAt: date(using: &random), updatedAt: date(using: &random)
            )
            // The default location (the first) is never deleted.
            let isDeleted = index > 0 && Bool.random(using: &random)
            return ArchivedLocation(location: location, deletedAt: isDeleted ? date(using: &random) : nil)
        }
        let boxes = (0..<Int.random(in: 0...60, using: &random)).map { index in
            box(index, in: locations, using: &random)
        }
        let app = ArchiveApp(
            name: text(1...50, using: &random), platform: ArchiveApp.platforms.randomElement(using: &random) ?? "ios",
            version: text(1...20, using: &random), build: text(1...20, using: &random)
        )
        let cabinet = CabinetContents(locations: locations, boxes: boxes, namedProducts: namedProducts(using: &random))
        return Archive(exportedAt: date(using: &random), app: app, cabinet: cabinet)
    }

    /// Named products with a few repeated GTINs: at most one live product per GTIN, any number of forgotten ones.
    private static func namedProducts(using random: inout SeededGenerator) -> [ArchivedNamedProduct] {
        var liveGTINs = Set<String>()
        return (0..<Int.random(in: 0...12, using: &random)).map { _ in
            let gtin = String(format: "%014d", Int.random(in: 0..<6, using: &random))
            let isForgotten = !liveGTINs.insert(gtin).inserted || Bool.random(using: &random)
            let product = NamedProduct(
                id: id(using: &random), gtin: gtin, name: text(1...200, using: &random),
                category: CategoryID(rawValue: text(1...100, using: &random)), categoryOverridden: Bool.random(using: &random),
                createdAt: date(using: &random), updatedAt: date(using: &random)
            )
            return ArchivedNamedProduct(product: product, deletedAt: isForgotten ? date(using: &random) : nil)
        }
    }

    private static func box(_ index: Int, in locations: [ArchivedLocation], using random: inout SeededGenerator) -> ArchivedBox {
        let gtin = Bool.random(using: &random) ? (0..<14).map { _ in String(Int.random(in: 0...9, using: &random)) }.joined() : nil
        let expiry = Bool.random(using: &random)
            ? try? ExpiryDate.make(
                year: Int.random(in: 2000...2099, using: &random), month: Int.random(in: 1...12, using: &random),
                day: Bool.random(using: &random) ? Int.random(in: 1...28, using: &random) : nil
            )
            : nil
        let removal = Bool.random(using: &random)
            ? Box.Removal(reason: RemovalReason.allCases.randomElement(using: &random) ?? .usedUp, date: date(using: &random))
            : nil
        let deletedAt = Int.random(in: 0...4, using: &random) == 0 ? date(using: &random) : nil
        // Only removed and deleted boxes may sit in a deleted location.
        let isHistory = removal != nil || deletedAt != nil
        let candidates = locations.filter { isHistory || $0.deletedAt == nil }
        let box = Box(
            id: id(using: &random), product: ProductRef(gtin: gtin, name: text(1...200, using: &random)),
            category: CategoryID(rawValue: text(1...100, using: &random)), categoryOverridden: Bool.random(using: &random),
            locationID: (candidates.randomElement(using: &random) ?? locations[0]).location.id, expiry: expiry,
            lot: Bool.random(using: &random) ? text(1...20, using: &random) : nil,
            // Unique per box, so a (gtin, serial) pair never repeats.
            serial: Bool.random(using: &random) ? "S\(index)" : nil, isOpened: Bool.random(using: &random),
            createdAt: date(using: &random), updatedAt: date(using: &random), removal: removal
        )
        return ArchivedBox(box: box, deletedAt: deletedAt)
    }
}
