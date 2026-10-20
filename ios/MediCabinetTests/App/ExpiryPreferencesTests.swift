import Foundation
import MediCabinetCore
import MediCabinetCoreTestSupport
import Testing
@testable import MediCabinet

@Suite struct ExpiryPreferencesTests {
    private func defaults() throws -> UserDefaults {
        let name = "ExpiryPreferencesTests-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: name))
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    // MARK: Defaults and round trip

    @Test func nothingStoredReadsAsTheDefaults() throws {
        let preferences = UserDefaultsExpiryPreferences(defaults: try defaults())
        #expect(preferences.window == ExpiryWindow.default)
        #expect(!preferences.reminderEnabled)
        #expect(!preferences.cardDismissed)
    }

    @Test func valuesRoundTripThroughUserDefaultsUnderTheSharedKeys() throws {
        let store = try defaults()
        let preferences = UserDefaultsExpiryPreferences(defaults: store)
        preferences.window = .days90
        preferences.reminderEnabled = true
        preferences.cardDismissed = true

        let reopened = UserDefaultsExpiryPreferences(defaults: store)
        #expect(reopened.window == .days90)
        #expect(reopened.reminderEnabled)
        #expect(reopened.cardDismissed)
        #expect(store.integer(forKey: "expiry.windowDays") == 90)
        #expect(store.bool(forKey: "reminder.enabled"))
        #expect(store.bool(forKey: "reminder.cardDismissed"))
    }

    @Test(arguments: [45, 0, -30, 61, 3_650])
    func aStoredWindowThatIsNotAChoiceReadsAsTheDefault(stored: Int) throws {
        let store = try defaults()
        store.set(stored, forKey: "expiry.windowDays")
        #expect(UserDefaultsExpiryPreferences(defaults: store).window == ExpiryWindow.default)
    }

    @Test func aStoredWindowOfTheWrongTypeReadsAsTheDefault() throws {
        let store = try defaults()
        store.set("ninety", forKey: "expiry.windowDays")
        #expect(UserDefaultsExpiryPreferences(defaults: store).window == ExpiryWindow.default)
    }

    @Test(arguments: [30, 60, 90])
    func everyChoiceIsReadBack(days: Int) throws {
        let store = try defaults()
        store.set(days, forKey: "expiry.windowDays")
        #expect(UserDefaultsExpiryPreferences(defaults: store).window.days == days)
    }

    // MARK: Changes

    @Test func theChangeStreamStartsWithTheCurrentValuesThenFollowsEveryChange() async throws {
        let preferences = UserDefaultsExpiryPreferences(defaults: try defaults())
        preferences.reminderEnabled = true
        var iterator = preferences.changes().makeAsyncIterator()
        #expect(await iterator.next() == ExpiryPreferencesSnapshot(window: .days60, reminderEnabled: true))

        preferences.window = .days30
        #expect(await iterator.next() == ExpiryPreferencesSnapshot(window: .days30, reminderEnabled: true))
        preferences.cardDismissed = true
        #expect(await iterator.next()
            == ExpiryPreferencesSnapshot(window: .days30, reminderEnabled: true, cardDismissed: true))
    }

    @Test func everySubscriberSeesTheChangeAndALeftSubscriberIsForgotten() async throws {
        let preferences = InMemoryExpiryPreferences()
        var first = preferences.changes().makeAsyncIterator()
        var second = preferences.changes().makeAsyncIterator()
        _ = await first.next()
        _ = await second.next()
        preferences.window = .days90
        #expect(await first.next()?.window == .days90)
        #expect(await second.next()?.window == .days90)

        let task = Task {
            for await _ in preferences.changes() {}
        }
        task.cancel()
        await task.value
        preferences.window = .days30  // must not trap or leak into the cancelled stream
        #expect(await first.next()?.window == .days30)
    }

    @Test func theInMemorySetBehavesLikeTheStoredOne() async throws {
        let preferences = InMemoryExpiryPreferences(
            ExpiryPreferencesSnapshot(window: .days30, reminderEnabled: true, cardDismissed: true))
        #expect(preferences.snapshot.window == .days30)
        #expect(preferences.reminderEnabled)
        #expect(preferences.cardDismissed)
        preferences.reminderEnabled = false
        preferences.cardDismissed = false
        #expect(preferences.snapshot == ExpiryPreferencesSnapshot(window: .days30))
    }

    // MARK: The environment follows the preference at runtime

    @Test func theEnvironmentWindowFollowsThePreference() throws {
        let preferences = InMemoryExpiryPreferences()
        let store = InMemoryCabinetStore(clock: SystemClock())
        let environment = AppEnvironment(
            locations: store, boxes: store, clock: SystemClock(),
            reference: InMemoryReferenceData(status: .unavailable(.missing)), preferences: preferences)
        #expect(environment.expiryWindow == .days60)
        preferences.window = .days30
        #expect(environment.expiryWindow == .days30)
        let day = environment.today()
        let expiry = try ExpiryDate.make(year: day.year, month: day.month, day: day.day)
        #expect(environment.expiryStatus(of: expiry, on: day) == .expiringSoon)
        #expect(environment.expiryStatus(of: nil, on: day) == .unknown)
    }
}
