import Foundation
import MediCabinetCore

/// Everything the UI layer needs, built once at launch. Views see repository protocols only.
struct AppEnvironment: Sendable {
    let locations: any LocationRepository
    let boxes: any BoxRepository
    let clock: any Clock
    /// The bundled BDPM reference data; opened lazily, never during launch.
    let reference: any ReferenceDataSource
    /// The taxonomy's order: the Cabinet list's category sections follow it, `uncategorized` last.
    var categoryOrder: [CategoryID] = Taxonomy.ordered
    /// How early a box counts as "expiring soon" (the setting is #23's; its default until then).
    var expiryWindow: ExpiryWindow = .default
    /// The wall-clock zone that decides what "today" is; the device's, unless a test pins it.
    var timeZone: @Sendable () -> TimeZone = { TimeZone.current }

    /// Today's calendar day, from the injected clock in the device time zone.
    func today() -> CalendarDay {
        CalendarDay.from(clock.now, timeZone: timeZone())
    }

    /// The status of an expiry date on `day`: the core rule with the "expiring soon" window. An unknown expiry
    /// is `.unknown`, never `.ok`.
    func expiryStatus(of expiry: ExpiryDate?, on day: CalendarDay) -> ExpiryStatus {
        ExpiryRule.assess(expiry, today: day, window: expiryWindow).status
    }

    /// Seconds until the next local midnight, from the injected clock (at least 1).
    func secondsUntilMidnight() -> Int {
        let minutes = CalendarDay.minutesSinceMidnight(of: clock.now, timeZone: timeZone())
        return max(1, (24 * 60 - minutes) * 60)
    }
}
