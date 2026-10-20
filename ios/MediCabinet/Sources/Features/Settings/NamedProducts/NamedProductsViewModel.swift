import MediCabinetCore
import Observation
import SwiftUI

/// Presentation state of Settings → Products you named. Every change goes through the repository; the list follows
/// the repository's observation, so nothing here caches a stale copy. Names and codes are never logged.
@MainActor @Observable
final class NamedProductsViewModel {
    enum Phase: Equatable {
        case loading
        case loaded([NamedProductSummary])
        case failed
    }

    /// How a save ended; `invalid` leaves the form open with the problem shown.
    enum SaveOutcome: Equatable {
        case saved
        case invalid(NamedProductEditError)
        case failed
    }

    /// A forget waiting for the user's confirmation.
    struct ForgetRequest: Identifiable, Equatable {
        let summary: NamedProductSummary
        var id: NamedProduct.ID { summary.id }
    }

    private(set) var phase: Phase = .loading
    /// The product whose edit sheet is open.
    var editing: NamedProductSummary?
    var forgetRequest: ForgetRequest?
    /// A write failed and nothing was changed: the screen shows an alert.
    var changeFailed = false

    private let repository: any NamedProductRepository
    private let undo: UndoCenter?

    init(repository: any NamedProductRepository, undo: UndoCenter?) {
        self.repository = repository
        self.undo = undo
    }

    /// Follows the repository until the screen goes away.
    func observe() async {
        phase = .loading
        do {
            for try await summaries in repository.observeSummaries() {
                phase = .loaded(summaries)
            }
        } catch {
            guard !(error is CancellationError) else { return }
            Log.persistence.error("Observing the named products failed: \(String(describing: type(of: error)))")
            phase = .failed
        }
    }

    // MARK: Row text

    /// "3401234567890": the code as box detail shows it.
    func code(of product: NamedProduct) -> String {
        CabinetText.code(gtin: product.gtin)
    }

    /// The category the product gives its boxes: the one the user chose, else *Uncategorized*.
    func category(of product: NamedProduct) -> String {
        CategoryLabel.text(for: product.categoryOverridden ? product.category : .uncategorized)
    }

    /// "2 boxes in the cabinet" / "1 box in the cabinet" / "Not in the cabinet". `bundle` and `locale` let tests check
    /// each language.
    func boxCount(of summary: NamedProductSummary, bundle: Bundle = .main, locale: Locale = .current) -> String {
        guard summary.boxesInCabinet > 0 else {
            return bundle.localizedString(forKey: "namedProducts.notInCabinet", value: nil, table: nil)
        }
        return String(
            format: bundle.localizedString(forKey: "namedProducts.inCabinet", value: nil, table: nil),
            locale: locale, summary.boxesInCabinet)
    }

    // MARK: Edit

    func edit(_ summary: NamedProductSummary) {
        editing = summary
    }

    /// Writes what the form changed, in the repository's order: the name (the product and all its boxes), then the
    /// category. Nothing is written when nothing changed. An invalid name or category writes nothing.
    func save(_ edit: NamedProductEdit, of summary: NamedProductSummary) async -> SaveOutcome {
        let change: (name: String?, category: (CategoryID, Bool)?)
        do {
            change = try edit.validated()
        } catch {
            return .invalid(error)
        }
        let id = summary.id
        do {
            if let name = change.name { try await repository.rename(id: id, to: name) }
            if let (category, overridden) = change.category {
                try await repository.setCategory(id: id, category, overridden: overridden)
            }
            return .saved
        } catch {
            Log.persistence.error("A named product change failed: \(String(describing: type(of: error)))")
            changeFailed = true
            return .failed
        }
    }

    // MARK: Forget

    func requestForget(_ summary: NamedProductSummary) {
        forgetRequest = ForgetRequest(summary: summary)
    }

    func cancelForget() {
        forgetRequest = nil
    }

    func forgetTitle(for request: ForgetRequest, bundle: Bundle = .main) -> String {
        String(
            format: bundle.localizedString(forKey: "namedProducts.forget.title", value: nil, table: nil),
            request.summary.product.name)
    }

    /// Says how many boxes are still in the cabinet (they keep their name) and that the next scan asks for a name.
    func forgetMessage(for request: ForgetRequest, bundle: Bundle = .main, locale: Locale = .current) -> String {
        let count = request.summary.boxesInCabinet
        guard count > 0 else { return bundle.localizedString(forKey: "namedProducts.forget.message", value: nil, table: nil) }
        return String(
            format: bundle.localizedString(forKey: "namedProducts.forget.messageInCabinet", value: nil, table: nil),
            locale: locale, count)
    }

    /// Forgets the product (its boxes are untouched) and offers Undo. `request` is the one the dialog was showing: the
    /// dialog clears `forgetRequest` as it closes, so the button passes it along.
    func confirmForget(_ request: ForgetRequest? = nil) async {
        guard let request = request ?? forgetRequest else { return }
        forgetRequest = nil
        let product = request.summary.product
        let repository = repository
        do {
            try await repository.forget(id: product.id)
        } catch {
            Log.persistence.error("Forgetting a named product failed: \(String(describing: type(of: error)))")
            changeFailed = true
            return
        }
        let undo = undo
        undo?.show(String(format: String(localized: "namedProducts.forgotten"), product.name)) {
            do {
                try await repository.undoForget(id: product.id)
            } catch RepositoryError.duplicate {
                undo?.show(String(localized: "namedProducts.undoFailed"))
            } catch {
                Log.persistence.error("Restoring a named product failed: \(String(describing: type(of: error)))")
                undo?.show(String(localized: "box.error"))
            }
        }
    }
}
