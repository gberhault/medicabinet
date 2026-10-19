import MediCabinetCore
import SwiftUI
import UIKit
import VisionKit

/// Continuous GS1 DataMatrix / EAN-13 recognition on VisionKit's scanner. Recognition runs in the system
/// scanner, off our main thread; we only forward the payloads of the codes in view. Never logs a payload.
@MainActor
final class VisionKitScanner: NSObject, CodeScanner, DataScannerViewControllerDelegate {
    let snapshots: AsyncStream<VisibleCodes>
    private let continuation: AsyncStream<VisibleCodes>.Continuation
    private let controller: DataScannerViewController
    private let clock: any Clock

    init(clock: any Clock) {
        self.clock = clock
        controller = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.dataMatrix, .ean13])],
            qualityLevel: .balanced,
            recognizesMultipleItems: true,
            isHighFrameRateTrackingEnabled: true,
            isPinchToZoomEnabled: true,
            isGuidanceEnabled: false,
            isHighlightingEnabled: true
        )
        (snapshots, continuation) = AsyncStream.makeStream(of: VisibleCodes.self, bufferingPolicy: .bufferingNewest(1))
        super.init()
        controller.delegate = self
    }

    var preview: AnyView { AnyView(VisionKitPreview(controller: controller)) }

    func start() throws(CodeScannerError) {
        guard DataScannerViewController.isSupported else { throw .unsupported }
        guard DataScannerViewController.isAvailable else { throw .unavailable }
        do {
            try controller.startScanning()
        } catch {
            throw .failedToStart
        }
    }

    func stop() {
        controller.stopScanning()
    }

    // MARK: DataScannerViewControllerDelegate

    func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
        publish(allItems)
    }

    func dataScanner(_ dataScanner: DataScannerViewController, didUpdate updatedItems: [RecognizedItem], allItems: [RecognizedItem]) {
        publish(allItems)
    }

    func dataScanner(_ dataScanner: DataScannerViewController, didRemove removedItems: [RecognizedItem], allItems: [RecognizedItem]) {
        publish(allItems)
    }

    private func publish(_ items: [RecognizedItem]) {
        let payloads = items.compactMap { item -> String? in
            if case let .barcode(barcode) = item { return barcode.payloadStringValue }
            return nil
        }
        continuation.yield(VisibleCodes(payloads: payloads, instant: clock.now))
    }
}

private struct VisionKitPreview: UIViewControllerRepresentable {
    let controller: DataScannerViewController

    func makeUIViewController(context: Context) -> DataScannerViewController { controller }

    func updateUIViewController(_ uiViewController: DataScannerViewController, context: Context) {}
}
