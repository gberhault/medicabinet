import MediCabinetCore
import SwiftUI

struct ScanView: View {
    @Bindable var model: ScanViewModel
    @Environment(\.scenePhase) private var scenePhase

    /// Restarts the scanner when it is retried, and stops it when the app leaves the foreground.
    private struct Lifecycle: Hashable {
        let attempt: Int
        let active: Bool
    }

    var body: some View {
        content
            .onAppear { model.resetMode() }
            .task(id: Lifecycle(attempt: model.attempt, active: scenePhase == .active)) {
                guard scenePhase == .active else { return }
                await model.run()
            }
            .sheet(item: $model.completion) { completion in
                CompletionSheet(
                    completion: completion,
                    onConfirm: { name, expiry in Task { await model.confirmCompletion(name: name, expiry: expiry) } },
                    onCancel: { model.cancelCompletion() }
                )
            }
            .sheet(item: $model.candidates) { candidates in
                RemovalCandidatesSheet(candidates: candidates, model: model)
            }
            .sheet(isPresented: $model.showsSessionList) { SessionListView(model: model) }
            .sheet(isPresented: $model.showsManualEntry) { ManualEntryView(model: model) }
            .sheet(isPresented: Binding(get: { model.showsSummary }, set: { if !$0 { model.closeSummary() } })) {
                ScanSummaryView(model: model)
            }
    }

    @ViewBuilder private var content: some View {
        switch model.cameraState {
        case .needsPermission:
            status(.needsPermission)
        case let .denied(canOpenSettings):
            status(.denied(canOpenSettings: canOpenSettings))
        case .unsupported:
            status(.unsupported)
        case .failed:
            status(.failed)
        case .starting:
            ProgressView { Text("scan.camera.starting") }
                .accessibilityIdentifier("scan.camera.starting")
        case .scanning:
            ScannerScreen(model: model)
        }
    }

    private func status(_ kind: ScanStatusView.Kind) -> some View {
        ScanStatusView(
            kind: kind,
            allow: { Task { await model.requestPermission() } },
            openSettings: { model.openSettings() },
            retry: { model.retry() },
            addManually: { model.showsManualEntry = true }
        )
    }
}

private struct ScannerScreen: View {
    @Bindable var model: ScanViewModel

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let scanner = model.scanner {
                scanner.preview.ignoresSafeArea()
            }
            Viewfinder(removing: model.mode == .remove)
            VStack(spacing: 12) {
                modePicker
                topBar
                Spacer()
                Text("scan.camera.hint")
                    .font(.footnote)
                    .padding(8)
                    .background(.ultraThinMaterial, in: Capsule())
                if let another = model.another { AnotherCard(another: another, model: model) }
                if let notice = model.notice { NoticeCard(notice: notice, model: model) }
                bottomBar
            }
            .padding()
        }
    }

    private var modePicker: some View {
        Picker(selection: Binding(get: { model.mode }, set: { model.setMode($0) })) {
            Text("scan.mode.add").tag(ScanMode.add)
            Text("scan.mode.remove").tag(ScanMode.remove)
        } label: {
            Text("scan.mode.picker")
        }
        .pickerStyle(.segmented)
        .frame(minHeight: 44)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 8))
        .accessibilityIdentifier("scan.mode")
    }

    @ViewBuilder private var topBar: some View {
        if model.mode == .remove { removeBar } else { addBar }
    }

    /// Remove mode says so in text and icon (and tint), and carries the session reason (R2, R3).
    private var removeBar: some View {
        // Two rows so that nothing is pushed out of the bar at the largest text sizes.
        VStack(alignment: .leading, spacing: 0) {
            Label("scan.remove.title", systemImage: "minus.circle.fill")
                .font(.headline)
                .foregroundStyle(.red)
                .padding(.top, 8)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("scan.remove.title")
            HStack(spacing: 8) {
                RemovalReasonChip(model: model)
                Spacer()
                soundButton
            }
        }
        .padding(.horizontal, 12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 24))
        .overlay(RoundedRectangle(cornerRadius: 24).strokeBorder(.red, lineWidth: 2))
    }

    private var soundButton: some View {
        Button {
            model.soundEnabled.toggle()
        } label: {
            Image(systemName: model.soundEnabled ? "speaker.wave.2.fill" : "speaker.slash")
                .frame(minWidth: 44, minHeight: 44)
        }
        .accessibilityLabel(Text(model.soundEnabled ? "scan.sound.on" : "scan.sound.off"))
        .accessibilityIdentifier("scan.sound")
    }

    private var addBar: some View {
        HStack(spacing: 8) {
            Menu {
                ForEach(model.locations) { location in
                    Button(location.name) { model.selectLocation(location.id) }
                }
            } label: {
                Label(
                    ScanText.format("scan.location.label", model.sessionLocation?.name ?? ""),
                    systemImage: "mappin.and.ellipse"
                )
                .lineLimit(1)
                .frame(minHeight: 44)
            }
            .accessibilityLabel(Text("scan.location.change"))
            .accessibilityValue(Text(verbatim: model.sessionLocation?.name ?? ""))
            .accessibilityIdentifier("scan.location")
            Spacer()
            soundButton
            Button {
                model.showsManualEntry = true
            } label: {
                Image(systemName: "square.and.pencil")
                    .frame(minWidth: 44, minHeight: 44)
            }
            .accessibilityLabel(Text("scan.manual.open"))
            .accessibilityIdentifier("scan.manual.open")
        }
        .padding(.horizontal, 12)
        .background(.regularMaterial, in: Capsule())
    }

    private var countText: String {
        model.mode == .remove
            ? ScanText.format("scan.removal.count", model.removalSession.count)
            : ScanText.format("scan.count", model.session.count)
    }

    private var bottomBar: some View {
        HStack {
            Button {
                model.showsSessionList = true
            } label: {
                Text(verbatim: countText)
                    .frame(minHeight: 44)
            }
            .buttonStyle(.bordered)
            .accessibilityLabel(Text("scan.session.open"))
            .accessibilityValue(Text(verbatim: countText))
            .accessibilityIdentifier("scan.count")
            Spacer()
            Button("scan.done") { model.finish() }
                .buttonStyle(.borderedProminent)
                .frame(minHeight: 44)
                .accessibilityIdentifier("scan.done")
        }
    }
}

private struct Viewfinder: View {
    let removing: Bool

    var body: some View {
        RoundedRectangle(cornerRadius: 24)
            .strokeBorder(
                removing ? AnyShapeStyle(.red) : AnyShapeStyle(.white.opacity(0.8)),
                style: StrokeStyle(lineWidth: 3, dash: [14, 10])
            )
            .frame(width: 260, height: 260)
            .accessibilityHidden(true)
    }
}
