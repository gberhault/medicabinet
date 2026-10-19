package com.medicabinet.core.testing

import com.medicabinet.core.RemovalReason
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.testing.RepositoryContract.Check
import com.medicabinet.core.testing.RepositoryContract.GTIN
import com.medicabinet.core.testing.RepositoryContract.expect
import com.medicabinet.core.testing.RepositoryContract.require
import java.time.Duration

private const val OTHER_GTIN = "03400930000002"

/** Checks for deleted boxes (tombstones), the unique serial and the lookups the scan uses. */
internal val RepositoryContract.deletionChecks: List<Check>
    get() = listOf(
        Check("a deleted box disappears from every query") { env ->
            val location = env.location()
            env.locations.insert(location)
            val box = env.box(location, serial = "S1")
            env.boxes.insert(box)
            env.boxes.delete(box.id)
            require(env.boxes.activeBoxes().isEmpty(), "a deleted box must not be active")
            require(env.boxes.box(box.id) == null, "a deleted box must not be found by id")
            require(env.boxes.box(GTIN, "S1") == null, "found by serial")
            require(env.boxes.latestBox(GTIN) == null, "found as latest")
        },
        Check("undoDelete restores a deleted box") { env ->
            val location = env.location()
            env.locations.insert(location)
            val box = env.box(location)
            env.boxes.insert(box)
            env.boxes.delete(box.id)
            env.clock.advance(Duration.ofSeconds(30))
            env.boxes.undoDelete(box.id)
            require(env.boxes.activeBoxes().map { it.id } == listOf(box.id), "box should be active again")
            require(env.boxes.box(box.id)?.updatedAt == env.clock.now(), "updatedAt must be bumped")
            require(env.boxes.box(box.id)?.createdAt == box.createdAt, "createdAt must be kept")
        },
        Check("delete and undoDelete on unknown or wrong-state boxes throw notFound") { env ->
            val location = env.location()
            env.locations.insert(location)
            val ghost = env.box(location)
            expect(RepositoryError.NOT_FOUND) { env.boxes.delete(ghost.id) }
            expect(RepositoryError.NOT_FOUND) { env.boxes.undoDelete(ghost.id) }
            val box = env.box(location)
            env.boxes.insert(box)
            expect(RepositoryError.NOT_FOUND) { env.boxes.undoDelete(box.id) }
            env.boxes.delete(box.id)
            expect(RepositoryError.NOT_FOUND) { env.boxes.delete(box.id) }
            expect(RepositoryError.NOT_FOUND) { env.boxes.update(box) }
            expect(RepositoryError.NOT_FOUND) { env.boxes.markRemoved(box.id, RemovalReason.USED_UP, env.clock.now()) }
            expect(RepositoryError.NOT_FOUND) { env.boxes.undoRemoval(box.id) }
        },
        Check("a second box with the same gtin and serial is rejected with duplicateSerial") { env ->
            val location = env.location()
            env.locations.insert(location)
            env.boxes.insert(env.box(location, serial = "S1"))
            expect(RepositoryError.DUPLICATE_SERIAL) { env.boxes.insert(env.box(location, serial = "S1")) }
            env.boxes.insert(env.box(location, gtin = OTHER_GTIN, serial = "S1"))
            env.boxes.insert(env.box(location, serial = "S2"))
            env.boxes.insert(env.box(location, serial = null))
            env.boxes.insert(env.box(location, serial = null))
            require(env.boxes.activeBoxes().size == 5, "only the duplicate must be rejected")
        },
        Check("a deleted box frees its serial") { env ->
            val location = env.location()
            env.locations.insert(location)
            val first = env.box(location, serial = "S1")
            env.boxes.insert(first)
            env.boxes.delete(first.id)
            val second = env.box(location, serial = "S1")
            env.boxes.insert(second)
            require(env.boxes.box(GTIN, "S1")?.id == second.id, "wrong box")
        },
        Check("box by gtin and serial returns removed boxes but not deleted ones") { env ->
            val location = env.location()
            env.locations.insert(location)
            val removed = env.box(location, serial = "S1")
            val deleted = env.box(location, serial = "S2")
            env.boxes.insert(removed)
            env.boxes.insert(deleted)
            env.boxes.markRemoved(removed.id, RemovalReason.USED_UP, env.clock.now())
            env.boxes.delete(deleted.id)
            val found = env.boxes.box(GTIN, "S1")
            require(
                found?.id == removed.id && found.removal?.reason == RemovalReason.USED_UP,
                "removed box must be found"
            )
            require(env.boxes.box(GTIN, "S2") == null, "deleted box found")
            require(env.boxes.box(GTIN, "S3") == null, "unknown serial found")
        },
        Check("latestBox returns the most recent non-deleted box of a gtin") { env ->
            val location = env.location()
            env.locations.insert(location)
            val older = env.box(location, name = "Older")
            env.boxes.insert(older)
            env.clock.advance(Duration.ofSeconds(10))
            val newer = env.box(location, name = "Newer")
            env.boxes.insert(newer)
            env.clock.advance(Duration.ofSeconds(10))
            env.boxes.insert(env.box(location, name = "Other", gtin = OTHER_GTIN))
            require(env.boxes.latestBox(GTIN)?.id == newer.id, "newest expected")
            env.boxes.delete(newer.id)
            require(env.boxes.latestBox(GTIN)?.id == older.id, "older expected")
            require(env.boxes.latestBox("03400930000009") == null, "unknown gtin must be null")
        },
        Check("update to a serial already in the cabinet is rejected") { env ->
            val location = env.location()
            env.locations.insert(location)
            env.boxes.insert(env.box(location, serial = "S1"))
            val second = env.box(location, serial = "S2")
            env.boxes.insert(second)
            expect(RepositoryError.DUPLICATE_SERIAL) { env.boxes.update(second.copy(serial = "S1")) }
            require(env.boxes.box(second.id)?.serial == "S2", "a rejected update must not persist")
        },
        Check("undoDelete is rejected when the serial was taken meanwhile") { env ->
            val location = env.location()
            env.locations.insert(location)
            val first = env.box(location, serial = "S1")
            env.boxes.insert(first)
            env.boxes.delete(first.id)
            val second = env.box(location, serial = "S1")
            env.boxes.insert(second)
            expect(RepositoryError.DUPLICATE_SERIAL) { env.boxes.undoDelete(first.id) }
            require(env.boxes.activeBoxes().map { it.id } == listOf(second.id), "first must stay deleted")
        }
    )
