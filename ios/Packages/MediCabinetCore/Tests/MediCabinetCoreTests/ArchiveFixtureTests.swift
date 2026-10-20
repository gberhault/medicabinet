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
        #expect(fixture.cases.count >= 150)
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
            deleted: summary.deleted, unknownExpiry: summary.unknownExpiry
        ))
        #expect(archive.cabinet.needsBackup == fixtureCase.expect.needsBackup)
    }

    /// The canonical writer reproduces the golden file byte for byte: the proof that both platforms
    /// write the same bytes (the Android suite runs the same check on the same file).
    @Test func theEncoderReproducesTheGoldenArchiveByteForByte() throws {
        let golden = try SharedFixtures.goldenArchive()
        let archive = try ArchiveDecoder.decode(golden)
        #expect(try ArchiveEncoder.encode(archive) == golden)
    }

    @Test func everyShippedFormatVersionKeepsAGoldenArchive() {
        for version in 1...ArchiveFormat.currentVersion {
            #expect(SharedFixtures.goldenArchiveExists(version: version), "missing fixtures/archive/v\(version)/golden.medicabinet")
        }
    }

    @Test func theCurrentVersionIsTheNumberOfReaders() {
        #expect(ArchiveFormat.currentVersion == ArchiveDecoder.readers.count)
        #expect(ArchiveFormat.currentVersion == 1)
    }
}
