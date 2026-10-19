#if DEBUG
import MediCabinetCore
import SwiftUI

/// Debug builds only: lists the *shape* of each payload the camera sees (prefix, AIs, field lengths,
/// separators), never a lot or serial value, so real boxes can be turned into anonymised fixture cases.
@MainActor
@Observable
final class PayloadCaptureModel {
    private(set) var shapes: [String] = []
    private(set) var error: CodeScannerError?
    private static let limit = 50

    func run(_ scanner: any CodeScanner) async {
        do {
            try scanner.start()
        } catch {
            self.error = error
            return
        }
        for await snapshot in scanner.snapshots {
            for payload in snapshot.payloads { record(GS1Parser.shape(of: payload)) }
        }
        scanner.stop()
    }

    func record(_ shape: String) {
        guard shapes.first != shape else { return }
        shapes.insert(shape, at: 0)
        if shapes.count > Self.limit { shapes.removeLast() }
    }
}

struct PayloadCaptureView: View {
    @State private var model = PayloadCaptureModel()
    @State private var scanner: (any CodeScanner)?

    var body: some View {
        VStack(spacing: 0) {
            if let scanner {
                scanner.preview.frame(height: 260)
            }
            if let error = model.error {
                Text(verbatim: "Scanner: \(String(describing: error))").padding()
            }
            List(model.shapes, id: \.self) { shape in
                Text(verbatim: shape).font(.system(.footnote, design: .monospaced))
            }
        }
        .navigationTitle(Text(verbatim: "Payload shapes"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let created = VisionKitScanner.make(clock: SystemClock())
            scanner = created
            await model.run(created)
        }
    }
}
#endif
