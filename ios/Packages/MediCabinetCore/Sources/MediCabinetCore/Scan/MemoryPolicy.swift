/// A box save that may teach the app the name of its code.
public enum MemoryEvent: Sendable {
    /// A new box (scan, *confirm another*, *Add without scanning*).
    case added(Box)
    /// A box edit.
    case changed(before: Box, after: Box)
    /// A correction in the scan session list.
    case sessionCorrected(before: Box, after: Box)

    /// The box as saved.
    public var after: Box {
        switch self {
        case let .added(box): box
        case let .changed(_, after), let .sessionCorrected(_, after): after
        }
    }
}

public enum MemoryDecision: Equatable, Sendable {
    case none
    /// Create or update the live named product of the box's code with these values.
    case remember(name: String, category: CategoryID, overridden: Bool)
}

/// When a box save remembers its name and category as the named product of its code (#57).
public enum MemoryPolicy {
    /// `codeKnown`: `nil` = the reference is unavailable or the lookup failed, so nothing can be remembered.
    /// `current`: the live named product of the code, if any.
    public static func decide(_ event: MemoryEvent, codeKnown: Bool?, current: NamedProduct?) -> MemoryDecision {
        let after = event.after
        guard after.product.gtin != nil, codeKnown == false else { return .none }
        switch event {
        case .added:
            break
        case let .changed(before, after), let .sessionCorrected(before, after):
            // An expiry, a location or an "opened" change must neither write nor bring a forgotten product back.
            if before.product.name == after.product.name, before.category == after.category,
               before.categoryOverridden == after.categoryOverridden { return .none }
        }
        if let current, current.name == after.product.name, current.category == after.category,
           current.categoryOverridden == after.categoryOverridden { return .none }
        return .remember(name: after.product.name, category: after.category, overridden: after.categoryOverridden)
    }
}
