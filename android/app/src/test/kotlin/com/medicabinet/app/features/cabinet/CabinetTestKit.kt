package com.medicabinet.app.features.cabinet

import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.core.Box
import com.medicabinet.core.BoxRepository
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.Location
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.reference.ReferenceDataDate
import com.medicabinet.core.reference.ReferenceProduct
import com.medicabinet.core.reference.ReferenceStatus
import com.medicabinet.core.testing.InMemoryCabinetStore
import com.medicabinet.core.testing.InMemoryReferenceData
import com.medicabinet.core.testing.MutableClock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * An in-memory cabinet with a default location, a clock the test moves, and a small reference database. "Today" is
 * 2027-01-15 (UTC) and the expiring-soon window is the 60-day default.
 */
class CabinetTestKit(products: Map<String, ReferenceProduct> = emptyMap()) {
    val clock = MutableClock(START)
    val store = InMemoryCabinetStore(clock)
    private val reference = InMemoryReferenceData(
        products,
        ReferenceStatus.Available(ReferenceDataDate(2026, 10, 1))
    )
    val environment = AppEnvironment(store, store, store.named, clock, reference, ZoneOffset.UTC)
    lateinit var home: Location
        private set

    suspend fun open(): CabinetTestKit {
        home = store.ensureDefault("Home")
        return this
    }

    suspend fun box(
        name: String,
        gtin: String? = null,
        expiry: ExpiryDate? = null,
        location: Location = home,
        category: CategoryID = CategoryID.UNCATEGORIZED,
        overridden: Boolean = false,
        opened: Boolean = false,
        lot: String? = null,
        serial: String? = null
    ): Box {
        val box = Box(
            product = ProductRef(gtin, name),
            category = category,
            categoryOverridden = overridden,
            locationId = location.id,
            expiry = expiry,
            lot = lot,
            serial = serial,
            isOpened = opened,
            createdAt = clock.now()
        )
        store.insert(box)
        clock.advance(Duration.ofSeconds(1))
        return box
    }

    /** An environment whose boxes cannot be read, for the error states. */
    fun failingEnvironment(): AppEnvironment {
        val broken = object : BoxRepository by store {
            override fun observeCabinet(): Flow<List<Box>> = flow { throw IllegalStateException("unreadable") }
        }
        return AppEnvironment(store, broken, store.named, clock, reference, ZoneOffset.UTC)
    }

    /** An environment whose writes to boxes fail. */
    fun readOnlyEnvironment(): AppEnvironment {
        val readOnly = object : BoxRepository by store {
            override suspend fun update(box: Box) = throw IllegalStateException("read only")
            override suspend fun delete(id: UUID) = throw IllegalStateException("read only")

            override suspend fun markRemoved(id: UUID, reason: RemovalReason, at: Instant) =
                throw IllegalStateException("read only")
        }
        return AppEnvironment(store, readOnly, store.named, clock, reference, ZoneOffset.UTC)
    }

    companion object {
        val START: Instant = Instant.ofEpochSecond(1_800_000_000)
        const val DOLIPRANE = "03400930000014"
        const val EFFERALGAN = "03400930000021"

        val EXPIRED: ExpiryDate = ExpiryDate.of(2026, 12, null)
        val SOON: ExpiryDate = ExpiryDate.of(2027, 2, 10)
        val FINE: ExpiryDate = ExpiryDate.of(2028, 6, null)
    }
}
