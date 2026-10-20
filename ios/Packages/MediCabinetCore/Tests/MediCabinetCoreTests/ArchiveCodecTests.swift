import Foundation
import Testing
import MediCabinetCoreTestSupport
@testable import MediCabinetCore

/// The encoder's own validation (what the shared decode cases cannot reach, because a decoded model is
/// already valid), the encoder's ordering, and a round trip over random cabinets.
@Suite struct ArchiveCodecTests {
    private let stamp = ArchiveDates.date(milliseconds: 1_792_397_730_123)
    private let home = UUID(uuidString: "10000001-0000-4000-8000-000000000001") ?? UUID()
    private let app = ArchiveApp(name: "MediCabinet", platform: "ios", version: "0.3.0", build: "3")

    private func location(_ id: UUID? = nil, name: String = "Home", isDefault: Bool = true, order: Int = 0) -> Location {
        Location(id: id ?? home, name: name, isDefault: isDefault, sortOrder: order, createdAt: stamp)
    }

    private func box(_ number: Int = 1, in locationID: UUID? = nil, edit: (inout Box) -> Void = { _ in }) -> ArchivedBox {
        let id = UUID(uuidString: SyntheticArchive.uuid("B", number)) ?? UUID()
        var box = Box(
            id: id, product: ProductRef(gtin: "03400936404564", name: "Box \(number)"),
            locationID: locationID ?? home, createdAt: stamp
        )
        edit(&box)
        return ArchivedBox(box: box)
    }

    private func named(
        _ number: Int = 1, gtin: String = "03401234567890", forgotten: Bool = false, id: UUID? = nil
    ) -> ArchivedNamedProduct {
        let product = NamedProduct(
            id: id ?? UUID(uuidString: SyntheticArchive.uuid("C", number)) ?? UUID(), gtin: gtin, name: "Named \(number)",
            category: CategoryID(rawValue: "skin-wound"), categoryOverridden: true,
            createdAt: stamp.addingTimeInterval(Double(number))
        )
        return ArchivedNamedProduct(product: product, deletedAt: forgotten ? stamp : nil)
    }

    private func archive(
        locations: [Location]? = nil, boxes: [ArchivedBox] = [], namedProducts: [ArchivedNamedProduct] = []
    ) -> Archive {
        let archived = (locations ?? [location()]).map { ArchivedLocation(location: $0) }
        let cabinet = CabinetContents(locations: archived, boxes: boxes, namedProducts: namedProducts)
        return Archive(exportedAt: stamp, app: app, cabinet: cabinet)
    }

    private func encoding(_ archive: Archive, limits: ArchiveLimits = .default) throws -> [UInt8] {
        try ArchiveEncoder.encode(archive, limits: limits)
    }

    // MARK: The encoder refuses what the decoder would

    @Test func aValidArchiveEncodesAndDecodesBackToItself() throws {
        let original = archive(boxes: [box(1), box(2) { $0.expiry = try? ExpiryDate.make(year: 2027, month: 4, day: nil) }])
        let decoded = try ArchiveDecoder.decode(try encoding(original))
        #expect(decoded == original)
    }

    @Test func namedProductsSurviveEncodingForgottenOnesIncluded() throws {
        let products = [named(1), named(2, gtin: "03401234567891", forgotten: true), named(3, forgotten: true)]
        let original = archive(boxes: [box(1)], namedProducts: products)
        let decoded = try ArchiveDecoder.decode(try encoding(original))
        #expect(decoded == original)
        #expect(decoded.cabinet.summary.namedProducts == 1)
        #expect(decoded.cabinet.namedProductsNeedFill == false)
    }

    @Test func tooManyLocationsOrBoxesAreRefusedAtTheLimit() throws {
        var limits = ArchiveLimits()
        limits.maxNamedProducts = 1
        #expect(throws: ArchiveError(.tooManyItems, path: "namedProducts")) {
            try encoding(archive(namedProducts: [named(1), named(2, gtin: "03401234567891")]), limits: limits)
        }
        _ = try encoding(archive(namedProducts: [named(1)]), limits: limits)
        limits.maxBoxes = 1
        #expect(throws: ArchiveError(.tooManyItems, path: "boxes")) { try encoding(archive(boxes: [box(1), box(2)]), limits: limits) }
        limits.maxLocations = 1
        let two = [location(), location(UUID(), name: "Other", isDefault: false, order: 1)]
        #expect(throws: ArchiveError(.tooManyItems, path: "locations")) { try encoding(archive(locations: two), limits: limits) }
    }

    @Test func aFileOverTheSizeLimitIsRefused() {
        var limits = ArchiveLimits()
        limits.maxFileBytes = 100
        #expect(throws: ArchiveError(.tooLarge)) { try encoding(archive(), limits: limits) }
    }

    /// One way to make a valid archive invalid, and the field the error must name.
    struct Breakage: Sendable, CustomTestStringConvertible {
        let path: String
        let edit: @Sendable (inout Archive) -> Void
        var testDescription: String { path }
        init(_ path: String, edit: @escaping @Sendable (inout Archive) -> Void) {
            self.path = path
            self.edit = edit
        }
    }

    static let breakages: [Breakage] = [
        Breakage("exportedAt") { $0.exportedAt = Date(timeIntervalSince1970: 1e18) },
        Breakage("app.name") { $0.app.name = "" },
        Breakage("app.platform") { $0.app.platform = "windows" },
        Breakage("app.version") { $0.app.version = String(repeating: "v", count: 21) },
        Breakage("app.build") { $0.app.build = "" },
        Breakage("locations[0].name") { $0.cabinet.locations[0].location.name = "" },
        Breakage("locations[0].sortOrder") { $0.cabinet.locations[0].location.sortOrder = Int(Int32.max) + 1 },
        Breakage("locations[0].sortOrder") { $0.cabinet.locations[0].location.sortOrder = Int(Int32.min) - 1 },
        Breakage("locations[0].createdAt") {
            $0.cabinet.locations[0] = ArchivedLocation(
                location: $0.cabinet.locations[0].location.with(createdAt: Date(timeIntervalSince1970: -1e18))
            )
        },
        Breakage("locations[0].updatedAt") { $0.cabinet.locations[0].location.updatedAt = Date(timeIntervalSince1970: 1e18) },
        Breakage("locations[0].deletedAt") { $0.cabinet.locations[0].deletedAt = Date(timeIntervalSince1970: 1e18) },
        Breakage("boxes[0].gtin") { $0.cabinet.boxes[0].box.product.gtin = "123" },
        Breakage("boxes[0].gtin") { $0.cabinet.boxes[0].box.product.gtin = "0340093640456A" },
        Breakage("boxes[0].productName") { $0.cabinet.boxes[0].box.product.name = "" },
        Breakage("boxes[0].productName") { $0.cabinet.boxes[0].box.product.name = String(repeating: "p", count: 201) },
        Breakage("boxes[0].category") { $0.cabinet.boxes[0].box.category = CategoryID(rawValue: "") },
        Breakage("boxes[0].lot") { $0.cabinet.boxes[0].box.lot = "" },
        Breakage("boxes[0].serial") { $0.cabinet.boxes[0].box.serial = String(repeating: "s", count: 21) },
        Breakage("boxes[0].removal.at") {
            $0.cabinet.boxes[0].box.removal = Box.Removal(reason: .usedUp, date: Date(timeIntervalSince1970: 1e18))
        },
        Breakage("boxes[0].createdAt") {
            $0.cabinet.boxes[0].box = $0.cabinet.boxes[0].box.with(createdAt: Date(timeIntervalSince1970: 1e18))
        },
        Breakage("boxes[0].updatedAt") { $0.cabinet.boxes[0].box.updatedAt = Date(timeIntervalSince1970: 1e18) },
        Breakage("boxes[0].deletedAt") { $0.cabinet.boxes[0].deletedAt = Date(timeIntervalSince1970: 1e18) },
        Breakage("namedProducts[0].gtin") { $0.cabinet.namedProducts[0].product = $0.cabinet.namedProducts[0].product.with(gtin: "123") },
        Breakage("namedProducts[0].gtin") {
            $0.cabinet.namedProducts[0].product = $0.cabinet.namedProducts[0].product.with(gtin: "0340123456789A")
        },
        Breakage("namedProducts[0].name") { $0.cabinet.namedProducts[0].product.name = "" },
        Breakage("namedProducts[0].name") { $0.cabinet.namedProducts[0].product.name = String(repeating: "n", count: 201) },
        Breakage("namedProducts[0].category") { $0.cabinet.namedProducts[0].product.category = CategoryID(rawValue: "") },
        Breakage("namedProducts[0].category") {
            $0.cabinet.namedProducts[0].product.category = CategoryID(rawValue: String(repeating: "c", count: 101))
        },
        Breakage("namedProducts[0].createdAt") {
            $0.cabinet.namedProducts[0].product = $0.cabinet.namedProducts[0].product.with(createdAt: Date(timeIntervalSince1970: 1e18))
        },
        Breakage("namedProducts[0].updatedAt") { $0.cabinet.namedProducts[0].product.updatedAt = Date(timeIntervalSince1970: 1e18) },
        Breakage("namedProducts[0].deletedAt") { $0.cabinet.namedProducts[0].deletedAt = Date(timeIntervalSince1970: 1e18) }
    ]

    @Test(arguments: ArchiveCodecTests.breakages)
    func aFieldTheDecoderWouldRefuseIsRefusedAtEncodingToo(_ breakage: Breakage) {
        var broken = archive(boxes: [box(1)], namedProducts: [named(1)])
        breakage.edit(&broken)
        #expect(throws: ArchiveError(.invalidValue, path: breakage.path)) { try encoding(broken) }
    }

    @Test func crossChecksAreEnforcedAtEncodingToo() {
        let other = location(UUID(), name: "Other", isDefault: false, order: 1)
        #expect(throws: ArchiveError(.duplicateId, path: "locations[1].id")) {
            try encoding(archive(locations: [location(), location(home, name: "Again", isDefault: false)]))
        }
        #expect(throws: ArchiveError(.duplicateId, path: "boxes[1].id")) { try encoding(archive(boxes: [box(1), box(1)])) }
        #expect(throws: ArchiveError(.defaultLocation, path: "locations")) {
            try encoding(archive(locations: [location(isDefault: false)]))
        }
        #expect(throws: ArchiveError(.defaultLocation, path: "locations")) {
            try encoding(archive(locations: [location(), location(UUID(), name: "Two", isDefault: true, order: 1)]))
        }
        #expect(throws: ArchiveError(.unknownLocation, path: "boxes[0].locationId")) {
            try encoding(archive(boxes: [box(1, in: UUID())]))
        }
        #expect(throws: ArchiveError(.duplicateSerial, path: "boxes[1].serial")) {
            try encoding(archive(locations: [location(), other], boxes: [box(1) { $0.serial = "S" }, box(2) { $0.serial = "S" }]))
        }
    }

    @Test func namedProductChecksAreEnforcedAtEncodingToo() throws {
        #expect(throws: ArchiveError(.duplicateId, path: "namedProducts[1].id")) {
            try encoding(archive(namedProducts: [named(1), named(2, gtin: "03401234567891", id: named(1).product.id)]))
        }
        #expect(throws: ArchiveError(.duplicateNamedProduct, path: "namedProducts[1].gtin")) {
            try encoding(archive(namedProducts: [named(1), named(2)]))
        }
        // A forgotten product and a live one (or several forgotten ones) may share a GTIN, in any order.
        _ = try encoding(archive(namedProducts: [named(1, forgotten: true), named(2), named(3, forgotten: true)]))
        // A named product may share its id with a box (ids are unique per kind of row).
        _ = try encoding(archive(boxes: [box(1)], namedProducts: [named(1, id: box(1).box.id)]))
    }

    // MARK: Deleted locations

    private func contents(_ locations: [ArchivedLocation], _ boxes: [ArchivedBox]) -> Archive {
        Archive(exportedAt: stamp, app: app, cabinet: CabinetContents(locations: locations, boxes: boxes))
    }

    @Test func aDeletedLocationKeepsTheHistoryButNotTheBoxesInTheCabinet() throws {
        let home = ArchivedLocation(location: location())
        let goneID = UUID()
        let gone = ArchivedLocation(location: location(goneID, name: "Gone", isDefault: false, order: 1), deletedAt: stamp)
        var removed = box(1, in: goneID)
        removed.box.removal = Box.Removal(reason: .usedUp, date: stamp)
        var mistake = box(2, in: goneID)
        mistake.deletedAt = stamp
        let archive = contents([home, gone], [removed, mistake, box(3)])
        #expect(try ArchiveDecoder.decode(try encoding(archive)) == archive)

        #expect(throws: ArchiveError(.unknownLocation, path: "boxes[0].locationId")) {
            try encoding(contents([home, gone], [box(1, in: goneID)]))
        }
        let deletedDefault = ArchivedLocation(location: location(), deletedAt: stamp)
        #expect(throws: ArchiveError(.defaultLocation, path: "locations")) { try encoding(contents([deletedDefault], [])) }
    }

    @Test func aSerialRepeatedWithoutAGtinOrAfterADeletionIsFine() throws {
        let noGtin = [box(1) { $0.product.gtin = nil; $0.serial = "S" }, box(2) { $0.product.gtin = nil; $0.serial = "S" }]
        _ = try encoding(archive(boxes: noGtin))
        var deleted = box(1) { $0.serial = "S" }
        deleted.deletedAt = stamp
        _ = try encoding(archive(boxes: [deleted, box(2) { $0.serial = "S" }]))
        _ = try encoding(archive(boxes: [box(1) { $0.serial = "S" }, box(2) { $0.serial = "T" }, box(3)]))
    }

    // MARK: Ordering

    @Test func theEncoderOrdersLocationsAndBoxesWhateverTheInputOrder() throws {
        let golden = try SharedFixtures.goldenArchive()
        var archive = try ArchiveDecoder.decode(golden)
        archive.cabinet.locations.reverse()
        archive.cabinet.boxes.reverse()
        #expect(try encoding(archive) == golden)
        var generator = SeededGenerator(seed: 7)
        archive.cabinet.boxes.shuffle(using: &generator)
        #expect(try encoding(archive) == golden)
    }

    @Test func theEncoderOrdersNamedProductsByCreationThenIdWhateverTheInputOrder() throws {
        let golden = try SharedFixtures.goldenArchive()
        var archive = try ArchiveDecoder.decode(golden)
        #expect(archive.cabinet.namedProducts.count == 3)
        archive.cabinet.namedProducts.reverse()
        #expect(try encoding(archive) == golden)
        // The same instant breaks the tie by id, upper case.
        let first = named(2, gtin: "03401234567891", id: UUID(uuidString: "C000000B-0000-4000-8000-000000000002"))
        let second = named(1, id: UUID(uuidString: "C000000A-0000-4000-8000-000000000001"))
        let sameInstant = [first, second].map { archived in
            var copy = archived
            copy.product = archived.product.with(createdAt: stamp)
            return copy
        }
        let decoded = try ArchiveDecoder.decode(try encoding(self.archive(namedProducts: sameInstant)))
        #expect(decoded.cabinet.namedProducts.map(\.product.id.uuidString) == [
            "C000000A-0000-4000-8000-000000000001", "C000000B-0000-4000-8000-000000000002"
        ])
    }

    // MARK: Round trip over random cabinets

    @Test(arguments: Array(1...40))
    func aRandomCabinetSurvivesEncodingAndDecoding(_ seed: Int) throws {
        var random = SeededGenerator(seed: UInt64(seed))
        let original = RandomCabinet.make(using: &random)
        let decoded = try ArchiveDecoder.decode(try encoding(original))
        #expect(decoded.exportedAt == original.exportedAt)
        #expect(decoded.app == original.app)
        #expect(Set(decoded.cabinet.locations) == Set(original.cabinet.locations))
        #expect(Set(decoded.cabinet.boxes) == Set(original.cabinet.boxes))
        #expect(Set(decoded.cabinet.namedProducts) == Set(original.cabinet.namedProducts))
        // Idempotence: writing what was read gives the same bytes.
        #expect(try encoding(decoded) == (try encoding(original)))
    }
}
