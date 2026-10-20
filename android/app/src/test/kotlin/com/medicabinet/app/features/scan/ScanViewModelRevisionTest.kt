package com.medicabinet.app.features.scan

import com.medicabinet.core.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/** I7, I10, design §1.6: after an import or a restore, the scan session ends and points at the new cabinet only. */
class ScanViewModelRevisionTest {
    @Test
    fun aReplacedCabinetEndsTheSessionWhenTheScreenLoadsAgain() = runBlocking {
        val kit = ScanTestKit().open()
        kit.see(ScanTestKit.payload())
        kit.see(ScanTestKit.payload(gtin = "03400930000021", serial = "OTHER"), at = 5.0)
        assertEquals(1, kit.model.state.value.session.count)

        kit.environment.cabinetReplaced()
        kit.model.load()

        val state = kit.model.state.value
        assertEquals(0, state.session.count)
        assertEquals(0, state.removalSession.count)
        assertNull(state.notice)
        assertNull(state.completion)
        assertNull(state.another)
    }

    @Test
    fun loadingWithoutAReplaceKeepsTheSession() = runBlocking {
        val kit = ScanTestKit().open()
        kit.see(ScanTestKit.payload())
        kit.model.load()
        assertEquals(1, kit.model.state.value.session.count)
    }

    @Test
    fun aSessionLocationThatNoLongerExistsFallsBackToTheDefault() = runBlocking {
        val kit = ScanTestKit().open()
        val kitchen = Location(name = "Kitchen", sortOrder = 1, createdAt = kit.clock.now())
        kit.store.insert(kitchen)
        kit.model.load()
        kit.model.selectLocation(kitchen.id)

        // The import brought a cabinet without the kitchen.
        kit.store.deleteLocation(kitchen.id, movingBoxesTo = null)
        kit.environment.cabinetReplaced()
        kit.model.load()

        assertEquals(kit.defaultLocation.id, kit.model.state.value.sessionLocation?.id)
    }
}
