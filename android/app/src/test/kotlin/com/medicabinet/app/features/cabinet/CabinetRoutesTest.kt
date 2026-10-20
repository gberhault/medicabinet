package com.medicabinet.app.features.cabinet

import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ExpiryStatus
import com.medicabinet.core.ProductRef
import com.medicabinet.core.cabinet.ProductKey
import com.medicabinet.core.cabinet.ProductRow
import com.medicabinet.core.cabinet.ProductRowId
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.junit.Test

class CabinetRoutesTest {
    private fun box() = Box(
        product = ProductRef(null, "X"),
        locationId = UUID.randomUUID(),
        createdAt = Instant.EPOCH
    )

    private fun row(vararg boxes: Box) = ProductRow(
        ProductRowId(CategoryID.PAIN_FEVER, ProductKey.Name("x")),
        "X",
        "X",
        boxes.toList(),
        ExpiryStatus.OK,
        null
    )

    @Test
    fun aRowWithOneBoxOpensItsDetailDirectly() {
        val only = box()
        assertEquals(CabinetDestination.Box(only.id), CabinetRoutes.destination(row(only)))
    }

    @Test
    fun aRowWithSeveralBoxesOpensTheProductList() {
        val destination = CabinetRoutes.destination(row(box(), box()))
        assertIs<CabinetDestination.Product>(destination)
    }

    @Test
    fun theBoxRouteCarriesTheId() {
        val id = UUID.randomUUID()
        assertEquals("cabinet/box/$id", CabinetRoutes.route(CabinetDestination.Box(id)))
    }

    @Test
    fun theProductRouteEncodesEverythingButLettersAndDigits() {
        val id = ProductRowId(CategoryID.PAIN_FEVER, ProductKey.Name("gel à 50%/ml {x}"))
        val route = CabinetRoutes.route(CabinetDestination.Product(id))
        assertEquals("cabinet/product/pain%2Dfever/name%3Agel%20%C3%A0%2050%25%2Fml%20%7Bx%7D", route)
    }

    @Test
    fun theRowIdIsReadBackFromTheDecodedArguments() {
        assertEquals(
            ProductRowId(CategoryID.OTHER, ProductKey.Gtin("03400930000014")),
            CabinetRoutes.rowId("other", "gtin:03400930000014")
        )
        assertEquals(
            ProductRowId(CategoryID.OTHER, ProductKey.Name("pansements")),
            CabinetRoutes.rowId("other", "name:pansements")
        )
        assertNull(CabinetRoutes.rowId("other", "something else"))
    }

    @Test
    fun rowKeysDifferByCategoryAndByKey() {
        val gtin = ProductKey.Gtin("1")
        val keys = setOf(
            CabinetRoutes.rowKey(ProductRowId(CategoryID.OTHER, gtin)),
            CabinetRoutes.rowKey(ProductRowId(CategoryID.EYES, gtin)),
            CabinetRoutes.rowKey(ProductRowId(CategoryID.OTHER, ProductKey.Name("1")))
        )
        assertEquals(3, keys.size)
        assertNotEquals(keys.first(), keys.last())
    }

    @Test
    fun aBoxCodeShowsThirteenDigitsWhenTheGtinStartsWithZero() {
        assertEquals("3400930000014", BoxCode.display("03400930000014"))
    }

    @Test
    fun aBoxCodeShowsTheFullGtinOtherwiseAndNothingWhenAbsent() {
        assertEquals("13400930000011", BoxCode.display("13400930000011"))
        assertEquals("12345", BoxCode.display("12345"))
        assertNull(BoxCode.display(null))
    }
}
