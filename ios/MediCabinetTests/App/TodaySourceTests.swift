import Foundation
import Testing
import UIKit
@testable import MediCabinet

@Suite struct TodaySourceTests {
    @Test(arguments: TodaySource.notifications)
    func eachSystemNotificationMeansTodayMayHaveChanged(name: Notification.Name) async {
        let center = NotificationCenter()
        var iterator = TodaySource.live(center: center).changes().makeAsyncIterator()
        center.post(name: name, object: nil)
        #expect(await iterator.next() != nil)
    }

    @Test func theSourceListensToTheDayZoneAndClockChanges() {
        #expect(TodaySource.notifications.contains(.NSCalendarDayChanged))
        #expect(TodaySource.notifications.contains(.NSSystemTimeZoneDidChange))
        #expect(TodaySource.notifications.contains(UIApplication.significantTimeChangeNotification))
    }

    @Test func aCancelledSubscriberStopsObserving() async {
        let center = NotificationCenter()
        let source = TodaySource.live(center: center)
        let task = Task { for await _ in source.changes() {} }
        task.cancel()
        await task.value
        // No observer is left to be called (and nothing traps).
        center.post(name: .NSCalendarDayChanged, object: nil)
    }

    @Test func theNeverSourceDoesNotEmit() async {
        let task = Task { () -> Bool in
            for await _ in TodaySource.never.changes() { return true }
            return false
        }
        try? await Task.sleep(for: .milliseconds(50))
        task.cancel()
        #expect(await task.value == false)
    }
}
