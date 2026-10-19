/// Something a box cannot be saved without, that the scan did not provide.
public enum IntakeField: String, Hashable, Sendable {
    case name
    case expiry
}

public enum IntakeDecision: Hashable, Sendable {
    /// Save the box now.
    case add
    /// Ask for what is missing (while the box is still in hand), then save.
    case complete(missing: Set<IntakeField>, expiryUnreadable: Bool)
    /// Same product, no serial, already added this session: ask "another box?" first.
    case confirmAnother(thenMissing: Set<IntakeField>, expiryUnreadable: Bool)
    case alreadyInCabinet
    case removedEarlier

    /// Platform-neutral name, shared with the Android app and `fixtures/scan-intake.json`.
    public var kind: String {
        switch self {
        case .add: "add"
        case .complete: "complete"
        case .confirmAnother: "confirmAnother"
        case .alreadyInCabinet: "alreadyInCabinet"
        case .removedEarlier: "removedEarlier"
        }
    }
}

public enum IntakePolicy {
    /// - Parameters:
    ///   - productKnown: the name is already resolved (reference data or an earlier box).
    ///   - cabinetMatch: a non-deleted box with the same GTIN and serial; only looked at when the code has a serial.
    ///   - sessionHasGTIN: a box with this GTIN was already added in the current scan session.
    public static func decide(
        code: ScannedCode, productKnown: Bool, cabinetMatch: Box?, sessionHasGTIN: Bool
    ) -> IntakeDecision {
        if code.serial != nil, let cabinetMatch {
            return cabinetMatch.isInCabinet ? .alreadyInCabinet : .removedEarlier
        }
        var missing: Set<IntakeField> = []
        if !productKnown { missing.insert(.name) }
        var expiryUnreadable = false
        switch code.expiry {
        case .date:
            break
        case .missing:
            missing.insert(.expiry)
        case .invalid:
            missing.insert(.expiry)
            expiryUnreadable = true
        }
        if code.serial == nil, sessionHasGTIN {
            return .confirmAnother(thenMissing: missing, expiryUnreadable: expiryUnreadable)
        }
        return missing.isEmpty ? .add : .complete(missing: missing, expiryUnreadable: expiryUnreadable)
    }
}
