import Foundation

/// What will become a box. The completion step, the session list and manual entry edit it before `ScanIntake.add`.
public struct BoxDraft: Hashable, Sendable {
    public var gtin: String?
    /// `nil` = unknown, the app asks for it.
    public var name: String?
    public var category: CategoryID
    public var categoryOverridden: Bool
    /// `nil` = unknown. Only ever set from the code or typed by the user — never defaulted.
    public var expiry: ExpiryDate?
    public var lot: String?
    public var serial: String?

    public init(
        gtin: String? = nil, name: String? = nil, category: CategoryID = .uncategorized,
        categoryOverridden: Bool = false, expiry: ExpiryDate? = nil, lot: String? = nil, serial: String? = nil
    ) {
        self.gtin = gtin
        self.name = name
        self.category = category
        self.categoryOverridden = categoryOverridden
        self.expiry = expiry
        self.lot = lot
        self.serial = serial
    }
}

public enum IntakeOutcome: Hashable, Sendable {
    /// Add it now.
    case ready(BoxDraft)
    /// Ask for what is missing, then add.
    case needsCompletion(BoxDraft, missing: Set<IntakeField>, expiryUnreadable: Bool)
    /// Ask "another box?", then (when `thenMissing` is not empty) ask for what is missing, then add.
    case confirmAnother(BoxDraft, thenMissing: Set<IntakeField>, expiryUnreadable: Bool)
    case alreadyInCabinet(Box)
    case removedEarlier(Box)
}

public enum IntakeError: Error, Equatable, Sendable {
    /// The name is empty after trimming, or longer than `ScanIntake.maxNameLength`.
    case invalidName
    /// A typed code is not a valid EAN-13 / GTIN-14.
    case invalidCode

    /// Platform-neutral name, shared with the Android app.
    public var kind: String {
        switch self {
        case .invalidName: "invalidName"
        case .invalidCode: "invalidCode"
        }
    }
}

/// Turns scans and typed entries into boxes. Stateless: the scan session (GTINs added so far, the review
/// list) lives in the view model.
public struct ScanIntake: Sendable {
    public static let maxNameLength = 200

    private let boxes: any BoxRepository
    private let lookup: any ProductLookup
    private let clock: any Clock

    public init(boxes: any BoxRepository, lookup: any ProductLookup, clock: any Clock) {
        self.boxes = boxes
        self.lookup = lookup
        self.clock = clock
    }

    public func evaluate(_ code: ScannedCode, sessionGTINs: Set<String>) async throws -> IntakeOutcome {
        var match: Box?
        if let serial = code.serial { match = try await boxes.box(gtin: code.gtin, serial: serial) }
        let resolved = try await resolve(gtin: code.gtin)
        let draft = BoxDraft(
            gtin: code.gtin, name: resolved.name, category: resolved.category,
            categoryOverridden: resolved.categoryOverridden, expiry: Self.date(of: code.expiry),
            lot: code.lot, serial: code.serial
        )
        let decision = IntakePolicy.decide(
            code: code, productKnown: resolved.name != nil, cabinetMatch: match,
            sessionHasGTIN: sessionGTINs.contains(code.gtin)
        )
        switch decision {
        case .add:
            return .ready(draft)
        case let .complete(missing, unreadable):
            return .needsCompletion(draft, missing: missing, expiryUnreadable: unreadable)
        case let .confirmAnother(thenMissing, unreadable):
            return .confirmAnother(draft, thenMissing: thenMissing, expiryUnreadable: unreadable)
        case .alreadyInCabinet:
            return .alreadyInCabinet(try Self.matched(match))
        case .removedEarlier:
            return .removedEarlier(try Self.matched(match))
        }
    }

    /// A draft for a typed entry. `code` may be empty or `nil` (no code); a non-empty code must be a valid
    /// EAN-13 / GTIN-14. A typed `name` wins over a resolved one.
    public func manualDraft(code: String?, name: String?) async throws -> BoxDraft {
        let typedName = name?.trimmingCharacters(in: .whitespacesAndNewlines)
        let typedCode = code?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !typedCode.isEmpty else {
            return BoxDraft(name: typedName?.isEmpty == false ? typedName : nil)
        }
        guard let gtin = GTIN.normalized(typedCode) else { throw IntakeError.invalidCode }
        let resolved = try await resolve(gtin: gtin)
        return BoxDraft(
            gtin: gtin, name: typedName?.isEmpty == false ? typedName : resolved.name,
            category: resolved.category, categoryOverridden: resolved.categoryOverridden
        )
    }

    /// Validates the name (1…200 characters once trimmed), stamps the identity and the creation date and
    /// inserts. Throws `RepositoryError.duplicateSerial` when the same box is already in the cabinet.
    @discardableResult
    public func add(_ draft: BoxDraft, location: Location.ID) async throws -> Box {
        guard let name = draft.name?.trimmingCharacters(in: .whitespacesAndNewlines),
              (1...Self.maxNameLength).contains(name.unicodeScalars.count)
        else { throw IntakeError.invalidName }
        let box = Box(
            product: ProductRef(gtin: draft.gtin, name: name), category: draft.category,
            categoryOverridden: draft.categoryOverridden, locationID: location, expiry: draft.expiry,
            lot: draft.lot, serial: draft.serial, createdAt: clock.now
        )
        try await boxes.insert(box)
        return box
    }

    /// Undoes the removal of a box scanned again, and moves it to `location`.
    @discardableResult
    public func putBack(_ box: Box, to location: Location.ID) async throws -> Box {
        try await boxes.undoRemoval(id: box.id)
        guard var restored = try await boxes.box(id: box.id) else { throw RepositoryError.notFound }
        if restored.locationID != location {
            restored.locationID = location
            try await boxes.update(restored)
            restored = try await boxes.box(id: box.id) ?? restored
        }
        return restored
    }

    public func delete(_ id: Box.ID) async throws {
        try await boxes.delete(id: id)
    }

    public func undoDelete(_ id: Box.ID) async throws {
        try await boxes.undoDelete(id: id)
    }

    // MARK: -

    /// A failing reference lookup leaves the product unknown (the user types the name): intake never stops on it.
    private func resolve(gtin: String) async throws -> ResolvedProduct {
        let reference = try? await lookup.product(gtin: gtin)
        let previous = try await boxes.latestBox(gtin: gtin)
        return ProductResolver.resolve(reference: reference, previousBox: previous)
    }

    private static func date(of reading: ExpiryReading) -> ExpiryDate? {
        if case let .date(date) = reading { return date }
        return nil
    }

    private static func matched(_ match: Box?) throws -> Box {
        guard let match else { throw RepositoryError.notFound }
        return match
    }
}
