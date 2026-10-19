package com.medicabinet.app.features.scan

import com.medicabinet.core.ExpiryDate

/** What the user picked for an expiry date: nothing is preselected, so a date is never guessed. */
data class ExpiryInput(val month: Int? = null, val year: Int? = null, val day: Int? = null) {
    sealed interface Outcome {
        /** Month or year not chosen yet. */
        data object Incomplete : Outcome

        data class Valid(val date: ExpiryDate) : Outcome

        /** A combination that is not a real date (e.g. 31 April). */
        data object Invalid : Outcome
    }

    val outcome: Outcome
        get() = when {
            month == null || year == null -> Outcome.Incomplete
            ExpiryDate.validate(year, month, day) != null -> Outcome.Invalid
            else -> Outcome.Valid(ExpiryDate.of(year, month, day))
        }

    companion object {
        /** Seeds the input from an existing date (editing a box). */
        fun of(date: ExpiryDate?) = ExpiryInput(date?.month, date?.year, date?.day)
    }
}

/** An expiry being edited: a date, or the explicit "Date not printed" (saved as unknown, never as OK). */
data class ExpiryChoice(val input: ExpiryInput = ExpiryInput(), val notPrinted: Boolean = false) {
    sealed interface Resolved {
        data class Date(val date: ExpiryDate) : Resolved

        data object Unknown : Resolved

        /** Nothing chosen yet, or an impossible date: cannot be saved. */
        data object Pending : Resolved
    }

    val resolved: Resolved
        get() = when {
            notPrinted -> Resolved.Unknown
            input.outcome is ExpiryInput.Outcome.Valid -> Resolved.Date(
                (input.outcome as ExpiryInput.Outcome.Valid).date
            )
            else -> Resolved.Pending
        }

    /** The date to save: `null` for "not printed" (and while pending, which the form never lets through). */
    val expiry: ExpiryDate? get() = (resolved as? Resolved.Date)?.date

    companion object {
        fun of(date: ExpiryDate?, editing: Boolean = false) =
            ExpiryChoice(ExpiryInput.of(date), notPrinted = editing && date == null)
    }
}
