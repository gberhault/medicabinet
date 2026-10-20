import MediCabinetCore

/// The end of a session when the whole cabinet was replaced (design §1.6).
extension ScanViewModel {
    /// After an import or a restore, the session's boxes and its location may no longer exist: the session ends
    /// (both lists, prompts, the summary) and the location falls back to the remembered one or the default (I10).
    func resetIfCabinetReplaced() async {
        guard revision.value != seenRevision else { return }
        seenRevision = revision.value
        closeSummary()
        session = ScanSession()
        removalSession = ScanSession()
        completion = nil
        candidates = nil
        showsSessionList = false
        await loadLocations(keepingCurrent: false)
    }
}
