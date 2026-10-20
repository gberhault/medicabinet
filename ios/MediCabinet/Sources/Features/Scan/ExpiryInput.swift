import MediCabinetCore

/// What the user picked for an expiry date: nothing is preselected, so a date is never guessed.
struct ExpiryInput: Equatable {
    var month: Int?
    var year: Int?
    var day: Int?

    enum Outcome: Equatable {
        /// Month or year not chosen yet.
        case incomplete
        case valid(ExpiryDate)
        /// A combination that is not a real date (e.g. 31 April).
        case invalid
    }

    var outcome: Outcome {
        guard let month, let year else { return .incomplete }
        do {
            return .valid(try ExpiryDate.make(year: year, month: month, day: day))
        } catch {
            return .invalid
        }
    }

    init(month: Int?, year: Int?, day: Int?) {
        self.month = month
        self.year = year
        self.day = day
    }

    /// Seeds the input from an existing date (editing a box).
    init(_ date: ExpiryDate? = nil) {
        month = date?.month
        year = date?.year
        day = date?.day
    }
}

/// An expiry being edited: a date, or the explicit "Date not printed" (saved as unknown, never as OK).
struct ExpiryChoice: Equatable {
    var input = ExpiryInput()
    var notPrinted = false

    enum Resolved: Equatable {
        case date(ExpiryDate)
        case unknown
        /// Nothing chosen yet, or an impossible date: cannot be saved.
        case pending
    }

    var resolved: Resolved {
        if notPrinted { return .unknown }
        if case let .valid(date) = input.outcome { return .date(date) }
        return .pending
    }

    /// The date to save: `nil` for "not printed" (and while pending, which the form never lets through).
    var expiry: ExpiryDate? {
        if case let .date(date) = resolved { return date }
        return nil
    }

    init(_ date: ExpiryDate? = nil, editing: Bool = false) {
        input = ExpiryInput(date)
        notPrinted = editing && date == nil
    }
}
