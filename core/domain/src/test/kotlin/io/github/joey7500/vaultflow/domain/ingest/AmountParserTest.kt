package io.github.joey7500.vaultflow.domain.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountParserTest {

    @Test fun `czech notation with space thousands and comma decimals`() {
        val a = AmountParser.transactionAmount("Platba kartou 1 234,50 Kč u ALBERT")!!
        assertEquals(123_450L, a.minor)
        assertEquals("CZK", a.currency)
    }

    @Test fun `non-breaking space thousands`() {
        assertEquals(1_234_500L, AmountParser.transactionAmount("12 345,00 Kč")!!.minor)
    }

    @Test fun `english notation with currency before`() {
        val a = AmountParser.transactionAmount("CZK 1,234.50 with Visa")!!
        assertEquals(123_450L, a.minor)
        assertEquals("CZK", a.currency)
    }

    @Test fun `euro symbol and dot decimals`() {
        val a = AmountParser.transactionAmount("You paid €12.50")!!
        assertEquals(1_250L, a.minor)
        assertEquals("EUR", a.currency)
    }

    @Test fun `integer amount`() {
        assertEquals(24_500L, AmountParser.transactionAmount("245 Kč")!!.minor)
    }

    @Test fun `explicit sign glued to number`() {
        assertEquals(-1, AmountParser.transactionAmount("Odchozí -245,00 CZK")!!.explicitSign)
        assertEquals(1, AmountParser.transactionAmount("+1 000 Kč")!!.explicitSign)
    }

    @Test fun `dash separated by space is not a sign`() {
        assertEquals(0, AmountParser.transactionAmount("Příchozí platba - 1 000 Kč")!!.explicitSign)
    }

    @Test fun `card digits before an amount are not swallowed`() {
        assertEquals(24_500L, AmountParser.transactionAmount("Karta 1234 245 Kč")!!.minor)
    }

    @Test fun `balance amount is skipped`() {
        val text = "Platba 350 Kč. Zůstatek: 12 000,00 Kč"
        assertEquals(35_000L, AmountParser.transactionAmount(text)!!.minor)
        assertTrue(AmountParser.findAll(text).last().isBalance)
    }

    @Test fun `text without money`() {
        assertNull(AmountParser.transactionAmount("Nová zpráva od banky"))
        assertFalse(AmountParser.containsMoney("Your code is 123456"))
    }

    @Test fun `parseNumber edge cases`() {
        assertEquals(1_250L, AmountParser.parseNumber("12,5"))
        assertEquals(123_400L, AmountParser.parseNumber("1.234"))
        assertEquals(123_456L, AmountParser.parseNumber("1.234,56"))
        assertEquals(123_456L, AmountParser.parseNumber("1,234.56"))
    }
}
