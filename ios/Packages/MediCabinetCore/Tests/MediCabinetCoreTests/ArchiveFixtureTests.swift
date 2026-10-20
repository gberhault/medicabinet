import Foundation
import Testing
import MediCabinetCoreTestSupport
@testable import MediCabinetCore

/// Runs every case of the repository-wide `fixtures/archive-cases.json`, which the Android core also
/// runs, and the canonical byte-for-byte check on the golden archive. A case added there runs here
/// with no code change.
@Suite struct ArchiveFixtureTests {
    @Test func theSharedFixtureLoadsAndHasCases() throws {
        let fixture = try SharedFixtures.archiveCaseFixture()
        #expect(fixture.formatVersion == 1)
        #expect(fixture.cases.count >= 250)
    }

    @Test(arguments: SharedFixtures.archiveCases())
    func caseMatchesTheSharedExpectation(_ fixtureCase: ArchiveCase) throws {
        let bytes = try fixtureCase.bytes()
        if let expected = fixtureCase.expect.error {
            let kind = try #require(ArchiveErrorKind(rawValue: expected.kind), "unknown error kind \(expected.kind)")
            #expect(throws: ArchiveError(kind, path: expected.path)) { try ArchiveDecoder.decode(bytes) }
            return
        }
        let archive = try ArchiveDecoder.decode(bytes)
        let summary = try #require(fixtureCase.expect.summary)
        #expect(archive.sourceFormatVersion == summary.sourceFormatVersion)
        #expect(archive.sourceFormatVersion == summary.formatVersion)
        #expect(ArchiveDates.format(archive.exportedAt) == summary.exportedAt)
        #expect(archive.app == ArchiveApp(
            name: summary.app.name, platform: summary.app.platform, version: summary.app.version, build: summary.app.build
        ))
        #expect(archive.cabinet.summary == CabinetSummary(
            locations: summary.locations, inCabinet: summary.inCabinet, removed: summary.removed,
            deleted: summary.deleted, unknownExpiry: summary.unknownExpiry, namedProducts: summary.namedProducts
        ))
        #expect(archive.cabinet.namedProductsNeedFill == summary.namedProductsNeedFill)
        #expect(archive.cabinet.needsBackup == fixtureCase.expect.needsBackup)
    }

    /// The canonical writer reproduces the golden file of the current version byte for byte: the proof that both
    /// platforms write the same bytes (the Android suite runs the same check on the same file).
    @Test func theEncoderReproducesTheGoldenArchiveByteForByte() throws {
        let golden = try SharedFixtures.goldenArchive(version: ArchiveFormat.currentVersion)
        let archive = try ArchiveDecoder.decode(golden)
        #expect(archive.sourceFormatVersion == 2)
        #expect(try ArchiveEncoder.encode(archive) == golden)
    }

    @Test func everyShippedFormatVersionKeepsAGoldenArchive() {
        for version in 1...ArchiveFormat.currentVersion {
            #expect(SharedFixtures.goldenArchiveExists(version: version), "missing fixtures/archive/v\(version)/golden.medicabinet")
        }
    }

    /// Every later version imports every earlier golden file (F9). Version 1 has no named products: they are filled
    /// from the boxes after the import.
    @Test(arguments: 1...ArchiveFormat.currentVersion)
    func everyGoldenArchiveStillDecodes(version: Int) throws {
        let archive = try ArchiveDecoder.decode(try SharedFixtures.goldenArchive(version: version))
        #expect(archive.sourceFormatVersion == version)
        #expect(archive.cabinet.namedProductsNeedFill == (version == 1))
        #expect(archive.cabinet.namedProducts.isEmpty == (version == 1))
    }

    /// The version 1 golden is never rewritten: re-encoding it gives the same cabinet in the current version.
    @Test func theVersionOneGoldenReEncodesAsTheSameCabinetInVersionTwo() throws {
        let old = try ArchiveDecoder.decode(try SharedFixtures.goldenArchive(version: 1))
        let renewed = try ArchiveDecoder.decode(try ArchiveEncoder.encode(old))
        #expect(renewed.sourceFormatVersion == 2)
        #expect(renewed.cabinet.locations == old.cabinet.locations)
        #expect(renewed.cabinet.boxes == old.cabinet.boxes)
        #expect(renewed.cabinet.namedProducts.isEmpty)
        #expect(renewed.cabinet.namedProductsNeedFill == false)
    }

    @Test func theCurrentVersionIsTheNumberOfReaders() {
        #expect(ArchiveFormat.currentVersion == ArchiveDecoder.readers.count)
        #expect(ArchiveFormat.currentVersion == 2)
    }
}
