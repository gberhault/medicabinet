package com.medicabinet.core.testing

import com.medicabinet.core.ExpiryDate
import java.time.Instant
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

private val fixtureJson = Json { ignoreUnknownKeys = true }

fun SharedFixtures.expiryStatus(): ExpiryStatusFixture = fixtureJson.decodeFromString(text("expiry-status.json"))
