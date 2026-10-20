import Foundation
import MediCabinetCore

/// Checks for the named products and for `latestBoxPerGTIN`, the query that fills them after the upgrade.
extension RepositoryContract {
    private static let gtinA = "03400930000001"
    private static let gtinB = "03400930000002"
    private static let skin = CategoryID(rawValue: "skin-wound")
    private static let other = CategoryID(rawValue: "other")

    /// The live named product of `gtin`, which every check below expects to exist.
    private static func product(_ env: Fixture, _ gtin: String) async throws -> NamedProduct {
        guard let found = try await env.named.namedProduct(gtin: gtin) else {
            throw ContractFailure(description: "expected a named product for \(gtin)")
        }
        return found
    }

    private struct ThreeBoxes {
        let cabinet: Box
        let used: Box
        let deleted: Box
    }

    /// Boxes with the given GTIN in the default location: one in the cabinet, one used up and one deleted.
    private static func threeBoxes(_ env: Fixture, gtin: String = gtinA) async throws -> ThreeBoxes {
        let location = try await env.locations.ensureDefault(named: "Cabinet")
        let cabinet = env.box(in: location, name: "Test", gtin: gtin)
        let used = env.box(in: location, name: "Test", gtin: gtin)
        let deleted = env.box(in: location, name: "Test", gtin: gtin)
        for box in [cabinet, used, deleted] { try await env.boxes.insert(box) }
        try await env.boxes.markRemoved(id: used.id, reason: .usedUp, at: env.clock.now)
        try await env.boxes.delete(id: deleted.id)
        return ThreeBoxes(cabinet: cabinet, used: used, deleted: deleted)
    }

    static var namedProductChecks: [Check] {
        rememberChecks + editChecks + forgetChecks + summaryChecks + backfillChecks
    }

    private static var rememberChecks: [Check] {
        [
            Check(name: "named products: remember creates the live named product of a gtin") { env in
                try require(try await env.named.namedProduct(gtin: gtinA) == nil, "nothing is named yet")
                try await env.named.remember(gtin: gtinA, name: "Crème solaire", category: skin, categoryOverridden: true)
                let stored = try await product(env, gtinA)
                try require(stored.name == "Crème solaire" && stored.category == skin && stored.categoryOverridden, "values")
                try require(stored.createdAt == env.clock.now && stored.updatedAt == env.clock.now, "dates come from the clock")
                try require(try await env.named.namedProduct(gtin: gtinB) == nil, "another gtin stays unnamed")
            },
            Check(name: "named products: remember with equal values does not write") { env in
                try await env.named.remember(gtin: gtinA, name: "Crème", category: .uncategorized, categoryOverridden: false)
                let first = try await product(env, gtinA)
                env.clock.advance(by: 60)
                try await env.named.remember(gtin: gtinA, name: "Crème", category: .uncategorized, categoryOverridden: false)
                try require(try await product(env, gtinA) == first, "an equal remember must change nothing, updatedAt included")
            },
            Check(name: "named products: remember updates the name, category and flag of the live product") { env in
                try await env.named.remember(gtin: gtinA, name: "Crème", category: .uncategorized, categoryOverridden: false)
                let first = try await product(env, gtinA)
                env.clock.advance(by: 60)
                try await env.named.remember(gtin: gtinA, name: "Crème SPF50", category: skin, categoryOverridden: true)
                let updated = try await product(env, gtinA)
                try require(updated.id == first.id && updated.createdAt == first.createdAt, "same product, createdAt kept")
                try require(updated.name == "Crème SPF50" && updated.category == skin && updated.categoryOverridden, "values")
                try require(updated.updatedAt == env.clock.now, "updatedAt must be bumped")
            },
            Check(name: "named products: at most one live named product per gtin") { env in
                try await env.named.remember(gtin: gtinA, name: "One", category: .uncategorized, categoryOverridden: false)
                try await env.named.remember(gtin: gtinA, name: "Two", category: .uncategorized, categoryOverridden: false)
                try await env.named.remember(gtin: gtinB, name: "Three", category: .uncategorized, categoryOverridden: false)
                let (seen, task) = observe(env.named.observeSummaries())
                defer { task.cancel() }
                try await seen.wait("one product per gtin, the second name replacing the first") {
                    $0.map(\.product.name).sorted() == ["Three", "Two"]
                }
            }
        ]
    }

    private static var editChecks: [Check] {
        [
            Check(name: "named products: rename renames the product and every box with its gtin that is not deleted") { env in
                let boxes = try await threeBoxes(env)
                let bystander = env.box(in: try await env.locations.ensureDefault(named: "Cabinet"), name: "Test", gtin: gtinB)
                try await env.boxes.insert(bystander)
                try await env.named.remember(gtin: gtinA, name: "Test", category: .uncategorized, categoryOverridden: false)
                let id = try await product(env, gtinA).id
                env.clock.advance(by: 60)
                try await env.named.rename(id: id, to: "Crème solaire")
                let renamed = try await product(env, gtinA)
                try require(renamed.name == "Crème solaire" && renamed.updatedAt == env.clock.now, "product renamed and stamped")
                for box in [boxes.cabinet, boxes.used] {
                    let stored = try await env.boxes.box(id: box.id)
                    try require(stored?.product.name == "Crème solaire", "a box in the cabinet or used up takes the name")
                    try require(stored?.updatedAt == env.clock.now, "the box is stamped")
                    let inCabinet = box.id == boxes.cabinet.id
                    try require((stored?.removal == nil) == inCabinet && stored?.createdAt == box.createdAt, "nothing else changes")
                }
                try require(try await env.boxes.box(id: bystander.id)?.product.name == "Test", "another gtin keeps its name")
                try await env.boxes.undoDelete(id: boxes.deleted.id)
                try require(try await env.boxes.box(id: boxes.deleted.id)?.product.name == "Test", "a deleted box keeps its name")
            },
            Check(name: "named products: setCategory sets the category and flag of the product and of those boxes") { env in
                let boxes = try await threeBoxes(env)
                try await env.named.remember(gtin: gtinA, name: "Test", category: .uncategorized, categoryOverridden: false)
                let id = try await product(env, gtinA).id
                env.clock.advance(by: 60)
                try await env.named.setCategory(id: id, skin, overridden: true)
                let changed = try await product(env, gtinA)
                try require(changed.category == skin && changed.categoryOverridden, "product category and flag")
                for box in [boxes.cabinet, boxes.used] {
                    let stored = try await env.boxes.box(id: box.id)
                    try require(stored?.category == skin && stored?.categoryOverridden == true, "box category and flag")
                    try require(stored?.product.name == "Test" && stored?.updatedAt == env.clock.now, "name kept, box stamped")
                }
                try await env.named.setCategory(id: id, .uncategorized, overridden: false)
                try require(try await env.boxes.box(id: boxes.cabinet.id)?.categoryOverridden == false, "flag off again")
                try await env.boxes.undoDelete(id: boxes.deleted.id)
                try require(try await env.boxes.box(id: boxes.deleted.id)?.category.rawValue == "pain-fever", "deleted box untouched")
            },
            Check(name: "named products: rename and setCategory throw notFound for an unknown or forgotten product") { env in
                try await expect(.notFound) { try await env.named.rename(id: UUID(), to: "X") }
                try await expect(.notFound) { try await env.named.setCategory(id: UUID(), skin, overridden: true) }
                try await env.named.remember(gtin: gtinA, name: "Test", category: .uncategorized, categoryOverridden: false)
                let id = try await product(env, gtinA).id
                try await env.named.forget(id: id)
                try await expect(.notFound) { try await env.named.rename(id: id, to: "X") }
                try await expect(.notFound) { try await env.named.setCategory(id: id, skin, overridden: true) }
            }
        ]
    }

    private static var forgetChecks: [Check] {
        [
            Check(name: "named products: forget hides the product and keeps its boxes") { env in
                let boxes = try await threeBoxes(env)
                try await env.named.remember(gtin: gtinA, name: "Test", category: skin, categoryOverridden: true)
                let id = try await product(env, gtinA).id
                try await env.named.forget(id: id)
                try require(try await env.named.namedProduct(gtin: gtinA) == nil, "a forgotten product is not live")
                let (seen, task) = observe(env.named.observeSummaries())
                defer { task.cancel() }
                try await seen.wait("no summary") { $0.isEmpty }
                try require(try await env.boxes.box(id: boxes.cabinet.id) == boxes.cabinet, "the box in the cabinet is untouched")
                try require(try await env.boxes.box(id: boxes.used.id)?.product.name == "Test", "the used box is untouched")
            },
            Check(name: "named products: undoForget restores the product as it was") { env in
                try await env.named.remember(gtin: gtinA, name: "Test", category: skin, categoryOverridden: true)
                let before = try await product(env, gtinA)
                try await env.named.forget(id: before.id)
                env.clock.advance(by: 60)
                try await env.named.undoForget(id: before.id)
                let restored = try await product(env, gtinA)
                try require(restored.id == before.id && restored.createdAt == before.createdAt, "same product")
                try require(restored.name == "Test" && restored.category == skin && restored.categoryOverridden, "same values")
            },
            Check(name: "named products: undoForget refuses when the gtin was named again") { env in
                try await env.named.remember(gtin: gtinA, name: "Old", category: .uncategorized, categoryOverridden: false)
                let old = try await product(env, gtinA)
                try await env.named.forget(id: old.id)
                try await env.named.remember(gtin: gtinA, name: "New", category: .uncategorized, categoryOverridden: false)
                let new = try await product(env, gtinA)
                try require(new.id != old.id, "a forgotten product is never revived by remember")
                try await expect(.duplicate) { try await env.named.undoForget(id: old.id) }
                try require(try await product(env, gtinA) == new, "the new product is untouched")
            },
            Check(name: "named products: forget and undoForget throw notFound when there is nothing to forget or restore") { env in
                try await expect(.notFound) { try await env.named.forget(id: UUID()) }
                try await expect(.notFound) { try await env.named.undoForget(id: UUID()) }
                try await env.named.remember(gtin: gtinA, name: "Test", category: .uncategorized, categoryOverridden: false)
                let id = try await product(env, gtinA).id
                try await expect(.notFound) { try await env.named.undoForget(id: id) }
                try await env.named.forget(id: id)
                try await expect(.notFound) { try await env.named.forget(id: id) }
            }
        ]
    }

    private static var summaryChecks: [Check] {
        [
            Check(name: "named products: summaries count only boxes in the cabinet and order by normalized name then gtin") { env in
                _ = try await threeBoxes(env)
                try await env.named.remember(gtin: gtinB, name: "ARNICA", category: .uncategorized, categoryOverridden: false)
                try await env.named.remember(gtin: gtinA, name: "arnica", category: .uncategorized, categoryOverridden: false)
                try await env.named.remember(gtin: "03400930000003", name: "Zinc", category: .uncategorized, categoryOverridden: false)
                try await env.named.remember(gtin: "03400930000004", name: "Écharpe", category: .uncategorized, categoryOverridden: false)
                let (seen, task) = observe(env.named.observeSummaries())
                defer { task.cancel() }
                try await seen.wait("four summaries") { $0.count == 4 }
                let summaries = await seen.value ?? []
                try require(summaries.map(\.product.gtin) == [gtinA, gtinB, "03400930000004", "03400930000003"], "order")
                try require(summaries.map(\.boxesInCabinet) == [1, 0, 0, 0], "only the box in the cabinet counts")
            },
            Check(name: "named products: observeSummaries emits after a named product or a box changes") { env in
                let (seen, task) = observe(env.named.observeSummaries())
                defer { task.cancel() }
                try await seen.wait("the initial, empty list") { $0.isEmpty }
                try await env.named.remember(gtin: gtinA, name: "Test", category: .uncategorized, categoryOverridden: false)
                try await seen.wait("the remembered product") { $0.map(\.boxesInCabinet) == [0] }
                let box = env.box(in: try await env.locations.ensureDefault(named: "Cabinet"), name: "Test", gtin: gtinA)
                try await env.boxes.insert(box)
                try await seen.wait("the inserted box") { $0.map(\.boxesInCabinet) == [1] }
                try await env.boxes.markRemoved(id: box.id, reason: .usedUp, at: env.clock.now)
                try await seen.wait("the removed box") { $0.map(\.boxesInCabinet) == [0] }
                let id = try await product(env, gtinA).id
                try await env.named.rename(id: id, to: "Crème")
                try await seen.wait("the new name") { $0.map(\.product.name) == ["Crème"] }
                try await env.named.forget(id: id)
                try await seen.wait("the forgotten product to disappear") { $0.isEmpty }
            }
        ]
    }

    private static var backfillChecks: [Check] {
        [
            Check(name: "named products: a new store has a pending backfill and completeBackfill clears it") { env in
                try require(try await env.named.backfillPending(), "a new store starts with the fill pending")
                let filled = NamedProduct(gtin: gtinA, name: "Arnica", category: skin, categoryOverridden: true, createdAt: env.clock.now)
                try await env.named.completeBackfill([filled])
                try require(try await env.named.backfillPending() == false, "completeBackfill clears the mark")
                try require(try await env.named.namedProduct(gtin: gtinA) == filled, "the product is stored as given")
                try await env.named.completeBackfill([])
                try require(try await env.named.backfillPending() == false, "and it stays clear")
            },
            Check(name: "named products: completeBackfill keeps a live product that appeared meanwhile") { env in
                try await env.named.remember(gtin: gtinA, name: "Mine", category: .uncategorized, categoryOverridden: false)
                let mine = try await product(env, gtinA)
                try await env.named.completeBackfill([
                    NamedProduct(gtin: gtinA, name: "Filled", createdAt: env.clock.now),
                    NamedProduct(gtin: gtinB, name: "Other", createdAt: env.clock.now)
                ])
                try require(try await product(env, gtinA) == mine, "the live product wins")
                try require(try await product(env, gtinB).name == "Other", "the others are inserted")
                try require(try await env.named.backfillPending() == false, "the mark is cleared")
            },
            Check(name: "boxes: latestBoxPerGTIN returns the newest non-deleted box of every gtin") { env in
                let location = env.location()
                try await env.locations.insert(location)
                let first = env.box(in: location, name: "A old", gtin: gtinA)
                try await env.boxes.insert(first)
                env.clock.advance(by: 10)
                let removedNewest = env.box(in: location, name: "A new", gtin: gtinA)
                try await env.boxes.insert(removedNewest)
                try await env.boxes.markRemoved(id: removedNewest.id, reason: .thrownAway, at: env.clock.now)
                let deletedNewest = env.box(in: location, name: "C deleted", gtin: "03400930000003")
                let beforeDeleted = env.box(in: location, name: "C kept", gtin: "03400930000003")
                try await env.boxes.insert(beforeDeleted)
                env.clock.advance(by: 10)
                try await env.boxes.insert(deletedNewest)
                try await env.boxes.delete(id: deletedNewest.id)
                let noCode = Box(product: ProductRef(name: "No code"), locationID: location.id, createdAt: env.clock.now)
                try await env.boxes.insert(noCode)
                let tieOne = env.box(in: location, name: "B one", gtin: gtinB)
                let tieTwo = env.box(in: location, name: "B two", gtin: gtinB)
                try await env.boxes.insert(tieOne)
                try await env.boxes.insert(tieTwo)
                let tieWinner = [tieOne, tieTwo].max { $0.id.uuidString < $1.id.uuidString }

                let latest = try await env.boxes.latestBoxPerGTIN()
                let byGTIN = Dictionary(uniqueKeysWithValues: latest.compactMap { box in box.product.gtin.map { ($0, box) } })
                try require(latest.count == 3 && byGTIN.count == 3, "one box per gtin, none without a gtin")
                try require(byGTIN[gtinA]?.id == removedNewest.id, "a removed box still counts as the newest")
                try require(byGTIN["03400930000003"]?.id == beforeDeleted.id, "a deleted box is skipped")
                try require(byGTIN[gtinB]?.id == tieWinner?.id, "a tie on createdAt is broken by the greater id")
                try require(latest.map(\.createdAt) == latest.map(\.createdAt).sorted(by: >), "newest first")
            }
        ]
    }
}
