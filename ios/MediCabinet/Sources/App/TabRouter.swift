import Observation

/// The tabs of the main screen.
enum AppTab: Hashable {
    case scan, cabinet, expiring, settings
}

/// Which tab is selected. Owned by the app, so something outside the views can select a tab: the tap on the monthly
/// reminder opens the Expiring tab, even on a cold launch (the choice waits until the tabs appear).
@MainActor @Observable
final class TabRouter {
    var selection: AppTab

    init(selection: AppTab = .scan) {
        self.selection = selection
    }

    func select(_ tab: AppTab) {
        selection = tab
    }
}
