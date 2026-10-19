package com.medicabinet.core.scan

import com.medicabinet.core.Box

/** Something a box cannot be saved without, that the scan did not provide. [rawValue] is shared with iOS. */
enum class IntakeField(val rawValue: String) {
    NAME("name"),
    EXPIRY("expiry")
}

/** [kind] is the platform-neutral name shared with iOS and `fixtures/scan-intake.json`. */
sealed interface IntakeDecision {
    val kind: String

    /** Save the box now. */
    data object Add : IntakeDecision {
        override val kind = "add"
    }

    /** Ask for what is missing (while the box is still in hand), then save. */
    data class Complete(val missing: Set<IntakeField>, val expiryUnreadable: Boolean) : IntakeDecision {
        override val kind = "complete"
    }

    /** Same product, no serial, already added this session: ask "another box?" first. */
    data class ConfirmAnother(val thenMissing: Set<IntakeField>, val expiryUnreadable: Boolean) : IntakeDecision {
        override val kind = "confirmAnother"
    }

    data object AlreadyInCabinet : IntakeDecision {
        override val kind = "alreadyInCabinet"
    }

    data object RemovedEarlier : IntakeDecision {
        override val kind = "removedEarlier"
    }
}

object IntakePolicy {
    /**
     * @param productKnown the name is already resolved (reference data or an earlier box).
     * @param cabinetMatch a non-deleted box with the same GTIN and serial; only looked at when the code has a serial.
     * @param sessionHasGtin a box with this GTIN was already added in the current scan session.
     */
    fun decide(code: ScannedCode, productKnown: Boolean, cabinetMatch: Box?, sessionHasGtin: Boolean): IntakeDecision {
        if (code.serial != null && cabinetMatch != null) {
            return if (cabinetMatch.isInCabinet) IntakeDecision.AlreadyInCabinet else IntakeDecision.RemovedEarlier
        }
        val missing = buildSet {
            if (!productKnown) add(IntakeField.NAME)
            if (code.expiry !is ExpiryReading.Date) add(IntakeField.EXPIRY)
        }
        val unreadable = code.expiry is ExpiryReading.Invalid
        return when {
            code.serial == null && sessionHasGtin -> IntakeDecision.ConfirmAnother(missing, unreadable)
            missing.isEmpty() -> IntakeDecision.Add
            else -> IntakeDecision.Complete(missing, unreadable)
        }
    }
}
