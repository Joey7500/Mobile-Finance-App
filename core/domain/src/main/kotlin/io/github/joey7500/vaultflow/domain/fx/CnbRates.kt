package io.github.joey7500.vaultflow.domain.fx

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/** Czech National Bank daily fixing: how many CZK one unit of a currency costs. */
data class FxRates(val date: LocalDate?, val czkPerUnit: Map<String, Double>) {

    fun rate(code: String): Double? = if (code.equals("CZK", true)) 1.0 else czkPerUnit[code.uppercase()]

    /** Converts signed minor units of [code] into CZK minor units, or null when the rate is unknown. */
    fun toCzkMinor(minor: Long, code: String): Long? = rate(code)?.let { (minor * it).roundToLong() }
}

/**
 * Parses the free ČNB "daily.txt" feed (no API key):
 *
 * ```
 * 07.11.2026 #216
 * země|měna|množství|kód|kurz
 * EMU|euro|1|EUR|24,345
 * Japonsko|jen|100|JPY|15,123
 * ```
 * The English feed uses the same layout with "Country|Currency|Amount|Code|Rate".
 */
object CnbRatesParser {
    const val URL_EN =
        "https://www.cnb.cz/en/financial-markets/foreign-exchange-market/central-bank-exchange-rate-fixing/central-bank-exchange-rate-fixing/daily.txt"
    const val URL_CS =
        "https://www.cnb.cz/cs/financni-trhy/devizovy-trh/kurzy-devizoveho-trhu/kurzy-devizoveho-trhu/denni_kurz.txt"

    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT),
        DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
    )

    fun parse(text: String): FxRates {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val date = lines.firstOrNull()?.substringBefore('#')?.trim()?.let(::parseDate)
        val rates = HashMap<String, Double>()
        for (line in lines.drop(1)) {
            val parts = line.split('|')
            if (parts.size != 5) continue
            val amount = parts[2].trim().toIntOrNull() ?: continue
            val code = parts[3].trim().uppercase()
            val rate = parts[4].trim().replace(',', '.').toDoubleOrNull() ?: continue
            if (amount <= 0 || code.length != 3) continue
            rates[code] = rate / amount
        }
        return FxRates(date, rates)
    }

    private fun parseDate(s: String): LocalDate? =
        DATE_FORMATS.firstNotNullOfOrNull { f -> runCatching { LocalDate.parse(s, f) }.getOrNull() }
}
