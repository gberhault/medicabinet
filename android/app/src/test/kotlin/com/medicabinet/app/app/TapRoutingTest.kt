package com.medicabinet.app.app

import android.content.Intent
import com.medicabinet.app.MainActivity
import com.medicabinet.app.MediCabinetApplication
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TapRoutingTest {
    private val app get() = RuntimeEnvironment.getApplication() as MediCabinetApplication

    @Test
    fun theDigestsTapDestinationSelectsTheExpiringTab() {
        val router = TabRouter()
        assertTrue(router.handle(Intent().putExtra(TabRouter.EXTRA_DESTINATION, "expiring")))
        assertEquals(AppTab.EXPIRING, router.requested.value)
    }

    @Test
    fun anUnknownOrMissingDestinationIsIgnored() {
        val router = TabRouter()
        assertFalse(router.handle(Intent().putExtra(TabRouter.EXTRA_DESTINATION, "../../etc")))
        assertFalse(router.handle(Intent()))
        assertFalse(router.handle(null))
        assertNull(router.requested.value)
    }

    @Test
    fun theExtraIsNamedDestination() {
        assertEquals("destination", TabRouter.EXTRA_DESTINATION)
    }

    @Test
    fun launchingFromTheNotificationOpensTheExpiringTab() {
        val intent = Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java)
            .putExtra(TabRouter.EXTRA_DESTINATION, "expiring")
        Robolectric.buildActivity(MainActivity::class.java, intent).create()
        assertEquals(AppTab.EXPIRING, app.tabRouter.requested.value)
    }

    @Test
    fun aPlainLaunchDoesNotRouteAnywhere() {
        Robolectric.buildActivity(MainActivity::class.java).create()
        assertNull(app.tabRouter.requested.value)
    }

    @Test
    fun tappingTheDigestWhileTheAppIsOpenRoutesThroughOnNewIntent() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        assertNull(app.tabRouter.requested.value)
        controller.newIntent(
            Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java)
                .putExtra(TabRouter.EXTRA_DESTINATION, "expiring")
        )
        assertEquals(AppTab.EXPIRING, app.tabRouter.requested.value)
    }

    @Test
    fun theCabinetIsOpenedOncePerProcessAndSharedWithTheReceivers() {
        val first = runBlocking { app.openCabinet() }
        val second = runBlocking { app.openCabinet() }
        assertTrue(first is BootstrapState.Ready)
        assertSame(first, second)
    }
}
