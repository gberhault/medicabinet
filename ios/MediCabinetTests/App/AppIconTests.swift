import Foundation
import Testing
@testable import MediCabinet

/// The app ships its own icon set (#46): the build must declare `AppIcon` as the primary icon.
struct AppIconTests {
    @Test func theBuiltAppDeclaresAppIconAsItsPrimaryIcon() throws {
        let info = try #require(Bundle.main.infoDictionary)
        let icons = try #require(info["CFBundleIcons"] as? [String: Any], "no CFBundleIcons: the icon set is empty")
        let primary = try #require(icons["CFBundlePrimaryIcon"] as? [String: Any])
        #expect(primary["CFBundleIconName"] as? String == "AppIcon")
    }
}
