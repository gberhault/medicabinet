public enum BoxEditError: Error, Equatable, Sendable {
    /// The name is empty once trimmed.
    case emptyName
    /// The name is longer than `ScanIntake.maxNameLength` code points.
    case nameTooLong

    /// Platform-neutral name, shared with the Android app.
    public var kind: String {
        switch self {
        case .emptyName: "emptyName"
        case .nameTooLong: "nameTooLong"
        }
    }
}

/// What the box edit form can change: the name, the expiry and the location. The code (GTIN), lot and serial
/// are what the box says and stay as scanned; the category has its own control (taxonomy, #10).
public struct BoxEdit: Hashable, Sendable {
    public var name: String
    /// `nil` = unknown. Set only by an explicit choice (**Date not printed**), never filled in or guessed.
    public var expiry: ExpiryDate?
    public var locationID: Location.ID

    public init(name: String, expiry: ExpiryDate?, locationID: Location.ID) {
        self.name = name
        self.expiry = expiry
        self.locationID = locationID
    }

    /// The form's starting point: the box as it is, an unknown expiry staying unknown.
    public init(from box: Box) {
        self.init(name: box.product.name, expiry: box.expiry, locationID: box.locationID)
    }

    /// The box with the edit applied. `updatedAt` is left to the repository, which stamps it on `update`.
    public func applied(to box: Box) throws(BoxEditError) -> Box {
        let trimmed = TextNormalizer.trimmed(name)
        guard !trimmed.isEmpty else { throw .emptyName }
        guard trimmed.unicodeScalars.count <= ScanIntake.maxNameLength else { throw .nameTooLong }
        var edited = box
        edited.product.name = trimmed
        edited.expiry = expiry
        edited.locationID = locationID
        return edited
    }
}
