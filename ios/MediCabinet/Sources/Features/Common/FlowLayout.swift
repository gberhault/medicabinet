import SwiftUI

/// Places its subviews left to right and wraps onto a new line when the next one does not fit. Each subview is
/// offered at most the full width, so a long pill wraps its own text instead of overflowing.
struct FlowLayout: Layout {
    var spacing: CGFloat = 6
    var lineSpacing: CGFloat = 0

    /// Where each item goes: pure arithmetic over measured sizes, so it is unit-tested without a view.
    static func place(
        sizes: [CGSize], width: CGFloat, spacing: CGFloat, lineSpacing: CGFloat
    ) -> (origins: [CGPoint], size: CGSize) {
        var origins: [CGPoint] = []
        var cursorX: CGFloat = 0
        var cursorY: CGFloat = 0
        var lineHeight: CGFloat = 0
        var widest: CGFloat = 0
        for size in sizes {
            let itemWidth = min(size.width, width)
            if cursorX > 0, cursorX + itemWidth > width {
                cursorY += lineHeight + lineSpacing
                cursorX = 0
                lineHeight = 0
            }
            origins.append(CGPoint(x: cursorX, y: cursorY))
            cursorX += itemWidth + spacing
            lineHeight = max(lineHeight, size.height)
            widest = max(widest, cursorX - spacing)
        }
        return (origins, CGSize(width: widest, height: sizes.isEmpty ? 0 : cursorY + lineHeight))
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        return Self.place(sizes: measure(subviews, width: width), width: width, spacing: spacing, lineSpacing: lineSpacing).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let sizes = measure(subviews, width: bounds.width)
        let placed = Self.place(sizes: sizes, width: bounds.width, spacing: spacing, lineSpacing: lineSpacing)
        for (index, subview) in subviews.enumerated() {
            let origin = placed.origins[index]
            subview.place(
                at: CGPoint(x: bounds.minX + origin.x, y: bounds.minY + origin.y), anchor: .topLeading,
                proposal: ProposedViewSize(width: min(sizes[index].width, bounds.width), height: sizes[index].height))
        }
    }

    private func measure(_ subviews: Subviews, width: CGFloat) -> [CGSize] {
        subviews.map { $0.sizeThatFits(ProposedViewSize(width: width, height: nil)) }
    }
}
