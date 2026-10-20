import MediCabinetCore

/// Corrections of the boxes added in this session, and boxes typed by hand.
extension ScanViewModel {
    func delete(_ box: Box) async {
        do {
            try await intake.delete(box.id)
            session.remove(box.id)
            show(.deleted(box), feedback: nil)
        } catch {
            show(.failed, feedback: .rejected)
        }
    }

    func undoDelete(_ box: Box) async {
        do {
            try await intake.undoDelete(box.id)
            if let restored = try await boxes.box(id: box.id) { session.add(restored) }
            notice = nil
        } catch {
            show(.failed, feedback: .rejected)
        }
    }

    /// Saves an inline correction. The expiry can go back to unknown but is never guessed.
    func update(_ box: Box, name: String, expiry: ExpiryDate?, locationID: Location.ID) async -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (1...ScanIntake.maxNameLength).contains(trimmed.unicodeScalars.count) else { return false }
        var edited = box
        edited.product.name = trimmed
        edited.expiry = expiry
        edited.locationID = locationID
        do {
            try await boxes.update(edited)
            if let saved = try await boxes.box(id: box.id) {
                session.replace(saved)
                await remember(.sessionCorrected(before: box, after: saved))
            }
            return true
        } catch {
            show(.failed, feedback: .rejected)
            return false
        }
    }

    /// The box is saved: a failing memory write is logged by kind and never fails the correction.
    private func remember(_ event: MemoryEvent) async {
        do {
            try await memory.record(event)
        } catch {
            Log.memoryFailed(error)
        }
    }

    // MARK: Manual entry

    /// Adds a box typed by hand: no scanner involved, works without camera permission.
    func addManually(code: String, name: String, expiry: ExpiryDate?, locationID: Location.ID) async -> ManualError? {
        do {
            var draft = try await intake.manualDraft(code: code, name: name)
            draft.expiry = expiry
            let box = try await intake.add(draft, location: locationID)
            session.add(box)
            show(.added(box), feedback: .added)
            return nil
        } catch let error as IntakeError {
            return error == .invalidCode ? .invalidCode : .invalidName
        } catch let error as RepositoryError where error == .duplicateSerial {
            return .failed
        } catch {
            return .failed
        }
    }
}
