package com.medicabinet.core.archive

import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.Location
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RemovalReason
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/** Valid random cabinets covering every field variant, with awkward text (quotes, control characters, emoji). */
object RandomCabinet {
    private val scalarPool: List<Int> = ('a'..'z').map { it.code } + ('0'..'9').map { it.code } + listOf(
        0x20, 0x22, 0x5C, 0x2F, 0x00, 0x01, 0x1F, 0x7F, 0xE9, 0x301, 0x20AC, 0x1F9F3, 0x65E5, 0xE000, 0xFFFF, 0x10FFFF
    )

    fun text(range: IntRange, random: Random): String {
        val builder = StringBuilder()
        repeat(random.nextInt(range.first, range.last + 1)) { builder.appendCodePoint(scalarPool.random(random)) }
        return builder.toString()
    }

    private fun instant(random: Random): Instant =
        ArchiveDates.instant(random.nextLong(946_684_800_000L, 4_102_444_800_000L))

    private fun id(random: Random): UUID = UUID(random.nextLong(), random.nextLong())

    fun make(random: Random): Archive {
        val locations = (0 until random.nextInt(1, 5)).map { index ->
            val location = Location(
                id(random),
                text(1..100, random),
                index == 0,
                random.nextInt(),
                instant(random),
                instant(random)
            )
            // The default location (the first) is never deleted.
            ArchivedLocation(location, if (index > 0 && random.nextBoolean()) instant(random) else null)
        }
        val boxes = (0 until random.nextInt(0, 61)).map { box(it, locations, random) }
        val app = ArchiveApp(
            text(1..50, random),
            ArchiveApp.PLATFORMS.random(random),
            text(1..20, random),
            text(1..20, random)
        )
        return Archive(instant(random), app, CabinetContents(locations, boxes))
    }

    private fun box(index: Int, locations: List<ArchivedLocation>, random: Random): ArchivedBox {
        val gtin = if (random.nextBoolean()) (0 until 14).joinToString("") { random.nextInt(10).toString() } else null
        val expiry = if (random.nextBoolean()) {
            val day = if (random.nextBoolean()) random.nextInt(1, 29) else null
            ExpiryDate.of(random.nextInt(2000, 2100), random.nextInt(1, 13), day)
        } else {
            null
        }
        val reason = RemovalReason.entries.random(random)
        val removal = if (random.nextBoolean()) Box.Removal(reason, instant(random)) else null
        val deletedAt = if (random.nextInt(5) == 0) instant(random) else null
        // Only removed and deleted boxes may sit in a deleted location.
        val isHistory = removal != null || deletedAt != null
        val candidates = locations.filter { isHistory || it.deletedAt == null }
        val box = Box(
            id = id(random),
            product = ProductRef(gtin, text(1..200, random)),
            category = CategoryID(text(1..100, random)),
            categoryOverridden = random.nextBoolean(),
            locationId = candidates.random(random).location.id,
            expiry = expiry,
            lot = if (random.nextBoolean()) text(1..20, random) else null,
            // Unique per box, so a (gtin, serial) pair never repeats.
            serial = if (random.nextBoolean()) "S$index" else null,
            isOpened = random.nextBoolean(),
            createdAt = instant(random),
            updatedAt = instant(random),
            removal = removal
        )
        return ArchivedBox(box, deletedAt)
    }
}
