package com.medicabinet.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device or emulator (optional locally; the gate only checks that it compiles). */
@RunWith(AndroidJUnit4::class)
class AppContextInstrumentedTest {
    @Test
    fun usesTheAppPackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.medicabinet.app", context.packageName)
    }
}
