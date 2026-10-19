import Testing
@testable import MediCabinet

/// A sleep the test opens by hand, so expiry is deterministic and instant.
private actor Gate {
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var isOpen = false

    func wait() async {
        if isOpen { return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func open() {
        isOpen = true
        waiters.forEach { $0.resume() }
        waiters.removeAll()
    }
}

@MainActor
private final class Counter {
    var value = 0
}

@MainActor
@Suite struct UndoCenterTests {
    private func settle() async {
        for _ in 0..<20 { await Task.yield() }
    }

    @Test func showingAMessageMakesItPending() {
        let center = UndoCenter()
        center.show("Removed") {}
        #expect(center.pending?.message == "Removed")
    }

    @Test func performRunsTheUndoOnceAndClearsTheMessage() async {
        let center = UndoCenter()
        let counter = Counter()
        center.show("Removed") { counter.value += 1 }
        await center.perform()
        await center.perform()
        #expect(counter.value == 1)
        #expect(center.pending == nil)
    }

    @Test func aNewActionReplacesThePreviousOneWithoutRunningItsUndo() async {
        let center = UndoCenter()
        let counter = Counter()
        center.show("First") { counter.value += 1 }
        center.show("Second") { counter.value += 10 }
        #expect(center.pending?.message == "Second")
        await center.perform()
        #expect(counter.value == 10)
    }

    @Test func theMessageExpiresAfterTheDisplayDuration() async {
        let gate = Gate()
        let center = UndoCenter(sleep: { _ in await gate.wait() })
        center.show("Removed") {}
        await settle()
        #expect(center.pending != nil)
        await gate.open()
        await settle()
        #expect(center.pending == nil)
    }

    @Test func aHeldMessageDoesNotExpireUntilResumed() async {
        let gate = Gate()
        let center = UndoCenter(sleep: { _ in await gate.wait() })
        center.show("Removed") {}
        center.hold()
        await gate.open()
        await settle()
        #expect(center.pending != nil, "a held message must stay")
        center.resume()
        await settle()
        #expect(center.pending == nil, "a resumed message expires (the gate is already open)")
    }

    @Test func anOldTimerNeverDismissesANewerMessage() async {
        let gate = Gate()
        let center = UndoCenter(sleep: { _ in await gate.wait() })
        center.show("First") {}
        center.show("Second") {}
        await settle()
        #expect(center.pending?.message == "Second")
        await gate.open()
        await settle()
        #expect(center.pending == nil)
    }

    @Test func dismissingClearsTheMessageWithoutUndoing() {
        let center = UndoCenter()
        let counter = Counter()
        center.show("Removed") { counter.value += 1 }
        center.dismiss()
        #expect(center.pending == nil)
        #expect(counter.value == 0)
    }

    @Test func resumingWithNothingPendingDoesNothing() {
        let center = UndoCenter()
        center.resume()
        #expect(center.pending == nil)
    }

    @Test func theDisplayDurationMatchesAndroidsLongSnackbar() {
        #expect(UndoCenter.displayDuration == .seconds(10))
    }
}
