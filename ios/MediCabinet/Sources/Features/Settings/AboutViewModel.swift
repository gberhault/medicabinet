import Foundation

struct AboutViewModel {
    let info: any AppInfo
    /// Where strings are looked up; tests pass a `fr.lproj` / `en.lproj` bundle to check each language.
    var bundle: Bundle = .main

    var versionLine: String {
        String(format: bundle.localizedString(forKey: "about.version", value: nil, table: nil), info.version, info.build)
    }
}
