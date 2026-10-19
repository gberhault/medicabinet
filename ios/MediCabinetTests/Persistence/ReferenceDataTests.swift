import Foundation
import GRDB
import MediCabinetCore
import MediCabinetCoreTestSupport
import Testing
@testable import MediCabinet

/// The bundled reference database: lookup against the shared fixtures, every way it can be unusable, the real bundled
/// file, and the timing budgets (requirements L3-L9).
@Suite struct ReferenceDataTests {
    private let directory = FileManager.default.temporaryDirectory
        .appendingPathComponent("ReferenceDataTests-\(UUID().uuidString)", isDirectory: true)

    /// A writable copy of the sample database that `change` may alter.
    private func sampleCopy(named name: String = "reference.sqlite", change: (DatabaseQueue) throws -> Void = { _ in }) throws -> URL {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let copy = directory.appendingPathComponent(name)
        try FileManager.default.copyItem(at: SharedFixtures.referenceSampleURL, to: copy)
        let queue = try DatabaseQueue(path: copy.path)
        try change(queue)
        try queue.close()
        return copy
    }

    // MARK: shared lookup fixture (L2-L5)

    @Test func theSharedFixtureHasCases() throws {
        #expect(try SharedFixtures.referenceLookup().count >= 16)
        #expect(FileManager.default.fileExists(atPath: SharedFixtures.referenceSampleURL.path))
    }

    @Test(arguments: SharedFixtures.referenceLookupCases())
    func lookupMatchesTheSharedExpectation(_ lookupCase: ReferenceLookupCase) async throws {
        let source = GRDBReferenceData(url: SharedFixtures.referenceSampleURL)
        let product = try await source.product(gtin: lookupCase.gtin)
        let expected = lookupCase.expect.product
        #expect(product?.name == expected?.name)
        #expect(product?.category?.rawValue == expected?.category)
        #expect((product == nil) == (expected == nil))
    }

    @Test func statusCarriesTheDataDateOfTheSample() async throws {
        let source = GRDBReferenceData(url: SharedFixtures.referenceSampleURL)
        let expected = try #require(ReferenceDataDate(iso: "2026-11-18"))
        #expect(await source.status() == .available(dataDate: expected))
    }

    @Test func theDatabaseIsOpenedReadOnly() throws {
        let database = try ReferenceDatabase.open(at: SharedFixtures.referenceSampleURL).get()
        #expect(throws: (any Error).self) {
            try database.queue.write { try $0.execute(sql: "DELETE FROM presentation") }
        }
    }

    // MARK: unavailable (L7)

    @Test func noURLMeansMissing() async throws {
        let source = GRDBReferenceData(url: nil)
        #expect(await source.status() == .unavailable(.missing))
        #expect(try await source.product(gtin: "03400900000013") == nil)
    }

    @Test func aFileThatDoesNotExistIsMissing() async {
        let source = GRDBReferenceData(url: directory.appendingPathComponent("nowhere.sqlite"))
        #expect(await source.status() == .unavailable(.missing))
    }

    @Test func aFileThatIsNotADatabaseIsUnreadable() async throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent("garbage.sqlite")
        try Data("this is not a database, it is long enough to look like a header".utf8).write(to: url)
        let source = GRDBReferenceData(url: url)
        #expect(await source.status() == .unavailable(.unreadable))
        #expect(try await source.product(gtin: "03400900000013") == nil)
    }

    @Test func aTruncatedCopyIsUnreadable() async throws {
        let url = try sampleCopy()
        let whole = try Data(contentsOf: url)
        try whole.prefix(whole.count / 2).write(to: url)
        let source = GRDBReferenceData(url: url)
        #expect(await source.status() == .unavailable(.unreadable))
        #expect(try await source.product(gtin: "03400900000013") == nil)
    }

    @Test func anotherSchemaVersionIsUnsupported() async throws {
        let url = try sampleCopy { try $0.write { try $0.execute(sql: "PRAGMA user_version = 2") } }
        let source = GRDBReferenceData(url: url)
        #expect(await source.status() == .unavailable(.unsupportedSchema))
        #expect(try await source.product(gtin: "03400900000013") == nil)
    }

    @Test func aMissingOrInvalidDataDateIsUnreadable() async throws {
        for value in ["not a date", "2026-02-30", ""] {
            let url = try sampleCopy(named: "date-\(abs(value.hashValue)).sqlite") {
                try $0.write { try $0.execute(sql: "UPDATE meta SET value = ? WHERE key = 'dataDate'", arguments: [value]) }
            }
            #expect(await GRDBReferenceData(url: url).status() == .unavailable(.unreadable), "data date '\(value)'")
        }
        let withoutDate = try sampleCopy(named: "no-date.sqlite") {
            try $0.write { try $0.execute(sql: "DELETE FROM meta WHERE key = 'dataDate'") }
        }
        #expect(await GRDBReferenceData(url: withoutDate).status() == .unavailable(.unreadable))
    }

    @Test func aDatabaseThatFailsAfterOpeningMakesProductsUnknownWithoutThrowing() async throws {
        let url = try sampleCopy { try $0.write { try $0.execute(sql: "ALTER TABLE presentation RENAME TO presentation_old") } }
        let source = GRDBReferenceData(url: url)
        #expect(await source.status() != .unavailable(.missing))
        #expect(try await source.product(gtin: "03400900000013") == nil)
    }

    @Test func theCabinetStartsWhenTheReferenceDataIsMissing() async throws {
        let root = directory.appendingPathComponent("cabinet", isDirectory: true)
        let state = await Bootstrap(
            directory: root, defaultLocationName: "Armoire", referenceURL: directory.appendingPathComponent("nowhere.sqlite")
        ).run()
        guard case .ready(let environment) = state else { Issue.record("expected ready, got \(state)"); return }
        #expect(await environment.reference.status() == .unavailable(.missing))
    }

    // MARK: the bundled file (L9)

    private var bundledURL: URL? { Bundle.main.url(forResource: "reference", withExtension: "sqlite") }

    @Test func theBundledDatabaseIsInTheApp() throws {
        #expect(try ReferenceDatabase.open(at: #require(bundledURL)).get().dataDate.year >= 2026)
    }

    @Test func theBundledDatabaseKnowsDoliprane() async throws {
        let source = GRDBReferenceData(url: bundledURL)
        let product = try #require(try await source.product(gtin: "03400935955838"))
        #expect(product.name.hasPrefix("DOLIPRANE 1000 mg"))
        #expect(product.category == .painFever)
        let status = await source.status()
        guard case .available = status else { Issue.record("bundled data unavailable"); return }
    }

    @Test func theBundledDatabaseAnswersUnknownForAParapharmacyCode() async throws {
        let source = GRDBReferenceData(url: bundledURL)
        #expect(try await source.product(gtin: "03401900000003") == nil)
    }

    // MARK: timing (L6): generous budgets, several runs

    @Test func openingTheBundledDatabaseStaysUnderItsBudget() throws {
        let url = try #require(bundledURL)
        let clock = ContinuousClock()
        var worst = Duration.zero
        for _ in 0..<5 {
            let elapsed = clock.measure { _ = ReferenceDatabase.open(at: url) }
            worst = max(worst, elapsed)
        }
        #expect(worst < .milliseconds(500), "slowest open took \(worst)")
    }

    @Test func lookupsOnTheFullDatabaseTakeUnder50Milliseconds() async throws {
        let url = try #require(bundledURL)
        let database = try ReferenceDatabase.open(at: url).get()
        let sql = "SELECT cip13 FROM presentation ORDER BY cip13 LIMIT 500"
        let cip13s = try await database.queue.read { try String.fetchAll($0, sql: sql) }
        #expect(cip13s.count == 500)
        let source = GRDBReferenceData(url: url)
        _ = try await source.product(gtin: "0" + cip13s[0])  // opens the database once
        let clock = ContinuousClock()
        var timings: [Duration] = []
        for cip13 in cip13s {
            let start = clock.now
            let product = try await source.product(gtin: "0" + cip13)
            timings.append(clock.now - start)
            #expect(product != nil)
        }
        timings.sort()
        let p99 = timings[timings.count * 99 / 100]
        #expect(p99 < .milliseconds(50), "99th percentile lookup took \(p99)")
        #expect(timings[timings.count / 2] < .milliseconds(5), "median lookup took \(timings[timings.count / 2])")
    }
}
