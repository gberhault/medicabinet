package com.medicabinet.core.named

import com.medicabinet.core.NamedProduct
import com.medicabinet.core.cabinet.TextNormalizer

/** The order of the *Products you named* list, identical on iOS: normalized name, then GTIN, by code point. */
object NamedProductOrdering {
    val BY_NAME = Comparator<NamedProduct> { left, right ->
        val byName = TextNormalizer.compare(TextNormalizer.normalize(left.name), TextNormalizer.normalize(right.name))
        if (byName != 0) byName else TextNormalizer.compare(left.gtin, right.gtin)
    }
}
