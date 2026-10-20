/// The public BDPM page of a medicine, which carries its official leaflet (*notice*). The apps only link to it, in the
/// system browser: they show no text of the leaflet. Pinned by `fixtures/leaflet-links.json`; the Kotlin twin is
/// `LeafletLink`.
public enum LeafletLink {
    public static let host = "base-donnees-publique.medicaments.gouv.fr"

    /// The page address (always an absolute `https` URL), or `nil` when there is none to show: no product, a
    /// withdrawn presentation (its page may be gone), no CIS, or a CIS that is not exactly 8 ASCII digits.
    public static func address(for product: ReferenceProduct?) -> String? {
        guard let product, !product.isWithdrawn, let cis = product.cis, isValid(cis) else { return nil }
        return "https://\(host)/medicament/\(cis)/extrait"
    }

    /// By byte, not `Character.isNumber`: that accepts digits of other scripts, which must never reach a URL.
    private static func isValid(_ cis: String) -> Bool {
        cis.utf8.count == 8 && cis.utf8.allSatisfy { (0x30...0x39).contains($0) }
    }
}
