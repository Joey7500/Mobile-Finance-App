package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.text.TextNormalizer

/** One money amount found in free text. */
data class FoundAmount(
    /** Absolute value in minor units. */
    val minor: Long,
    val currency: String,
    /** −1 / +1 when a sign was glued to the number or currency ("-245 Kč", "+1 000 Kč"), else 0. */
    val explicitSign: Int,
    /** True when the amount is introduced by "balance / zůstatek / available". */
    val isBalance: Boolean,
    val range: IntRange,
)

/**
 * Finds money amounts in notification text in both Czech ("1 234,50 Kč") and
 * English ("CZK 1,234.50", "€12.50") notation.
 */
object AmountParser {

    // A number that is not glued to a preceding digit or separator. Either
    // grouped thousands ("1 234", "1.234", "1,234") with optional decimals, or
    // a plain number with optional 1-2 decimals.
    private const val NUM =
        """(?<![\d.,])(\d{1,3}(?:[   .,]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)(?![\d])"""
    private const val CUR = """(Kč|Kc|CZK|EUR|€|USD|US\$|\$|GBP|£)"""
    private const val SIGN = """([+\-−–])?"""
    private const val SP = """[\s\u00A0\u202F]?"""

    private val CURRENCY_AFTER = Regex("""$SIGN$NUM$SP$CUR(?![A-Za-z])""", RegexOption.IGNORE_CASE)
    private val CURRENCY_BEFORE = Regex("""$SIGN$CUR$SP$SIGN$NUM""", RegexOption.IGNORE_CASE)

    private val BALANCE_WORDS = listOf(
        "zustatek", "disponibilni", "k dispozici", "balance", "available", "limit",
    )

    fun findAll(text: String): List<FoundAmount> {
        val found = mutableListOf<FoundAmount>()

        for (m in CURRENCY_AFTER.findAll(text)) {
            val minor = parseNumber(m.groupValues[2]) ?: continue
            found += FoundAmount(
                minor = minor,
                currency = normalizeCurrency(m.groupValues[3]),
                explicitSign = signOf(m.groupValues[1]),
                isBalance = precededByBalanceWord(text, m.range.first),
                range = m.range,
            )
        }
        for (m in CURRENCY_BEFORE.findAll(text)) {
            if (found.any { it.range.overlaps(m.range) }) continue
            val minor = parseNumber(m.groupValues[4]) ?: continue
            val sign = signOf(m.groupValues[1]).takeIf { it != 0 } ?: signOf(m.groupValues[3])
            found += FoundAmount(
                minor = minor,
                currency = normalizeCurrency(m.groupValues[2]),
                explicitSign = sign,
                isBalance = precededByBalanceWord(text, m.range.first),
                range = m.range,
            )
        }
        return found.sortedBy { it.range.first }
    }

    /** The transaction amount: the first amount that is not a balance. */
    fun transactionAmount(text: String): FoundAmount? = findAll(text).firstOrNull { !it.isBalance }

    fun containsMoney(text: String): Boolean = findAll(text).isNotEmpty()

    /**
     * "1 234,50" -> 123450, "1,234.50" -> 123450, "245" -> 24500, "12,5" -> 1250.
     * A lone separator followed by exactly 3 digits is treated as a thousands separator.
     */
    fun parseNumber(raw: String): Long? {
        val t = raw.replace(" ", "").replace(" ", "").replace(" ", "")
        if (t.isEmpty()) return null
        val lastComma = t.lastIndexOf(',')
        val lastDot = t.lastIndexOf('.')
        val decimalIndex = when {
            lastComma >= 0 && lastDot >= 0 -> maxOf(lastComma, lastDot)
            lastComma >= 0 -> lastComma.takeIf { t.length - it - 1 in 1..2 } ?: -1
            lastDot >= 0 -> lastDot.takeIf { t.length - it - 1 in 1..2 } ?: -1
            else -> -1
        }
        val intPart: String
        val fracPart: String
        if (decimalIndex >= 0) {
            intPart = t.substring(0, decimalIndex).filter(Char::isDigit)
            fracPart = t.substring(decimalIndex + 1).filter(Char::isDigit).padEnd(2, '0').take(2)
        } else {
            intPart = t.filter(Char::isDigit)
            fracPart = "00"
        }
        if (intPart.isEmpty() || intPart.length > 13) return null
        return (intPart + fracPart).toLongOrNull()
    }

    fun normalizeCurrency(raw: String): String = when (raw.uppercase()) {
        "KČ", "KC", "CZK" -> "CZK"
        "€", "EUR" -> "EUR"
        "$", "US$", "USD" -> "USD"
        "£", "GBP" -> "GBP"
        else -> raw.uppercase()
    }

    private fun signOf(s: String): Int = when (s) {
        "-", "−", "–" -> -1
        "+" -> 1
        else -> 0
    }

    private fun precededByBalanceWord(text: String, start: Int): Boolean {
        val window = TextNormalizer.lowerAscii(text.substring(maxOf(0, start - 28), start))
        return BALANCE_WORDS.any { window.contains(it) }
    }

    private fun IntRange.overlaps(other: IntRange) = first <= other.last && other.first <= last
}
