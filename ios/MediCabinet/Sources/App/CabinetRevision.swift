import Observation

/// "The whole cabinet was replaced" (design §1.6). The repositories are observed, so lists follow any change by
/// themselves; this signal is for what holds cabinet data of its own, like the scan session, whose review list points
/// at boxes that no longer exist after an import or a restore.
@MainActor
@Observable
final class CabinetRevision {
    private(set) var value = 0

    /// Built off the main actor by `Bootstrap`.
    nonisolated init() {}

    /// Called once a replace has committed.
    func bump() {
        value += 1
    }
}
