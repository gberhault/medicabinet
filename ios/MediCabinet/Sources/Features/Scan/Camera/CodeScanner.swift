import Foundation
import SwiftUI

/// The codes in view at one instant, as the scanner reported them.
struct VisibleCodes: Sendable, Equatable {
    let payloads: [String]
    let instant: Date
}

enum CodeScannerError: Error, Equatable {
    /// The device cannot scan codes (no camera, or no scanner hardware support).
    case unsupported
    /// Scanning is not available right now (camera permission missing or restricted).
    case unavailable
    case failedToStart
}

/// The camera behind a protocol, so UI tests inject payloads without a camera. Only the adapters in this
/// folder know VisionKit.
@MainActor
protocol CodeScanner: AnyObject {
    /// One snapshot per change of the codes in view (the newest one wins when the consumer is slow).
    var snapshots: AsyncStream<VisibleCodes> { get }
    /// The live camera view.
    var preview: AnyView { get }
    func start() throws(CodeScannerError)
    /// Stops the camera; the scanner can be started again.
    func stop()
}

/// A scanner for devices that cannot scan: starting it fails with `.unsupported`.
@MainActor
final class UnsupportedCodeScanner: CodeScanner {
    let snapshots = AsyncStream<VisibleCodes> { $0.finish() }
    var preview: AnyView { AnyView(Color.black) }

    func start() throws(CodeScannerError) {
        throw .unsupported
    }

    func stop() {}
}
