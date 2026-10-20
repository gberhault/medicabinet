import UniformTypeIdentifiers

extension UTType {
    /// `com.medicabinet.archive`, the `.medicabinet` file, declared in Info.plist (`UTExportedTypeDeclarations`).
    /// It conforms to `public.data`, not `public.json`, so other apps do not offer to open it (design §1.4).
    static let medicabinetArchive = UTType(exportedAs: "com.medicabinet.archive", conformingTo: .data)
}
