package com.medicabinet.app.app

import com.medicabinet.core.Box
import com.medicabinet.core.NamedProductRepository
import com.medicabinet.core.ProductRef
import com.medicabinet.core.reference.ReferenceDataDate
import com.medicabinet.core.reference.ReferenceStatus
import com.medicabinet.core.reference.ReferenceUnavailableReason
import com.medicabinet.core.testing.InMemoryCabinetStore
import com.medicabinet.core.testing.InMemoryReferenceData
import com.medicabinet.core.testing.MutableClock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class NamedProductBackfillLauncherTest {
    private val gtin = "03401900000003"
    private val clock = MutableClock(Instant.ofEpochSecond(1_800_000_000))
    private val store = InMemoryCabinetStore(clock)

    private suspend fun cabinetWithABox() {
        val location = store.ensureDefault("Cabinet")
        store.insert(Box(product = ProductRef(gtin, "Arnica"), locationId = location.id, createdAt = clock.now()))
    }

    private fun environment(
        status: ReferenceStatus = ReferenceStatus.Available(ReferenceDataDate(2026, 10, 18)),
        named: NamedProductRepository = store.named
    ) = AppEnvironment(store, store, named, clock, InMemoryReferenceData(status = status), ZoneOffset.UTC)

    @Test
    fun theRunnerFillsTheNamedProductsOnceAndClearsTheMarkerInTheBackground() = runTest {
        cabinetWithABox()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val launcher = NamedProductBackfillLauncher(CoroutineScope(SupervisorJob() + dispatcher), dispatcher)
        val job = launcher.start(environment())
        assertTrue(store.named.backfillPending(), "start must return before the fill runs")
        job.join()
        assertEquals("Arnica", store.named.namedProduct(gtin)?.name)
        assertEquals(false, store.named.backfillPending())
    }

    @Test
    fun anUnavailableReferenceLeavesTheFillPendingForTheNextLaunch() = runTest {
        cabinetWithABox()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val unavailable = ReferenceStatus.Unavailable(ReferenceUnavailableReason.MISSING)
        NamedProductBackfillLauncher(CoroutineScope(SupervisorJob() + dispatcher), dispatcher)
            .start(environment(unavailable))
            .join()
        assertNull(store.named.namedProduct(gtin))
        assertTrue(store.named.backfillPending())
    }

    @Test
    fun aFailingStoreIsLoggedAndNeverCrashesTheApp() = runTest {
        cabinetWithABox()
        val failing = object : NamedProductRepository by store.named {
            override suspend fun backfillPending(): Boolean = throw IllegalStateException("unreadable")
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val job = NamedProductBackfillLauncher(scope, dispatcher).start(environment(named = failing))
        job.join()
        assertTrue(job.isCompleted && !job.isCancelled)
    }
}
