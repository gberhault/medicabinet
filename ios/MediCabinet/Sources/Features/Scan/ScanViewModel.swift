import MediCabinetCore
import Observation
import SwiftUI

/// Presentation state of the Scan tab: camera lifecycle, the scan loop, prompts, the session. Persistence goes
/// through `ScanIntake` and the repositories; the camera and haptics through `ScanServices`.
@MainActor
@Observable
final class ScanViewModel {
    enum CameraState: Equatable {
        case needsPermission
        case denied(canOpenSettings: Bool)
        case unsupported
        case starting
        case scanning
        case failed
    }

    /// Something a box cannot be saved without: asked while the box is in hand. Cancel adds nothing.
    struct Completion: Equatable, Identifiable {
        let id = UUID()
        var draft: BoxDraft
        var missing: Set<IntakeField>
        var expiryUnreadable: Bool
    }

    /// "Another box of X?" — does not stop the camera; the next scan replaces it.
    struct Another: Equatable {
        var draft: BoxDraft
        var thenMissing: Set<IntakeField>
        var expiryUnreadable: Bool
    }

    enum Notice: Equatable {
        case added(Box)
        case alreadyInCabinet(Box, locationName: String)
        case removedEarlier(Box)
        case putBack(Box)
        case deleted(Box)
        /// Remove mode: the box is out of the cabinet (stock movement written), with Undo.
        case removed(Box, RemovalReason)
        case alreadyRemoved(Box)
        case notInCabinet
        case unrecognized
        case failed
    }

    /// Several boxes match a scan in Remove mode: the user picks one. Recognitions are ignored while it is open.
    struct Candidates: Equatable, Identifiable {
        let id = UUID()
        /// Ordered: the first is pre-selected.
        let boxes: [Box]
    }

    struct Summary: Equatable {
        var mode: ScanMode = .add
        let added: Int
        let unknownExpiry: Int
        var usedUp = 0
        var thrownAway = 0
    }

    enum ManualError: Equatable {
        case invalidCode
        case invalidName
        case failed
    }

    private(set) var cameraState: CameraState = .starting
    var session = ScanSession()
    var mode: ScanMode = .add
    var removalReason: RemovalReason
    /// The boxes taken out in the current Remove session (cleared by Done and by switching back to Add).
    var removalSession = ScanSession()
    var candidates: Candidates?
    private(set) var locations: [Location] = []
    private(set) var sessionLocation: Location?
    var notice: Notice?
    var another: Another?
    private(set) var scanner: (any CodeScanner)?
    private(set) var summary: Summary?
    /// Bumped by Retry and after the permission answer; the view restarts `run()` when it changes.
    private(set) var attempt = 0
    var completion: Completion?
    var showsSessionList = false
    var showsManualEntry = false
    var soundEnabled: Bool {
        didSet { services.preferences.soundEnabled = soundEnabled }
    }

    let intake: ScanIntake
    private let locationRepository: any LocationRepository
    let boxes: any BoxRepository
    let removal: ScanRemoval
    let services: ScanServices
    private var debouncer = ScanDebouncer()
    private var reportedUnrecognized = Set<String>()

    init(environment: AppEnvironment, services: ScanServices) {
        self.services = services
        intake = ScanIntake(boxes: environment.boxes, lookup: services.lookup, clock: environment.clock)
        removal = ScanRemoval(boxes: environment.boxes, clock: environment.clock)
        removalReason = services.preferences.removalReason
        locationRepository = environment.locations
        boxes = environment.boxes
        soundEnabled = services.preferences.soundEnabled
    }

    var showsSummary: Bool { summary != nil }

    // MARK: Camera lifecycle

    /// Opens the scanner and consumes it until the task is cancelled (tab left, app backgrounded). The camera runs
    /// only while this does.
    func run() async {
        await loadLocations()
        refreshAccess()
        guard cameraState == .starting else { return }
        let scanner = services.makeScanner()
        do {
            try scanner.start()
        } catch {
            switch error {
            case .unsupported: cameraState = .unsupported
            case .unavailable: refreshAccess()
            case .failedToStart: cameraState = .failed
            }
            return
        }
        self.scanner = scanner
        cameraState = .scanning
        defer {
            scanner.stop()
            self.scanner = nil
            if cameraState == .scanning { cameraState = .starting }
        }
        for await snapshot in scanner.snapshots {
            await handle(snapshot)
        }
    }

    func requestPermission() async {
        _ = await services.permission.request()
        attempt += 1
    }

    func openSettings() {
        services.permission.openSettings()
    }

    func retry() {
        cameraState = .starting
        attempt += 1
    }

    private func refreshAccess() {
        switch services.permission.access {
        case .notDetermined: cameraState = .needsPermission
        case .authorized: cameraState = .starting
        case .denied: cameraState = .denied(canOpenSettings: true)
        case .restricted: cameraState = .denied(canOpenSettings: false)
        }
    }

    // MARK: Locations

    func loadLocations() async {
        guard let loaded = try? await locationRepository.all() else { return }
        locations = loaded
        let preferred = services.preferences.lastLocationID.flatMap { id in loaded.first { $0.id == id } }
        let current = sessionLocation.flatMap { current in loaded.first { $0.id == current.id } }
        sessionLocation = current ?? preferred ?? loaded.first { $0.isDefault } ?? loaded.first
    }

    func selectLocation(_ id: Location.ID) {
        guard let location = locations.first(where: { $0.id == id }) else { return }
        sessionLocation = location
        services.preferences.lastLocationID = id
    }

    func locationName(_ id: Location.ID) -> String {
        locations.first { $0.id == id }?.name ?? ""
    }

    // MARK: Scanning

    func handle(_ snapshot: VisibleCodes) async {
        // Sightings always refresh the debouncer, even while a prompt is open, so a box still in front of the
        // camera is not counted again when the prompt closes.
        let scans = debouncer.observe(visible: snapshot.payloads, at: snapshot.instant)
        guard completion == nil, candidates == nil, !showsSummary else { return }
        for payload in scans where completion == nil && candidates == nil {
            await process(payload)
        }
    }

    private func process(_ payload: String) async {
        another = nil
        let code: ScannedCode
        do {
            code = try GS1Parser.parse(payload)
        } catch {
            if reportedUnrecognized.insert(payload).inserted { show(.unrecognized, feedback: .rejected) }
            return
        }
        if mode == .remove {
            await processRemoval(code)
            return
        }
        do {
            await apply(try await intake.evaluate(code, sessionGTINs: session.gtins))
        } catch {
            show(.failed, feedback: .rejected)
        }
    }

    private func apply(_ outcome: IntakeOutcome) async {
        switch outcome {
        case let .ready(draft):
            await commit(draft)
        case let .needsCompletion(draft, missing, unreadable):
            completion = Completion(draft: draft, missing: missing, expiryUnreadable: unreadable)
            play(.attention)
        case let .confirmAnother(draft, thenMissing, unreadable):
            another = Another(draft: draft, thenMissing: thenMissing, expiryUnreadable: unreadable)
            play(.attention)
        case let .alreadyInCabinet(box):
            show(.alreadyInCabinet(box, locationName: locationName(box.locationID)), feedback: .rejected)
        case let .removedEarlier(box):
            show(.removedEarlier(box), feedback: .rejected)
        }
    }

    /// Saves a draft in the session location, then confirms (card, haptic, optional sound, VoiceOver).
    func commit(_ draft: BoxDraft) async {
        guard let location = sessionLocation else {
            show(.failed, feedback: .rejected)
            return
        }
        do {
            let box = try await intake.add(draft, location: location.id)
            session.add(box)
            show(.added(box), feedback: .added)
            AccessibilityNotification.Announcement(ScanText.announcement(name: box.product.name, expiry: box.expiry)).post()
        } catch let error as RepositoryError where error == .duplicateSerial {
            await showDuplicate(of: draft)
        } catch {
            show(.failed, feedback: .rejected)
        }
    }

    private func showDuplicate(of draft: BoxDraft) async {
        if let gtin = draft.gtin, let serial = draft.serial, let existing = try? await boxes.box(gtin: gtin, serial: serial) {
            show(.alreadyInCabinet(existing, locationName: locationName(existing.locationID)), feedback: .rejected)
        } else {
            show(.failed, feedback: .rejected)
        }
    }

    // MARK: Done

    func finish() {
        let removed = removalSession.newestFirst
        summary = Summary(
            mode: mode,
            added: session.count,
            unknownExpiry: session.unknownExpiryCount,
            usedUp: removed.filter { $0.removal?.reason == .usedUp }.count,
            thrownAway: removed.filter { $0.removal?.reason == .thrownAway }.count
        )
    }

    func closeSummary() {
        if summary?.mode == .remove {
            removalSession = ScanSession()
        } else {
            session = ScanSession()
        }
        summary = nil
        debouncer = ScanDebouncer()
        reportedUnrecognized = []
        notice = nil
        another = nil
    }

    // MARK: Feedback

    func show(_ new: Notice, feedback event: FeedbackEvent?) {
        notice = new
        if let event { play(event) }
    }

    func play(_ event: FeedbackEvent) {
        services.feedback.play(event, sound: soundEnabled)
    }
}
