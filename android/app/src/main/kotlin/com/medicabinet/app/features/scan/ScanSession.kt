package com.medicabinet.app.features.scan

import com.medicabinet.core.Box
import java.util.UUID

/** The boxes added since the scanner was opened (cleared by **Done**). The boxes themselves are already saved. */
data class ScanSession(val boxes: List<Box> = emptyList()) {
    val count: Int get() = boxes.size
    val newestFirst: List<Box> get() = boxes.asReversed()

    /** GTINs of the boxes in the session: the same product without serial asks before it is added again. */
    val gtins: Set<String> get() = boxes.mapNotNull { it.product.gtin }.toSet()
    val unknownExpiryCount: Int get() = boxes.count { it.expiry == null }

    fun add(box: Box) = copy(boxes = boxes + box)

    fun remove(id: UUID) = copy(boxes = boxes.filterNot { it.id == id })

    fun replace(box: Box) = copy(boxes = boxes.map { if (it.id == box.id) box else it })
}
