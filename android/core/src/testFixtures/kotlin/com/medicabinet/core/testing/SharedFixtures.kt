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

/** Loads the repository's shared fixtures (folder given by the `medicabinet.fixtures` system property). */
object SharedFixtures {
    private val json = Json { ignoreUnknownKeys = true }

    val directory: File
        get() = File(requireNotNull(System.getProperty("medicabinet.fixtures")) { "medicabinet.fixtures is not set" })

    fun text(name: String): String = File(directory, name).readText()

    fun expiryDates(): ExpiryDateFixture = json.decodeFromString(text("expiry-dates.json"))

    /** Names of the repository contract checks, in order (`fixtures/repository-contract.json`). */
    fun repositoryContractCheckNames(): List<String> =
        json.decodeFromString<NamedCases>(text("repository-contract.json")).cases.map { it.name }

    @Serializable
    private data class NamedCases(val cases: List<NamedCase>)

    @Serializable
    private data class NamedCase(val name: String)
}
