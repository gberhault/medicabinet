import SwiftUI
import UIKit

/// The system share sheet for one file (E5): Save to Files, AirDrop, Mail… Presented by UIKit over whatever is on
/// screen, because its completion is the only reliable signal that the share ended (by any means, cancel included),
/// which is when the temporary file is deleted (E6).
@MainActor
enum SharePresenter {
    static func present(_ url: URL, onComplete: @escaping @MainActor (_ activity: String?, _ completed: Bool) -> Void) {
        let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        controller.completionWithItemsHandler = { activity, completed, _, _ in
            MainActor.assumeIsolated { onComplete(activity?.rawValue, completed) }
        }
        guard let presenter = topViewController() else {
            onComplete(nil, false)
            return
        }
        // An iPad shows the sheet as a popover; this app is iPhone-only, but never let it crash.
        controller.popoverPresentationController?.sourceView = presenter.view
        presenter.present(controller, animated: true)
    }

    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first { $0.isKeyWindow } ?? scenes.first?.windows.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
