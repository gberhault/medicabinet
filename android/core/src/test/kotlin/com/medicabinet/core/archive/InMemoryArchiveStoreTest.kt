package com.medicabinet.core.archive

import com.medicabinet.core.testing.ArchiveStoreContract
import com.medicabinet.core.testing.InMemoryArchiveBackupStore
import com.medicabinet.core.testing.InMemoryArchiveStore
import com.medicabinet.core.testing.SharedFixtures
import com.medicabinet.core.testing.SyntheticArchive
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class InMemoryArchiveStoreTest {
    @TestFactory
    fun fakeSatisfiesTheStoreContract(): List<DynamicTest> = ArchiveStoreContract.checks.map { check ->
        dynamicTest(check.name) { runBlocking { check.run { InMemoryArchiveStore() } } }
    }

    @Test
    fun contractChecksMatchTheSharedList() {
        assertEquals(SharedFixtures.archiveStoreContractCheckNames(), ArchiveStoreContract.checks.map { it.name })
    }
}

class InMemoryArchiveBackupStoreTest {
    private val validArchive = SyntheticArchive.json(locations = 2, boxes = 3).toByteArray()
    private val day = Instant.ofEpochSecond(1_800_000_000)

    @Test
    fun aWrittenBackupIsListedWithItsSummaryAndReadsBack() = runBlocking {
        val store = InMemoryArchiveBackupStore()
        val entry = store.write(validArchive, BackupReason.BEFORE_IMPORT, day)
        assertEquals(CabinetSummary(2, 3, 0, 0, 0), entry.summary)
        assertEquals(listOf(entry), store.list())
        assertEquals(validArchive.toList(), store.read(entry.id).toList())
    }

    @Test
    fun somethingThatIsNotAnArchiveIsNotWritten() {
        val store = InMemoryArchiveBackupStore()
        val notAnArchive = byteArrayOf('{'.code.toByte())
        assertFailsWith<ArchiveException> { store.write(notAnArchive, BackupReason.BEFORE_IMPORT, day) }
        assertEquals(0, store.count)
    }

    @Test
    fun backupsAreListedNewestFirstAndPrunedFromTheOldest() = runBlocking {
        val store = InMemoryArchiveBackupStore()
        for (offset in 0L until 5L) store.write(validArchive, BackupReason.BEFORE_RESTORE, day.plusSeconds(offset))
        assertEquals((0L until 5L).reversed().map { day.plusSeconds(it) }, store.list().map { it.takenAt })
        store.prune(3)
        assertEquals((2L until 5L).reversed().map { day.plusSeconds(it) }, store.list().map { it.takenAt })
        store.prune(-1)
        assertEquals(0, store.count)
    }

    @Test
    fun aDamagedBackupIsStillListedAsUnreadable() = runBlocking {
        val store = InMemoryArchiveBackupStore()
        val entry = store.write(validArchive, BackupReason.BEFORE_IMPORT, day)
        store.corrupt(entry.id)
        val listed = store.list()
        assertEquals(1, listed.size)
        assertNull(listed.first().summary)
    }

    @Test
    fun writesAndPrunesCanBeMadeToFail() = runBlocking {
        val store = InMemoryArchiveBackupStore()
        store.setFailingWrites(true)
        assertFailsWith<InMemoryArchiveBackupStore.Failure> {
            store.write(validArchive, BackupReason.BEFORE_IMPORT, day)
        }
        store.setFailingWrites(false)
        store.write(validArchive, BackupReason.BEFORE_IMPORT, day)
        store.setFailingPrunes(true)
        assertFailsWith<InMemoryArchiveBackupStore.Failure> { store.prune(1) }
        assertFailsWith<InMemoryArchiveBackupStore.Failure> { store.read("missing") }
    }
}
