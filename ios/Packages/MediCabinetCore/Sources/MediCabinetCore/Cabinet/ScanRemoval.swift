import Foundation

public enum RemovalDecision: Hashable, Sendable {
    case remove(Box.ID)
    /// Several candidates, ordered: the first is the one the picker pre-selects.
    case choose([Box.ID])
    case alreadyRemoved(Box.ID)
    case notInCabinet

    /// Platform-neutral name, shared with the Android app and `fixtures/scan-removal.json`.
    public var kind: String {
        switch self {
        case .remove: "remove"
        case .choose: "choose"
        case .alreadyRemoved: "alreadyRemoved"
        case .notInCabinet: "notInCabinet"
        }
    }
}

/// What a scan in Remove mode does. Pure and fixture-driven (`fixtures/scan-removal.json`).
public enum RemovalPolicy {
    /// - Parameters:
    ///   - serialMatch: the non-deleted box (in the cabinet or removed) with the scan's GTIN and serial; `nil` when
    ///     the scan has no serial or no box matches.
    ///   - cabinet: the boxes in the cabinet with the scan's GTIN.
    public static func decide(code: ScannedCode, serialMatch: Box?, cabinet: [Box]) -> RemovalDecision {
        if code.serial != nil, let match = serialMatch {
            return match.isInCabinet ? .remove(match.id) : .alreadyRemoved(match.id)
        }
        let candidates = cabinet
            .filter { $0.isInCabinet && $0.product.gtin == code.gtin && (code.serial == nil || $0.serial == nil) }
            .sorted { isBefore($0, $1, scanExpiry: scanExpiry(of: code)) }
        switch candidates.count {
        case 0: return .notInCabinet
        case 1: return .remove(candidates[0].id)
        default: return .choose(candidates.map(\.id))
        }
    }

    private static func scanExpiry(of code: ScannedCode) -> ExpiryDate? {
        if case .date(let date) = code.expiry { return date }
        return nil
    }

    /// Same expiry as the scan first, then earliest expiry (unknown last), then creation order, then id.
    private static func isBefore(_ lhs: Box, _ rhs: Box, scanExpiry: ExpiryDate?) -> Bool {
        if let scanExpiry {
            let leftSame = lhs.expiry == scanExpiry
            let rightSame = rhs.expiry == scanExpiry
            if leftSame != rightSame { return leftSame }
        }
        switch (lhs.expiry?.sortKey, rhs.expiry?.sortKey) {
        case let (left?, right?) where left != right: return left < right
        case (nil, _?): return false
        case (_?, nil): return true
        default: break
        }
        if lhs.createdAt != rhs.createdAt { return lhs.createdAt < rhs.createdAt }
        return TextNormalizer.compare(lhs.id.uuidString.lowercased(), rhs.id.uuidString.lowercased()) < 0
    }
}

public enum RemovalOutcome: Sendable {
    /// Already removed (stock movement written); the box as stored.
    case removed(Box)
    /// Nothing written yet: the user picks one, then `remove(_:reason:)`.
    case choose([Box])
    case alreadyRemoved(Box)
    case notInCabinet
}

/// Removal by scanning: the same stock movement as the box detail's **Used up** / **Thrown away**, undoable.
public struct ScanRemoval: Sendable {
    private let boxes: any BoxRepository
    private let clock: any Clock

    public init(boxes: any BoxRepository, clock: any Clock) {
        self.boxes = boxes
        self.clock = clock
    }

    /// Decides, and when the decision is `.remove`, removes with `reason` at the clock's date.
    public func handle(_ code: ScannedCode, reason: RemovalReason) async throws -> RemovalOutcome {
        var serialMatch: Box?
        if let serial = code.serial {
            serialMatch = try await boxes.box(gtin: code.gtin, serial: serial)
        }
        // A serial match decides on its own: skip the cabinet query.
        let cabinet = serialMatch == nil ? try await boxes.cabinetBoxes(gtin: code.gtin) : []
        switch RemovalPolicy.decide(code: code, serialMatch: serialMatch, cabinet: cabinet) {
        case .remove(let id):
            return .removed(try await remove(id, reason: reason))
        case .choose(let ids):
            return .choose(ids.compactMap { id in cabinet.first { $0.id == id } })
        case .alreadyRemoved:
            return serialMatch.map(RemovalOutcome.alreadyRemoved) ?? .notInCabinet
        case .notInCabinet:
            return .notInCabinet
        }
    }

    /// Removes the box the user picked (or the one `handle` found). Throws `.notFound` when it is gone.
    public func remove(_ id: Box.ID, reason: RemovalReason) async throws -> Box {
        try await boxes.markRemoved(id: id, reason: reason, at: clock.now)
        guard let box = try await boxes.box(id: id) else { throw RepositoryError.notFound }
        return box
    }

    /// Puts the box back in the cabinet exactly as it was.
    public func undo(_ id: Box.ID) async throws {
        try await boxes.undoRemoval(id: id)
    }
}
