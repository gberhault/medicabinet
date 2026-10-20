import AVFoundation
import UIKit

enum CameraAccess: Equatable, Sendable {
    case notDetermined
    case authorized
    case denied
    case restricted
}

@MainActor
protocol CameraPermission {
    var access: CameraAccess { get }
    /// Shows the system prompt (only when not yet decided) and returns whether access is now granted.
    func request() async -> Bool
    /// Opens the app's page in Settings, where the user can allow the camera.
    func openSettings()
}

struct SystemCameraPermission: CameraPermission {
    var access: CameraAccess {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: .authorized
        case .notDetermined: .notDetermined
        case .restricted: .restricted
        default: .denied
        }
    }

    func request() async -> Bool {
        await AVCaptureDevice.requestAccess(for: .video)
    }

    func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

/// UI tests and previews: a fixed answer, no system prompt.
@MainActor
final class FixedCameraPermission: CameraPermission {
    private(set) var access: CameraAccess
    private let grantsOnRequest: Bool

    init(access: CameraAccess, grantsOnRequest: Bool = true) {
        self.access = access
        self.grantsOnRequest = grantsOnRequest
    }

    func request() async -> Bool {
        if access == .notDetermined { access = grantsOnRequest ? .authorized : .denied }
        return access == .authorized
    }

    func openSettings() {}
}
