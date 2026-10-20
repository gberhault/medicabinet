/// A notification center that lives in memory, for unit tests and for UI tests (`-UITestNotifications`), so neither
/// shows a system prompt nor posts anything.
actor InMemoryNotificationClient: NotificationClient {
    private(set) var status: NotificationAuthorization
    /// What the user answers the system prompt.
    private let grantsOnRequest: Bool
    private(set) var requestCount = 0
    private(set) var pending: [String: DigestNotification] = [:]
    /// Every request ever scheduled, in order (a replaced one stays here).
    private(set) var scheduledHistory: [DigestNotification] = []
    private var failure: (any Error)?
    private var requestFailure: (any Error)?

    init(status: NotificationAuthorization = .notDetermined, grantsOnRequest: Bool = true) {
        self.status = status
        self.grantsOnRequest = grantsOnRequest
    }

    struct Failure: Error {}

    /// The next `schedule` calls throw (a full system queue, say).
    func failScheduling(_ enabled: Bool = true) {
        failure = enabled ? Failure() : nil
    }

    /// The next permission requests throw (the prompt could not be shown).
    func failAuthorizationRequests(_ enabled: Bool = true) {
        requestFailure = enabled ? Failure() : nil
    }

    /// The user changes the permission in the system settings.
    func setStatus(_ status: NotificationAuthorization) {
        self.status = status
    }

    var pendingList: [DigestNotification] {
        pending.values.sorted { $0.id < $1.id }
    }

    func authorization() async -> NotificationAuthorization { status }

    func requestAuthorization() async throws -> Bool {
        requestCount += 1
        if let requestFailure { throw requestFailure }
        // The system asks only once: a later request answers with the current permission.
        if status == .notDetermined { status = grantsOnRequest ? .authorized : .denied }
        return status == .authorized
    }

    func pendingDigestIDs() async -> [String] {
        pending.keys.filter { $0.hasPrefix(NotificationIDs.digestPrefix) }.sorted()
    }

    func schedule(_ digest: DigestNotification) async throws {
        if let failure { throw failure }
        pending[digest.id] = digest
        scheduledHistory.append(digest)
    }

    func removePending(ids: [String]) async {
        for id in ids { pending[id] = nil }
    }
}
