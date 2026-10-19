import Foundation

/// App identity shown in About. Behind a protocol so tests do not depend on the real bundle.
protocol AppInfo: Sendable {
    var version: String { get }
    var build: String { get }
}

struct BundleAppInfo: AppInfo {
    let version: String
    let build: String

    init(bundle: Bundle = .main) {
        version = bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        build = bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
    }
}
