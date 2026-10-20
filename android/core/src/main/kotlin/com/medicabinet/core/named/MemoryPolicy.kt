package com.medicabinet.core.named

import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.NamedProduct

/** A box save that may be worth remembering as the named product of its code. */
sealed interface MemoryEvent {
    /** The box this event is about, as saved. */
    val after: Box

    /** A new box. */
    data class Added(val box: Box) : MemoryEvent {
        override val after: Box get() = box
    }

    /** The box edit form. */
    data class Changed(val before: Box, override val after: Box) : MemoryEvent

    /** A correction in the scan session list. Follows the same rule as [Changed]; kept apart to name the source. */
    data class SessionCorrected(val before: Box, override val after: Box) : MemoryEvent
}

sealed interface MemoryDecision {
    /** Nothing is written. */
    data object None : MemoryDecision

    /** Create or update the live named product of the code with these values. */
    data class Remember(val name: String, val category: CategoryID, val overridden: Boolean) : MemoryDecision
}

/**
 * When does a box save remember its name and category as the named product of its code? Pure; pinned by
 * `fixtures/product-memory.json`, which the Swift core also runs.
 */
object MemoryPolicy {
    /**
     * [codeKnown]: `true` = the reference names the code, `false` = it does not, `null` = it is unavailable or the
     * lookup failed (cannot tell). [current]: the live named product of the code.
     *
     * 1. No GTIN on the box, or the code is known or undecidable: nothing.
     * 2. A change that touches neither name, category nor its flag: nothing, so an expiry edit cannot bring a
     *    forgotten product back.
     * 3. The remembered values already equal the box's: nothing.
     * 4. Otherwise: remember the box's name, category and flag.
     */
    fun decide(event: MemoryEvent, codeKnown: Boolean?, current: NamedProduct?): MemoryDecision {
        val after = event.after
        val eligible = after.product.gtin != null && codeKnown == false && touchesTheMemory(event)
        val unchanged = current != null &&
            current.name == after.product.name &&
            current.category == after.category &&
            current.categoryOverridden == after.categoryOverridden
        return if (eligible && !unchanged) {
            MemoryDecision.Remember(after.product.name, after.category, after.categoryOverridden)
        } else {
            MemoryDecision.None
        }
    }

    private fun touchesTheMemory(event: MemoryEvent): Boolean = when (event) {
        is MemoryEvent.Added -> true
        is MemoryEvent.Changed -> differs(event.before, event.after)
        is MemoryEvent.SessionCorrected -> differs(event.before, event.after)
    }

    private fun differs(before: Box, after: Box): Boolean = before.product.name != after.product.name ||
        before.category != after.category ||
        before.categoryOverridden != after.categoryOverridden
}
