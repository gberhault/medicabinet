import CoreGraphics
import Testing
@testable import MediCabinet

@Suite struct FlowLayoutTests {
    private func place(_ sizes: [CGSize], width: CGFloat) -> (origins: [CGPoint], size: CGSize) {
        FlowLayout.place(sizes: sizes, width: width, spacing: 6, lineSpacing: 4)
    }

    @Test func noItemsTakeNoSpace() {
        let placed = place([], width: 100)
        #expect(placed.origins.isEmpty)
        #expect(placed.size == .zero)
    }

    @Test func itemsThatFitShareALine() {
        let placed = place([CGSize(width: 40, height: 20), CGSize(width: 30, height: 30)], width: 100)
        #expect(placed.origins == [CGPoint(x: 0, y: 0), CGPoint(x: 46, y: 0)])
        #expect(placed.size == CGSize(width: 76, height: 30))
    }

    @Test func anItemThatDoesNotFitStartsANewLine() {
        let placed = place([CGSize(width: 60, height: 20), CGSize(width: 50, height: 20)], width: 100)
        #expect(placed.origins == [CGPoint(x: 0, y: 0), CGPoint(x: 0, y: 24)])
        #expect(placed.size == CGSize(width: 60, height: 44))
    }

    @Test func aLineIsAsTallAsItsTallestItem() {
        let placed = place(
            [CGSize(width: 30, height: 20), CGSize(width: 30, height: 40), CGSize(width: 90, height: 10)], width: 100)
        #expect(placed.origins[2] == CGPoint(x: 0, y: 44))
        #expect(placed.size.height == 54)
    }

    @Test func anItemWiderThanTheContainerIsCappedAndAloneOnItsLine() {
        let placed = place([CGSize(width: 20, height: 10), CGSize(width: 300, height: 10), CGSize(width: 20, height: 10)], width: 100)
        #expect(placed.origins == [CGPoint(x: 0, y: 0), CGPoint(x: 0, y: 14), CGPoint(x: 0, y: 28)])
        #expect(placed.size.width == 100)
    }

    @Test func anItemExactlyTheContainerWidthFitsOnItsLine() {
        let placed = place([CGSize(width: 100, height: 10)], width: 100)
        #expect(placed.origins == [CGPoint(x: 0, y: 0)])
        #expect(placed.size == CGSize(width: 100, height: 10))
    }

    @Test func anUnboundedWidthNeverWraps() {
        let placed = place([CGSize(width: 500, height: 10), CGSize(width: 500, height: 10)], width: .infinity)
        #expect(placed.origins == [CGPoint(x: 0, y: 0), CGPoint(x: 506, y: 0)])
    }
}
