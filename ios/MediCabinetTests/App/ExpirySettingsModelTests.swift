import Foundation
import MediCabinetCore
import Testing
@testable import MediCabinet

@MainActor
@Suite struct ExpirySettingsModelTests {
    @Test func offersThirtySixtyAndNinetyDays() {
        #expect(ExpirySettingsModel.windowChoices.map(\.days) == [30, 60, 90])
    }

    @Test func startsOnTheStoredWindowOrTheDefault() {
        #expect(ExpirySettingsModel(preferences: InMemoryExpiryPreferences()).window == .default)
        let stored = InMemoryExpiryPreferences(ExpiryPreferencesSnapshot(window: .days90))
        #expect(ExpirySettingsModel(preferences: stored).window == .days90)
    }

    @Test func choosingAWindowWritesThePreferenceAtOnce() {
        let preferences = InMemoryExpiryPreferences()
        let model = ExpirySettingsModel(preferences: preferences)
        model.window = .days30
        #expect(preferences.window == .days30)
        model.window = .days90
        #expect(preferences.window == .days90)
    }

    @Test func choosingTheSameWindowWritesNothing() async {
        let preferences = InMemoryExpiryPreferences()
        let model = ExpirySettingsModel(preferences: preferences)
        var iterator = preferences.changes().makeAsyncIterator()
        _ = await iterator.next()
        model.window = .days60
        preferences.cardDismissed = true  // the next emission must be this one, not a rewrite of the window
        #expect(await iterator.next()?.cardDismissed == true)
    }

    @Test func theWindowOptionsReadAsDays() {
        #expect(ExpiryText.windowOption(.days60).contains("60"))
    }
}
