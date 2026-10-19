import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

@Suite struct InMemoryRepositoryContractTests {
    @Test(arguments: RepositoryContract.checks)
    func fakeSatisfiesContract(check: RepositoryContract.Check) async throws {
        try await check.run { clock in
            let store = InMemoryCabinetStore(clock: clock)
            return (store, store)
        }
    }
}
