import MediCabinetCore

/// Answers to the prompts of Add mode (completion, "another box?", put back) and the notice card.
extension ScanViewModel {
    func confirmCompletion(name: String?, expiry: ExpiryDate?) async {
        guard let current = completion else { return }
        var draft = current.draft
        if current.missing.contains(.name) { draft.name = name }
        if current.missing.contains(.expiry) { draft.expiry = expiry }
        completion = nil
        await commit(draft)
    }

    func cancelCompletion() {
        completion = nil
    }

    func confirmAnother() async {
        guard let pending = another else { return }
        another = nil
        if pending.thenMissing.isEmpty {
            await commit(pending.draft)
        } else {
            completion = Completion(draft: pending.draft, missing: pending.thenMissing, expiryUnreadable: pending.expiryUnreadable)
        }
    }

    func dismissAnother() {
        another = nil
    }

    func dismissNotice() {
        notice = nil
    }

    func putBack(_ box: Box) async {
        guard let location = sessionLocation else { return }
        do {
            let restored = try await intake.putBack(box, to: location.id)
            session.add(restored)
            show(.putBack(restored), feedback: .added)
        } catch {
            show(.failed, feedback: .rejected)
        }
    }
}
