package io.github.joey7500.vaultflow.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Money is stored as a [Long] number of minor units (haléře / cents) to avoid
 * floating-point drift. 1 CZK = 100 minor units.
 */
object Money {
    const val MINOR_PER_UNIT = 100L

    fun ofUnits(units: Double): Long =
        BigDecimal.valueOf(units).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()

    fun ofUnits(units: Long): Long = units * MINOR_PER_UNIT

    fun toUnits(minor: Long): Double = minor / MINOR_PER_UNIT.toDouble()

    /** Formats as Czech-style "12 345 Kč" (no decimals) or "12 345,50 Kč". */
    fun format(minor: Long, currency: String = "CZK", showDecimals: Boolean = false): String {
        val symbols = DecimalFormatSymbols(Locale.ROOT).apply {
            groupingSeparator = ' '
            decimalSeparator = ','
        }
        val pattern = if (showDecimals) "#,##0.00" else "#,##0"
        val df = DecimalFormat(pattern, symbols)
        val units = BigDecimal.valueOf(minor).movePointLeft(2)
        val body = df.format(units.abs())
        val sign = if (minor < 0) "−" else ""
        val suffix = when (currency) {
            "CZK" -> "Kč"
            "EUR" -> "€"
            "USD" -> "$"
            "GBP" -> "£"
            else -> currency
        }
        return "$sign$body $suffix"
    }
}
