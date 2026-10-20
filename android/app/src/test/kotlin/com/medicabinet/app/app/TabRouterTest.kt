package com.medicabinet.app.app

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class TabRouterTest {
    // Issue #53: the app opens on the cabinet; the camera only starts once the Scan tab is shown.
    @Test
    fun theLaunchTabIsTheCabinet() {
        assertEquals(AppTab.CABINET, AppTab.LAUNCH)
    }

    @Test
    fun aRequestIsKeptUntilTheMainScreenConsumesIt() {
        val router = TabRouter()
        assertNull(router.requested.value)
        router.select(AppTab.EXPIRING)
        assertEquals(AppTab.EXPIRING, router.requested.value)
        router.consume(AppTab.EXPIRING)
        assertNull(router.requested.value)
    }

    @Test
    fun aNewerRequestForAnotherTabSurvivesTheConsumptionOfAnOlderOne() {
        val router = TabRouter()
        router.select(AppTab.EXPIRING)
        router.select(AppTab.CABINET)
        router.consume(AppTab.EXPIRING)
        assertEquals(AppTab.CABINET, router.requested.value)
    }
}
