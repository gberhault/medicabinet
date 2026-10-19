package com.medicabinet.core

import com.medicabinet.core.testing.InMemoryCabinetStore
import com.medicabinet.core.testing.RepositoryContract
import com.medicabinet.core.testing.SharedFixtures
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class InMemoryRepositoryContractTest {
    @TestFactory
    fun fakeSatisfiesContract(): List<DynamicTest> = RepositoryContract.checks.map { check ->
        dynamicTest(check.name) {
            runBlocking {
                check.run { clock ->
                    val store = InMemoryCabinetStore(clock)
                    store to store
                }
            }
        }
    }

    @Test
    fun contractChecksMatchTheSharedList() {
        assertEquals(SharedFixtures.repositoryContractCheckNames(), RepositoryContract.checks.map { it.name })
    }

    @Test
    fun repositoryErrorsUseThePlatformNeutralNames() {
        assertEquals(
            listOf("notFound", "unknownLocation", "duplicate", "duplicateDefault"),
            RepositoryError.entries.map { it.rawValue }
        )
    }
}
