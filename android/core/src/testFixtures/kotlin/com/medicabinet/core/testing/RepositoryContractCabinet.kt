package com.medicabinet.core.testing

import com.medicabinet.core.RemovalReason
import com.medicabinet.core.testing.RepositoryContract.Check
import com.medicabinet.core.testing.RepositoryContract.GTIN
import com.medicabinet.core.testing.RepositoryContract.require
import java.time.Duration

private const val OTHER_GTIN = "03400930000002"
private const val UNKNOWN_GTIN = "03400930000009"
private val STEP: Duration = Duration.ofSeconds(10)

/** Checks for the queries the Cabinet tab and removal by scan use. */
internal val RepositoryContract.cabinetChecks: List<Check>
    get() = listOf(
        Check("cabinetBoxes returns only boxes in the cabinet with that gtin, oldest first") { env ->
            val location = env.location()
            env.locations.insert(location)
            val first = env.box(location, name = "First")
            env.boxes.insert(first)
            env.clock.advance(STEP)
            val removed = env.box(location, name = "Removed")
            env.boxes.insert(removed)
            env.clock.advance(STEP)
            val deleted = env.box(location, name = "Deleted")
            env.boxes.insert(deleted)
            env.clock.advance(STEP)
            val other = env.box(location, name = "Other", gtin = OTHER_GTIN)
            env.boxes.insert(other)
            env.clock.advance(STEP)
            val last = env.box(location, name = "Last")
            env.boxes.insert(last)
            env.boxes.markRemoved(removed.id, RemovalReason.THROWN_AWAY, env.clock.now())
            env.boxes.delete(deleted.id)

            require(
                env.boxes.cabinetBoxes(GTIN).map { it.id } == listOf(first.id, last.id),
                "expected the two boxes in the cabinet, oldest first"
            )
            require(env.boxes.cabinetBoxes(OTHER_GTIN).map { it.id } == listOf(other.id), "other gtin")
            require(env.boxes.cabinetBoxes(UNKNOWN_GTIN).isEmpty(), "unknown gtin must be empty")
        }
    )
