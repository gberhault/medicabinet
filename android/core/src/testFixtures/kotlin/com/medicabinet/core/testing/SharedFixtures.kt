package com.medicabinet.core.testing

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

/** Loads the repository's shared fixtures (folder given by the `medicabinet.fixtures` system property). */
object SharedFixtures {
    private val json = Json { ignoreUnknownKeys = true }

    val directory: File
        get() = File(requireNotNull(System.getProperty("medicabinet.fixtures")) { "medicabinet.fixtures is not set" })

    fun text(name: String): String = File(directory, name).readText()

    fun expiryDates(): ExpiryDateFixture = json.decodeFromString(text("expiry-dates.json"))

    fun schemaV1(): SchemaSnapshot = json.decodeFromString(text("schema-v1.json"))

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
