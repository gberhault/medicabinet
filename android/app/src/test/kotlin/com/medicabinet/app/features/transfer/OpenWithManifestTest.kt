package com.medicabinet.app.features.transfer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.medicabinet.app.MainActivity
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** O2, design §7.3: the open-with filters, exactly; never ordinary JSON, never every file. */
@RunWith(RobolectricTestRunner::class)
class OpenWithManifestTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val activity = ComponentName("com.medicabinet.app", MainActivity::class.java.name)
    private val mime = "application/vnd.medicabinet+json"

    private fun describe(filter: IntentFilter): String {
        val parts = mutableListOf<String>()
        parts += (0 until filter.countActions()).map { "action=${filter.getAction(it)}" }
        parts += (0 until filter.countCategories()).map { "category=${filter.getCategory(it)}" }
        parts += (0 until filter.countDataSchemes()).map { "scheme=${filter.getDataScheme(it)}" }
        parts += (0 until filter.countDataAuthorities()).map { "host=${filter.getDataAuthority(it).host}" }
        parts += (0 until filter.countDataTypes()).map { "type=${filter.getDataType(it)}" }
        parts += (0 until filter.countDataPaths()).map { "path=${filter.getDataPath(it).path}" }
        return parts.joinToString(" ")
    }

    @Test
    fun theActivityDeclaresExactlyTheFiltersOfTheDesign() {
        val filters = shadowOf(context.packageManager).getIntentFiltersForActivity(activity).map(::describe)
        assertEquals(
            listOf(
                "action=android.intent.action.MAIN category=android.intent.category.LAUNCHER",
                "action=android.intent.action.VIEW category=android.intent.category.DEFAULT scheme=content type=$mime",
                "action=android.intent.action.SEND category=android.intent.category.DEFAULT type=$mime",
                "action=android.intent.action.VIEW category=android.intent.category.DEFAULT scheme=content host=* " +
                    "type=application/octet-stream path=.*\\.medicabinet path=.*\\..*\\.medicabinet " +
                    "path=.*\\..*\\..*\\.medicabinet path=.*\\..*\\..*\\..*\\.medicabinet"
            ),
            filters
        )
        for (filter in filters) {
            assertTrue("type=*/*" !in filter && "type=application/json" !in filter && "type=text/" !in filter)
        }
    }

    @Test
    fun theActivityIsSingleTopSoAnOpenedFileReachesTheRunningApp() {
        val info = context.packageManager.getActivityInfo(activity, PackageManager.GET_META_DATA)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
    }

    private fun opensTheApp(intent: Intent): Boolean = context.packageManager.queryIntentActivities(intent, 0).any {
        it.activityInfo.name ==
            MainActivity::class.java.name
    }

    @Test
    fun archivesOpenTheAppAndOtherFilesDoNot() {
        fun view(path: String, type: String) =
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://downloads/$path"), type)
        assertTrue(opensTheApp(view("1", mime)))
        assertTrue(opensTheApp(view("MediCabinet-2027-01-15.medicabinet", "application/octet-stream")))
        assertTrue(opensTheApp(Intent(Intent.ACTION_SEND).setType(mime)))
        assertTrue(!opensTheApp(view("cabinet.json", "application/json")))
        assertTrue(!opensTheApp(view("notes.json", "application/octet-stream")))
        assertTrue(!opensTheApp(view("photo.jpg", "image/jpeg")))
        assertTrue(!opensTheApp(Intent(Intent.ACTION_SEND).setType("application/json")))
        assertTrue(!opensTheApp(Intent(Intent.ACTION_SEND).setType("text/plain")))
    }
}
