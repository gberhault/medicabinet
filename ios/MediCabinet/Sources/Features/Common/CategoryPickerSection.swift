import MediCabinetCore
import SwiftUI

/// The category part of an edit form: pick a category from the taxonomy, or give it back to the classifier
/// (**Automatic category**). Used by the box edit and by the Settings edit of a named product.
struct CategoryPickerSection: View {
    let category: CategoryID
    /// "Set by you": the category was picked by the user and the classifier leaves it alone.
    let isSetByUser: Bool
    /// Prefix of the accessibility identifiers: `<prefix>`, `<prefix>.automatic`, `<prefix>.manual`.
    let identifierPrefix: String
    let choose: (CategoryID) -> Void
    let resetToAutomatic: () -> Void

    var body: some View {
        Section {
            Picker("box.category.choose", selection: Binding(get: { category }, set: choose)) {
                if category == .uncategorized {
                    Text(LocalizedStringKey(CategoryLabel.key(for: .uncategorized))).tag(CategoryID.uncategorized)
                }
                ForEach(Taxonomy.userSelectable, id: \.self) { category in
                    Text(LocalizedStringKey(CategoryLabel.key(for: category))).tag(category)
                }
            }
            .frame(minHeight: 44)
            .accessibilityIdentifier(identifierPrefix)
            Button(action: resetToAutomatic) {
                Label("box.category.automatic", systemImage: "wand.and.stars")
            }
            .frame(minHeight: 44)
            .accessibilityIdentifier("\(identifierPrefix).automatic")
            if isSetByUser {
                Label("box.category.manual", systemImage: "person.fill.checkmark")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("\(identifierPrefix).manual")
            }
        } header: {
            Text("box.category")
        } footer: {
            Text("box.category.note")
        }
    }
}
