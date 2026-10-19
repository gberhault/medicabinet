package com.medicabinet.app.features.settings.locations

import kotlin.test.assertEquals
import org.junit.Test

class ReorderTargetTest {
    private val heights = listOf(100, 100, 100, 100)

    @Test
    fun aRowThatDoesNotMoveFarEnoughStaysWhereItIs() {
        assertEquals(1, reorderTarget(1, 0f, heights))
        assertEquals(1, reorderTarget(1, 49f, heights))
        assertEquals(1, reorderTarget(1, -49f, heights))
    }

    @Test
    fun passingHalfAHeightMovesPastTheNeighbour() {
        assertEquals(2, reorderTarget(1, 50f, heights))
        assertEquals(0, reorderTarget(1, -50f, heights))
    }

    @Test
    fun aLongDragPassesSeveralRows() {
        assertEquals(3, reorderTarget(0, 260f, heights))
        assertEquals(0, reorderTarget(3, -260f, heights))
    }

    @Test
    fun theResultStaysInsideTheList() {
        assertEquals(3, reorderTarget(2, 10_000f, heights))
        assertEquals(0, reorderTarget(1, -10_000f, heights))
    }

    @Test
    fun rowsOfDifferentHeightsAreMeasuredEach() {
        val uneven = listOf(100, 300, 100)
        assertEquals(0, reorderTarget(0, 100f, uneven)) // the next row is 300 tall: half is 150
        assertEquals(1, reorderTarget(0, 150f, uneven))
        assertEquals(2, reorderTarget(0, 350f, uneven))
    }
}
