package com.medicabinet.core.testing

import com.medicabinet.core.CategoryID
import com.medicabinet.core.NamedProduct
import com.medicabinet.core.NamedProductRepository
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.testing.RepositoryContract.Check
import com.medicabinet.core.testing.RepositoryContract.GTIN
import com.medicabinet.core.testing.RepositoryContract.expect
import com.medicabinet.core.testing.RepositoryContract.require
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.flow.first

private const val OTHER_GTIN = "03400930000002"
private const val THIRD_GTIN = "03400930000003"
private val STEP: Duration = Duration.ofSeconds(10)
private val SKIN = CategoryID.SKIN_WOUND
private val EYES = CategoryID.EYES

private suspend fun RepositoryContract.Fixture.live(gtin: String): NamedProduct? = named.namedProduct(gtin)

private suspend fun RepositoryContract.Fixture.liveOrFail(gtin: String): NamedProduct =
    live(gtin) ?: throw ContractFailure("no live named product for $gtin")

/** Checks for the products the household named: remember, edit, forget, the list, the one-time fill. */
internal val RepositoryContract.namedProductChecks: List<Check>
    get() = rememberChecks + editChecks + forgetChecks + listChecks + backfillChecks

private val RepositoryContract.rememberChecks: List<Check>
    get() = listOf(
        Check("named products: remember creates the live named product of a gtin") { env ->
            require(env.live(GTIN) == null, "nothing is named yet")
            env.named.remember(GTIN, "Crème solaire", SKIN, categoryOverridden = true)
            val stored = env.liveOrFail(GTIN)
            require(stored.name == "Crème solaire" && stored.gtin == GTIN, "name or gtin not stored")
            require(stored.category == SKIN && stored.categoryOverridden, "category not stored")
            require(stored.createdAt == env.clock.now(), "createdAt must come from the clock")
            require(stored.updatedAt == env.clock.now(), "updatedAt must come from the clock")
            require(env.live(OTHER_GTIN) == null, "another gtin stays unnamed")
        },
        Check("named products: remember with equal values does not write") { env ->
            env.named.remember(GTIN, "Arnica", CategoryID.UNCATEGORIZED, categoryOverridden = false)
            val first = env.liveOrFail(GTIN)
            env.clock.advance(STEP)
            env.named.remember(GTIN, "Arnica", CategoryID.UNCATEGORIZED, categoryOverridden = false)
            require(env.liveOrFail(GTIN) == first, "equal values must leave the product, updatedAt included, as it was")
        },
        Check("named products: remember updates the name, category and flag of the live product") { env ->
            env.named.remember(GTIN, "Arnica", CategoryID.UNCATEGORIZED, categoryOverridden = false)
            val first = env.liveOrFail(GTIN)
            env.clock.advance(STEP)
            env.named.remember(GTIN, "Arnica montana", SKIN, categoryOverridden = true)
            val updated = env.liveOrFail(GTIN)
            require(updated.id == first.id && updated.createdAt == first.createdAt, "same product, same creation")
            require(updated.name == "Arnica montana" && updated.category == SKIN, "values not updated")
            require(updated.categoryOverridden, "flag not updated")
            require(updated.updatedAt == env.clock.now(), "updatedAt must come from the clock")
            env.clock.advance(STEP)
            env.named.remember(GTIN, "Arnica montana", CategoryID.UNCATEGORIZED, categoryOverridden = false)
            val reset = env.liveOrFail(GTIN)
            require(reset.category == CategoryID.UNCATEGORIZED && !reset.categoryOverridden, "reset not stored")
        },
        Check("named products: at most one live named product per gtin") { env ->
            env.named.remember(GTIN, "One", CategoryID.UNCATEGORIZED, categoryOverridden = false)
            env.named.remember(GTIN, "Two", CategoryID.UNCATEGORIZED, categoryOverridden = false)
            val summaries = env.named.observeSummariesNow()
            require(summaries.count { it.product.gtin == GTIN } == 1, "a second live product for one gtin")
            require(env.liveOrFail(GTIN).name == "Two", "the later name wins")
        }
    )

private val RepositoryContract.editChecks: List<Check>
    get() = listOf(
        Check("named products: rename renames the product and every box with its gtin that is not deleted") { env ->
            val location = env.location()
            env.locations.insert(location)
            env.named.remember(GTIN, "Test", CategoryID.UNCATEGORIZED, categoryOverridden = false)
            val product = env.liveOrFail(GTIN)
            val inCabinet = env.box(location, name = "Test")
            val usedUp = env.box(location, name = "Test")
            val deleted = env.box(location, name = "Test")
            val other = env.box(location, name = "Test", gtin = OTHER_GTIN)
            for (box in listOf(inCabinet, usedUp, deleted, other)) env.boxes.insert(box)
            env.boxes.markRemoved(usedUp.id, RemovalReason.USED_UP, env.clock.now())
            env.boxes.delete(deleted.id)
            env.clock.advance(STEP)
            env.named.rename(product.id, "Crème solaire")
            val renamedAt = env.clock.now()
            require(env.liveOrFail(GTIN).name == "Crème solaire", "product not renamed")
            require(env.liveOrFail(GTIN).updatedAt == renamedAt, "product not stamped")
            for (box in listOf(inCabinet, usedUp)) {
                val stored = env.boxes.box(box.id)
                require(stored?.product?.name == "Crème solaire", "a box of the code keeps the old name")
                require(stored?.updatedAt == renamedAt, "a renamed box must be stamped")
            }
            require(env.boxes.box(other.id)?.product?.name == "Test", "a box of another code was renamed")
            env.boxes.undoDelete(deleted.id)
            require(env.boxes.box(deleted.id)?.product?.name == "Test", "a deleted box must not be renamed")
        },
        Check("named products: setCategory sets the category and flag of the product and of those boxes") { env ->
            val location = env.location()
            env.locations.insert(location)
            env.named.remember(GTIN, "Test", CategoryID.UNCATEGORIZED, categoryOverridden = false)
            val product = env.liveOrFail(GTIN)
            val inCabinet = env.box(location)
            val usedUp = env.box(location)
            val deleted = env.box(location)
            val other = env.box(location, gtin = OTHER_GTIN)
            for (box in listOf(inCabinet, usedUp, deleted, other)) env.boxes.insert(box)
            env.boxes.markRemoved(usedUp.id, RemovalReason.THROWN_AWAY, env.clock.now())
            env.boxes.delete(deleted.id)
            env.clock.advance(STEP)
            env.named.setCategory(product.id, EYES, overridden = true)
            val changedAt = env.clock.now()
            val stored = env.liveOrFail(GTIN)
            require(stored.category == EYES && stored.categoryOverridden, "product category not set")
            require(stored.updatedAt == changedAt, "product not stamped")
            for (box in listOf(inCabinet, usedUp)) {
                val after = env.boxes.box(box.id)
                require(after?.category == EYES && after.categoryOverridden, "a box of the code was not recategorised")
                require(after?.updatedAt == changedAt, "a recategorised box must be stamped")
            }
            require(env.boxes.box(other.id)?.category == CategoryID.PAIN_FEVER, "a box of another code changed")
            env.boxes.undoDelete(deleted.id)
            require(env.boxes.box(deleted.id)?.category == CategoryID.PAIN_FEVER, "a deleted box changed")
            env.named.setCategory(product.id, CategoryID.UNCATEGORIZED, overridden = false)
            val automatic = env.boxes.box(inCabinet.id)
            require(automatic?.category == CategoryID.UNCATEGORIZED, "the automatic category was not applied")
            require(automatic?.categoryOverridden == false, "the flag was not cleared")
        },
        Check("named products: rename and setCategory throw notFound for an unknown or forgotten product") { env ->
            expect(RepositoryError.NOT_FOUND) { env.named.rename(UUID.randomUUID(), "x") }
            expect(RepositoryError.NOT_FOUND) { env.named.setCategory(UUID.randomUUID(), SKIN, overridden = true) }
            env.named.remember(GTIN, "Test", CategoryID.UNCATEGORIZED, categoryOverridden = false)
            val product = env.liveOrFail(GTIN)
            env.named.forget(product.id)
            expect(RepositoryError.NOT_FOUND) { env.named.rename(product.id, "x") }
            expect(RepositoryError.NOT_FOUND) { env.named.setCategory(product.id, SKIN, overridden = true) }
        }
    )

private val RepositoryContract.forgetChecks: List<Check>
    get() = listOf(
        Check("named products: forget hides the product and keeps its boxes") { env ->
            val location = env.location()
            env.locations.insert(location)
            env.named.remember(GTIN, "Test", CategoryID.UNCATEGORIZED, categoryOverridden = false)
            val product = env.liveOrFail(GTIN)
            val box = env.box(location, name = "Test")
            env.boxes.insert(box)
            env.named.forget(product.id)
            require(env.live(GTIN) == null, "a forgotten product is still named")
            require(env.named.observeSummariesNow().isEmpty(), "a forgotten product is still listed")
            require(env.boxes.box(box.id) == box, "forgetting must leave the boxes alone")
        },
        Check("named products: undoForget restores the product as it was") { env ->
            env.named.remember(GTIN, "Crème solaire", SKIN, categoryOverridden = true)
            val product = env.liveOrFail(GTIN)
            env.named.forget(product.id)
            env.clock.advance(STEP)
            env.named.undoForget(product.id)
            val restored = env.liveOrFail(GTIN)
            require(restored.id == product.id && restored.createdAt == product.createdAt, "another product came back")
            require(restored.name == product.name && restored.category == SKIN, "name or category changed")
            require(restored.categoryOverridden, "flag changed")
            require(env.named.observeSummariesNow().map { it.product.id } == listOf(product.id), "not listed again")
        },
        Check("named products: undoForget refuses when the gtin was named again") { env ->
            env.named.remember(GTIN, "Old", CategoryID.UNCATEGORIZED, categoryOverridden = false)
            val old = env.liveOrFail(GTIN)
            env.named.forget(old.id)
            env.named.remember(GTIN, "New", CategoryID.UNCATEGORIZED, categoryOverridden = false)
            val fresh = env.liveOrFail(GTIN)
            require(fresh.id != old.id, "a forgotten product must not be revived by remember")
            expect(RepositoryError.DUPLICATE) { env.named.undoForget(old.id) }
            require(env.liveOrFail(GTIN).id == fresh.id, "the new product must stay the live one")
        },
        Check(
            "named products: forget and undoForget throw notFound when there is nothing to forget or restore"
        ) { env ->
            expect(RepositoryError.NOT_FOUND) { env.named.forget(UUID.randomUUID()) }
            expect(RepositoryError.NOT_FOUND) { env.named.undoForget(UUID.randomUUID()) }
            env.named.remember(GTIN, "Test", CategoryID.UNCATEGORIZED, categoryOverridden = false)
            val product = env.liveOrFail(GTIN)
            expect(RepositoryError.NOT_FOUND) { env.named.undoForget(product.id) }
            env.named.forget(product.id)
            expect(RepositoryError.NOT_FOUND) { env.named.forget(product.id) }
        }
    )

private val RepositoryContract.listChecks: List<Check>
    get() = listOf(
        Check(
            "named products: summaries count only boxes in the cabinet and order by normalized name then gtin"
        ) { env ->
            val location = env.location()
            env.locations.insert(location)
            env.named.remember(GTIN, "Zinc", CategoryID.UNCATEGORIZED, categoryOverridden = false)
            env.named.remember(OTHER_GTIN, "arnica", CategoryID.UNCATEGORIZED, categoryOverridden = false)
            env.named.remember(THIRD_GTIN, "Écran total", CategoryID.UNCATEGORIZED, categoryOverridden = false)
            env.named.remember("03400930000004", "Arnica", CategoryID.UNCATEGORIZED, categoryOverridden = false)
            val first = env.box(location, gtin = GTIN)
            val second = env.box(location, gtin = GTIN)
            val usedUp = env.box(location, gtin = GTIN)
            val deleted = env.box(location, gtin = GTIN)
            val arnica = env.box(location, gtin = OTHER_GTIN)
            for (box in listOf(first, second, usedUp, deleted, arnica)) env.boxes.insert(box)
            env.boxes.markRemoved(usedUp.id, RemovalReason.USED_UP, env.clock.now())
            env.boxes.delete(deleted.id)
            val rows = env.named.observeSummariesNow()
            require(
                rows.map { it.product.gtin } == listOf(OTHER_GTIN, "03400930000004", THIRD_GTIN, GTIN),
                "wrong order: ${rows.map { it.product.name }}"
            )
            val counts = rows.map { it.boxesInCabinet }
            require(counts == listOf(1, 0, 0, 2), "wrong counts: $counts")
        },
        Check("named products: observeSummaries emits after a named product or a box changes") { env ->
            val location = env.location()
            env.locations.insert(location)
            observing(env.named.observeSummaries()) { seen ->
                seen.until("the initial, empty list") { it.isEmpty() }
                env.named.remember(GTIN, "Arnica", CategoryID.UNCATEGORIZED, categoryOverridden = false)
                seen.until("the named product") { rows -> rows.map { it.boxesInCabinet } == listOf(0) }
                val box = env.box(location, name = "Arnica")
                env.boxes.insert(box)
                seen.until("the box counted") { rows -> rows.map { it.boxesInCabinet } == listOf(1) }
                env.boxes.markRemoved(box.id, RemovalReason.USED_UP, env.clock.now())
                seen.until("the used-up box not counted") { rows -> rows.map { it.boxesInCabinet } == listOf(0) }
                env.named.rename(env.liveOrFail(GTIN).id, "Arnica montana")
                seen.until("the new name") { rows -> rows.firstOrNull()?.product?.name == "Arnica montana" }
                env.named.forget(env.liveOrFail(GTIN).id)
                seen.until("the forgotten product to disappear") { it.isEmpty() }
            }
        }
    )

private val RepositoryContract.backfillChecks: List<Check>
    get() = listOf(
        Check("named products: a new store has a pending backfill and completeBackfill clears it") { env ->
            require(env.named.backfillPending(), "a new store must start with the fill pending")
            val filled = NamedProduct(gtin = GTIN, name = "Arnica", createdAt = env.clock.now())
            env.named.completeBackfill(listOf(filled))
            require(!env.named.backfillPending(), "the marker must be cleared")
            require(env.live(GTIN) == filled, "the product was not inserted as given")
            env.named.completeBackfill(emptyList())
            require(!env.named.backfillPending(), "completing again keeps the marker cleared")
        },
        Check("named products: completeBackfill keeps a live product that appeared meanwhile") { env ->
            env.named.remember(GTIN, "Typed after the upgrade", SKIN, categoryOverridden = true)
            val kept = env.liveOrFail(GTIN)
            val fromBoxes = NamedProduct(gtin = GTIN, name = "From a box", createdAt = env.clock.now())
            val other = NamedProduct(gtin = OTHER_GTIN, name = "Other", createdAt = env.clock.now())
            env.named.completeBackfill(listOf(fromBoxes, other))
            require(env.liveOrFail(GTIN) == kept, "the product created meanwhile must win")
            require(env.live(OTHER_GTIN) == other, "the other product must still be inserted")
            require(!env.named.backfillPending(), "the marker must be cleared")
        }
    )

/** One box per gtin: the newest non-deleted one. */
internal val RepositoryContract.latestBoxChecks: List<Check>
    get() = listOf(
        Check("boxes: latestBoxPerGTIN returns the newest non-deleted box of every gtin") { env ->
            val location = env.location()
            env.locations.insert(location)
            val oldest = env.box(location, name = "Oldest")
            env.boxes.insert(oldest)
            env.clock.advance(STEP)
            val middle = env.box(location, name = "Middle")
            env.boxes.insert(middle)
            env.clock.advance(STEP)
            val deleted = env.box(location, name = "Deleted")
            env.boxes.insert(deleted)
            env.boxes.delete(deleted.id)
            env.clock.advance(STEP)
            val other = env.box(location, name = "Other", gtin = OTHER_GTIN)
            env.boxes.insert(other)
            env.boxes.markRemoved(other.id, RemovalReason.USED_UP, env.clock.now())
            val noCode = env.box(location, name = "No code")
            env.boxes.insert(noCode.copy(product = noCode.product.copy(gtin = null)))
            val latest = env.boxes.latestBoxPerGTIN()
            require(
                latest.map { it.id } == listOf(other.id, middle.id),
                "expected the newest box of each gtin, newest first"
            )
            require(latest.all { it.product.gtin != null }, "a box without a gtin must be left out")
        }
    )

/** The list as the screen sees it, read once. */
private suspend fun NamedProductRepository.observeSummariesNow() = observeSummaries().first()
