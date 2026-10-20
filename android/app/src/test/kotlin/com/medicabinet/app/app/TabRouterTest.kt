package com.medicabinet.app.app

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class TabRouterTest {
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
