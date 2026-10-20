import MediCabinetCore
import SwiftUI
import Testing
import UIKit
@testable import MediCabinet

@Suite struct PillStyleTests {
    private func resolved(_ name: String, dark: Bool) throws -> UIColor {
        let traits = UITraitCollection(userInterfaceStyle: dark ? .dark : .light)
        let color = try #require(UIColor(named: name, in: .main, compatibleWith: traits), "no asset colour \(name)")
        return color.resolvedColor(with: traits)
    }

    /// WCAG 2 relative luminance of an sRGB colour.
    private func luminance(_ color: UIColor) -> Double {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        color.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        func linear(_ value: CGFloat) -> Double {
            let component = Double(value)
            return component <= 0.03928 ? component / 12.92 : pow((component + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
    }

    private func contrast(_ first: UIColor, _ second: UIColor) -> Double {
        let (high, low) = (max(luminance(first), luminance(second)), min(luminance(first), luminance(second)))
        return (high + 0.05) / (low + 0.05)
    }

    @Test(arguments: [ExpiryStatus.expired, .expiringSoon], [false, true])
    func alertColoursMeetWCAGAAInLightAndDark(status: ExpiryStatus, dark: Bool) throws {
        let names = try #require(PillStyle.assetNames(for: status))
        let ratio = contrast(try resolved(names.background, dark: dark), try resolved(names.foreground, dark: dark))
        #expect(ratio >= 4.5, "\(status) \(dark ? "dark" : "light"): contrast \(ratio)")
    }

    @Test func onlyAlertStatusesHaveAssetColours() {
        #expect(PillStyle.assetNames(for: .expired) != nil)
        #expect(PillStyle.assetNames(for: .expiringSoon) != nil)
        #expect(PillStyle.assetNames(for: .unknown) == nil)
        #expect(PillStyle.assetNames(for: .ok) == nil)
    }

    @Test func eachAlertStatusHasItsOwnIconAndOkHasNone() {
        let symbols = [ExpiryStatus.expired, .expiringSoon, .unknown].map { PillStyle.style(for: $0).symbol }
        #expect(symbols.allSatisfy { $0 != nil })
        #expect(Set(symbols.compactMap { $0 }).count == 3)
        #expect(PillStyle.style(for: .ok).symbol == nil)
    }

    @Test func theUnknownPillIsOutlinedAndTheOthersAreNot() {
        #expect(PillStyle.style(for: .unknown).outline != .clear)
        for status in [ExpiryStatus.expired, .expiringSoon, .ok] {
            #expect(PillStyle.style(for: status).outline == .clear)
        }
    }

    @Test func everyAlertColourExistsInTheAssetCatalog() throws {
        for status in [ExpiryStatus.expired, .expiringSoon] {
            let names = try #require(PillStyle.assetNames(for: status))
            #expect(UIColor(named: names.background) != nil)
            #expect(UIColor(named: names.foreground) != nil)
        }
    }
}
