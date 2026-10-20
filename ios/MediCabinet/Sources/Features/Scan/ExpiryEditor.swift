import SwiftUI

/// Month, year and optional day, plus "Date not printed". Nothing is preselected: a date is never guessed.
struct ExpiryEditor: View {
    @Binding var choice: ExpiryChoice
    var showsUnreadableWarning = false

    var body: some View {
        if showsUnreadableWarning {
            Label("scan.complete.expiryUnreadable", systemImage: "exclamationmark.triangle")
                .foregroundStyle(.orange)
                .accessibilityIdentifier("expiry.unreadable")
        }
        Toggle("scan.complete.dateNotPrinted", isOn: $choice.notPrinted)
            .accessibilityIdentifier("expiry.notPrinted")
        if !choice.notPrinted {
            Picker("expiry.field.month", selection: $choice.input.month) {
                Text(verbatim: "—").tag(Int?.none)
                ForEach(1...12, id: \.self) { month in
                    Text(verbatim: Calendar.current.monthSymbols[month - 1]).tag(Int?.some(month))
                }
            }
            .accessibilityIdentifier("expiry.month")
            TextField("expiry.field.year", value: $choice.input.year, format: .number.grouping(.never))
                .keyboardType(.numberPad)
                .accessibilityIdentifier("expiry.year")
            Picker("expiry.field.day", selection: $choice.input.day) {
                Text("expiry.field.noDay").tag(Int?.none)
                ForEach(1...31, id: \.self) { day in
                    Text(verbatim: "\(day)").tag(Int?.some(day))
                }
            }
            .accessibilityIdentifier("expiry.day")
            if choice.input.outcome == .invalid {
                Label("expiry.field.invalid", systemImage: "xmark.octagon")
                    .foregroundStyle(.red)
                    .accessibilityIdentifier("expiry.invalid")
            }
        }
    }
}
