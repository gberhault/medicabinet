import Foundation

/// UI preferences of the scanner. Not cabinet data: never exported, never part of the cabinet backup.
@MainActor
protocol ScanPreferences: AnyObject {
    /// The location of the last scan session, preselected next time while it still exists.
    var lastLocationID: UUID? { get set }
    /// Off by default.
    var soundEnabled: Bool { get set }
}

@MainActor
final class UserDefaultsScanPreferences: ScanPreferences {
    static let locationKey = "scan.lastLocationID"
    static let soundKey = "scan.soundEnabled"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var lastLocationID: UUID? {
        get { defaults.string(forKey: Self.locationKey).flatMap(UUID.init(uuidString:)) }
        set { defaults.set(newValue?.uuidString, forKey: Self.locationKey) }
    }

    var soundEnabled: Bool {
        get { defaults.bool(forKey: Self.soundKey) }
        set { defaults.set(newValue, forKey: Self.soundKey) }
    }
}

@MainActor
final class InMemoryScanPreferences: ScanPreferences {
    var lastLocationID: UUID?
    var soundEnabled = false
}
