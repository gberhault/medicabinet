package com.medicabinet.core.reference

/**
 * The public BDPM page of a medicine, which carries its official leaflet (*notice*). The apps only link to it, in the
 * system browser: they show no text of the leaflet. Pinned by `fixtures/leaflet-links.json`; the Swift twin is
 * `LeafletLink`.
 */
object LeafletLink {
    const val HOST = "base-donnees-publique.medicaments.gouv.fr"
    private const val CIS_LENGTH = 8

    /**
     * The page address (always an absolute `https` URL), or `null` when there is none to show: no product, a withdrawn
     * presentation (its page may be gone), no CIS, or a CIS that is not exactly 8 ASCII digits.
     */
    fun address(product: ReferenceProduct?): String? {
        val cis = product?.cis
        return if (product == null || product.isWithdrawn || cis == null || !isValid(cis)) {
            null
        } else {
            "https://$HOST/medicament/$cis/extrait"
        }
    }

    // `'0'..'9'`, not `Char.isDigit()`: that accepts digits of other scripts, which must never reach a URL.
    private fun isValid(cis: String): Boolean = cis.length == CIS_LENGTH && cis.all { it in '0'..'9' }
}
