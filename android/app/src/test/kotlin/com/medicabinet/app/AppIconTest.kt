package com.medicabinet.app

import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** The launcher icon is MediCabinet's own adaptive icon, generated from design/app-icon (#46). */
@RunWith(RobolectricTestRunner::class)
class AppIconTest {
    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun launcherIcon(): AdaptiveIconDrawable {
        val drawable = context.resources.getDrawable(R.mipmap.ic_launcher, context.theme)
        return assertIs<AdaptiveIconDrawable>(drawable)
    }

    /** `android:roundIcon` of the `<application>` element, which `ApplicationInfo` does not expose. */
    private fun manifestRoundIcon(): Int = context.assets.openXmlResourceParser("AndroidManifest.xml").use { parser ->
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "application") {
                return parser.getAttributeResourceValue(ANDROID_NAMESPACE, "roundIcon", 0)
            }
        }
        0
    }

    @Test
    fun theManifestPointsAtTheLauncherMipmaps() {
        assertEquals(R.mipmap.ic_launcher, context.applicationInfo.icon)
        assertEquals(R.mipmap.ic_launcher_round, manifestRoundIcon())
    }

    @Test
    @Config(sdk = [26])
    fun onAndroid8TheIconIsAnAdaptiveIconWithBothLayers() {
        val icon = launcherIcon()
        assertNotNull(icon.background)
        assertNotNull(icon.foreground)
    }

    @Test
    @Config(sdk = [33])
    fun onAndroid13TheIconCarriesAMonochromeLayerForThemedIcons() {
        assertNotNull(launcherIcon().monochrome)
    }

    @Test
    @Config(sdk = [26])
    fun theRoundIconIsAdaptiveToo() {
        assertIs<AdaptiveIconDrawable>(context.resources.getDrawable(R.mipmap.ic_launcher_round, context.theme))
    }
}
