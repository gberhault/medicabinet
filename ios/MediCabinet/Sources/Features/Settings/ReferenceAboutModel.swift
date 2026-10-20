import Foundation
import MediCabinetCore
import Observation

/// What About shows about the bundled BDPM data: loading, its date, or that it is unavailable.
@MainActor
@Observable
final class ReferenceAboutModel {
    enum State: Equatable {
        case loading
        case available(ReferenceDataDate)
        case unavailable
    }

    private(set) var state: State = .loading

    /// Asks the source (opening the database if it was not yet); `nil` source means no environment, so unavailable.
    func load(from source: (any ReferenceDataSource)?) async {
        guard let source else {
            state = .unavailable
            return
        }
        switch await source.status() {
        case .available(let date): state = .available(date)
        case .unavailable: state = .unavailable
        }
    }

    /// The localized "Data as of <date>" line, once the date is known.
    func dateLine(bundle: Bundle = .main, locale: Locale = .current) -> String? {
        guard case .available(let date) = state else { return nil }
        let format = bundle.localizedString(forKey: "about.reference.date", value: nil, table: nil)
        return String(format: format, Self.text(for: date, locale: locale))
    }

    /// The date in the user's locale. It is a calendar day, not an instant: formatting at noon UTC in UTC means no
    /// time zone can move it to the day before or after.
    static func text(for date: ReferenceDataDate, locale: Locale) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .gmt
        let components = DateComponents(year: date.year, month: date.month, day: date.day, hour: 12)
        guard let instant = calendar.date(from: components) else {
            return String(format: "%04d-%02d-%02d", date.year, date.month, date.day)
        }
        return instant.formatted(Date.FormatStyle(date: .long, time: .omitted, locale: locale, calendar: calendar, timeZone: .gmt))
    }
}
