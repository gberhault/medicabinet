package com.medicabinet.core.testing

import com.medicabinet.core.Box
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RemovalReason
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** An expiry as written in the shared fixtures (`day` null = month precision). */
@Serializable
data class FixtureExpiry(val year: Int, val month: Int, val day: Int? = null) {
    fun expiryDate(): ExpiryDate = ExpiryDate.of(year, month, day)
}

/** One case of `fixtures/expiry-status.json` → `cases`. */
@Serializable
data class ExpiryStatusCase(
    val name: String,
    val today: String,
    val expiry: FixtureExpiry? = null,
    val windowDays: Int,
    val expect: Expect
) {
    @Serializable
    data class Expect(val status: String, val daysLeft: Int? = null)

    override fun toString(): String = name
}

/** One case of `fixtures/expiry-status.json` → `todayCases`. */
@Serializable
data class TodayCase(
    val name: String,
    val instant: String,
    val timeZone: String,
    val expect: String,
    val minutesSinceMidnight: Int
) {
    fun instantValue(): Instant = Instant.parse(instant)

    override fun toString(): String = name
}

@Serializable
data class ExpiryStatusFixture(
    val formatVersion: Int,
    val description: String,
    val cases: List<ExpiryStatusCase>,
    val todayCases: List<TodayCase>
)

private const val FIXTURE_CREATED_AT_SECONDS = 0L

private fun fixtureBox(
    id: UUID = UUID.randomUUID(),
    name: String,
    expiry: FixtureExpiry?,
    inCabinet: Boolean,
    location: UUID
): Box {
    val moment = Instant.ofEpochSecond(FIXTURE_CREATED_AT_SECONDS)
    return Box(
        id = id,
        product = ProductRef(name = name),
        locationId = location,
        expiry = expiry?.expiryDate(),
        createdAt = moment,
        removal = if (inCabinet) null else Box.Removal(RemovalReason.THROWN_AWAY, moment)
    )
}

/** A box as written in `fixtures/expiring-overview.json`. */
@Serializable
data class FixtureBox(val id: String, val name: String, val expiry: FixtureExpiry? = null, val inCabinet: Boolean) {
    fun box(location: UUID): Box = fixtureBox(UUID.fromString(id), name, expiry, inCabinet, location)
}

/** One case of `fixtures/expiring-overview.json`. */
@Serializable
data class OverviewCase(
    val name: String,
    val today: String,
    val windowDays: Int,
    val boxes: List<FixtureBox>,
    val expect: Expect
) {
    @Serializable
    data class Expect(
        val expired: List<String>,
        val expiringSoon: List<String>,
        val unknown: List<String>,
        val badge: Int,
        val isEmpty: Boolean
    )

    override fun toString(): String = name
}

@Serializable
data class OverviewFixture(val formatVersion: Int, val description: String, val cases: List<OverviewCase>)

/** A box as written in `fixtures/expiry-digest.json` (only what the digest looks at). */
@Serializable
data class DigestBox(val expiry: FixtureExpiry? = null, val inCabinet: Boolean) {
    fun box(location: UUID): Box = fixtureBox(name = "Box", expiry = expiry, inCabinet = inCabinet, location = location)
}

/** One case of `fixtures/expiry-digest.json` → `cases`. */
@Serializable
data class DigestCountCase(
    val name: String,
    val digestDay: String,
    val windowDays: Int,
    val boxes: List<DigestBox>,
    /** `null`: nothing is sent. */
    val expect: Expect? = null
) {
    @Serializable
    data class Expect(val expired: Int, val expiringSoon: Int)

    override fun toString(): String = name
}

/** One case of `fixtures/expiry-digest.json` → `scheduleCases`. */
@Serializable
data class DigestScheduleCase(
    val name: String,
    val today: String,
    val minutesSinceMidnight: Int,
    val count: Int,
    val expect: Expect
) {
    @Serializable
    data class Expect(val upcoming: List<String>, val due: String)

    override fun toString(): String = name
}

@Serializable
data class DigestFixture(
    val formatVersion: Int,
    val description: String,
    val cases: List<DigestCountCase>,
    val scheduleCases: List<DigestScheduleCase>
)

private val fixtureJson = Json { ignoreUnknownKeys = true }

fun SharedFixtures.expiryStatus(): ExpiryStatusFixture = fixtureJson.decodeFromString(text("expiry-status.json"))

fun SharedFixtures.expiringOverview(): OverviewFixture = fixtureJson.decodeFromString(text("expiring-overview.json"))

fun SharedFixtures.expiryDigest(): DigestFixture = fixtureJson.decodeFromString(text("expiry-digest.json"))
