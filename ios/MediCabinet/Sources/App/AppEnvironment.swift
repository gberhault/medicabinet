import Foundation
import MediCabinetCore

/// Everything the UI layer needs, built once at launch. Views see repository protocols only.
struct AppEnvironment: Sendable {
    let locations: any LocationRepository
    let boxes: any BoxRepository
    /// What the household named for the codes the reference does not know (Settings → Products you named).
    let named: any NamedProductRepository
    let clock: any Clock
    /// The bundled BDPM reference data; opened lazily, never during launch.
    let reference: any ReferenceDataSource
    /// The label a category shows in the app's language: the Cabinet's category cards are ordered A–Z by it, so a
    /// test can inject labels without touching the string catalog.
    var categoryLabel: @Sendable (CategoryID) -> String = { CategoryLabel.text(for: $0) }
    /// The device preferences of expiry tracking: the "expiring soon" window and the reminder choices.
    var preferences: any ExpiryPreferences = InMemoryExpiryPreferences()
    /// What says "today" may have changed (midnight, time zone, clock).
    var todaySource: TodaySource = .live()
    /// The wall-clock zone that decides what "today" is; the device's, unless a test pins it.
    var timeZone: @Sendable () -> TimeZone = { TimeZone.current }
    /// Export, import and the automatic backups (design §6). Always set by `Bootstrap`; `nil` only in tests of
    /// screens that do not use it, and Settings then offers no transfer.
    var transfer: CabinetTransfer?
    /// Bumped when an import or a restore replaced the whole cabinet: what caches cabinet data reloads (I7).
    var revision = CabinetRevision()

    /// Resolves a scanned code to a name (reference, then the named product) and remembers the names typed.
    var productMemory: ProductMemory { ProductMemory(named: named, reference: reference) }

    /// How early a box counts as "expiring soon": the setting, read now (it can change while the app runs).
    var expiryWindow: ExpiryWindow { preferences.window }

    /// Today's calendar day, from the injected clock in the device time zone.
    func today() -> CalendarDay {
        CalendarDay.from(clock.now, timeZone: timeZone())
    }

    /// The status of an expiry date on `day`: the core rule with the "expiring soon" window. An unknown expiry
    /// is `.unknown`, never `.ok`.
    func expiryStatus(of expiry: ExpiryDate?, on day: CalendarDay, window: ExpiryWindow? = nil) -> ExpiryStatus {
        ExpiryRule.assess(expiry, today: day, window: window ?? expiryWindow).status
    }

    /// Seconds until the next local midnight, from the injected clock (at least 1).
    func secondsUntilMidnight() -> Int {
        let minutes = CalendarDay.minutesSinceMidnight(of: clock.now, timeZone: timeZone())
        return max(1, (24 * 60 - minutes) * 60)
    }
}
