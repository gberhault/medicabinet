import MediCabinetCore
import SwiftUI
import UIKit
import VisionKit

/// Continuous GS1 DataMatrix / EAN-13 recognition on VisionKit's scanner. Recognition runs in the system
/// scanner, off our main thread; we only forward the payloads of the codes in view. Never logs a payload.
@MainActor
final class VisionKitScanner: NSObject, CodeScanner, DataScannerViewControllerDelegate {
    /// The scanner needs hardware support (not the simulator); never create one when this is false.
    static var isSupported: Bool { DataScannerViewController.isSupported }

    /// The real scanner when the hardware supports it, else one that reports `.unsupported`.
    static func make(clock: any Clock) -> any CodeScanner {
        isSupported ? VisionKitScanner(clock: clock) : UnsupportedCodeScanner()
    }

    let snapshots: AsyncStream<VisibleCodes>
    private let continuation: AsyncStream<VisibleCodes>.Continuation
    private let controller: DataScannerViewController
    private let clock: any Clock
    private var inView: [String] = []
    private var refresh: Task<Void, Never>?
    /// A held box stops generating updates; republishing keeps its sighting fresh for the debouncer.
    private static let refreshInterval = Duration.milliseconds(200)

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
        refresh = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: Self.refreshInterval)
                self?.republish()
            }
        }
    }

    func stop() {
        refresh?.cancel()
        refresh = nil
        inView = []
        controller.stopScanning()
    }

    private func republish() {
        guard !inView.isEmpty else { return }
        continuation.yield(VisibleCodes(payloads: inView, instant: clock.now))
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
        inView = payloads
        continuation.yield(VisibleCodes(payloads: payloads, instant: clock.now))
    }
}

private struct VisionKitPreview: UIViewControllerRepresentable {
    let controller: DataScannerViewController

    func makeUIViewController(context: Context) -> DataScannerViewController { controller }

    func updateUIViewController(_ uiViewController: DataScannerViewController, context: Context) {}
}
