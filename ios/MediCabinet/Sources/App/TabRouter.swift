import Observation

/// The tabs of the main screen.
enum AppTab: Hashable {
    case scan, cabinet, expiring, settings
}

/// Which tab is selected. The app opens on the Cabinet, not on the camera (which only starts once Scan is shown).
/// Owned by the app, so something outside the views can select a tab: the tap on the monthly
/// reminder opens the Expiring tab, even on a cold launch (the choice waits until the tabs appear).
@MainActor @Observable
final class TabRouter {
    var selection: AppTab

    init(selection: AppTab = .cabinet) {
        self.selection = selection
    }

    func select(_ tab: AppTab) {
        selection = tab
    }
}
