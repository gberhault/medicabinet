package com.medicabinet.core.testing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One case of `fixtures/text-normalization.json`: [kind] is `normalize` or `match`. */
@Serializable
data class TextNormalizationCase(
    val name: String,
    val kind: String,
    val input: String? = null,
    val normalized: String? = null,
    val text: String? = null,
    val query: String? = null,
    val matches: Boolean? = null
) {
    override fun toString(): String = name
}

/** One case of `fixtures/location-names.json`. */
@Serializable
data class LocationNameCase(
    val name: String,
    val input: String,
    val existing: List<String>,
    val renaming: String? = null,
    val expect: Expect
) {
    @Serializable
    data class Expect(
        val name: String? = null,
        /** `empty` | `tooLong` | `duplicateName` */
        val error: String? = null
    )

    override fun toString(): String = name
}

@Serializable
data class CabinetCaseList<T>(val formatVersion: Int, val description: String, val cases: List<T>)

private val cabinetFixtureJson = Json { ignoreUnknownKeys = true }

/** Loaders of the cabinet-inventory fixtures, kept apart from [SharedFixtures] so that file stays small. */
fun SharedFixtures.textNormalization(): CabinetCaseList<TextNormalizationCase> =
    cabinetFixtureJson.decodeFromString(text("text-normalization.json"))

fun SharedFixtures.locationNames(): CabinetCaseList<LocationNameCase> =
    cabinetFixtureJson.decodeFromString(text("location-names.json"))
