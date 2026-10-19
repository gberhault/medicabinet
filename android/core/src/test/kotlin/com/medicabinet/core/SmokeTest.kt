package com.medicabinet.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun moduleBuilds() {
        assertEquals("MediCabinetCore", MediCabinetCore.MODULE_NAME)
    }
}
