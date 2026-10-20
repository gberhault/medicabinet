package com.medicabinet.core.archive

import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.Location
import com.medicabinet.core.NamedProduct
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.testing.ArchiveFixtureFiles
import com.medicabinet.core.testing.SyntheticArchive
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * The encoder's own validation (what the shared decode cases cannot reach, because a decoded model is already
 * valid), the encoder's ordering, and a round trip over random cabinets.
 */
class ArchiveCodecTest {
    private val stamp = ArchiveDates.instant(1_792_397_730_123L)
    private val home = UUID.fromString("10000001-0000-4000-8000-000000000001")
    private val app = ArchiveApp("MediCabinet", "ios", "0.3.0", "3")

    private fun location(id: UUID = home, name: String = "Home", isDefault: Boolean = true, order: Int = 0) =
        Location(id, name, isDefault, order, stamp)

    private fun box(number: Int = 1, locationId: UUID = home, edit: (Box) -> Box = { it }): ArchivedBox {
        val id = UUID.fromString(SyntheticArchive.uuid("B", number))
        val product = ProductRef("03400936404564", "Box $number")
        val box = Box(id = id, product = product, locationId = locationId, createdAt = stamp)
        return ArchivedBox(edit(box))
    }

    private fun named(
        number: Int = 1,
        gtin: String = "03401234567890",
        forgotten: Boolean = false,
        id: UUID = UUID.fromString(SyntheticArchive.uuid("C", number))
    ): ArchivedNamedProduct {
        val product = NamedProduct(
            id = id,
            gtin = gtin,
            name = "Named $number",
            category = CategoryID("skin-wound"),
            categoryOverridden = true,
            createdAt = stamp.plusSeconds(number.toLong())
        )
        return ArchivedNamedProduct(product, if (forgotten) stamp else null)
    }

    private fun archive(
        locations: List<Location> = listOf(location()),
        boxes: List<ArchivedBox> = emptyList(),
        namedProducts: List<ArchivedNamedProduct> = emptyList()
    ) = Archive(stamp, app, CabinetContents(locations.map { ArchivedLocation(it) }, boxes, namedProducts))

    private fun assertRefused(
        kind: ArchiveErrorKind,
        path: String?,
        archive: Archive,
        limits: ArchiveLimits = ArchiveLimits.DEFAULT
    ) {
        val error = assertFailsWith<ArchiveException> { ArchiveEncoder.encode(archive, limits) }
        assertEquals(kind, error.kind)
        assertEquals(path, error.path)
    }

    // MARK: The encoder refuses what the decoder would

    @Test
    fun aValidArchiveEncodesAndDecodesBackToItself() {
        val month = ExpiryDate.of(2027, 4, null)
        val original = archive(boxes = listOf(box(1), box(2) { it.copy(expiry = month) }))
        assertEquals(original, ArchiveDecoder.decode(ArchiveEncoder.encode(original)))
    }

    @Test
    fun namedProductsSurviveEncodingForgottenOnesIncluded() {
        val products = listOf(named(1), named(2, gtin = "03401234567891", forgotten = true), named(3, forgotten = true))
        val original = archive(boxes = listOf(box(1)), namedProducts = products)
        val decoded = ArchiveDecoder.decode(ArchiveEncoder.encode(original))
        assertEquals(original, decoded)
        assertEquals(1, decoded.cabinet.summary.namedProducts)
        assertFalse(decoded.cabinet.namedProductsNeedFill)
    }

    @Test
    fun tooManyLocationsOrBoxesAreRefusedAtTheLimit() {
        val twoProducts = archive(namedProducts = listOf(named(1), named(2, gtin = "03401234567891")))
        val oneAllowed = ArchiveLimits(maxNamedProducts = 1)
        assertRefused(ArchiveErrorKind.TOO_MANY_ITEMS, "namedProducts", twoProducts, oneAllowed)
        ArchiveEncoder.encode(archive(namedProducts = listOf(named(1))), oneAllowed)
        val twoBoxes = archive(boxes = listOf(box(1), box(2)))
        assertRefused(ArchiveErrorKind.TOO_MANY_ITEMS, "boxes", twoBoxes, ArchiveLimits(maxBoxes = 1))
        val two = listOf(location(), location(UUID(0, 1), "Other", false, 1))
        assertRefused(ArchiveErrorKind.TOO_MANY_ITEMS, "locations", archive(two), ArchiveLimits(maxLocations = 1))
    }

    @Test
    fun aFileOverTheSizeLimitIsRefused() {
        assertRefused(ArchiveErrorKind.TOO_LARGE, null, archive(), ArchiveLimits(maxFileBytes = 100))
    }

    private class Breakage(val path: String, val edit: (Archive) -> Archive) {
        override fun toString() = path
    }

    private fun ArchivedBox.withProduct(edit: (ProductRef) -> ProductRef) =
        copy(box = box.copy(product = edit(box.product)))

    private fun mapNamed(archive: Archive, edit: (NamedProduct) -> NamedProduct): Archive = archive.copy(
        cabinet = archive.cabinet.copy(
            namedProducts = archive.cabinet.namedProducts.map { it.copy(product = edit(it.product)) }
        )
    )

    private fun mapBox(archive: Archive, edit: (ArchivedBox) -> ArchivedBox): Archive =
        archive.copy(cabinet = archive.cabinet.copy(boxes = archive.cabinet.boxes.map(edit)))

    private fun mapLocation(archive: Archive, edit: (Location) -> Location): Archive = archive.copy(
        cabinet = archive.cabinet.copy(
            locations = archive.cabinet.locations.map {
                it.copy(location = edit(it.location))
            }
        )
    )

    private val beyond = Instant.ofEpochSecond(1_000_000_000_000_000L)
    private val before = Instant.ofEpochSecond(-1_000_000_000_000_000L)

    private fun breakages() = listOf(
        Breakage("exportedAt") { it.copy(exportedAt = beyond) },
        Breakage("app.name") { it.copy(app = it.app.copy(name = "")) },
        Breakage("app.platform") { it.copy(app = it.app.copy(platform = "windows")) },
        Breakage("app.version") { it.copy(app = it.app.copy(version = "v".repeat(21))) },
        Breakage("app.build") { it.copy(app = it.app.copy(build = "")) },
        Breakage("locations[0].name") { mapLocation(it) { location -> location.copy(name = "") } },
        Breakage("locations[0].createdAt") { mapLocation(it) { location -> location.copy(createdAt = before) } },
        Breakage("locations[0].updatedAt") { mapLocation(it) { location -> location.copy(updatedAt = beyond) } },
        Breakage("locations[0].deletedAt") {
            it.copy(cabinet = it.cabinet.copy(locations = it.cabinet.locations.map { l -> l.copy(deletedAt = beyond) }))
        },
        Breakage("boxes[0].gtin") { mapBox(it) { b -> b.withProduct { p -> p.copy(gtin = "123") } } },
        Breakage("boxes[0].gtin") { mapBox(it) { b -> b.withProduct { p -> p.copy(gtin = "0340093640456A") } } },
        Breakage("boxes[0].productName") { mapBox(it) { b -> b.withProduct { p -> p.copy(name = "") } } },
        Breakage("boxes[0].productName") { mapBox(it) { b -> b.withProduct { p -> p.copy(name = "p".repeat(201)) } } },
        Breakage("boxes[0].productName") {
            mapBox(it) { b -> b.withProduct { p -> p.copy(name = "lone ${0xD83E.toChar()}") } }
        },
        Breakage("boxes[0].productName") {
            mapBox(it) { b -> b.withProduct { p -> p.copy(name = "lone ${0xDD0F.toChar()}") } }
        },
        Breakage("boxes[0].category") { mapBox(it) { b -> b.copy(box = b.box.copy(category = CategoryID(""))) } },
        Breakage("boxes[0].lot") { mapBox(it) { b -> b.copy(box = b.box.copy(lot = "")) } },
        Breakage("boxes[0].serial") { mapBox(it) { b -> b.copy(box = b.box.copy(serial = "s".repeat(21))) } },
        Breakage("boxes[0].removal.at") {
            mapBox(it) { b -> b.copy(box = b.box.copy(removal = Box.Removal(RemovalReason.USED_UP, beyond))) }
        },
        Breakage("boxes[0].createdAt") { mapBox(it) { b -> b.copy(box = b.box.copy(createdAt = beyond)) } },
        Breakage("boxes[0].updatedAt") { mapBox(it) { b -> b.copy(box = b.box.copy(updatedAt = beyond)) } },
        Breakage("boxes[0].deletedAt") { mapBox(it) { b -> b.copy(deletedAt = beyond) } },
        Breakage("namedProducts[0].gtin") { mapNamed(it) { p -> p.copy(gtin = "123") } },
        Breakage("namedProducts[0].gtin") { mapNamed(it) { p -> p.copy(gtin = "0340123456789A") } },
        Breakage("namedProducts[0].name") { mapNamed(it) { p -> p.copy(name = "") } },
        Breakage("namedProducts[0].name") { mapNamed(it) { p -> p.copy(name = "n".repeat(201)) } },
        Breakage("namedProducts[0].category") { mapNamed(it) { p -> p.copy(category = CategoryID("")) } },
        Breakage("namedProducts[0].category") { mapNamed(it) { p -> p.copy(category = CategoryID("c".repeat(101))) } },
        Breakage("namedProducts[0].createdAt") { mapNamed(it) { p -> p.copy(createdAt = beyond) } },
        Breakage("namedProducts[0].updatedAt") { mapNamed(it) { p -> p.copy(updatedAt = beyond) } },
        Breakage("namedProducts[0].deletedAt") {
            val forgotten = it.cabinet.namedProducts.map { n -> n.copy(deletedAt = beyond) }
            it.copy(cabinet = it.cabinet.copy(namedProducts = forgotten))
        }
    )

    @TestFactory
    fun aFieldTheDecoderWouldRefuseIsRefusedAtEncodingToo(): List<DynamicTest> = breakages().map { breakage ->
        dynamicTest(breakage.path) {
            val valid = archive(boxes = listOf(box(1)), namedProducts = listOf(named(1)))
            assertRefused(ArchiveErrorKind.INVALID_VALUE, breakage.path, breakage.edit(valid))
        }
    }

    @Test
    fun wellFormedSurrogatePairsAreAccepted() {
        assertEquals(true, isWellFormed("a 🧳 b"))
        assertEquals(false, isWellFormed("\uD83E"))
        assertEquals(false, isWellFormed("\uD83E\uD83E"))
        assertEquals(false, isWellFormed("\uDD0F"))
    }

    @Test
    fun crossChecksAreEnforcedAtEncodingToo() {
        val other = location(UUID(0, 1), "Other", false, 1)
        val twins = archive(listOf(location(), location(home, "Again", false)))
        assertRefused(ArchiveErrorKind.DUPLICATE_ID, "locations[1].id", twins)
        assertRefused(ArchiveErrorKind.DUPLICATE_ID, "boxes[1].id", archive(boxes = listOf(box(1), box(1))))
        assertRefused(ArchiveErrorKind.DEFAULT_LOCATION, "locations", archive(listOf(location(isDefault = false))))
        assertRefused(
            ArchiveErrorKind.DEFAULT_LOCATION,
            "locations",
            archive(listOf(location(), location(UUID(0, 2), "Two", true, 1)))
        )
        val lost = archive(boxes = listOf(box(1, UUID(9, 9))))
        assertRefused(ArchiveErrorKind.UNKNOWN_LOCATION, "boxes[0].locationId", lost)
        val same = listOf(box(1) { it.copy(serial = "S") }, box(2) { it.copy(serial = "S") })
        assertRefused(ArchiveErrorKind.DUPLICATE_SERIAL, "boxes[1].serial", archive(listOf(location(), other), same))
    }

    @Test
    fun aSerialRepeatedWithoutAGtinOrAfterADeletionIsFine() {
        val noGtin = listOf(
            box(1) { it.copy(product = ProductRef(null, "A"), serial = "S") },
            box(2) { it.copy(product = ProductRef(null, "B"), serial = "S") }
        )
        ArchiveEncoder.encode(archive(boxes = noGtin))
        val deleted = box(1) { it.copy(serial = "S") }.copy(deletedAt = stamp)
        ArchiveEncoder.encode(archive(boxes = listOf(deleted, box(2) { it.copy(serial = "S") })))
        val distinct = listOf(box(1) { it.copy(serial = "S") }, box(2) { it.copy(serial = "T") }, box(3))
        ArchiveEncoder.encode(archive(boxes = distinct))
    }

    @Test
    fun namedProductChecksAreEnforcedAtEncodingToo() {
        val twin = named(2, gtin = "03401234567891", id = named(1).product.id)
        val sameId = archive(namedProducts = listOf(named(1), twin))
        assertRefused(ArchiveErrorKind.DUPLICATE_ID, "namedProducts[1].id", sameId)
        val sameGtin = archive(namedProducts = listOf(named(1), named(2)))
        assertRefused(ArchiveErrorKind.DUPLICATE_NAMED_PRODUCT, "namedProducts[1].gtin", sameGtin)
        // A forgotten product and a live one (or several forgotten ones) may share a GTIN, in any order.
        val shared = listOf(named(1, forgotten = true), named(2), named(3, forgotten = true))
        ArchiveEncoder.encode(archive(namedProducts = shared))
        // A named product may share its id with a box (ids are unique per kind of row).
        ArchiveEncoder.encode(archive(boxes = listOf(box(1)), namedProducts = listOf(named(1, id = box(1).box.id))))
    }

    // MARK: Deleted locations

    private fun contents(locations: List<ArchivedLocation>, boxes: List<ArchivedBox>) =
        Archive(stamp, app, CabinetContents(locations, boxes))

    @Test
    fun aDeletedLocationKeepsTheHistoryButNotTheBoxesInTheCabinet() {
        val home = ArchivedLocation(location())
        val goneId = UUID(0, 5)
        val gone = ArchivedLocation(location(goneId, "Gone", false, 1), stamp)
        val removed = box(1, goneId) { it.copy(removal = Box.Removal(RemovalReason.USED_UP, stamp)) }
        val mistake = box(2, goneId).copy(deletedAt = stamp)
        val archive = contents(listOf(home, gone), listOf(removed, mistake, box(3)))
        assertEquals(archive, ArchiveDecoder.decode(ArchiveEncoder.encode(archive)))

        assertRefused(
            ArchiveErrorKind.UNKNOWN_LOCATION,
            "boxes[0].locationId",
            contents(listOf(home, gone), listOf(box(1, goneId)))
        )
        val deletedDefault = ArchivedLocation(location(), stamp)
        assertRefused(ArchiveErrorKind.DEFAULT_LOCATION, "locations", contents(listOf(deletedDefault), emptyList()))
    }

    // MARK: Ordering

    @Test
    fun theEncoderOrdersLocationsAndBoxesWhateverTheInputOrder() {
        val golden = ArchiveFixtureFiles.goldenArchive()
        val decoded = ArchiveDecoder.decode(golden)
        val cabinet = decoded.cabinet
        val reversed = decoded.copy(
            cabinet = cabinet.copy(
                locations = cabinet.locations.reversed(),
                boxes = cabinet.boxes.reversed(),
                namedProducts = cabinet.namedProducts.reversed()
            )
        )
        assertContentEquals(golden, ArchiveEncoder.encode(reversed))
        val shuffledBoxes = reversed.cabinet.boxes.shuffled(Random(7))
        val shuffled = reversed.copy(cabinet = reversed.cabinet.copy(boxes = shuffledBoxes))
        assertContentEquals(golden, ArchiveEncoder.encode(shuffled))
    }

    @Test
    fun theEncoderOrdersNamedProductsByCreationThenIdWhateverTheInputOrder() {
        val golden = ArchiveFixtureFiles.goldenArchive()
        val decoded = ArchiveDecoder.decode(golden)
        assertEquals(3, decoded.cabinet.namedProducts.size)
        val backwards = decoded.cabinet.namedProducts.reversed()
        val reversed = decoded.copy(cabinet = decoded.cabinet.copy(namedProducts = backwards))
        assertContentEquals(golden, ArchiveEncoder.encode(reversed))
        // The same instant breaks the tie by id, upper case.
        val first = named(2, "03401234567891", id = UUID.fromString("C000000B-0000-4000-8000-000000000002"))
        val second = named(1, id = UUID.fromString("C000000A-0000-4000-8000-000000000001"))
        val sameInstant = listOf(first, second).map { it.copy(product = it.product.copy(createdAt = stamp)) }
        val encoded = ArchiveDecoder.decode(ArchiveEncoder.encode(archive(namedProducts = sameInstant)))
        assertEquals(
            listOf("C000000A-0000-4000-8000-000000000001", "C000000B-0000-4000-8000-000000000002"),
            encoded.cabinet.namedProducts.map { it.product.id.toString().uppercase() }
        )
    }

    // MARK: Round trip over random cabinets

    @TestFactory
    fun aRandomCabinetSurvivesEncodingAndDecoding(): List<DynamicTest> = (1..40).map { seed ->
        dynamicTest("seed $seed") {
            val original = RandomCabinet.make(Random(seed))
            val decoded = ArchiveDecoder.decode(ArchiveEncoder.encode(original))
            assertEquals(original.exportedAt, decoded.exportedAt)
            assertEquals(original.app, decoded.app)
            assertEquals(original.cabinet.locations.toSet(), decoded.cabinet.locations.toSet())
            assertEquals(original.cabinet.boxes.toSet(), decoded.cabinet.boxes.toSet())
            assertEquals(original.cabinet.namedProducts.toSet(), decoded.cabinet.namedProducts.toSet())
            // Idempotence: writing what was read gives the same bytes.
            assertContentEquals(ArchiveEncoder.encode(original), ArchiveEncoder.encode(decoded))
        }
    }
}
