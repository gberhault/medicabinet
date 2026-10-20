import Foundation
import MediCabinetCore
import Testing
import UserNotifications
@testable import MediCabinet

@MainActor
private final class Taps {
    var count = 0
}

@MainActor
@Suite struct DigestTextAndDelegateTests {
    private func bundle(_ code: String) throws -> Bundle {
        let path = try #require(Bundle.main.path(forResource: code, ofType: "lproj"), "no \(code).lproj in the app")
        return try #require(Bundle(path: path))
    }

    private func body(_ expired: Int, _ soon: Int, window: ExpiryWindow = .days60, code: String) throws -> String? {
        DigestText.body(
            DigestCounts(expired: expired, expiringSoon: soon), window: window, bundle: try bundle(code),
            locale: Locale(identifier: code))
    }

    // MARK: Wording, identical on both platforms (N6, N15)

    @Test(arguments: [
        ("en", 1, 0, "1 box expired"), ("en", 2, 0, "2 boxes expired"),
        ("en", 0, 1, "1 box expires within 60 days"), ("en", 0, 3, "3 boxes expire within 60 days"),
        ("en", 2, 3, "2 boxes expired · 3 boxes expire within 60 days"),
        ("en", 1, 1, "1 box expired · 1 box expires within 60 days"),
        ("fr", 1, 0, "1 boîte périmée"), ("fr", 2, 0, "2 boîtes périmées"),
        ("fr", 0, 1, "1 boîte périme dans les 60 jours"), ("fr", 0, 3, "3 boîtes périment dans les 60 jours"),
        ("fr", 2, 3, "2 boîtes périmées · 3 boîtes périment dans les 60 jours")
    ])
    func theBodyStatesTheCountsWithTheRightPlural(code: String, expired: Int, soon: Int, expected: String) throws {
        #expect(try body(expired, soon, code: code) == expected)
    }

    @Test(arguments: [(ExpiryWindow.days30, "30"), (.days60, "60"), (.days90, "90")])
    func theBodyNamesTheWindow(window: ExpiryWindow, days: String) throws {
        #expect(try body(0, 2, window: window, code: "en") == "2 boxes expire within \(days) days")
    }

    @Test func nothingToSayIsNoBody() throws {
        #expect(try body(0, 0, code: "en") == nil)
    }

    @Test(arguments: [("en", "Your medicine cabinet"), ("fr", "Votre armoire à pharmacie")])
    func theTitleIsTheSameForEveryDigest(code: String, expected: String) throws {
        #expect(DigestText.title(bundle: try bundle(code)) == expected)
    }

    // MARK: Delegate (N9)

    @Test func tappingADigestOpensTheExpiringTab() async {
        let taps = Taps()
        let delegate = ReminderNotificationDelegate(openExpiring: { taps.count += 1 })
        await delegate.handleTap(on: "expiry-digest-2027-04")
        #expect(taps.count == 1)
    }

    @Test func tappingAnythingElseDoesNothing() async {
        let taps = Taps()
        let delegate = ReminderNotificationDelegate(openExpiring: { taps.count += 1 })
        await delegate.handleTap(on: "something-else")
        await delegate.handleTap(on: "")
        #expect(taps.count == 0)
    }

    @Test func aDigestStillShowsAsABannerWhileTheAppIsOpen() {
        let options = ReminderNotificationDelegate.foregroundPresentation
        #expect(options.contains(.banner))
        #expect(options.contains(.list))
        #expect(options.contains(.sound))
        #expect(!options.contains(.badge), "no app-icon badge")
    }

    @Test func installingMakesItTheCentersDelegate() {
        let center = UNUserNotificationCenter.current()
        let previous = center.delegate
        defer { center.delegate = previous }
        let delegate = ReminderNotificationDelegate(openExpiring: {})
        delegate.install(on: center)
        #expect(center.delegate === delegate)
    }

    @Test func aTapRoutesToTheExpiringTabThroughTheRouter() async {
        let router = TabRouter()
        let delegate = ReminderNotificationDelegate(openExpiring: { router.select(.expiring) })
        #expect(router.selection == .cabinet)
        await delegate.handleTap(on: NotificationIDs.digestPrefix + "2027-04")
        #expect(router.selection == .expiring)
    }
}
