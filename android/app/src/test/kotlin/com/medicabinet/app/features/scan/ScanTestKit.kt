package com.medicabinet.app.features.scan

import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.features.scan.camera.FakeCodeScanner
import com.medicabinet.app.features.scan.camera.VisibleCodes
import com.medicabinet.core.Box
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.Location
import com.medicabinet.core.reference.ProductLookup
import com.medicabinet.core.reference.ReferenceProduct
import com.medicabinet.core.reference.ReferenceStatus
import com.medicabinet.core.reference.ReferenceUnavailableReason
import com.medicabinet.core.testing.InMemoryCabinetStore
import com.medicabinet.core.testing.InMemoryReferenceData
import com.medicabinet.core.testing.MutableClock
import java.time.Instant

class StubLookup(private val products: Map<String, ReferenceProduct> = emptyMap()) : ProductLookup {
    override suspend fun product(gtin: String): ReferenceProduct? = products[gtin]
}

/** A view model on an in-memory cabinet with a default location, and a fake scanner the test controls. */
class ScanTestKit(
    lookup: ProductLookup = StubLookup(mapOf(GTIN to ReferenceProduct("DOLIPRANE"))),
    hasCamera: Boolean = true
) {
    val clock = MutableClock(START)
    val store = InMemoryCabinetStore(clock)
    val scanner = FakeCodeScanner()
    val preferences = InMemoryScanPreferences()
    val services = ScanServices({ scanner }, preferences, lookup, hasCamera)
    val environment =
        AppEnvironment(
            store,
            store,
            clock,
            InMemoryReferenceData(status = ReferenceStatus.Unavailable(ReferenceUnavailableReason.MISSING))
        )
    val model = ScanViewModel(environment, services)
    lateinit var defaultLocation: Location
        private set
    val feedback = mutableListOf<FeedbackRequest>()

    /** Creates the default location and loads the locations into the model. */
    suspend fun open(): ScanTestKit {
        defaultLocation = store.ensureDefault("Cabinet")
        model.load()
        return this
    }

    /** One frame with these codes in view, `seconds` after the start. */
    suspend fun see(vararg payloads: String, at: Double = 0.0) {
        model.handle(VisibleCodes(payloads.toList(), START.plusMillis((at * MILLIS).toLong())))
    }

    suspend fun storedBoxes(): List<Box> = store.activeBoxes()

    companion object {
        val START: Instant = Instant.ofEpochSecond(1_800_000_000)
        const val GTIN = "03400930000014"
        const val GS = "\u001D"
        private const val MILLIS = 1000

        /** A GS1 DataMatrix payload. */
        fun payload(
            gtin: String = GTIN,
            expiry: String? = "270331",
            lot: String? = "LOT1",
            serial: String? = "SER1"
        ): String {
            val text = StringBuilder("01$gtin")
            if (expiry != null) text.append("17$expiry")
            if (lot != null) text.append("10$lot").append(if (serial == null) "" else GS)
            if (serial != null) text.append("21$serial")
            return text.toString()
        }

        fun date(year: Int, month: Int, day: Int?): ExpiryDate = ExpiryDate.of(year, month, day)
    }
}

/** Services for the screens: a fake camera, in-memory preferences, one known product. */
fun testScanServices(): ScanServices =
    ScanServices({ FakeCodeScanner() }, InMemoryScanPreferences(), StubLookup(), hasCamera = true)

/** A cabinet environment with a default location, for screens that need one. */
fun testEnvironment(): AppEnvironment {
    val clock = MutableClock(ScanTestKit.START)
    val store = InMemoryCabinetStore(clock)
    return AppEnvironment(
        store,
        store,
        clock,
        InMemoryReferenceData(status = ReferenceStatus.Unavailable(ReferenceUnavailableReason.MISSING))
    )
}
