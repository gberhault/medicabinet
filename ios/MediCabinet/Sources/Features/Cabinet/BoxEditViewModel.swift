import Foundation
import MediCabinetCore
import Observation

/// The state of the box edit form (B2, B4): name, expiry, location and category. Nothing is written until **Save**;
/// **Cancel** just drops this object. The expiry starts as the box has it: an unknown expiry stays unknown ("Date not
/// printed") and nothing is ever pre-filled.
@MainActor @Observable
final class BoxEditViewModel: Identifiable {
    let id = UUID()
    let box: Box
    let locations: [Location]
    var name: String
    var expiry: ExpiryChoice
    var locationID: Location.ID
    private(set) var edit: BoxEdit
    private(set) var isSaving = false
    /// A write or a lookup failed; nothing was changed.
    var failed = false

    private let boxes: any BoxRepository
    private let lookup: any ProductLookup
    private let memory: ProductMemory

    init(box: Box, locations: [Location], boxes: any BoxRepository, lookup: any ProductLookup, memory: ProductMemory) {
        self.box = box
        self.locations = locations
        self.boxes = boxes
        self.lookup = lookup
        self.memory = memory
        name = box.product.name
        expiry = ExpiryChoice(box.expiry, editing: true)
        locationID = box.locationID
        edit = BoxEdit(from: box)
    }

    // MARK: Name

    /// Why the name cannot be saved; `nil` when it can.
    var nameProblem: BoxEditError? {
        let trimmed = TextNormalizer.trimmed(name)
        if trimmed.isEmpty { return .emptyName }
        if trimmed.unicodeScalars.count > ScanIntake.maxNameLength { return .nameTooLong }
        return nil
    }

    /// Save needs a valid name and a decided expiry: a date, or "Date not printed" — never a blank.
    var canSave: Bool {
        nameProblem == nil && expiry.resolved != .pending && !isSaving
    }

    // MARK: Category (B4)

    /// The category the form shows: the pending change, else the box's own.
    var category: CategoryID {
        switch edit.categoryChange {
        case .chosen(let category), .automatic(let category): category
        case nil: box.category
        }
    }

    /// "Set by you": the category was picked by the user and the classifier will leave it alone.
    var isCategorySetByUser: Bool {
        switch edit.categoryChange {
        case .chosen: true
        case .automatic: false
        case nil: box.categoryOverridden
        }
    }

    func choose(_ category: CategoryID) {
        try? edit.chooseCategory(category)
    }

    /// **Automatic category**: the category the classifier gives this box (reference data by GTIN), else
    /// uncategorized. A failed lookup changes nothing.
    func resetToAutomatic() async {
        let reference: ReferenceProduct?
        do {
            if let gtin = box.product.gtin {
                reference = try await lookup.product(gtin: gtin)
            } else {
                reference = nil
            }
        } catch {
            Log.reference.error("Automatic category lookup failed: \(String(describing: type(of: error)))")
            failed = true
            return
        }
        edit.resetCategory(to: ProductResolver.resolve(reference: reference, named: nil, previousBox: nil).category)
    }

    // MARK: Save

    /// Writes the edit. Returns `true` when it was saved; on `false` nothing was changed.
    func save() async -> Bool {
        guard canSave else { return false }
        isSaving = true
        defer { isSaving = false }
        var applied = edit
        applied.name = name
        applied.expiry = expiry.expiry
        applied.locationID = locationID
        do {
            let edited = try applied.applied(to: box)
            try await boxes.update(edited)
            await remember(edited)
            return true
        } catch {
            Log.persistence.error("Saving a box edit failed: \(String(describing: type(of: error)))")
            failed = true
            return false
        }
    }

    /// The box is saved: a failing memory write is logged by kind and never fails the save.
    private func remember(_ edited: Box) async {
        do {
            try await memory.record(.changed(before: box, after: edited))
        } catch {
            Log.memoryFailed(error)
        }
    }
}
