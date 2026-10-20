import AudioToolbox
import UIKit

enum FeedbackEvent: Equatable {
    /// A box was added.
    case added
    /// A box was taken out of the cabinet (Remove mode): a firmer, different haptic than an addition.
    case removed
    /// A scan was refused or failed (duplicate, unreadable code, error).
    case rejected
    /// The user is asked something (completion, another box).
    case attention
}

@MainActor
protocol ScanFeedback {
    func play(_ event: FeedbackEvent, sound: Bool)
}

/// Haptics and the optional confirmation tone (which follows the silent switch).
@MainActor
final class SystemScanFeedback: ScanFeedback {
    private let haptics = UINotificationFeedbackGenerator()
    private let removalHaptics = UIImpactFeedbackGenerator(style: .rigid)
    private var soundID: SystemSoundID = 0

    init(bundle: Bundle = .main) {
        if let url = bundle.url(forResource: "scan_confirm", withExtension: "wav") {
            AudioServicesCreateSystemSoundID(url as CFURL, &soundID)
        }
    }

    func play(_ event: FeedbackEvent, sound: Bool) {
        switch event {
        case .added: haptics.notificationOccurred(.success)
        case .rejected: haptics.notificationOccurred(.error)
        case .attention: haptics.notificationOccurred(.warning)
        case .removed: removalHaptics.impactOccurred()
        }
        if sound, event == .added || event == .removed, soundID != 0 { AudioServicesPlaySystemSound(soundID) }
    }
}
