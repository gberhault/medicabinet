package com.medicabinet.app

import com.medicabinet.core.MediCabinetCore
import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun appSeesTheCoreModule() {
        assertEquals("MediCabinetCore", MediCabinetCore.MODULE_NAME)
    }
}
