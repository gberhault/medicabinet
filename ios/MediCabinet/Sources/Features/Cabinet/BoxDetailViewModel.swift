import MediCabinetCore
import Observation
import SwiftUI

/// Presentation state of a box's detail screen: the box and the locations follow the repositories; **Opened** saves
/// at once; taking a box out (used up, thrown away, added by mistake) writes, leaves the screen and offers Undo.
@MainActor @Observable
final class BoxDetailViewModel {
    let boxID: Box.ID
    private(set) var box: Box?
    private(set) var locations: [Location] = []
    /// The box is no longer in the cabinet (taken out here, or elsewhere): the screen goes away.
    private(set) var isGone = false
    /// A write failed and nothing was changed: the screen shows an alert.
    var actionFailed = false
    /// The "expiring soon" window in force; it follows Settings, so the status below is never stale.
    private(set) var window: ExpiryWindow
    /// The official BDPM page of the box's medicine, once its code has been looked up; `nil` while looking up and when
    /// there is none to show (no code, a code the reference does not know, a withdrawn presentation).
    private(set) var leaflet: URL?

    @ObservationIgnored private var leafletGTIN: String?
    @ObservationIgnored private var leafletLookup: Task<Void, Never>?
    private let environment: AppEnvironment
    private let undo: UndoCenter?

    init(boxID: Box.ID, environment: AppEnvironment, undo: UndoCenter?) {
        self.boxID = boxID
        self.environment = environment
        self.undo = undo
        window = environment.expiryWindow
    }

    /// Follows the box and the locations until the screen goes away.
    func observe() async {
        async let boxes: Void = followBox()
        async let locations: Void = followLocations()
        async let window: Void = followWindow()
        _ = await (boxes, locations, window)
        leafletLookup?.cancel()
    }

    private func followWindow() async {
        for await snapshot in environment.preferences.changes() {
            window = snapshot.window
        }
    }

    private func followBox() async {
        do {
            for try await cabinet in environment.boxes.observeCabinet() {
                box = cabinet.first { $0.id == boxID }
                isGone = box == nil
                refreshLeaflet(for: box?.product.gtin)
            }
        } catch {
            guard !(error is CancellationError) else { return }
            Log.persistence.error("Observing a box failed: \(String(describing: type(of: error)))")
            actionFailed = true
        }
    }

    private func followLocations() async {
        do {
            for try await current in environment.locations.observeAll() {
                locations = current
            }
        } catch {
            guard !(error is CancellationError) else { return }
            Log.persistence.error("Observing the locations failed: \(String(describing: type(of: error)))")
        }
    }

    /// Looks the code up again only when it changed: an edit of the box (same code) does not query the reference. The
    /// lookup runs off the main actor (the reference source is an actor) and never blocks the box's own observation.
    private func refreshLeaflet(for gtin: String?) {
        guard gtin != leafletGTIN else { return }
        leafletGTIN = gtin
        leafletLookup?.cancel()
        leaflet = nil
        guard let gtin else { return }
        let reference = environment.reference
        leafletLookup = Task { [weak self] in
            let product = try? await reference.product(gtin: gtin)
            guard !Task.isCancelled, let self else { return }
            self.leaflet = LeafletLink.address(for: product).flatMap { URL(string: $0) }
        }
    }

    /// The system refused to open the page: say so, with no Undo.
    func openLeafletFailed() {
        undo?.show(String(localized: "box.leaflet.openFailed"))
    }

    // MARK: What the screen shows

    var status: ExpiryStatus? {
        box.map { environment.expiryStatus(of: $0.expiry, on: environment.today(), window: window) }
    }

    var locationName: String {
        box.flatMap { box in locations.first { $0.id == box.locationID }?.name } ?? ""
    }

    // MARK: Changes

    func setOpened(_ opened: Bool) async {
        guard var edited = box, edited.isOpened != opened else { return }
        edited.isOpened = opened
        do {
            try await environment.boxes.update(edited)
            box = edited
        } catch {
            fail(error)
        }
    }

    /// Used up or thrown away: a stock movement dated now, then Undo.
    func takeOut(_ reason: RemovalReason) async {
        guard let box else { return }
        let boxes = environment.boxes
        let id = box.id
        do {
            try await boxes.markRemoved(id: id, reason: reason, at: environment.clock.now)
        } catch {
            fail(error)
            return
        }
        isGone = true
        let key = reason == .usedUp ? "undo.usedUp" : "undo.thrownAway"
        offerUndo(CabinetText.undoMessage(key, name: box.product.name)) { try await boxes.undoRemoval(id: id) }
    }

    /// Added by mistake: the box is deleted without being counted as used or thrown away, then Undo.
    func deleteAsMistake() async {
        guard let box else { return }
        let boxes = environment.boxes
        let id = box.id
        do {
            try await boxes.delete(id: id)
        } catch {
            fail(error)
            return
        }
        isGone = true
        offerUndo(CabinetText.undoMessage("undo.deleted", name: box.product.name)) { try await boxes.undoDelete(id: id) }
    }

    private func offerUndo(_ message: String, restore: @escaping @Sendable () async throws -> Void) {
        undo?.offerRestore(message, restore: restore)
    }

    func makeEditor() -> BoxEditViewModel? {
        guard let box else { return nil }
        return BoxEditViewModel(box: box, locations: locations, boxes: environment.boxes, lookup: environment.reference)
    }

    private func fail(_ error: any Error) {
        Log.persistence.error("A box change failed: \(String(describing: type(of: error)))")
        actionFailed = true
    }
}
