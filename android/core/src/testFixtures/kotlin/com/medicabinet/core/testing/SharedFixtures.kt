package com.medicabinet.core.testing

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** One case of `fixtures/expiry-dates.json`, the test vectors shared with the iOS app. */
@Serializable
data class ExpiryDateCase(val name: String, val year: Int, val month: Int, val day: Int? = null, val expect: Expect) {
    @Serializable
    data class Expect(
        val lastValidDay: Int? = null,
        val sortKey: String? = null,
        val monthPrecision: Boolean? = null,
        /** `invalidYear` | `invalidMonth` | `invalidDay` */
        val error: String? = null
    )

    override fun toString(): String = name
}

@Serializable
data class ExpiryDateFixture(val formatVersion: Int, val description: String, val cases: List<ExpiryDateCase>)

/** One case of `fixtures/categories.json`: a category in display order. */
@Serializable
data class CategoryCase(val name: String, val assignedByBuild: Boolean, val userSelectable: Boolean) {
    override fun toString(): String = name
}

/** One case of `fixtures/reference-lookup.json`: a GTIN, the CIP13 it converts to and the sample database's answer. */
@Serializable
data class ReferenceLookupCase(val name: String, val gtin: String, val expect: Expect) {
    @Serializable
    data class Expect(val cip13: String? = null, val product: Product? = null)

    @Serializable
    data class Product(val name: String, val category: String)

    override fun toString(): String = name
}

/** One case of `fixtures/gs1-datamatrix.json`, the payloads shared with the iOS app. */
@Serializable
data class GS1Case(val name: String, val payload: String, val shape: String, val expect: Expect) {
    @Serializable
    data class Expect(
        val gtin: String? = null,
        /** `dataMatrix` | `ean13` */
        val symbology: String? = null,
        /** `"missing"`, `{year, month, day|null}` or `{invalid: kind}`; see the fixture description. */
        val expiry: JsonElement? = null,
        val lot: String? = null,
        val serial: String? = null,
        /** `empty` | `tooLong` | `invalidCharacters` | `missingGTIN` | `invalidGTIN` */
        val error: String? = null
    )

    override fun toString(): String = name
}

@Serializable
data class GS1Fixture(val formatVersion: Int, val description: String, val cases: List<GS1Case>)

/** One case of `fixtures/scan-debounce.json`. Times are milliseconds from an arbitrary start. */
@Serializable
data class ScanDebounceCase(
    val name: String,
    val rescanGapMs: Int,
    val frames: List<Frame>,
    val expectScans: List<Scan>
) {
    @Serializable
    data class Frame(val t: Long, val visible: List<String>)

    @Serializable
    data class Scan(val t: Long, val payload: String)

    override fun toString(): String = name
}

/** One case of `fixtures/scan-intake.json`. */
@Serializable
data class IntakeCase(
    val name: String,
    val scan: Scan,
    val productKnown: Boolean,
    /** `inCabinet` | `removed` | `null` */
    val cabinetMatch: String? = null,
    val sessionHasGTIN: Boolean,
    val expect: Expect
) {
    @Serializable
    data class Scan(
        val hasSerial: Boolean,
        /** `known` | `missing` | `invalid` */
        val expiry: String
    )

    @Serializable
    data class Expect(
        /** `add` | `complete` | `confirmAnother` | `alreadyInCabinet` | `removedEarlier` */
        val decision: String,
        val missing: List<String>? = null,
        val thenMissing: List<String>? = null,
        val expiryUnreadable: Boolean? = null
    )

    override fun toString(): String = name
}

/** One case of `fixtures/product-resolution.json`. */
@Serializable
data class ProductResolutionCase(
    val name: String,
    val reference: Reference? = null,
    val previousBox: Previous? = null,
    val expect: Expect
) {
    @Serializable
    data class Reference(val name: String, val category: String? = null)

    @Serializable
    data class Previous(val name: String, val category: String, val categoryOverridden: Boolean)

    @Serializable
    data class Expect(val name: String? = null, val category: String, val categoryOverridden: Boolean)

    override fun toString(): String = name
}

/** One case of `fixtures/archive-code-points.json`. */
@Serializable
data class ArchiveCodePointCase(val name: String, val text: String, val codePoints: Int)

@Serializable
data class ArchiveCodePointFixture(
    val formatVersion: Int,
    val description: String,
    val cases: List<ArchiveCodePointCase>
)

@Serializable
private data class CaseList<T>(val cases: List<T>)

/** Loads the repository's shared fixtures (folder given by the `medicabinet.fixtures` system property). */
object SharedFixtures {
    private val json = Json { ignoreUnknownKeys = true }

    val directory: File
        get() = File(requireNotNull(System.getProperty("medicabinet.fixtures")) { "medicabinet.fixtures is not set" })

    fun text(name: String): String = File(directory, name).readText()

    fun expiryDates(): ExpiryDateFixture = json.decodeFromString(text("expiry-dates.json"))

    fun scanDebounceCases(): List<ScanDebounceCase> =
        json.decodeFromString<CaseList<ScanDebounceCase>>(text("scan-debounce.json")).cases

    fun intakeCases(): List<IntakeCase> = json.decodeFromString<CaseList<IntakeCase>>(text("scan-intake.json")).cases

    fun productResolutionCases(): List<ProductResolutionCase> =
        json.decodeFromString<CaseList<ProductResolutionCase>>(text("product-resolution.json")).cases

    fun gs1Datamatrix(): GS1Fixture = json.decodeFromString(text("gs1-datamatrix.json"))

    fun archiveCodePoints(): ArchiveCodePointFixture = json.decodeFromString(text("archive-code-points.json"))

    fun archiveCaseFixture(): ArchiveCaseFixture = json.decodeFromString(text("archive-cases.json"))

    /** The canonical archive of [version], kept forever (`fixtures/archive/v<version>/golden.medicabinet`). */
    fun goldenArchive(version: Int = 1): ByteArray = File(directory, "archive/v$version/golden.medicabinet").readBytes()

    fun goldenArchiveExists(version: Int): Boolean = File(directory, "archive/v$version/golden.medicabinet").exists()

    fun schemaV1(): SchemaSnapshot = json.decodeFromString(text("schema-v1.json"))

    fun schemaV2(): SchemaSnapshot = json.decodeFromString(text("schema-v2.json"))

    /** Names of the repository contract checks, in order (`fixtures/repository-contract.json`). */
    fun repositoryContractCheckNames(): List<String> =
        json.decodeFromString<NamedCases>(text("repository-contract.json")).cases.map { it.name }

    /** The taxonomy in display order (`fixtures/categories.json`). */
    fun categories(): List<CategoryCase> = json.decodeFromString<Cases<CategoryCase>>(text("categories.json")).cases

    fun referenceLookup(): List<ReferenceLookupCase> =
        json.decodeFromString<Cases<ReferenceLookupCase>>(text("reference-lookup.json")).cases

    /** The sample reference database the lookup cases are answered from (`fixtures/reference-sample.sqlite`). */
    val referenceSample: File get() = File(directory, "reference-sample.sqlite")

    @Serializable
    private data class Cases<T>(val cases: List<T>)

    @Serializable
    private data class NamedCases(val cases: List<NamedCase>)

    @Serializable
    private data class NamedCase(val name: String)
}
