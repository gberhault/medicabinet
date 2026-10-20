package com.medicabinet.app.features.transfer

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.medicabinet.app.R
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

/** Design §7.1, §10: the export provider is private and reaches one cache folder only. */
@RunWith(RobolectricTestRunner::class)
class TransferManifestTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun theExportProviderIsNotExportedAndOnlyGrantsPerUriAccess() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
        val provider = assertNotNull(info.providers?.singleOrNull { it.name == "androidx.core.content.FileProvider" })
        assertEquals("com.medicabinet.app.export", provider.authority)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        assertEquals(null, provider.readPermission)
    }

    @Test
    fun theProviderReachesTheExportCacheFolderOnly() {
        val paths = mutableListOf<String>()
        context.resources.getXml(R.xml.export_paths).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name != "paths") {
                    paths += "${parser.name}:${parser.getAttributeValue(null, "path")}"
                }
            }
        }
        assertEquals(listOf("cache-path:export/"), paths)
    }
}
