import Foundation
import MediCabinetCore

/// The device-facing parts of the scan screen, injected so tests and UI tests run without a camera.
@MainActor
struct ScanServices {
    let permission: any CameraPermission
    let makeScanner: @MainActor () -> any CodeScanner
    let preferences: any ScanPreferences
    let feedback: any ScanFeedback
    let lookup: any ReferenceDataSource

    /// UI tests: `-UITestCameraAccess <notDetermined|authorized|denied|restricted|unsupported>`, and the payloads
    /// to replay in `MEDICABINET_UI_TEST_SCANS` (see `InjectedCodeScanner`).
    static let cameraAccessArgument = "-UITestCameraAccess"
    /// UI tests: products the reference lookup knows, as `gtin=Name|gtin=Name` (otherwise every product is unknown).
    static let productsKey = "MEDICABINET_UI_TEST_PRODUCTS"

    /// `lookup` is the bundled BDPM reference data (`AppEnvironment.reference`); UI tests replace it.
    static func live(clock: any Clock, lookup: any ReferenceDataSource) -> ScanServices {
        make(
            clock: clock, arguments: ProcessInfo.processInfo.arguments, environment: ProcessInfo.processInfo.environment,
            lookup: lookup
        )
    }

    static func make(
        clock: any Clock, arguments: [String], environment: [String: String],
        lookup: any ReferenceDataSource = UnknownProductLookup()
    ) -> ScanServices {
        guard let index = arguments.firstIndex(of: cameraAccessArgument), index + 1 < arguments.count else {
            return ScanServices(
                permission: SystemCameraPermission(),
                makeScanner: { VisionKitScanner.make(clock: clock) },
                preferences: UserDefaultsScanPreferences(),
                feedback: SystemScanFeedback(),
                lookup: lookup
            )
        }
        let requested = arguments[index + 1]
        let access: CameraAccess = switch requested {
        case "notDetermined": .notDetermined
        case "denied": .denied
        case "restricted": .restricted
        default: .authorized
        }
        let startError: CodeScannerError? = requested == "unsupported" ? .unsupported : nil
        return ScanServices(
            permission: FixedCameraPermission(access: access),
            makeScanner: {
                InjectedCodeScanner.fromEnvironment(environment, clock: clock, startError: startError)
                    ?? InjectedCodeScanner(script: [], startError: startError, clock: clock)
            },
            preferences: InMemoryScanPreferences(),
            feedback: SystemScanFeedback(),
            lookup: products(from: environment[productsKey])
        )
    }

    private static func products(from text: String?) -> any ReferenceDataSource {
        guard let text else { return UnknownProductLookup() }
        var known: [String: ReferenceProduct] = [:]
        for pair in text.split(separator: "|") {
            let parts = pair.split(separator: "=", maxSplits: 1).map(String.init)
            if parts.count == 2 { known[parts[0]] = ReferenceProduct(name: parts[1]) }
        }
        return FixedProductLookup(products: known)
    }
}
