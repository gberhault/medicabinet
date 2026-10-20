package com.medicabinet.app.features.cabinet

import com.medicabinet.core.CategoryID
import com.medicabinet.core.cabinet.ProductKey
import com.medicabinet.core.cabinet.ProductRow
import com.medicabinet.core.cabinet.ProductRowId
import java.util.UUID

/** Where a tap on a product row goes (P2): one box opens its detail, several open the product's box list. */
sealed interface CabinetDestination {
    data class Product(val id: ProductRowId) : CabinetDestination

    data class Box(val id: UUID) : CabinetDestination
}

/**
 * The Cabinet tab's navigation routes: `cabinet/home`, `cabinet/product/{category}/{key}`, `cabinet/box/{id}`. The
 * product key (a code, or a normalized name) travels in the route argument, percent-encoded; it is never logged.
 */
object CabinetRoutes {
    const val GRAPH = "cabinet"
    const val HOME = "cabinet/home"
    const val CATEGORY_ARG = "category"
    const val KEY_ARG = "key"
    const val ID_ARG = "id"
    const val PRODUCT = "cabinet/product/{$CATEGORY_ARG}/{$KEY_ARG}"
    const val BOX = "cabinet/box/{$ID_ARG}"

    private const val GTIN_PREFIX = "gtin:"
    private const val NAME_PREFIX = "name:"
    private const val HEX_RADIX = 16
    private const val BYTE_MASK = 0xFF

    fun destination(row: ProductRow): CabinetDestination =
        row.boxes.singleOrNull()?.let { CabinetDestination.Box(it.id) } ?: CabinetDestination.Product(row.id)

    fun route(destination: CabinetDestination): String = when (destination) {
        is CabinetDestination.Box -> "cabinet/box/${destination.id}"
        is CabinetDestination.Product ->
            "cabinet/product/${encode(destination.id.category.rawValue)}/${encode(keyText(destination.id.key))}"
    }

    /** The row id named by the (already decoded) route arguments. */
    fun rowId(category: String, key: String): ProductRowId? = when {
        key.startsWith(
            GTIN_PREFIX
        ) -> ProductRowId(CategoryID(category), ProductKey.Gtin(key.removePrefix(GTIN_PREFIX)))
        key.startsWith(
            NAME_PREFIX
        ) -> ProductRowId(CategoryID(category), ProductKey.Name(key.removePrefix(NAME_PREFIX)))
        else -> null
    }

    /** A key unique to the row, for list item keys. */
    fun rowKey(id: ProductRowId): String = "${encode(id.category.rawValue)}|${keyText(id.key)}"

    private fun keyText(key: ProductKey): String = when (key) {
        is ProductKey.Gtin -> GTIN_PREFIX + key.value
        is ProductKey.Name -> NAME_PREFIX + key.normalized
    }

    /** Percent-encodes everything but ASCII letters and digits (UTF-8), so `/`, `%`, `{` and spaces are safe. */
    fun encode(text: String): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and BYTE_MASK
            val char = value.toChar()
            if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9') {
                append(char)
            } else {
                append('%').append(value.toString(HEX_RADIX).uppercase().padStart(2, '0'))
            }
        }
    }
}

/** What a box's code looks like to the user (B1): the 13-digit CIP13 / EAN-13 when the GTIN-14 starts with `0`. */
object BoxCode {
    private const val GTIN14_LENGTH = 14

    /** `null` when the box has no code (the screen says "No code"). */
    fun display(gtin: String?): String? = when {
        gtin == null -> null
        gtin.length == GTIN14_LENGTH && gtin.startsWith("0") -> gtin.substring(1)
        else -> gtin
    }
}
