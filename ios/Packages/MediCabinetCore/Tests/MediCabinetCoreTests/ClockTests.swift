import Foundation
import Testing
@testable import MediCabinetCore

@Suite struct ClockTests {
    @Test func fixedClockAlwaysReturnsItsDate() {
        let date = Date(timeIntervalSince1970: 1_800_000_000)
        let clock = FixedClock(date)
        #expect(clock.now == date)
        #expect(clock.now == date)
    }

    @Test func fixedClockAdvances() {
        var clock = FixedClock(Date(timeIntervalSince1970: 0))
        clock.advance(by: 86_400)
        #expect(clock.now == Date(timeIntervalSince1970: 86_400))
    }

    @Test func fixedClockIsUsableThroughTheProtocol() {
        let clock: any Clock = FixedClock(Date(timeIntervalSince1970: 42))
        #expect(clock.now.timeIntervalSince1970 == 42)
    }

    @Test func systemClockIsNonDecreasing() {
        let clock = SystemClock()
        let first = clock.now
        let second = clock.now
        #expect(second >= first)
    }

    @Test func systemClockIsCloseToWallTime() {
        let reading = SystemClock().now.timeIntervalSinceReferenceDate
        #expect(abs(reading - Date.timeIntervalSinceReferenceDate) < 5)
    }
}
