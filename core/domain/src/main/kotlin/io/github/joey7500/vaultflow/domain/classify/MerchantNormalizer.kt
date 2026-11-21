package io.github.joey7500.vaultflow.domain.classify

import io.github.joey7500.vaultflow.domain.text.TextNormalizer

/**
 * Turns the many spellings banks, Wallet and card terminals use for one shop
 * into a single canonical name:
 *
 *   "NYX*VeryGoodies"           -> "VERYGOODIES"
 *   "SumUp *Kavárna U Lípy"     -> "KAVARNA U LIPY"
 *   "ALBERT 0123 BRNO CZ"       -> "ALBERT"
 *   "PAYPAL *NETFLIX.COM"       -> "NETFLIX"
 *   "Lidl Česká republika v.o.s." -> "LIDL"
 *
 * Steps: diacritics + case, card-processor prefixes, web domains, punctuation,
 * store / terminal numbers, then trailing legal forms, countries and cities.
 * Never returns an empty string for a non-blank input.
 */
object MerchantNormalizer {

    /** Payment processors that put their own tag in front of the merchant ("NYX*", "SUMUP *", "PAYPAL *"). */
    private val PROCESSOR_PREFIX = Regex(
        "^(?:NYX|SUMUP|SUM UP|SQ|SQU|SQUARE|ZTL|ZETTLE|IZ|IZETTLE|PAYPAL|PP|GOPAY|GP|COMGATE|CGP|PAYU|STRIPE|" +
            "TST|SP|DL|ADYEN|MOLLIE|WIX|GOOGLE|CSOB|THEPAY|PAYLIBRE|UBR|SHOPIFY)\\s*[*#]+\\s*",
    )

    /** Processors that write their name without a star ("SUMUP  KAVARNA", "ZETTLE_BISTRO"). */
    private val PROCESSOR_WORD = Regex("^(?:SUMUP|ZETTLE|IZETTLE)[\\s_]+")

    private val WWW = Regex("^WWW\\.")
    private val TLD = Regex("\\.(?:CZ|COM|EU|SK|DE|AT|NET|ORG|IO|CO|UK|AI|APP|SHOP|STORE)\\b")
    private val NOT_WORD = Regex("[^A-Z0-9&]+")

    private val LEGAL_FORMS = listOf(
        "SPOL S R O", "S R O", "SRO", "A S", "AS", "SPOL", "K S", "V O S", "VOS", "Z S", "O P S",
        "GMBH", "AG", "LTD", "LIMITED", "INC", "LLC", "B V", "BV", "SE", "SA", "SAS", "SRL", "PLC", "CO",
    )

    private val COUNTRIES = listOf(
        "CZECH REPUBLIC", "CESKA REPUBLIKA", "CZECHIA", "CZECH", "CZ", "CZE", "CR",
        "SLOVAKIA", "SK", "SVK", "GERMANY", "DE", "DEU", "AUSTRIA", "AT", "AUT", "POLAND", "PL", "POL",
        "HU", "HUN", "GB", "UK", "GBR", "IE", "IRL", "IRELAND", "NL", "NLD", "US", "USA", "LU", "LUX", "EU", "FR", "ES", "IT",
    )

    private val CITIES = listOf(
        "PRAHA", "PRAGUE", "PRAG", "BRNO", "OSTRAVA", "PLZEN", "LIBEREC", "OLOMOUC", "CESKE BUDEJOVICE",
        "HRADEC KRALOVE", "PARDUBICE", "USTI NAD LABEM", "ZLIN", "HAVIROV", "KLADNO", "OPAVA", "FRYDEK MISTEK",
        "KARVINA", "JIHLAVA", "TEPLICE", "DECIN", "KARLOVY VARY", "CHOMUTOV", "JABLONEC NAD NISOU",
        "MLADA BOLESLAV", "PROSTEJOV", "PREROV", "TREBIC", "CESKA LIPA", "TABOR", "ZNOJMO", "KOLIN", "PISEK",
        "KROMERIZ", "VYSKOV", "BLANSKO", "BRECLAV", "HODONIN", "UHERSKE HRADISTE", "BRATISLAVA", "WIEN", "VIENNA",
        "MUNCHEN", "MUNICH", "BERLIN", "DRESDEN", "LONDON", "DUBLIN", "AMSTERDAM", "LUXEMBOURG",
    )

    /** One trailing noise phrase, longest first so "CESKA REPUBLIKA" goes before "CR". */
    private val TRAILING = (LEGAL_FORMS + COUNTRIES + CITIES)
        .sortedByDescending { it.length }
        .map { Regex("\\s+" + Regex.escape(it) + "$") }

    fun canonical(raw: String): String {
        val key = TextNormalizer.merchantKey(raw)
        if (key.isBlank()) return ""
        var s = key
        repeat(3) {
            s = s.replace(PROCESSOR_PREFIX, "").replace(PROCESSOR_WORD, "").trim()
        }
        s = s.replace(WWW, "").replace(TLD, " ")
        s = s.replace(NOT_WORD, " ").trim()

        // Store, terminal and receipt numbers: "0123", "T4711", "2311A". Short tokens like "O2" stay.
        val tokens = s.split(' ').filter { it.isNotBlank() && !isNumberNoise(it) }
        s = tokens.joinToString(" ")

        // Trailing legal form / country / city / single letters, repeatedly ("... BRNO CZ S R O").
        var changed = true
        while (changed) {
            changed = false
            for (r in TRAILING) {
                val next = s.replace(r, "")
                if (next != s && next.isNotBlank()) {
                    s = next
                    changed = true
                }
            }
            val single = Regex("\\s+[A-Z0-9]$")
            if (single.containsMatchIn(s)) {
                s = s.replace(single, "")
                changed = true
            }
        }
        s = s.trim()
        return s.ifBlank { key.replace(NOT_WORD, " ").trim().ifBlank { key } }
    }

    /** "DECATHLON OLOMOUC" -> "Olomouc", "ALBERT 0123 BRNO CZ" -> "Brno"; null when no known city ends the name. */
    fun trailingCity(raw: String): String? {
        var s = TextNormalizer.merchantKey(raw).replace(NOT_WORD, " ").trim()
        s = s.split(' ').filter { it.isNotBlank() && !isNumberNoise(it) }.joinToString(" ")
        for (country in COUNTRIES.sortedByDescending { it.length }) {
            if (s.endsWith(" $country")) s = s.removeSuffix(" $country").trim()
        }
        val city = CITIES.sortedByDescending { it.length }.firstOrNull { s.endsWith(" $it") } ?: return null
        return city.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) }
    }

    private fun isNumberNoise(token: String): Boolean =
        token.all(Char::isDigit) || token.count(Char::isDigit) >= 3
}
