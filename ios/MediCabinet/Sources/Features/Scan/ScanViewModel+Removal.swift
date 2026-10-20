import MediCabinetCore
import SwiftUI

/// Remove mode (requirements R1-R9): the same camera, debouncer and lifecycle, but a recognised code takes a box out.
extension ScanViewModel {
    /// The tab was opened: Remove is never remembered (R1).
    func resetMode() {
        guard mode != .add || candidates != nil else { return }
        setMode(.add, announce: false)
    }

    func setMode(_ new: ScanMode, announce: Bool = true) {
        guard new != mode || candidates != nil else { return }
        let changed = new != mode
        mode = new
        candidates = nil
        another = nil
        notice = nil
        // Leaving Remove ends the Remove session; what was removed stays removed.
        if new == .add { removalSession = ScanSession() }
        if changed && announce {
            let key = new == .remove ? "scan.mode.announcement.remove" : "scan.mode.announcement.add"
            AccessibilityNotification.Announcement(ScanText.format(key)).post()
        }
    }

    func setRemovalReason(_ reason: RemovalReason) {
        removalReason = reason
        services.preferences.removalReason = reason
    }

    func processRemoval(_ code: ScannedCode) async {
        do {
            switch try await removal.handle(code, reason: removalReason) {
            case let .removed(box):
                confirmRemoval(box)
            case let .choose(found):
                candidates = Candidates(boxes: found)
                play(.attention)
            case let .alreadyRemoved(box):
                show(.alreadyRemoved(box), feedback: .rejected)
            case .notInCabinet:
                show(.notInCabinet, feedback: .rejected)
            }
        } catch {
            show(.failed, feedback: .rejected)
        }
    }

    /// The user picked one of the candidates.
    func removeCandidate(_ id: Box.ID) async {
        candidates = nil
        do {
            confirmRemoval(try await removal.remove(id, reason: removalReason))
        } catch {
            show(.failed, feedback: .rejected)
        }
    }

    func cancelCandidates() {
        candidates = nil
    }

    func undoRemoval(_ box: Box) async {
        do {
            try await removal.undo(box.id)
            removalSession.remove(box.id)
            notice = nil
        } catch {
            show(.failed, feedback: .rejected)
        }
    }

    private func confirmRemoval(_ box: Box) {
        let reason = box.removal?.reason ?? removalReason
        removalSession.add(box)
        show(.removed(box, reason), feedback: .removed)
        AccessibilityNotification.Announcement(
            ScanText.format("scan.removal.announcement", box.product.name)
        ).post()
    }
}
