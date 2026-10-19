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

/** One case of `fixtures/cabinet-rows.json`. */
@Serializable
data class CabinetRowsCase(
    val name: String,
    val categoryOrder: List<String>,
    val boxes: List<FixtureBox>,
    val filter: FixtureFilter,
    val expect: Expect
) {
    @Serializable
    data class FixtureExpiry(val year: Int, val month: Int, val day: Int? = null)

    @Serializable
    data class FixtureBox(
        val id: String,
        val gtin: String? = null,
        val name: String,
        val category: String,
        val location: String,
        val expiry: FixtureExpiry? = null,
        /** `expired` | `expiringSoon` | `ok` | `unknown` */
        val status: String,
        val createdOrder: Int
    )

    @Serializable
    data class FixtureFilter(val query: String, val location: String? = null, val status: String? = null)

    @Serializable
    data class FixtureRow(
        val name: String,
        val boxes: List<String>,
        val status: String,
        val earliestExpiry: FixtureExpiry? = null
    )

    @Serializable
    data class FixtureSection(val category: String, val rows: List<FixtureRow>)

    @Serializable
    data class Expect(val boxCount: Int, val sections: List<FixtureSection>)

    override fun toString(): String = name
}

/** One case of `fixtures/scan-removal.json`. */
@Serializable
data class ScanRemovalCase(val name: String, val scan: Scan, val cabinet: List<FixtureBox>, val expect: Expect) {
    @Serializable
    data class Scan(val gtin: String, val serial: String? = null, val expiry: CabinetRowsCase.FixtureExpiry? = null)

    @Serializable
    data class FixtureBox(
        val id: String,
        val gtin: String,
        val serial: String? = null,
        val expiry: CabinetRowsCase.FixtureExpiry? = null,
        /** `inCabinet` | `removed` | `deleted` */
        val state: String,
        val createdOrder: Int
    )

    @Serializable
    data class Expect(
        /** `remove` | `choose` | `alreadyRemoved` | `notInCabinet` */
        val decision: String,
        val box: String? = null,
        val candidates: List<String>? = null
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

fun SharedFixtures.cabinetRows(): CabinetCaseList<CabinetRowsCase> =
    cabinetFixtureJson.decodeFromString(text("cabinet-rows.json"))

fun SharedFixtures.scanRemoval(): CabinetCaseList<ScanRemovalCase> =
    cabinetFixtureJson.decodeFromString(text("scan-removal.json"))

fun SharedFixtures.schemaV3(): SchemaSnapshot = cabinetFixtureJson.decodeFromString(text("schema-v3.json"))
