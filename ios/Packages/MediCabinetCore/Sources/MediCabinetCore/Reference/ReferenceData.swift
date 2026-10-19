/// The date of the bundled BDPM data, read from the reference database (`YYYY-MM-DD`, a real calendar day).
public struct ReferenceDataDate: Hashable, Sendable {
    public let year: Int
    public let month: Int
    public let day: Int

    public init?(iso: String) {
        let parts = iso.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              parts.allSatisfy({ $0.utf8.allSatisfy { (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0) } }),
              let year = Int(parts[0]), let month = Int(parts[1]), let day = Int(parts[2]),
              (try? ExpiryDate.make(year: year, month: month, day: day)) != nil else { return nil }
        self.year = year
        self.month = month
        self.day = day
    }
}

/// Why the reference data cannot be used. Raw values are shared with the Android app.
public enum ReferenceUnavailableReason: String, Hashable, Sendable {
    /// The bundled database is not there.
    case missing
    /// It could not be opened, failed its integrity check or has no valid data date.
    case unreadable
    /// Its schema version is not one this app version understands.
    case unsupportedSchema
}

public enum ReferenceStatus: Hashable, Sendable {
    case available(dataDate: ReferenceDataDate)
    case unavailable(ReferenceUnavailableReason)
}

/// The bundled reference data as the app sees it: product lookup plus the state shown in About. When it is
/// unavailable every product is unknown; the cabinet keeps working.
public protocol ReferenceDataSource: ProductLookup {
    func status() async -> ReferenceStatus
}
