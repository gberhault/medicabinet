import Foundation
import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

@Suite struct InMemoryArchiveStoreTests {
    @Test(arguments: ArchiveStoreContract.checks)
    func fakeSatisfiesTheStoreContract(check: ArchiveStoreContract.Check) async throws {
        try await check.run { InMemoryArchiveStore() }
    }

    @Test func contractChecksMatchTheSharedList() throws {
        #expect(ArchiveStoreContract.checks.map(\.name) == (try SharedFixtures.archiveStoreContractCheckNames()))
    }
}

@Suite struct InMemoryArchiveBackupStoreTests {
    private let validArchive = Array(SyntheticArchive.json(locations: 2, boxes: 3).utf8)
    private let day = Date(timeIntervalSince1970: 1_800_000_000)

    @Test func aWrittenBackupIsListedWithItsSummaryAndReadsBack() async throws {
        let store = InMemoryArchiveBackupStore()
        let entry = try store.write(validArchive, reason: .beforeImport, takenAt: day)
        #expect(entry.summary == CabinetSummary(locations: 2, inCabinet: 3, removed: 0, deleted: 0, unknownExpiry: 0))
        #expect(try await store.list() == [entry])
        #expect(try await store.read(entry.id) == validArchive)
    }

    @Test func somethingThatIsNotAnArchiveIsNotWritten() throws {
        let store = InMemoryArchiveBackupStore()
        #expect(throws: ArchiveError.self) { try store.write([0x7B], reason: .beforeImport, takenAt: day) }
        #expect(store.count == 0)
    }

    @Test func backupsAreListedNewestFirstAndPrunedFromTheOldest() async throws {
        let store = InMemoryArchiveBackupStore()
        for offset in 0..<5 {
            _ = try store.write(validArchive, reason: .beforeRestore, takenAt: day.addingTimeInterval(Double(offset)))
        }
        let listed = try await store.list()
        #expect(listed.map(\.takenAt) == (0..<5).reversed().map { day.addingTimeInterval(Double($0)) })
        try await store.prune(keeping: 3)
        #expect(try await store.list().map(\.takenAt) == (2..<5).reversed().map { day.addingTimeInterval(Double($0)) })
        try await store.prune(keeping: -1)
        #expect(store.count == 0)
    }

    @Test func aDamagedBackupIsStillListedAsUnreadable() async throws {
        let store = InMemoryArchiveBackupStore()
        let entry = try store.write(validArchive, reason: .beforeImport, takenAt: day)
        store.corrupt(entry.id)
        let listed = try await store.list()
        #expect(listed.count == 1)
        #expect(listed.first?.summary == nil)
    }

    @Test func writesAndPrunesCanBeMadeToFail() async throws {
        let store = InMemoryArchiveBackupStore()
        store.setFailingWrites(true)
        #expect(throws: InMemoryArchiveBackupStore.Failure.self) { try store.write(validArchive, reason: .beforeImport, takenAt: day) }
        store.setFailingWrites(false)
        _ = try store.write(validArchive, reason: .beforeImport, takenAt: day)
        store.setFailingPrunes(true)
        await #expect(throws: InMemoryArchiveBackupStore.Failure.self) { try await store.prune(keeping: 1) }
        await #expect(throws: InMemoryArchiveBackupStore.Failure.self) { try await store.read("missing") }
    }
}
