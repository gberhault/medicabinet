import MediCabinetCore

/// The boxes added since the scanner was opened (cleared by **Done**). The boxes themselves are already saved.
struct ScanSession: Equatable {
    private(set) var boxes: [Box] = []

    var count: Int { boxes.count }
    var newestFirst: [Box] { boxes.reversed() }
    /// GTINs of the boxes in the session: the same product without serial asks before it is added again.
    var gtins: Set<String> { Set(boxes.compactMap(\.product.gtin)) }
    var unknownExpiryCount: Int { boxes.filter { $0.expiry == nil }.count }

    mutating func add(_ box: Box) {
        boxes.append(box)
    }

    mutating func remove(_ id: Box.ID) {
        boxes.removeAll { $0.id == id }
    }

    mutating func replace(_ box: Box) {
        if let index = boxes.firstIndex(where: { $0.id == box.id }) { boxes[index] = box }
    }
}
